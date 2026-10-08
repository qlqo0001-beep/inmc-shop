package com.inmc.shop.auction

import com.inmc.shop.Shop
import com.inmc.shop.data.ListingRow
import com.inmc.shop.data.WarningRow
import com.inmc.shop.util.Inv
import com.inmc.shop.util.Labels
import com.inmc.shop.util.Ph
import kr.inmc.core.economy.Currency
import kr.inmc.core.util.Durations
import kr.inmc.core.util.Text
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 경매장. 등록은 공용 DB(없으면 로컬)의 `auction_listing` 이고, 이 서버는 **사본**을 들고 있다(몇 초마다 바뀐 것만 읽는다).
 *
 * 돈과 물건이 오가는 순간은 전부 DB 의 조건부 갱신으로 잡는다:
 * - 사기: `ACTIVE → SOLD`(한 사람만) → 돈 → 물건. 돈을 못 받으면 되돌린다(`unbuy`).
 * - 돈 받기: `SOLD → CLAIMED` 가 된 사람만 돈을 받는다. 돌려받기: `EXPIRED → RETURNED` 가 된 사람만 물건을 받는다.
 * - 올리기: 등록 수수료를 먼저 받고 → 물건을 들고 → 적는다. 적기가 실패하면 물건과 수수료를 돌려준다.
 */
class AuctionService(private val shop: Shop) {

    private val listings = ConcurrentHashMap<String, ListingRow>()

    @Volatile
    private var lastSeen = -1L

    private val busy = com.inmc.shop.util.BusyLock()

    private val settings get() = shop.config.auction

    fun load() {
        val rows = shop.db.call { shop.db.listingsSince(shop.db.shared, -1) }
        listings.clear()
        for (row in rows) listings[row.id] = row
        lastSeen = rows.maxOfOrNull { it.updated } ?: -1
    }

    // --- 읽기 ------------------------------------------------------------------------------

    fun active(now: Long = System.currentTimeMillis()): List<ListingRow> = listings.values.filter { it.state == ACTIVE && it.expires > now }

    fun get(id: String): ListingRow? = listings[id]

    fun ofSeller(player: UUID, state: String): List<ListingRow> = listings.values.filter { it.seller == player && it.state == state }.sortedByDescending { it.updated }

    fun activeOf(player: UUID, now: Long = System.currentTimeMillis()): List<ListingRow> = ofSeller(player, ACTIVE).filter { it.expires > now }

    /** 만료함 — 만료 처리 전이라도 시간이 지난 것은 만료로 본다. */
    fun expiredOf(player: UUID, now: Long = System.currentTimeMillis()): List<ListingRow> =
        (ofSeller(player, EXPIRED) + ofSeller(player, ACTIVE).filter { it.expires <= now }).sortedByDescending { it.updated }

    /** 팔린 것 전부(돈을 받았든 아니든) — 정리되기 전까지. */
    fun completedCount(): Int = listings.values.count { it.state == SOLD || it.state == CLAIMED }

    fun history(player: UUID): List<ListingRow> = listings.values.filter { it.seller == player && (it.state == SOLD || it.state == CLAIMED) }.sortedByDescending { it.soldAt }

    fun item(row: ListingRow): ItemStack? = Inv.decode(row.item)

    fun currencyOf(row: ListingRow): Currency? = shop.currency(row.currency, settings.currency)

    fun maxListings(player: Player): Long = settings.maxListings.valueFor(player::hasPermission).toLong()

    // --- 올리기 ----------------------------------------------------------------------------

    /** 올리기 전에 막히는 이유(메시지 키와 토큰). 없으면 null. */
    fun check(player: Player, stack: ItemStack, price: Long, currency: Currency): Pair<String, Ph?>? {
        if (!settings.enabled) return "module-disabled" to null
        if (stack.type.isAir) return "hand-empty" to null
        if (!player.hasPermission("inmcshop.auction.command.sell")) return "no-permission" to null
        if (price <= 0) return "auction-bad-price" to null
        if (!shop.canUse(player, currency, settings.currency)) return "currency-not-allowed" to Ph.of().currency(currency.name)
        banned(stack)?.let { return it to null }
        val max = maxListings(player)
        if (max >= 0 && activeOf(player.uniqueId).size >= max) return "auction-limit" to Ph.of().max(max)
        if (!player.hasPermission("inmcshop.auction.bypass.listing.price")) {
            settings.currencyBounds[currency.id.lowercase()]?.let { b -> if (!b.allows(price)) return "auction-price-bounds" to Ph.of().value(bounds(b.min, b.max, currency)) }
            settings.materialBounds[stack.type.name]?.let { b ->
                val each = price / stack.amount.coerceAtLeast(1)
                if (!b.allows(each)) return "auction-price-bounds-item" to Ph.of().value(bounds(b.min, b.max, currency))
            }
        }
        return null
    }

