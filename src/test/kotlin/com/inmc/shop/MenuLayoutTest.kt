package com.inmc.shop

import com.inmc.shop.gui.AuctionMenu
import com.inmc.shop.gui.AuctionMineMenu
import com.inmc.shop.gui.AuctionSellMenu
import com.inmc.shop.gui.ChestManageMenu
import com.inmc.shop.gui.ChestProductMenu
import com.inmc.shop.gui.ChestShopMenu
import com.inmc.shop.gui.SellMenu
import com.inmc.shop.gui.TradeMenu
import com.inmc.shop.virtual.Layout
import com.inmc.shop.virtual.LayoutButton
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 슬롯 상수 — 겹치면 나중에 그린 버튼만 보이고 **안 보이는 버튼의 클릭이 남는다**. 범위 밖은 `Menu.set` 이 조용히 버린다.
 * 컴파일러가 못 잡는 것이라 여기서 본다.
 */
class MenuLayoutTest {

    private fun slots(type: Class<*>): Map<String, Int> =
        type.declaredFields.filter { Modifier.isStatic(it.modifiers) && it.name.startsWith("SLOT_") && it.type == Int::class.javaPrimitiveType }
            .associate { it.isAccessible = true; it.name to it.getInt(null) }

    private fun check(type: Class<*>, size: Int, except: Set<Pair<String, String>> = emptySet()) {
        val map = slots(type)
        assertTrue(map.isNotEmpty(), type.simpleName)
        for ((name, slot) in map) assertTrue(slot in 0 until size, "${type.simpleName}.$name = $slot 은 $size 칸 밖")
        val clashes = map.entries.groupBy { it.value }.filter { it.value.size > 1 }
            .mapValues { e -> e.value.map { it.key } }
            .filter { (_, names) -> names.size != 2 || (names[0] to names[1]) !in except && (names[1] to names[0]) !in except }
        assertTrue(clashes.isEmpty(), "${type.simpleName} 겹침: $clashes")
    }

    @Test
    fun `화면마다 슬롯이 범위 안이고 겹치지 않는다`() {
        check(TradeMenu::class.java, 54)
        check(SellMenu::class.java, 54)
        check(ChestShopMenu::class.java, 54)
        // 관리 화면은 상품 칸 끝(36)과 첫 버튼이 같은 번호를 "경계" 로 쓴다.
        check(ChestManageMenu::class.java, 54, setOf("SLOT_PRODUCTS" to "SLOT_NAME"))
        check(ChestProductMenu::class.java, 27)
        check(AuctionMenu::class.java, 54)
        check(AuctionSellMenu::class.java, 27)
        check(AuctionMineMenu::class.java, 54)
    }

    @Test
    fun `구매 화면 — 수량 버튼과 다른 버튼이 겹치지 않는다`() {
        val buttons = slots(TradeMenu::class.java).values.toSet()
        val steps = (TradeMenu.ADD + TradeMenu.SUB).map { it.first }
        assertEquals(steps.size, steps.toSet().size)
        assertTrue(steps.none { it in buttons }, "수량 버튼이 다른 버튼을 덮는다")
    }

    @Test
    fun `경매장 — 물건 칸·분류 탭·아래 줄이 겹치지 않는다`() {
        val grid = AuctionMenu.GRID
        assertEquals(40, grid.size)
        assertTrue(grid.all { it in 0 until 45 }, "물건 칸은 위 다섯 줄")
        val tabs = AuctionMenu.TAB_SLOTS + listOf(AuctionMenu.SLOT_TAB_ALL, AuctionMenu.SLOT_TAB_MORE)
        assertTrue(tabs.none { it in grid }, "분류 탭이 물건 칸을 덮는다")
        val bottom = slots(AuctionMenu::class.java).filterKeys { !it.startsWith("SLOT_TAB") }.values
        assertTrue(bottom.all { it >= 45 }, "아래 줄 버튼은 45 이상")
    }

    @Test
    fun `내 상자 상점 관리 — 상품 칸은 버튼 위 네 줄`() {
        val buttons = slots(ChestManageMenu::class.java).filterKeys { it != "SLOT_PRODUCTS" }.values
        assertTrue(buttons.all { it >= ChestManageMenu.SLOT_PRODUCTS }, "버튼이 상품 칸을 덮는다")
    }

    @Test
    fun `기본 레이아웃 — 상품 칸 45개, 버튼은 맨 아래 줄`() {
        val layout = Layout.default(decoration = "x")
        assertEquals(45, layout.productSlots().size)
        assertTrue(layout.buttons.values.all { it in 45..53 })
        assertEquals(layout.buttons.size, layout.buttons.values.toSet().size)
        val main = Layout.mainMenu("x")
        assertTrue(LayoutButton.CLOSE in main.buttons)
    }
}
