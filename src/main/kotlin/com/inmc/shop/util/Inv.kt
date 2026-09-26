package com.inmc.shop.util

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 가방 계산. **가방 칸(0~35)만** 본다 — 갑옷·왼손은 사고팔기에 끼지 않는다(입은 것을 팔아 버리면 안 된다).
 */
object Inv {

    private const val STORAGE = 36

    fun count(player: Player, matches: (ItemStack) -> Boolean): Int {
        var total = 0
        val contents = player.inventory.storageContents
        for (i in 0 until minOf(STORAGE, contents.size)) {
            val stack = contents[i] ?: continue
            if (!stack.type.isAir && matches(stack)) total += stack.amount
        }
        return total
    }

    /** 맞는 것을 [amount] 개까지 뺀다. 뺀 개수를 돌려준다. 칸 번호로 읽고 써야 한다 — `getContents()` 는 복제본이다. */
    fun remove(player: Player, matches: (ItemStack) -> Boolean, amount: Int): Int {
        var left = amount
        val inventory = player.inventory
        for (i in 0 until STORAGE) {
            if (left <= 0) break
            val stack = inventory.getItem(i) ?: continue
            if (stack.type.isAir || !matches(stack)) continue
            val take = minOf(left, stack.amount)
            if (take == stack.amount) inventory.setItem(i, null) else inventory.setItem(i, stack.clone().apply { this.amount = stack.amount - take })
            left -= take
        }
        return amount - left
    }

    /** 맞는 것을 [amount] 개 빼고 **뺀 조각들**을 돌려준다 — 거래가 뒤에서 실패하면 그대로 [restore] 한다. */
    fun take(player: Player, matches: (ItemStack) -> Boolean, amount: Int): List<ItemStack> {
        var left = amount
        val taken = ArrayList<ItemStack>()
        val inventory = player.inventory
        for (i in 0 until STORAGE) {
            if (left <= 0) break
            val stack = inventory.getItem(i) ?: continue
            if (stack.type.isAir || !matches(stack)) continue
            val take = minOf(left, stack.amount)
            taken += stack.clone().apply { this.amount = take }
            if (take == stack.amount) inventory.setItem(i, null) else inventory.setItem(i, stack.clone().apply { this.amount = stack.amount - take })
            left -= take
        }
        return taken
    }

    /** [take] 로 뺀 것을 돌려놓는다. 안 들어가면 발밑에. */
    fun restore(player: Player, pieces: List<ItemStack>) {
        for (piece in pieces) {
            val over = player.inventory.addItem(piece)
            for (rest in over.values) player.world.dropItemNaturally(player.location, rest)
        }
    }

    /** [template] 이 몇 개 더 들어가나. */
    fun space(player: Player, template: ItemStack): Int {
        val max = template.maxStackSize.coerceAtLeast(1)
        var room = 0
        val contents = player.inventory.storageContents
        for (i in 0 until minOf(STORAGE, contents.size)) {
            val stack = contents[i]
            room += when {
                stack == null || stack.type.isAir -> max
                stack.isSimilar(template) -> (max - stack.amount).coerceAtLeast(0)
                else -> 0
            }
        }
        return room
    }

    /** [template] 을 [amount] 개 준다. 못 넣은 것은 [drop] 이면 발밑에, 아니면 돌려준다(남은 개수). */
    fun give(player: Player, template: ItemStack, amount: Int, drop: Boolean): Int {
        var left = amount
        val max = template.maxStackSize.coerceAtLeast(1)
        while (left > 0) {
            val batch = minOf(left, max)
            val over = player.inventory.addItem(template.clone().apply { this.amount = batch })
            val notFit = over.values.sumOf { it.amount }
            left -= batch - notFit
            if (notFit > 0) {
                if (!drop) return left
                dropAt(player.location, template, left)
                return 0
            }
        }
        return 0
    }

    fun dropAt(location: Location, template: ItemStack, amount: Int) {
        var left = amount
        val max = template.maxStackSize.coerceAtLeast(1)
        val world = location.world ?: return
        while (left > 0) {
            val batch = minOf(left, max)
            world.dropItemNaturally(location, template.clone().apply { this.amount = batch })
            left -= batch
        }
    }

    /** 아이템을 글자로(DB·파일). */
    fun encode(stack: ItemStack): String = java.util.Base64.getEncoder().encodeToString(stack.serializeAsBytes())

    fun decode(raw: String?): ItemStack? = raw?.let { runCatching { ItemStack.deserializeBytes(java.util.Base64.getDecoder().decode(it)) }.getOrNull() }
}
