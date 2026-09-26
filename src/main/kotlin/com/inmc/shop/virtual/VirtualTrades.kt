package com.inmc.shop.virtual

import com.inmc.shop.Shop
import com.inmc.shop.trade.TradeType
import com.inmc.shop.util.Inv
import com.inmc.shop.util.ItemSource
import com.inmc.shop.util.PlayerSource
import com.inmc.shop.util.Labels
import com.inmc.shop.util.Ph
import kr.inmc.core.economy.Currency
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** 끝난 거래 하나. */
data class TradeOutcome(val product: Product, val type: TradeType, val units: Long, val total: Long, val currency: Currency)

/**
 * 서버 상점의 사고팔기. **돈·아이템 불변식**이 여기 있다:
 *
 * 1. 구매 — 재고를 잡고 → 돈을 받고 → 아이템을 준다. 돈을 못 받으면 재고를 되돌린다. 가방 공간은 돈 받기 **전에** 본다.
 * 2. 판매 — 재고(공간)를 잡고 → 가방에서 **먼저 빼고** → 돈을 준다. 돈을 못 주면 뺀 조각을 그대로 돌려놓는다.
 * 3. 한 사람의 거래는 한 번에 하나(잡는 사이 두 번 누르기 방지).
 *
 * 공용 DB 면 재고 잡기가 비동기라 결과는 콜백으로 온다. 그 사이 가방·돈이 바뀌었을 수 있어 **잡은 뒤 다시 본다.**
 */
class VirtualTrades(private val shop: Shop) {

    private val busy = com.inmc.shop.util.BusyLock()

    private class Quote(val product: Product, val vshop: VirtualShop, val type: TradeType, val units: Long, val unitPrice: Long, val total: Long, val currency: Currency, val template: ItemStack?)

    /** 아이템 상품의 한 개짜리 견본(커스텀아이템이면 지금 정의로 만든다). */
    fun template(product: Product): ItemStack? = product.item?.let { shop.resolver.create(it, 1) }

    /** 화면·메시지에 쓸 이름. */
    fun label(product: Product): String =
        product.name?.takeIf { it.isNotBlank() } ?: Labels.of(template(product) ?: product.preview?.let { shop.resolver.create(it, 1) })

    fun matches(product: Product, stack: ItemStack): Boolean = product.item != null && shop.matcher.matches(stack, product.item)

    fun sellMultiplier(player: Player): Double = shop.config.virtual.sellMultiplier.valueFor(player::hasPermission).let { if (it < 0) 1.0 else it }

    fun canOpen(player: Player, vshop: VirtualShop): Boolean =
        !vshop.permissionRequired || player.hasPermission(vshop.permission) || player.hasPermission("inmcshop.virtual.shop.*")

    fun currencyOf(product: Product): Currency? {
        val vshop = shop.shops.get(product.shopId)
        return shop.currency(product.currency.ifEmpty { vshop?.currency.orEmpty() }, shop.config.virtual.currency)
    }

    /** 이 방향으로 지금 몇 단위까지 되나 — 돈·재고·한도·공간·가진 개수 중 가장 작은 것. */
    fun maxUnits(player: Player, product: Product, type: TradeType, source: ItemSource = PlayerSource(player)): Long {
        val unitPrice = shop.prices.price(product, type) ?: return 0
        val stock = shop.stocks.room(product, type)
        val limit = shop.limits.remaining(player.uniqueId, product, type)
        return if (type == TradeType.BUY) {
            val currency = currencyOf(product) ?: return 0
            val money = Quantity.affordable(currency.balance(player), unitPrice)
            val space = if (product.type == ProductType.ITEM) template(product)?.let { Inv.space(player, it).toLong() / product.unit } ?: 0 else Long.MAX_VALUE
            Quantity.max(money, stock, limit, if (shop.config.buyWithFullInventory) Long.MAX_VALUE else space)
        } else {
            val have = source.count { matches(product, it) }.toLong() / product.unit
            Quantity.max(Long.MAX_VALUE, stock, limit, have)
        }
    }

