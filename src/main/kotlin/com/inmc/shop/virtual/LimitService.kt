package com.inmc.shop.virtual

import com.inmc.shop.Shop
import com.inmc.shop.data.LimitRow
import com.inmc.shop.trade.TradeType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 한 사람 한도. 한 사람은 한 번에 한 서버에만 있으므로 **접속한 서버의 메모리가 진실**이다 — 접속할 때 읽고, 쓸 때마다 적는다.
 */
class LimitService(private val shop: Shop) {

    private val players = ConcurrentHashMap<UUID, ConcurrentHashMap<String, LimitState>>()

    /** 접속 — 읽어 온다. 읽기 전의 거래는 빈 한도로 보지 않도록 [loaded] 로 막는다. */
    fun load(player: UUID) {
        shop.db.run("한도 읽기") {
            val rows = shop.db.limits(shop.db.shared, player)
            val map = ConcurrentHashMap<String, LimitState>()
            for (row in rows) map[row.key] = LimitState(row.bought, row.sold, row.resetAt)
            shop.main { players[player] = map }
        }
    }

    fun loaded(player: UUID): Boolean = players.containsKey(player)

    fun unload(player: UUID) {
        players.remove(player)
    }

    private fun stateOf(player: UUID, product: Product): LimitState? {
        val map = players[player] ?: return null
        val state = map.getOrPut(product.key) { LimitState() }
        Stocks.resetIfDue(product.limits, state, System.currentTimeMillis())
        return state
    }

    /** 남은 단위 수. 한도가 없으면 무제한, 아직 못 읽었으면 0(읽기 전에는 한도 상품을 못 산다). */
    fun remaining(player: UUID, product: Product, type: TradeType): Long {
        if (product.limits.limit(type) < 0) return Long.MAX_VALUE
        val state = stateOf(player, product) ?: return 0
        return Stocks.remaining(product.limits, state, type)
    }

    fun used(player: UUID, product: Product, type: TradeType): Long = stateOf(player, product)?.used(type) ?: 0

    fun resetAt(player: UUID, product: Product): Long = stateOf(player, product)?.resetAt ?: 0

    fun use(player: UUID, product: Product, type: TradeType, units: Long) {
        if (!product.limits.enabled) return
        val state = stateOf(player, product) ?: return
        Stocks.use(product.limits, state, type, units, System.currentTimeMillis())
        val row = LimitRow(product.key, state.bought, state.sold, state.resetAt)
        shop.db.run("한도 저장") { shop.db.saveLimit(shop.db.shared, player, row) }
    }
}