    private fun bounds(min: Long, max: Long, currency: Currency) =
        (if (min >= 0) currency.format(min) else "0") + " ~ " + (if (max >= 0) currency.format(max) else "∞")

    fun banned(stack: ItemStack): String? {
        if (settings.bannedMaterials.any { it.equals(stack.type.name, true) }) return "auction-banned-item"
        val meta = stack.itemMeta ?: return null
        val name = meta.displayName()?.let { Text.plain(it) }.orEmpty()
        if (settings.bannedNames.any { it.isNotBlank() && name.contains(it, true) }) return "auction-banned-item"
        val lore = meta.lore().orEmpty().joinToString("\n") { Text.plain(it) }
        if (settings.bannedLores.any { it.isNotBlank() && lore.contains(it, true) }) return "auction-banned-item"
        @Suppress("DEPRECATION")
        if (meta.hasCustomModelData() && settings.bannedModels[stack.type.name]?.contains(meta.customModelData) == true) return "auction-banned-item"
        return null
    }

    fun listingTax(player: Player, price: Long): Long =
        if (player.hasPermission("inmcshop.auction.bypass.listing.tax")) 0 else Math.round(price * settings.listingTaxPercent / 100.0)

    fun purchaseTax(price: Long): Long = Math.round(price * settings.purchaseTaxPercent / 100.0)

    /**
     * 올린다. [stack] 은 이미 플레이어의 가방에서 빠져 있다(올리기 화면에 들어간 것) — 실패하면 돌려준다.
     * 벌칙(등록 금지)은 DB 에서 확인한다.
     */
    fun list(player: Player, stack: ItemStack, price: Long, currency: Currency, then: (Boolean) -> Unit) {
        fun giveBack() { Inv.restore(player, listOf(stack)) }
        check(player, stack, price, currency)?.let { (key, ph) -> shop.messages.send(player, key, ph); giveBack(); return then(false) }
        if (!shop.guard(player)) { giveBack(); return then(false) }
        if (!busy.acquire(player.uniqueId)) { shop.messages.send(player, "busy"); giveBack(); return then(false) }
        val id = player.uniqueId
        shop.db.run("경매 벌칙 확인") {
            val ban = shop.db.ban(shop.db.shared, id)
            shop.main {
                try {
                    val now = System.currentTimeMillis()
                    if (ban != null && ban.first > now) {
                        shop.messages.send(player, "auction-banned", Ph.of().time(Durations.formatShort((ban.first - now) / 1000)).reason(ban.second))
                        giveBack(); return@main then(false)
                    }
                    val tax = listingTax(player, price)
                    if (tax > 0 && !currency.withdraw(player, tax, "inmcshop:auction-tax")) {
                        shop.messages.send(player, "not-enough-money", Ph.of().price(currency.format(tax)))
                        giveBack(); return@main then(false)
                    }
                    val row = ListingRow(
                        id = UUID.randomUUID().toString(), seller = player.uniqueId, sellerName = player.name, item = Inv.encode(stack),
                        amount = stack.amount, search = searchText(stack), groupKey = groupKey(stack), currency = currency.id, price = price,
                        created = now, expires = now + settings.expireHours * 3_600_000L, state = ACTIVE, buyer = null, buyerName = null,
                        soldAt = 0, payout = 0, note = null, updated = now,
                    )
                    shop.db.run("경매 올리기") {
                        val ok = runCatching { shop.db.insertListing(shop.db.shared, row) }.isSuccess
                        shop.main {
                            if (!ok) {
                                if (tax > 0) currency.deposit(player, tax, "inmcshop:auction-tax-refund")
                                giveBack()
                                shop.messages.send(player, "auction-failed")
                                return@main then(false)
                            }
                            listings[row.id] = row
                            val label = Labels.of(stack)
                            shop.log.record("auction", "auction-list", player.uniqueId, player.name, shop.resolver.identify(stack).serialize(), label, stack.amount.toLong(), currency.format(price), row.id)
                            shop.messages.send(player, "auction-listed", Ph.of().item(label).amount(stack.amount.toLong()).price(currency.format(price)).value(if (tax > 0) currency.format(tax) else "없음"))
                            if (settings.announce) {
                                val ph = Ph.of().player(player.name).item(label).amount(stack.amount.toLong()).price(currency.format(price))
                                for (other in Bukkit.getOnlinePlayers()) if (other != player) shop.messages.send(other, "auction-announce", ph)
                            }
                            then(true)
                        }
                    }
                } finally {
                    busy.release(id)
                }
            }
        }
    }

