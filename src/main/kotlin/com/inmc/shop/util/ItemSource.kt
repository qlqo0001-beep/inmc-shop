package com.inmc.shop.util

import org.bukkit.block.ShulkerBox
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta

/**
 * 파는 물건이 어디서 나오나 — 가방 · 판매 창 · 가방 속 셜커 상자. 판매는 이 셋을 같은 길로 지난다.
 * [take] 는 뺀 조각을 돌려주고, 거래가 뒤에서 실패하면 [restore] 로 그대로 돌려놓는다.
 */
interface ItemSource {
    fun count(matches: (ItemStack) -> Boolean): Int
    fun take(matches: (ItemStack) -> Boolean, amount: Int): List<ItemStack>
    fun restore(pieces: List<ItemStack>)

    /** 물건 목록(최고가 찾기용). */
    fun stacks(): List<ItemStack>
}

class PlayerSource(private val player: Player) : ItemSource {
    override fun count(matches: (ItemStack) -> Boolean) = Inv.count(player, matches)
    override fun take(matches: (ItemStack) -> Boolean, amount: Int) = Inv.take(player, matches, amount)
    override fun restore(pieces: List<ItemStack>) = Inv.restore(player, pieces)
    override fun stacks(): List<ItemStack> = player.inventory.storageContents.take(36).filterNotNull().filter { !it.type.isAir }
}

/** 판매 창 같은 화면 — [slots] 칸만. */
class InventorySource(private val inventory: Inventory, private val slots: IntRange, private val owner: Player) : ItemSource {
    override fun count(matches: (ItemStack) -> Boolean): Int = slots.sumOf { i -> inventory.getItem(i)?.takeIf { !it.type.isAir && matches(it) }?.amount ?: 0 }

    override fun take(matches: (ItemStack) -> Boolean, amount: Int): List<ItemStack> {
        var left = amount
        val taken = ArrayList<ItemStack>()
        for (i in slots) {
            if (left <= 0) break
            val stack = inventory.getItem(i) ?: continue
            if (stack.type.isAir || !matches(stack)) continue
            val n = minOf(left, stack.amount)
            taken += stack.clone().apply { this.amount = n }
            if (n == stack.amount) inventory.setItem(i, null) else inventory.setItem(i, stack.clone().apply { this.amount = stack.amount - n })
            left -= n
        }
        return taken
    }

    override fun restore(pieces: List<ItemStack>) {
        for (piece in pieces) {
            val over = inventory.addItem(piece)
            for (rest in over.values) Inv.restore(owner, listOf(rest))
        }
    }

    override fun stacks(): List<ItemStack> = slots.mapNotNull { inventory.getItem(it) }.filter { !it.type.isAir }
}

/**
 * 가방 [slot] 칸의 셜커 상자 안. 읽을 때마다 아이템 메타를 새로 읽고, 고치면 그 칸에 다시 쓴다(가방의 아이템은 복제본이다).
 * 꾸러미(번들) 안은 보지 않는다 — 원본과 같다.
 */
class ContainerSource(private val player: Player, private val slot: Int) : ItemSource {

    private fun box(): Pair<ItemStack, ShulkerBox>? {
        val item = player.inventory.getItem(slot) ?: return null
        val meta = item.itemMeta as? BlockStateMeta ?: return null
        val state = meta.blockState as? ShulkerBox ?: return null
        return item to state
    }

    private fun write(item: ItemStack, state: ShulkerBox) {
        val meta = item.itemMeta as BlockStateMeta
        meta.blockState = state
        item.itemMeta = meta
        player.inventory.setItem(slot, item)
    }

    override fun count(matches: (ItemStack) -> Boolean): Int =
        box()?.second?.inventory?.contents?.filterNotNull()?.filter { !it.type.isAir && matches(it) }?.sumOf { it.amount } ?: 0

    override fun take(matches: (ItemStack) -> Boolean, amount: Int): List<ItemStack> {
        val (item, state) = box() ?: return emptyList()
        val inv = state.inventory
        var left = amount
        val taken = ArrayList<ItemStack>()
        for (i in 0 until inv.size) {
            if (left <= 0) break
            val stack = inv.getItem(i) ?: continue
            if (stack.type.isAir || !matches(stack)) continue
            val n = minOf(left, stack.amount)
            taken += stack.clone().apply { this.amount = n }
            if (n == stack.amount) inv.setItem(i, null) else inv.setItem(i, stack.clone().apply { this.amount = stack.amount - n })
            left -= n
        }
        write(item, state)
        return taken
    }

    override fun restore(pieces: List<ItemStack>) {
        val (item, state) = box() ?: return Inv.restore(player, pieces)
        val over = state.inventory.addItem(*pieces.toTypedArray())
        write(item, state)
        if (over.isNotEmpty()) Inv.restore(player, over.values.toList())
    }

    override fun stacks(): List<ItemStack> = box()?.second?.inventory?.contents?.filterNotNull()?.filter { !it.type.isAir }.orEmpty()

    companion object {
        /** 가방에서 셜커 상자가 든 칸. */
        fun slotsOf(player: Player): List<Int> = (0 until 36).filter { i ->
            val item = player.inventory.getItem(i) ?: return@filter false
            item.type.name.endsWith("SHULKER_BOX") && (item.itemMeta as? BlockStateMeta)?.blockState is ShulkerBox
        }
    }
}
