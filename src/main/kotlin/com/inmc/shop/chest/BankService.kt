package com.inmc.shop.chest

import com.inmc.shop.Shop
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 상점 은행 — 상자 상점의 판매 수익이 들어가는 곳(사람마다 · 화폐마다). 공용 DB 면 여러 서버가 같이 쓴다.
 *
 * 더하기는 차이로(`bankAdd`), 빼기는 **조건부**(`bankTake` — 모자라면 안 빠진다). 그래서 빼기는 언제나 비동기이고 결과는 콜백.
 * 메모리 값은 화면 표시용 사본이다.
 */
class BankService(private val shop: Shop) {

    private val cache = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Long>>()

    /** 화면에 보일 잔고 — 읽고 나서 메인에서 [then]. */
    fun balances(player: UUID, then: (Map<String, Long>) -> Unit) {
        shop.db.run("은행 읽기") {
            val map = shop.db.bank(shop.db.shared, player)
            shop.main {
                cache[player] = ConcurrentHashMap(map)
                then(map)
            }
        }
    }

    fun cached(player: UUID, currency: String): Long = cache[player]?.get(currency) ?: 0

    fun deposit(player: UUID, currency: String, amount: Long) {
        if (amount <= 0) return
        cache[player]?.merge(currency, amount, Long::plus)
        shop.db.run("은행 넣기") { shop.db.bankAdd(shop.db.shared, player, currency, amount) }
    }

    /** 뺀다 — 모자라면 false. 결과는 메인에서. */
    fun take(player: UUID, currency: String, amount: Long, then: (Boolean) -> Unit) {
        if (amount <= 0) return then(true)
        shop.db.run("은행 빼기") {
            val ok = shop.db.bankTake(shop.db.shared, player, currency, amount)
            val map = shop.db.bank(shop.db.shared, player)
            shop.main {
                cache[player] = ConcurrentHashMap(map)
                then(ok)
            }
        }
    }
}
