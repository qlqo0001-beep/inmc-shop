package com.inmc.shop.sell

import com.inmc.shop.Shop
import com.inmc.shop.trade.TradeType
import com.inmc.shop.util.ContainerSource
import com.inmc.shop.util.InventorySource
import com.inmc.shop.util.ItemSource
import com.inmc.shop.util.PlayerSource
import com.inmc.shop.util.Ph
import com.inmc.shop.virtual.Product
import com.inmc.shop.virtual.TradeOutcome
import kr.inmc.core.economy.Currency
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 한꺼번에 팔기 — `/전부판매` · `/손판매` · `/같은것판매` · 판매 창 · 상점 화면의 "전부 판매". 물건마다 **가장 비싸게 사주는 상품**을
 * 고른다(ExcellentShop 의 `getBestProductFor` 와 같다 — 권한·요구 조건·재고·한도를 통과하는 것 중). 결과는 한 번에 요약한다.
 */
class SellService(private val shop: Shop) {

    /** 요약 — 판 개수와 화폐별 합. */
    class Summary {
        var items = 0L
        val totals = LinkedHashMap<Currency, Long>()
        fun add(outcome: TradeOutcome) {
            items += outcome.units * outcome.product.unit
            totals.merge(outcome.currency, outcome.total, Long::plus)
        }
        fun describe(): String = totals.entries.joinToString(", ") { (c, v) -> c.format(v) }
    }

    /** 이 물건을 가장 비싸게 사주는 상품. [onlyShop] 이면 그 상점 안에서만(상점 화면의 "전부 판매"). 없으면 null. */
    fun best(player: Player, stack: ItemStack, onlyShop: String? = null): Product? {
        if (!shop.config.virtual.enabled) return null
        var best: Product? = null
        var bestScore = -1.0
        for (product in shop.shops.products()) {
            if (onlyShop != null && product.shopId != onlyShop) continue
            if (product.hidden || product.item == null || product.item.material != stack.type || !product.tradable(TradeType.SELL)) continue
            if (!shop.trades.matches(product, stack)) continue
            val vshop = shop.shops.get(product.shopId) ?: continue
            if (!vshop.selling || !shop.trades.canOpen(player, vshop)) continue
            if (!product.requirements.allows(player::hasPermission)) continue
            val price = shop.prices.price(product, TradeType.SELL) ?: continue
            val currency = shop.trades.currencyOf(product) ?: continue
            if (!shop.canUse(player, currency, shop.config.virtual.currency)) continue
            if (shop.limits.remaining(player.uniqueId, product, TradeType.SELL) <= 0) continue
            if (shop.stocks.room(product, TradeType.SELL) <= 0) continue
            val score = price.toDouble() / product.unit
            if (score > bestScore) { best = product; bestScore = score }
        }
        return best
    }

    /** 대략 얼마 — 판매 창의 버튼에 보여 준다. */
    fun estimate(player: Player, source: ItemSource): Map<Currency, Long> {
        val out = LinkedHashMap<Currency, Long>()
        val multiplier = shop.trades.sellMultiplier(player)
        val counted = HashMap<Product, Long>()
        for (stack in source.stacks()) {
            val product = best(player, stack) ?: continue
            counted.merge(product, stack.amount.toLong(), Long::plus)
        }
        for ((product, items) in counted) {
            val units = items / product.unit
            if (units <= 0) continue
            val price = shop.prices.price(product, TradeType.SELL) ?: continue
            val currency = shop.trades.currencyOf(product) ?: continue
            out.merge(currency, Math.floor(price * units * multiplier).toLong(), Long::plus)
        }
        return out
    }

    /** [sources] 에서 팔 수 있는 것을 전부 판다. 끝나면 요약 메시지 + [done]. */
    fun sellEverything(player: Player, sources: List<ItemSource>, onlyShop: String? = null, done: (Summary) -> Unit = {}) {
        val queue = ArrayDeque<Pair<ItemSource, Product>>()
        for (source in sources) {
            val products = LinkedHashSet<Product>()
            for (stack in source.stacks()) best(player, stack, onlyShop)?.let { products += it }
            for (product in products) queue += source to product
        }
        val summary = Summary()
        fun next() {
            val (source, product) = queue.removeFirstOrNull() ?: return finish(player, summary, done)
            shop.trades.sellAll(player, product, quiet = true, source = source) { outcome ->
                outcome?.let(summary::add)
                if (player.isOnline) next() else done(summary)
            }
        }
        next()
    }

    private fun finish(player: Player, summary: Summary, done: (Summary) -> Unit) {
        if (summary.items <= 0) shop.messages.send(player, "sell-nothing")
        else shop.messages.send(player, "sold-summary", Ph.of().count(summary.items).price(summary.describe()))
        done(summary)
    }

    /** `/전부판매` — 가방(+ 설정이면 셜커 상자 안). 배낭은 빼고(사용자 결정 2026-09-30). */
    fun sellAll(player: Player) {
        if (!shop.guard(player)) return
        val sources = ArrayList<ItemSource>()
        sources += PlayerSource(player, carried = false)
        if (shop.config.virtual.sellContainers) for (slot in ContainerSource.slotsOf(player)) sources += ContainerSource(player, slot)
        sellEverything(player, sources)
    }

    /** `/손판매` — 손에 든 한 묶음만. */
    fun sellHand(player: Player) {
        if (!shop.guard(player)) return
        val hand = player.inventory.itemInMainHand
        if (hand.type.isAir) return shop.messages.send(player, "hand-empty")
        val product = best(player, hand) ?: return shop.messages.send(player, "sell-nothing")
        val slot = player.inventory.heldItemSlot
        val source = InventorySource(player.inventory, slot..slot, player)
        shop.trades.sellAll(player, product, source = source)
    }

    /** `/같은것판매` — 손에 든 것과 같은 것 전부. */
    fun sellHandAll(player: Player) {
        if (!shop.guard(player)) return
        val hand = player.inventory.itemInMainHand
        if (hand.type.isAir) return shop.messages.send(player, "hand-empty")
        val product = best(player, hand) ?: return shop.messages.send(player, "sell-nothing")
        shop.trades.sellAll(player, product)
    }
}