    // --- 사기 ------------------------------------------------------------------------------

    fun buy(player: Player, id: String, then: (Boolean) -> Unit) {
        val row = listings[id]
        val now = System.currentTimeMillis()
        if (row == null || row.state != ACTIVE || row.expires <= now) { shop.messages.send(player, "auction-gone"); return then(false) }
        if (row.seller == player.uniqueId) { shop.messages.send(player, "auction-own"); return then(false) }
        if (!settings.enabled) { shop.messages.send(player, "module-disabled"); return then(false) }
        if (!shop.guard(player)) return then(false)
        val currency = currencyOf(row) ?: run { shop.messages.send(player, "currency-missing", Ph.of().currency(row.currency)); return then(false) }
        val stack = item(row) ?: run { shop.messages.send(player, "auction-gone"); return then(false) }
        if (Inv.space(player, stack) < stack.amount) { shop.messages.send(player, "inventory-full"); return then(false) }
        if (!currency.has(player, row.price)) { shop.messages.send(player, "not-enough-money", Ph.of().price(currency.format(row.price))); return then(false) }
        if (!busy.acquire(player.uniqueId)) { shop.messages.send(player, "busy"); return then(false) }
        val payout = row.price - purchaseTax(row.price)
        val buyer = player.uniqueId
        val buyerName = player.name
        shop.db.run("경매 사기") {
            val ok = shop.db.buyListing(shop.db.shared, id, buyer, buyerName, payout, now)
            val fresh = shop.db.listing(shop.db.shared, id)
            shop.main {
                try {
                    fresh?.let { listings[it.id] = it }
                    if (!ok) { shop.messages.send(player, "auction-gone"); return@main then(false) }
                    // 잡았다 — 이제 돈. 못 받으면 무른다.
                    if (!player.isOnline || !currency.withdraw(player, row.price, "inmcshop:auction-buy:$id")) {
                        shop.db.run("경매 무르기") {
                            shop.db.unbuyListing(shop.db.shared, id, buyer, System.currentTimeMillis())
                            val back = shop.db.listing(shop.db.shared, id)
                            shop.main { back?.let { listings[it.id] = it } }
                        }
                        if (player.isOnline) shop.messages.send(player, "not-enough-money", Ph.of().price(currency.format(row.price)))
                        return@main then(false)
                    }
                    Inv.give(player, stack, stack.amount, drop = true)
                    val label = Labels.of(stack)
                    shop.log.record("auction", "auction-buy", player.uniqueId, player.name, shop.resolver.identify(stack).serialize(), label, stack.amount.toLong(), currency.format(row.price), id)
                    shop.messages.send(player, "auction-bought", Ph.of().item(label).amount(stack.amount.toLong()).price(currency.format(row.price)).player(row.sellerName))
                    Bukkit.getPlayer(row.seller)?.let { seller ->
                        shop.messages.send(seller, "auction-sold-notice", Ph.of().player(player.name).item(label).price(currency.format(payout)))
                        if (settings.autoClaim) claim(seller, id) {}
                    }
                    then(true)
                } finally {
                    busy.release(buyer)
                }
            }
        }
    }

    // --- 내 것 -----------------------------------------------------------------------------

