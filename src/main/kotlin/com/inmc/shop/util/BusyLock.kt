package com.inmc.shop.util

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 한 사람의 거래는 한 번에 하나 — 재고·은행을 잡는 사이 두 번 누르기 방지. **시간이 지나면 저절로 풀린다**:
 * DB 콜백이 오류로 안 돌아오면 그 사람은 영영 거래를 못 하게 된다.
 */
class BusyLock(private val timeoutMillis: Long = 10_000L) {

    private val held = ConcurrentHashMap<UUID, Long>()

    fun acquire(player: UUID): Boolean {
        val now = System.currentTimeMillis()
        var won = false
        held.compute(player) { _, since ->
            if (since == null || now - since > timeoutMillis) { won = true; now } else since
        }
        return won
    }

    fun release(player: UUID) {
        held.remove(player)
    }
}
