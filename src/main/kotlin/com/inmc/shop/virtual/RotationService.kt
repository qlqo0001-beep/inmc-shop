package com.inmc.shop.virtual

import com.inmc.shop.Shop
import com.inmc.shop.util.Ph
import org.bukkit.Bukkit
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

/**
 * 회전 — 정한 때마다 회전 칸의 상품을 가중치로 다시 뽑는다. 상태(다음 시각·뽑힌 상품)는 공용 DB 의 `shop_rotation`.
 * 여러 서버면 **한 서버만** 굴린다(다음 시각 CAS). 나머지는 [sync] 로 가져온다.
 */
class RotationService(private val shop: Shop) {

    class RotationState(@Volatile var nextAt: Long, @Volatile var items: List<String>)

    private val states = ConcurrentHashMap<String, RotationState>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private fun keyOf(shopId: String, rotationId: String) = "$shopId/$rotationId"

    fun load() {
        val rows = shop.db.call { shop.db.rotations(shop.db.shared) }
        for (row in rows) states[row.id] = RotationState(row.nextAt, row.items.split(',').filter { it.isNotBlank() })
    }

    fun state(shopId: String, rotationId: String): RotationState? = states[keyOf(shopId, rotationId)]

    /**
     * 이 페이지의 칸 → 상품. 고정 상품 + 회전이 뽑은 상품(회전 칸을 페이지·칸 순으로 채운다).
     */
    fun productsAt(vshop: VirtualShop, page: Int): Map<Int, Product> {
        val out = HashMap<Int, Product>()
        for (product in vshop.products.values) if (!product.rotating && product.page == page && product.slot >= 0) out[product.slot] = product
        for (rotation in vshop.rotations.values) {
            val state = states[keyOf(vshop.id, rotation.id)] ?: continue
            val ordered = rotation.slots.toSortedMap().flatMap { (p, set) -> set.sorted().map { p to it } }
            for ((index, pos) in ordered.withIndex()) {
                if (pos.first != page) continue
                val product = state.items.getOrNull(index)?.let { vshop.products[it] } ?: continue
                out[pos.second] = product
            }
        }
        return out
    }

    /** 이 회전이 지금 걸어 둔 상품들. */
    fun current(vshop: VirtualShop, rotation: Rotation): List<Product> =
        states[keyOf(vshop.id, rotation.id)]?.items.orEmpty().mapNotNull { vshop.products[it] }

    fun tick(now: Long) {
        for (vshop in shop.shops.all()) {
            for (rotation in vshop.rotations.values) {
                val state = states[keyOf(vshop.id, rotation.id)]
                if (state == null || now >= state.nextAt) roll(vshop, rotation, state?.nextAt, now, announce = state != null)
            }
        }
    }

    /** 관리자 — 지금 굴린다. */
    fun force(vshop: VirtualShop, rotation: Rotation) {
        roll(vshop, rotation, states[keyOf(vshop.id, rotation.id)]?.nextAt, System.currentTimeMillis(), announce = true)
    }

    private fun roll(vshop: VirtualShop, rotation: Rotation, expected: Long?, now: Long, announce: Boolean) {
        val key = keyOf(vshop.id, rotation.id)
        if (!inFlight.add(key)) return
        val pool = rotation.products.ifEmpty { vshop.products.values.filter { it.rotating }.map { it.id } }
            .mapNotNull { vshop.products[it] }.filter { it.rotating }
        val picks = Rotations.pick(pool.map { it.id to it.weight }, rotation.slotCount) { ThreadLocalRandom.current().nextDouble() }
        val next = rotation.schedule.next(now, ZoneId.systemDefault()) ?: (now + 86_400_000L)
        val items = picks.joinToString(",")
        shop.db.run("회전 굴리기") {
            val mine = shop.db.rollRotation(shop.db.shared, key, expected, next, items)
            val row = if (mine) null else shop.db.rotation(shop.db.shared, key)
            shop.main {
                inFlight.remove(key)
                if (mine) {
                    states[key] = RotationState(next, picks)
                    if (announce && rotation.notify) {
                        val ph = Ph.of().shop(vshop.name)
                        for (player in Bukkit.getOnlinePlayers()) shop.messages.send(player, "rotation-changed", ph)
                    }
                } else if (row != null) {
                    states[key] = RotationState(row.nextAt, row.items.split(',').filter { it.isNotBlank() })
                }
            }
        }
    }

    /** 몇 초마다 — 공용 DB 면 다른 서버가 굴린 것을 가져온다. */
    fun sync() {
        if (shop.db.network == null) return
        shop.db.run("회전 읽기") {
            val rows = shop.db.rotations(shop.db.shared)
            shop.main {
                for (row in rows) {
                    if (row.id in inFlight) continue
                    states[row.id] = RotationState(row.nextAt, row.items.split(',').filter { it.isNotBlank() })
                }
            }
        }
    }

    fun forget(shopId: String, rotationId: String) {
        val key = keyOf(shopId, rotationId)
        states.remove(key)
        shop.db.run("회전 지우기") { shop.db.deleteRotation(shop.db.shared, key) }
    }
}