    /** 팔린 돈 받기. */
    fun claim(player: Player, id: String, then: (Boolean) -> Unit) {
        val row = listings[id] ?: return then(false)
        if (row.seller != player.uniqueId || row.state != SOLD) return then(false)
        val currency = currencyOf(row) ?: run { shop.messages.send(player, "currency-missing", Ph.of().currency(row.currency)); return then(false) }
        shop.db.run("경매 돈 받기") {
            val ok = shop.db.moveListing(shop.db.shared, id, SOLD, CLAIMED, System.currentTimeMillis())
            val fresh = shop.db.listing(shop.db.shared, id)
            shop.main {
                fresh?.let { listings[it.id] = it }
                if (!ok) return@main then(false)
                if (!currency.deposit(player, row.payout, "inmcshop:auction-claim:$id")) {
                    // 넣지 못했다(최대 금액 등) — 받지 않은 것으로 되돌린다.
                    shop.db.run("경매 돈 받기 되돌리기") {
                        shop.db.moveListing(shop.db.shared, id, CLAIMED, SOLD, System.currentTimeMillis())
                        val back = shop.db.listing(shop.db.shared, id)
                        shop.main { back?.let { listings[it.id] = it } }
                    }
                    shop.messages.send(player, "deposit-failed")
                    return@main then(false)
                }
                shop.messages.send(player, "auction-claimed", Ph.of().price(currency.format(row.payout)))
                then(true)
            }
        }
    }

    fun claimAll(player: Player) {
        val rows = ofSeller(player.uniqueId, SOLD)
        if (rows.isEmpty()) return shop.messages.send(player, "auction-nothing-to-claim")
        for (row in rows) claim(player, row.id) {}
    }

    /** 만료된 물건 돌려받기. */
    fun takeBack(player: Player, id: String, then: (Boolean) -> Unit) {
        val row = listings[id] ?: return then(false)
        val now = System.currentTimeMillis()
        if (row.seller != player.uniqueId) return then(false)
        val stack = item(row) ?: return then(false)
        if (Inv.space(player, stack) < stack.amount) { shop.messages.send(player, "inventory-full"); return then(false) }
        shop.db.run("경매 돌려받기") {
            // 시간이 지난 판매 중 물건은 먼저 만료로.
            if (row.state == ACTIVE && row.expires <= now) shop.db.moveListing(shop.db.shared, id, ACTIVE, EXPIRED, now)
            val ok = shop.db.moveListing(shop.db.shared, id, EXPIRED, RETURNED, now)
            val fresh = shop.db.listing(shop.db.shared, id)
            shop.main {
                fresh?.let { listings[it.id] = it }
                if (!ok) return@main then(false)
                Inv.give(player, stack, stack.amount, drop = true)
                shop.messages.send(player, "auction-returned", Ph.of().item(Labels.of(stack)).amount(stack.amount.toLong()))
                then(true)
            }
        }
    }

    /** 내 등록 내리기 — 만료함으로 옮기고 바로 돌려받는다. */
    fun cancel(player: Player, id: String, then: (Boolean) -> Unit) {
        val row = listings[id] ?: return then(false)
        if (row.seller != player.uniqueId || row.state != ACTIVE) return then(false)
        shop.db.run("경매 내리기") {
            val ok = shop.db.moveListing(shop.db.shared, id, ACTIVE, EXPIRED, System.currentTimeMillis(), "스스로 내림")
            val fresh = shop.db.listing(shop.db.shared, id)
            shop.main {
                fresh?.let { listings[it.id] = it }
                if (!ok) { shop.messages.send(player, "auction-gone"); return@main then(false) }
                takeBack(player, id, then)
            }
        }
    }

    // --- 관리자 내리기 · 경고 · 벌칙 ----------------------------------------------------------

    /**
     * 남의 등록을 내린다. [warn] 이면 경고를 남기고, 기간 안의 경고가 [com.inmc.shop.config.AuctionSettings.warningsToBan] 에
     * 닿으면 등록 금지. 물건은 판매자의 만료함으로 간다(돌려받을 수 있다).
     */
    fun adminRemove(admin: Player, id: String, warn: Boolean, reason: String, then: (Boolean) -> Unit) {
        val row = listings[id] ?: return then(false)
        val sellerId = row.seller
        val now = System.currentTimeMillis()
        val note = if (warn) "경고: $reason" else "관리자가 내림"
        val by = admin.name
        val window = now - settings.warningExpireDays * 86_400_000L
        val threshold = settings.warningsToBan
        val banUntil = now + settings.banDays * 86_400_000L
        shop.db.run("경매 관리자 내리기") {
            val ok = shop.db.moveListing(shop.db.shared, id, ACTIVE, EXPIRED, now, note)
            var count = 0
            var banned = false
            if (ok && warn) {
                shop.db.addWarning(shop.db.shared, sellerId, by, reason, now)
                count = shop.db.warnings(shop.db.shared, sellerId, window).size
                if (count >= threshold) {
                    shop.db.setBan(shop.db.shared, sellerId, banUntil, reason)
                    banned = true
                }
            }
            val fresh = shop.db.listing(shop.db.shared, id)
            shop.main {
                fresh?.let { listings[it.id] = it }
                if (!ok) { shop.messages.send(admin, "auction-gone"); return@main then(false) }
                val ph = Ph.of().player(row.sellerName).reason(reason).count(count).max(threshold.toLong()).time(Durations.formatShort(settings.banDays * 86_400L))
                shop.messages.send(admin, if (!warn) "auction-admin-removed" else if (banned) "auction-admin-banned" else "auction-admin-warned", ph)
                Bukkit.getPlayer(sellerId)?.let { seller ->
                    shop.messages.send(seller, if (!warn) "auction-removed-notice" else if (banned) "auction-banned-notice" else "auction-warned-notice", ph)
                }
                then(true)
            }
        }
    }