    /** 공통 확인. 막히면 메시지를 보내고(조용히가 아니면) null. */
    private fun prepare(player: Player, product: Product, type: TradeType, units: Long, quiet: Boolean, source: ItemSource): Quote? {
        fun fail(key: String, ph: Ph? = null): Quote? { if (!quiet) shop.messages.send(player, key, ph); return null }
        if (!shop.config.virtual.enabled) return fail("module-disabled")
        if (!shop.guard(player, quiet)) return null
        val vshop = shop.shops.get(product.shopId) ?: return fail("product-broken")
        // 열린 화면이 숨기기 전의 상품을 들고 있을 수 있다 — 지금 정의를 본다.
        if (vshop.product(product.id)?.hidden == true) return fail("product-hidden")
        if (!canOpen(player, vshop)) return fail("shop-no-permission")
        if (type == TradeType.BUY && !vshop.buying) return fail("shop-buying-disabled")
        if (type == TradeType.SELL && !vshop.selling) return fail("shop-selling-disabled")
        if (!product.requirements.allows(player::hasPermission)) return fail("requirements-not-met")
        val unitPrice = shop.prices.price(product, type) ?: return fail(if (type == TradeType.BUY) "buy-disabled" else "sell-disabled")
        val currency = currencyOf(product) ?: return fail("currency-missing", Ph.of().currency(product.currency.ifEmpty { "기본" }))
        if (!shop.canUse(player, currency, shop.config.virtual.currency)) return fail("currency-not-allowed", Ph.of().currency(currency.name))
        val template = if (product.type == ProductType.ITEM) template(product) ?: return fail("product-broken") else null
        if (!shop.limits.loaded(player.uniqueId) && product.limits.limit(type) >= 0) return fail("busy")
        val limit = shop.limits.remaining(player.uniqueId, product, type)
        if (limit < units) return fail("limit-reached", Ph.of().max(product.limits.limit(type)))
        if (shop.stocks.room(product, type) < units) return fail(if (type == TradeType.BUY) "out-of-stock" else "stock-full")
        val total = try {
            if (type == TradeType.BUY) Math.multiplyExact(unitPrice, units)
            else Math.floor(Math.multiplyExact(unitPrice, units) * sellMultiplier(player)).toLong()
        } catch (_: ArithmeticException) { return fail("too-much") }
        if (type == TradeType.BUY) {
            if (!currency.has(player, total)) return fail("not-enough-money", Ph.of().price(currency.format(total)))
            if (template != null && !shop.config.buyWithFullInventory && Inv.space(player, template) < units * product.unit) return fail("inventory-full")
        } else {
            if (source.count { matches(product, it) } < units * product.unit) return fail("not-enough-items")
        }
        return Quote(product, vshop, type, units, unitPrice, total, currency, template)
    }

    fun buy(player: Player, product: Product, units: Long, quiet: Boolean = false, then: (TradeOutcome?) -> Unit = {}) {
        if (units <= 0) return then(null)
        val quote = prepare(player, product, TradeType.BUY, units, quiet, PlayerSource(player)) ?: return then(null)
        if (!busy.acquire(player.uniqueId)) { if (!quiet) shop.messages.send(player, "busy"); return then(null) }
        shop.stocks.reserve(product, TradeType.BUY, units) { ok ->
            val outcome = runCatching { completeBuy(player, quote, ok, quiet) }.onFailure { shop.logger.log(java.util.logging.Level.SEVERE, "구매 처리 실패", it) }.getOrNull()
            busy.release(player.uniqueId)
            then(outcome)
        }
    }

    private fun completeBuy(player: Player, quote: Quote, reserved: Boolean, quiet: Boolean): TradeOutcome? {
        val product = quote.product
        fun fail(key: String, ph: Ph? = null): TradeOutcome? {
            if (reserved) shop.stocks.release(product, TradeType.BUY, quote.units)
            if (!quiet) shop.messages.send(player, key, ph)
            return null
        }
        if (!reserved) return fail("out-of-stock")
        if (!player.isOnline) return fail("busy")
        // 잡는 사이 바뀌었을 수 있다 — 다시 본다.
        if (quote.template != null && !shop.config.buyWithFullInventory && Inv.space(player, quote.template) < quote.units * product.unit) return fail("inventory-full")
        if (!quote.currency.withdraw(player, quote.total, "inmcshop:buy:" + product.key)) return fail("not-enough-money", Ph.of().price(quote.currency.format(quote.total)))
        deliver(player, product, quote)
        shop.limits.use(player.uniqueId, product, TradeType.BUY, quote.units)
        shop.prices.onTrade(product, TradeType.BUY, quote.units, player.uniqueId)
        val label = label(product)
        shop.log.record("virtual", "buy", player.uniqueId, player.name, subject(product), label, quote.units * product.unit, quote.currency.format(quote.total), product.key)
        if (!quiet) shop.messages.send(player, "bought", Ph.of().item(label).amount(quote.units * product.unit).price(quote.currency.format(quote.total)).shop(quote.vshop.name))
        return TradeOutcome(product, TradeType.BUY, quote.units, quote.total, quote.currency)
    }

