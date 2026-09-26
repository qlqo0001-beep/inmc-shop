package com.inmc.shop.trade

import com.inmc.shop.Shop
import com.inmc.shop.util.Inv
import com.inmc.shop.util.Ph
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * 돌려받을 물건 — 가방이 차서 못 준 것(상자 상점 삭제·회수·임대 종료). 로컬 DB 에 **먼저 적고** 나서 게임에서 뺀다.
 * 접속할 때 알리고, `/상자상점 돌려받기`(관리 화면 버튼)로 받는다. 받을 때는 DB 에서 지운 것만 준다(두 번 받기 방지).
 */
class ReturnService(private val shop: Shop) {

    /** 쌓아 둔다. [stack] 의 개수 그대로. */
    fun add(player: UUID, stack: ItemStack, reason: String) {
        if (stack.type.isAir || stack.amount <= 0) return
        val encoded = Inv.encode(stack)
        val now = System.currentTimeMillis()
        shop.db.run("돌려받을 물건 적기") { shop.db.addReturn(shop.db.local, player, encoded, reason, now) }
    }

    /** 가방에 넣고, 안 들어가는 것은 쌓아 둔다. */
    fun giveOrStore(player: Player, template: ItemStack, amount: Int, reason: String) {
        val left = Inv.give(player, template, amount, drop = false)
        if (left > 0) {
            storeSplit(player.uniqueId, template, left, reason)
            shop.messages.send(player, "returns-stored", Ph.of().amount(left.toLong()))
        }
    }

    /** 접속하지 않은 사람에게. */
    fun storeSplit(player: UUID, template: ItemStack, amount: Int, reason: String) {
        var left = amount
        val max = template.maxStackSize.coerceAtLeast(1)
        while (left > 0) {
            val batch = minOf(left, max)
            add(player, template.clone().apply { this.amount = batch }, reason)
            left -= batch
        }
    }

    /**
     * 받을 수 있는 만큼 받는다. 끝나면 [then](받은 묶음 수, 남은 묶음 수).
     * DB 에서 **지운 것만** 가져와 주고(두 번 누르거나 겹쳐도 한 번), 가방에 안 들어가는 것은 다시 적는다.
     */
    fun claim(player: Player, then: (Int, Int) -> Unit) {
        val id = player.uniqueId
        shop.db.run("돌려받을 물건 꺼내기") {
            val taken = shop.db.returns(shop.db.local, id).filter { shop.db.takeReturn(shop.db.local, it.id) }
            shop.main {
                var claimed = 0
                var left = 0
                for (row in taken) {
                    val stack = Inv.decode(row.item)
                    if (stack == null) { shop.logger.warning("돌려받을 물건을 읽지 못했습니다(${row.id}) - 원문을 다시 적습니다"); restore(id, row.item, row.reason); left++; continue }
                    if (!player.isOnline || Inv.space(player, stack) < stack.amount) { restore(id, row.item, row.reason); left++; continue }
                    Inv.give(player, stack, stack.amount, drop = true)
                    claimed++
                }
                then(claimed, left)
            }
        }
    }

    private fun restore(player: UUID, encoded: String, reason: String) {
        val now = System.currentTimeMillis()
        shop.db.run("돌려받을 물건 되돌리기") { shop.db.addReturn(shop.db.local, player, encoded, reason, now) }
    }

    fun count(player: UUID, then: (Int) -> Unit) {
        shop.db.run("돌려받을 물건 세기") {
            val n = shop.db.returns(shop.db.local, player).size
            shop.main { then(n) }
        }
    }
}