    fun warnings(player: UUID, then: (List<WarningRow>, Pair<Long, String>?) -> Unit) {
        val window = System.currentTimeMillis() - settings.warningExpireDays * 86_400_000L
        shop.db.run("경고 읽기") {
            val rows = shop.db.warnings(shop.db.shared, player, window)
            val ban = shop.db.ban(shop.db.shared, player)
            shop.main { then(rows, ban) }
        }
    }

    fun clearWarnings(player: UUID, then: () -> Unit) {
        shop.db.run("경고 지우기") {
            shop.db.clearWarnings(shop.db.shared, player)
            shop.db.clearBan(shop.db.shared, player)
            shop.main(then)
        }
    }

    // --- 티커 ------------------------------------------------------------------------------

    private var seconds = 0L

    fun tick(now: Long) {
        seconds++
        if (seconds % 30 == 0L) shop.db.run("경매 만료") { shop.db.expireListings(shop.db.shared, now) }
        if (seconds % 3600 == 0L) {
            val before = now - settings.purgeDays * 86_400_000L
            shop.db.run("경매 정리") { shop.db.purgeListings(shop.db.shared, before) }
        }
    }

    /** 몇 초마다 — 바뀐 등록을 읽는다(다른 서버·만료 처리). 자동 받기면 여기 있는 판매자의 팔린 돈을 받는다. */
    fun sync() {
        val since = lastSeen
        shop.db.run("경매 읽기") {
            val rows = shop.db.listingsSince(shop.db.shared, since)
            if (rows.isEmpty()) return@run
            shop.main {
                lastSeen = maxOf(lastSeen, rows.maxOf { it.updated })
                for (row in rows) {
                    if (row.state == CLAIMED || row.state == RETURNED) {
                        // 끝난 것도 기록 화면에 남긴다(정리될 때까지).
                        listings[row.id] = row
                        continue
                    }
                    val before = listings.put(row.id, row)
                    if (settings.autoClaim && row.state == SOLD && before?.state != SOLD) Bukkit.getPlayer(row.seller)?.let { claim(it, row.id) {} }
                }
            }
        }
    }

    /** 접속 알림 — 미수령·만료. */
    fun notifyJoin(player: Player) {
        if (!settings.enabled || !settings.notifyOnJoin) return
        val unclaimed = ofSeller(player.uniqueId, SOLD).size
        val expired = expiredOf(player.uniqueId).size
        if (unclaimed > 0) shop.messages.send(player, "auction-join-unclaimed", Ph.of().count(unclaimed))
        if (expired > 0) shop.messages.send(player, "auction-join-expired", Ph.of().count(expired))
    }

    companion object {
        const val ACTIVE = "ACTIVE"
        const val SOLD = "SOLD"
        const val CLAIMED = "CLAIMED"
        const val EXPIRED = "EXPIRED"
        const val RETURNED = "RETURNED"

        /** 같은 물건 묶기 — 개수를 1로 맞춘 아이템의 지문. */
        fun groupKey(stack: ItemStack): String {
            val one = stack.clone().apply { amount = 1 }
            val digest = MessageDigest.getInstance("SHA-1").digest(one.serializeAsBytes())
            return digest.take(10).joinToString("") { "%02x".format(it) }
        }

        /** 검색용 글자 — 이름(평문) + 재질. */
        fun searchText(stack: ItemStack): String {
            val name = stack.itemMeta?.displayName()?.let { Text.plain(it) }.orEmpty()
            return (name + " " + stack.type.name.lowercase().replace('_', ' ') + " " + kr.inmc.core.util.VanillaNames.of(stack.type).orEmpty()).trim().lowercase()
        }
    }
}
