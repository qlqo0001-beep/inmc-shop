package com.inmc.shop.gui

import com.inmc.shop.Shop
import com.inmc.shop.util.InventorySource
import com.inmc.shop.util.Inv
import kr.inmc.core.gui.Icon
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent

/**
 * 판매 창(`/판매`) — 위 다섯 줄에 팔 것을 넣고 "판매" 를 누른다. 못 파는 것과 남은 것은 **닫을 때 전부 돌려준다.**
 * 버튼에 지금 넣은 것의 예상 금액이 보인다(넣고 빼면 다음 틱에 다시 계산).
 */
class SellMenu(shop: Shop, viewer: Player) : Menu(shop, viewer, 54, "<dark_gray>판매 창 — 팔 것을 넣으세요</dark_gray>") {

    private val source = InventorySource(inventory, 0 until SELL_SLOTS, viewer)
    private var returned = false

    override fun isSlotEditable(slot: Int): Boolean = slot in 0 until SELL_SLOTS

    override fun acceptsShiftInsert(): Boolean = true

    override fun draw() {
        for (slot in SELL_SLOTS until size) set(slot, Icon.FILLER)
        drawButton()
        set(SLOT_INFO, Icon.of(Material.BOOK, "<yellow>판매 창</yellow>",
            "<gray>팔 것을 위 칸에 넣고 <green>판매</green> 를 누르세요.</gray>",
            "<gray>물건마다 가장 비싸게 사주는 상점에 팝니다.</gray>",
            "<gray>못 파는 것과 남은 것은 창을 닫으면 돌려받습니다.</gray>"))
        set(SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    private fun drawButton() {
        val estimate = shop.sell.estimate(viewer, source)
        val lore = if (estimate.isEmpty()) listOf("<gray>팔 수 있는 것이 없습니다</gray>")
        else listOf("<gray>예상: <white>" + estimate.entries.joinToString(", ") { (c, v) -> c.format(v) } + "</white></gray>", "", "<green>▶ 클릭해서 판매</green>")
        set(SLOT_SELL, Icon.of(Material.EMERALD, "<green><b>판매</b></green>", lore)) {
            if (!shop.guard(viewer)) return@set
            shop.sell.sellEverything(viewer, listOf(source)) { if (viewer.isOnline && viewer.openInventory.topInventory == inventory) drawButton() }
        }
    }

    override fun handleClick(event: InventoryClickEvent) {
        super.handleClick(event)
        viewer.scheduler.run(shop.plugin, { _ -> if (viewer.openInventory.topInventory == inventory) drawButton() }, null)
    }

    override fun onDrag(event: InventoryDragEvent) {
        super.onDrag(event)
        viewer.scheduler.run(shop.plugin, { _ -> if (viewer.openInventory.topInventory == inventory) drawButton() }, null)
    }

    override fun onClose(event: InventoryCloseEvent) {
        if (returned) return
        returned = true
        val left = (0 until SELL_SLOTS).mapNotNull { inventory.getItem(it)?.takeIf { s -> !s.type.isAir } }
        for (slot in 0 until SELL_SLOTS) inventory.setItem(slot, null)
        if (left.isNotEmpty()) Inv.restore(viewer, left)
    }

    companion object {
        const val SELL_SLOTS = 45
        const val SLOT_INFO = 45
        const val SLOT_SELL = 49
        const val SLOT_CLOSE = 53
    }
}
