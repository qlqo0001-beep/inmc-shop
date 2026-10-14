package com.inmc.shop.hook

import com.inmc.shop.Shop
import com.inmc.shop.auction.AuctionService
import kr.inmc.core.economy.Currencies
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player

/**
 * `%inmcshop_…%` — ExcellentShop 의 PAPI 식별자를 같은 뜻으로(접두사만 `inmcshop`).
 * PlaceholderAPI 클래스는 compileOnly 라 **플러그인이 있을 때만** 건드린다. [setup] 이 유일한 문이다.
 */
class PapiHook(private val shop: Shop) {

    private var expansion: ShopExpansion? = null

    /** 켰으면 true. */
    fun setup(): Boolean {
        teardown()
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return false
        return try {
            shop.papi = { player, text -> me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text) }
            expansion = ShopExpansion(shop).also { it.register() }
            shop.logger.info("PlaceholderAPI 연동 활성화 (%inmcshop_...%)")
            true
        } catch (t: Throwable) {
            shop.logger.warning("PlaceholderAPI 연동 실패: ${t.message}")
            teardown()
            false
        }
    }

    fun teardown() {
        expansion?.let { runCatching { it.unregister() } }
        expansion = null
        shop.papi = null
    }
}

/** 따로 둔 것은 PlaceholderAPI 가 있을 때만 이 클래스가 적재되게 하려는 것이다. */
private class ShopExpansion(private val shop: Shop) : PlaceholderExpansion() {

    override fun getIdentifier(): String = "inmcshop"
    override fun getAuthor(): String = "INMC"
    override fun getVersion(): String = shop.plugin.pluginMeta.version
    override fun persist(): Boolean = true

    /** PAPI 의 목록(`/papi info inmcshop`)에 보이는 것 — 고정 이름과, 지금 있는 화폐로 펼친 수입 이름. [onRequest] 의 키와 같아야 한다. */
    override fun getPlaceholders(): List<String> =
        ShopPlaceholders.keys(Currencies.all().map { it.id }).map { "%inmcshop_$it%" }

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        val p = params.lowercase()
        val auction = shop.auction
        when (p) {
            "auction_all_active_listings" -> return auction.active().size.toString()
            "auction_all_completed_listings" -> return auction.completedCount().toString()
        }
        val id = player?.uniqueId ?: return ""
        val online = player as? Player ?: Bukkit.getPlayer(id)
        ShopPlaceholders.income(p)?.let { return income(id, if (it.claimed) AuctionService.CLAIMED else AuctionService.SOLD, it.currency, it.raw) }
        return when {
            p == "auction_max_listings" -> online?.let { auction.maxListings(it).let { n -> if (n < 0) "무제한" else n.toString() } } ?: ""
            p == "auction_active_listings" -> auction.activeOf(id).size.toString()
            p == "auction_unclaimed_listings" -> auction.ofSeller(id, AuctionService.SOLD).size.toString()
            p == "auction_expired_listings" -> auction.expiredOf(id).size.toString()
            p == "auction_claimed_listings" -> auction.ofSeller(id, AuctionService.CLAIMED).size.toString()
            p == "chestshop_max_shops" -> online?.let { shop.config.chest.maxShops.valueFor(it::hasPermission).toLong().let { n -> if (n < 0) "무제한" else n.toString() } } ?: ""
            p == "chestshop_products_per_shop" -> online?.let { shop.config.chest.maxProducts.valueFor(it::hasPermission).toLong().let { n -> if (n < 0) "무제한" else n.toString() } } ?: ""
            p == "chestshop_shops" -> shop.chests.ownedBy(id).size.toString()
            p == "virtualshop_sell_multiplier" -> online?.let { "%.2f".format(shop.trades.sellMultiplier(it)).trimEnd('0').trimEnd('.') } ?: ""
            else -> null
        }
    }

    private fun income(id: java.util.UUID, state: String, currencyId: String, raw: Boolean): String {
        val total = shop.auction.ofSeller(id, state).filter { it.currency.equals(currencyId, true) }.sumOf { it.payout }
        if (raw) return total.toString()
        return shop.currency(currencyId, shop.config.auction.currency)?.format(total) ?: total.toString()
    }
}

/** 키 목록과 수입 키 풀기 — PlaceholderAPI 없이 시험하려고([ShopExpansion] 은 PAPI 가 있어야 적재된다) 밖에 둔다. */
internal object ShopPlaceholders {

    /** 화폐와 상관없는 키 — [ShopExpansion.onRequest] 의 `==` 갈래들. */
    val FIXED = listOf(
        "auction_all_active_listings", "auction_all_completed_listings", "auction_max_listings", "auction_active_listings",
        "auction_unclaimed_listings", "auction_expired_listings", "auction_claimed_listings",
        "chestshop_max_shops", "chestshop_products_per_shop", "chestshop_shops", "virtualshop_sell_multiplier",
    )

    data class Income(val claimed: Boolean, val raw: Boolean, val currency: String)

    /** (앞머리, 받았나, 숫자만). `_raw_` 가 먼저 — 아니면 `raw_<화폐>` 를 화폐 id 로 읽는다. */
    private val INCOME = listOf(
        Triple("auction_unclaimed_income_raw_", false, true),
        Triple("auction_unclaimed_income_", false, false),
        Triple("auction_claimed_income_raw_", true, true),
        Triple("auction_claimed_income_", true, false),
    )

    /** `%inmcshop_` 와 `%` 를 뺀 키들 — 고정 키와 [currencyIds] 마다 수입 키 넷. */
    fun keys(currencyIds: List<String>): List<String> =
        FIXED + currencyIds.map { it.lowercase() }.flatMap { id -> INCOME.map { it.first + id } }

    /** 수입 키면 풀어서, 아니면 null. */
    fun income(key: String): Income? =
        INCOME.firstOrNull { key.startsWith(it.first) }?.let { Income(it.second, it.third, key.removePrefix(it.first)) }
}
