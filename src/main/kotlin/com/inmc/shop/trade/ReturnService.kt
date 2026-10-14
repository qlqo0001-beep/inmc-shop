package com.inmc.shop.trade

import com.inmc.shop.Shop
import com.inmc.shop.util.Inv
import com.inmc.shop.util.Ph
import kr.inmc.core.integration.Mail
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * 돌려받을 물건 — 가방이 차서 못 준 것(상자 상점 삭제·회수·임대 종료).
 * 우편 창구(core [Mail])가 있으면 **우편으로** 보낸다(2026-10-10 — 미수령 보상은 우편 하나로). 없으면 로컬 DB `shop_return` 에 적는다.
 * 이미 `shop_return` 에 쌓인 옛 행은 그대로 — 접속할 때 알리고, `/상자상점 돌려받기`(관리 화면 버튼)로 받는다.
 * 받을 때는 DB 에서 지운 것만 준다(두 번 받기 방지). 한 묶음은 우편 아니면 표 — 둘 다로 가지 않는다.
 */
class ReturnService(private val shop: Shop) {

    /** 쌓아 둔다. [stack] 의 개수 그대로. */
    fun add(player: UUID, stack: ItemStack, reason: String) {
        if (stack.type.isAir || stack.amount <= 0) return
        val encoded = Inv.encode(stack)
        val now = System.currentTimeMillis()
        shop.db.run("돌려받을 물건 적기") { shop.db.addReturn(shop.db.local, player, encoded, reason, now) }
    }

    /** 가방에 넣고, 안 들어가는 것은 우편으로(창구가 없으면 쌓아 둔다). */
    fun giveOrStore(player: Player, template: ItemStack, amount: Int, reason: String) {
        val left = Inv.give(player, template, amount, drop = false)
        if (left > 0) {
            val mailed = storeSplit(player.uniqueId, template, left, reason)
            shop.messages.send(player, if (mailed) "returns-mailed" else "returns-stored", Ph.of().amount(left.toLong()))
        }
    }

    /**
     * 가방에 넣고, 안 들어가는 것은 우편으로 — 창구가 없거나 거절하면 예전처럼 발밑에(둘 다로는 안 간다).
     * 산 물건·경매에서 돌려받은 물건처럼 **원래 발밑에 떨구던** 넘침에 쓴다(2026-10-10). 표(`shop_return`)에 쌓는 넘침은 [giveOrStore].
     */
    fun giveOrMail(player: Player, template: ItemStack, amount: Int, reason: String) {
        val left = Inv.give(player, template, amount, drop = false)
        if (left <= 0) return
        val stacks = splitStacks(left, template.maxStackSize).map { n -> template.clone().apply { this.amount = n } }
        if (Mail.send(player.uniqueId, shop.plugin.name, "상점 · $reason", stacks)) {
            shop.messages.send(player, "returns-mailed", Ph.of().amount(left.toLong()))
        } else {
            Inv.dropAt(player.location, template, left)
        }
    }

    /** 접속하지 않은 사람에게. 우편으로 갔으면 true, `shop_return` 에 쌓았으면 false. */
    fun storeSplit(player: UUID, template: ItemStack, amount: Int, reason: String): Boolean {
        val stacks = splitStacks(amount, template.maxStackSize).map { n -> template.clone().apply { this.amount = n } }
        if (stacks.isEmpty()) return false
        // 우편이 받았으면 끝 — 표에도 적으면 두 번 받는다. 거절(창구 없음·실패)이면 예전처럼 표에.
        if (Mail.send(player, shop.plugin.name, "상점 · $reason", stacks)) return true
        for (stack in stacks) add(player, stack, reason)
        return false
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

    companion object {
        /** [amount] 개를 한 칸에 [maxStack] 개씩 나눈 묶음 크기들. 0 이하면 빈 목록. */
        fun splitStacks(amount: Int, maxStack: Int): List<Int> {
            val max = maxStack.coerceAtLeast(1)
            val out = ArrayList<Int>()
            var left = amount
            while (left > 0) {
                val batch = minOf(left, max)
                out += batch
                left -= batch
            }
            return out
        }
    }
}