    private fun deliver(player: Player, product: Product, quote: Quote) {
        if (quote.template != null) Inv.give(player, quote.template, (quote.units * product.unit).toInt(), drop = true)
        if (product.commands.isNotEmpty()) {
            repeat(quote.units.toInt().coerceIn(1, 10_000)) {
                for (line in product.commands) {
                    var command = line.replace("{player}", player.name).replace("%player_name%", player.name).replace("{uuid}", player.uniqueId.toString())
                    shop.papi?.let { apply -> command = runCatching { apply(player, command) }.getOrDefault(command) }
                    runCatching { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.removePrefix("/")) }
                        .onFailure { shop.logger.warning("명령어 상품 실행 실패 (${product.key}): $command - ${it.message}") }
                }
            }
        }
    }

    fun sell(player: Player, product: Product, units: Long, quiet: Boolean = false, source: ItemSource = PlayerSource(player), then: (TradeOutcome?) -> Unit = {}) {
        if (units <= 0) return then(null)
        val quote = prepare(player, product, TradeType.SELL, units, quiet, source) ?: return then(null)
        if (!busy.acquire(player.uniqueId)) { if (!quiet) shop.messages.send(player, "busy"); return then(null) }
        shop.stocks.reserve(product, TradeType.SELL, units) { ok ->
            val outcome = runCatching { completeSell(player, quote, ok, quiet, source) }.onFailure { shop.logger.log(java.util.logging.Level.SEVERE, "판매 처리 실패", it) }.getOrNull()
            busy.release(player.uniqueId)
            then(outcome)
        }
    }

    private fun completeSell(player: Player, quote: Quote, reserved: Boolean, quiet: Boolean, source: ItemSource): TradeOutcome? {
        val product = quote.product
        fun fail(key: String): TradeOutcome? {
            if (reserved) shop.stocks.release(product, TradeType.SELL, quote.units)
            if (!quiet) shop.messages.send(player, key)
            return null
        }
        if (!reserved) return fail("stock-full")
        if (!player.isOnline) return fail("busy")
        val needed = (quote.units * product.unit).toInt()
        if (source.count { matches(product, it) } < needed) return fail("not-enough-items")
        val taken = source.take({ matches(product, it) }, needed)
        if (!quote.currency.deposit(player, quote.total, "inmcshop:sell:" + product.key)) {
            source.restore(taken)
            return fail("deposit-failed")
        }
        shop.limits.use(player.uniqueId, product, TradeType.SELL, quote.units)
        shop.prices.onTrade(product, TradeType.SELL, quote.units, player.uniqueId)
        val label = label(product)
        shop.log.record("virtual", "sell", player.uniqueId, player.name, subject(product), label, needed.toLong(), quote.currency.format(quote.total), product.key)
        if (!quiet) shop.messages.send(player, "sold", Ph.of().item(label).amount(needed.toLong()).price(quote.currency.format(quote.total)).shop(quote.vshop.name))
        return TradeOutcome(product, TradeType.SELL, quote.units, quote.total, quote.currency)
    }

    /** 가진 것 전부(한도·재고 안에서). */
    fun sellAll(player: Player, product: Product, quiet: Boolean = false, source: ItemSource = PlayerSource(player), then: (TradeOutcome?) -> Unit = {}) {
        val units = maxUnits(player, product, TradeType.SELL, source)
        if (units <= 0) {
            if (!quiet) shop.messages.send(player, "not-enough-items")
            return then(null)
        }
        sell(player, product, units, quiet, source, then)
    }

    /** 신호의 subject — 커스텀아이템이면 그 참조, 아니면 재질. */
    fun subject(product: Product): String = product.item?.ref?.serialize() ?: ("command:" + product.key)
}
