package com.inmc.shop.virtual

import com.inmc.shop.Shop
import com.inmc.shop.trade.TradeType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

/**
 * 전체 재고. 공용 DB(없으면 로컬)의 `shop_stock`.
 *
 * - **공용 DB 가 없으면** 메모리가 진실이다 — 확인과 반영이 같은 틱에 끝나고, DB 는 순서대로 적기만 한다.
 * - **공용 DB 가 있으면** 빼고 더하는 것을 DB 의 조건부 갱신으로 한다([reserve]가 비동기) — 메모리 값만 보고 팔면 서버 수만큼 초과 판매된다.
 */
class StockService(private val shop: Shop) {

    private val states = ConcurrentHashMap<String, StockState>()

    @Volatile
    private var lastSeen = -1L

    private val networked: Boolean get() = shop.db.network != null

    fun load() {
        val rows = shop.db.call { shop.db.stocks(shop.db.shared) }
        for (row in rows) states[row.key] = StockState(row.units, row.restockAt)
        lastSeen = rows.maxOfOrNull { it.updated } ?: -1
    }

    /** 이 상품의 재고. 재고가 꺼져 있으면 null. 처음이면 만든다. */
    fun state(product: Product): StockState? {
        if (!product.stock.enabled) return null
        return states.getOrPut(product.key) {
            val now = System.currentTimeMillis()
            Stocks.fresh(product.stock, now, ::random).also { fresh ->
                val key = product.key
                val units = fresh.units
                val at = fresh.restockAt
                shop.db.run("재고 만들기") { if (shop.db.stockOf(shop.db.shared, key) == null) shop.db.setStock(shop.db.shared, key, units, at, now) }
            }
        }
    }

    fun room(product: Product, type: TradeType): Long = Stocks.room(product.stock, state(product), type)

    /**
     * 재고를 잡는다. 결과는 **메인 스레드에서** [then]. 성공하면 재고는 이미 반영돼 있다 — 거래가 실패하면 [release].
     */
    fun reserve(product: Product, type: TradeType, units: Long, then: (Boolean) -> Unit) {
        val state = state(product) ?: return then(true)
        val options = product.stock
        if (Stocks.room(options, state, type) < units) return then(false)
        val key = product.key
        val now = System.currentTimeMillis()
        if (!networked) {
            Stocks.apply(options, state, type, units)
            persist(key, state)
            return then(true)
        }
        shop.db.run("재고 잡기") {
            val ok = if (type == TradeType.BUY) shop.db.takeStock(shop.db.shared, key, units, now) else shop.db.giveStock(shop.db.shared, key, units, options.capacity, now)
            val row = shop.db.stockOf(shop.db.shared, key)
            shop.main {
                if (row != null) { state.units = row.units; state.restockAt = row.restockAt }
                then(ok)
            }
        }
    }

    /** 잡았던 재고를 되돌린다(돈을 못 받았을 때). */
    fun release(product: Product, type: TradeType, units: Long) {
        val state = state(product) ?: return
        val back = if (type == TradeType.BUY) TradeType.SELL else TradeType.BUY
        val key = product.key
        val now = System.currentTimeMillis()
        if (!networked) {
            Stocks.apply(product.stock, state, back, units)
            persist(key, state)
            return
        }
        shop.db.run("재고 되돌리기") {
            if (back == TradeType.BUY) shop.db.takeStock(shop.db.shared, key, units, now) else shop.db.giveStock(shop.db.shared, key, units, Long.MAX_VALUE, now)
        }
    }

    /** 관리자 — 재고를 그 값으로. */
    fun set(product: Product, units: Long) {
        val state = state(product) ?: return
        state.units = units.coerceIn(0, product.stock.capacity)
        persist(product.key, state)
    }

    private fun persist(key: String, state: StockState) {
        val units = state.units
        val at = state.restockAt
        val now = System.currentTimeMillis()
        shop.db.run("재고 저장") { shop.db.setStock(shop.db.shared, key, units, at, now) }
    }

    /** 1초마다 — 다시 채울 때가 된 것. */
    fun tick(now: Long) {
        for (product in shop.shops.products()) {
            if (!product.stock.enabled) continue
            val state = states[product.key] ?: continue
            if (product.stock.restockSeconds <= 0 || state.restockAt == 0L || now < state.restockAt) continue
            val expected = state.restockAt
            val units = Stocks.refillAmount(product.stock, ::random)
            val next = Stocks.nextRestock(product.stock, now)
            // 같은 때를 두 번 걸지 않게 메모리는 먼저 넘긴다.
            state.restockAt = next
            state.units = units
            val key = product.key
            shop.db.run("재고 다시 채우기") {
                if (!shop.db.restock(shop.db.shared, key, expected, units, next, now)) {
                    val row = shop.db.stockOf(shop.db.shared, key) ?: return@run
                    shop.main { state.units = row.units; state.restockAt = row.restockAt }
                }
            }
        }
    }

    /** 몇 초마다 — 공용 DB 면 다른 서버가 바꾼 재고를 읽는다. */
    fun sync() {
        if (!networked) return
        val since = lastSeen
        shop.db.run("재고 읽기") {
            val rows = shop.db.stocks(shop.db.shared, since)
            if (rows.isEmpty()) return@run
            shop.main {
                lastSeen = maxOf(lastSeen, rows.maxOf { it.updated })
                for (row in rows) {
                    val state = states[row.key]
                    if (state == null) states[row.key] = StockState(row.units, row.restockAt)
                    else { state.units = row.units; state.restockAt = row.restockAt }
                }
            }
        }
    }

    fun forget(product: Product) {
        states.remove(product.key)
    }

    private fun random(): Double = ThreadLocalRandom.current().nextDouble()
}
