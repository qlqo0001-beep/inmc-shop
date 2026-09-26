package com.inmc.shop.gui

import com.inmc.shop.Shop
import com.inmc.shop.trade.TradeType
import kr.inmc.core.economy.Currency
import kr.inmc.core.gui.DialogForm
import kr.inmc.core.gui.Icon
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** 구매·판매 화면이 거래 대상에게 묻는 것 — 서버 상점 상품과 상자 상점 상품이 같은 화면을 쓴다. */
interface TradeTarget {
    val type: TradeType
    val title: String
    fun icon(): ItemStack
    fun label(): String
    fun unit(): Int
    fun currency(): Currency?
    /** [units] 단위의 값(판매는 판매 배수까지). null = 지금 거래 불가. */
    fun total(player: Player, units: Long): Long?
    fun maxUnits(player: Player): Long
    fun execute(player: Player, units: Long, then: (Boolean) -> Unit)
}

/**
 * 구매·판매 수량 화면(ExcellentShop 의 Purchase GUI). 수량 ±1·8·32·64 · 1로 · 최대 · **직접 입력(입력창)** · 결제 · 취소.
 */
class TradeMenu(
    shop: Shop,
    viewer: Player,
    private val target: TradeTarget,
    override val back: (() -> Unit)?,
) : Menu(shop, viewer, 54, target.title) {

    private var units = 1L

    override fun draw() {
        clear()
        val max = target.maxUnits(viewer)
        units = units.coerceIn(1, maxOf(1, minOf(max, MAX)))
        for ((slot, step) in ADD) set(slot, step(step, true)) { units = (units + step).coerceAtMost(maxOf(1, max)); refresh() }
        for ((slot, step) in SUB) set(slot, step(step, false)) { units = (units - step).coerceAtLeast(1); refresh() }

        val preview = target.icon().clone().apply { amount = (units * target.unit()).coerceIn(1, 99).toInt() }
        val currency = target.currency()
        val total = target.total(viewer, units)
        set(SLOT_PREVIEW, Icon.annotate(preview, lore = listOf(
            "<gray>수량: <white>${units * target.unit()}</white>개 <dark_gray>(${units}단위)</dark_gray></gray>",
            "<gray>" + (if (target.type == TradeType.BUY) "지불" else "받음") + ": <white>" + (if (total == null || currency == null) "-" else currency.format(total)) + "</white></gray>",
            "<gray>최대: <white>" + (if (max >= MAX) "제한 없음" else "${max * target.unit()}개") + "</white></gray>",
        )))

        set(SLOT_CANCEL, Icon.of(Material.RED_DYE, "<red><b>취소</b></red>", "<gray>상점으로 돌아갑니다.</gray>")) { back?.invoke() ?: viewer.closeInventory() }
        set(SLOT_RESET, Icon.of(Material.BUCKET, "<red><b>1로</b></red>")) { units = 1; refresh() }
        set(SLOT_CUSTOM, Icon.of(Material.OAK_SIGN, "<gold><b>직접 입력</b></gold>", "<gray>원하는 수량을 적습니다(단위).</gray>")) {
            val form = DialogForm("<gold>수량</gold>").long("units", "단위 수 (1단위 = ${target.unit()}개)", units, min = 1, max = maxOf(1, minOf(max, MAX)))
            ask(form) { v -> units = v.long("units") ?: units }
        }
        set(SLOT_MAX, Icon.of(Material.LAVA_BUCKET, "<yellow><b>최대로</b></yellow>", "<gray>돈·재고·한도·가방 공간 안에서</gray>")) {
            units = maxOf(1, minOf(max, MAX)); refresh()
        }
        val verb = if (target.type == TradeType.BUY) "구매" else "판매"
        set(SLOT_CHECKOUT, Icon.of(Material.LIME_DYE, "<green><b>$verb</b></green>", listOf(
            "<gray>수량: <white>x${units * target.unit()}</white></gray>",
            "<gray>" + (if (target.type == TradeType.BUY) "지불" else "받음") + ": <white>" + (if (total == null || currency == null) "-" else currency.format(total)) + "</white></gray>",
            "", "<green>→ 클릭해서 $verb</green>",
        ))) {
            // 최대가 0 이어도 거래에 넘긴다 — 거래의 확인이 막힌 까닭(돈·재고·한도·가방 공간 …)을 하나씩 알려 준다.
            target.execute(viewer, units) { ok ->
                if (!viewer.isOnline) return@execute
                if (ok && shop.config.closeAfterPurchase) viewer.closeInventory()
                else if (ok && back != null) back.invoke()
                else refresh()
            }
        }
        fillEmpty(Icon.FILLER)
    }

    private fun step(amount: Long, add: Boolean): ItemStack {
        val stack = Icon.of(
            if (add) Material.LIME_STAINED_GLASS_PANE else Material.RED_STAINED_GLASS_PANE,
            if (add) "<green><b>+$amount</b></green>" else "<red><b>-$amount</b></red>",
        )
        stack.amount = amount.toInt().coerceIn(1, 64)
        return stack
    }

    companion object {
        const val SLOT_PREVIEW = 22
        const val SLOT_CANCEL = 45
        const val SLOT_RESET = 47
        const val SLOT_CUSTOM = 49
        const val SLOT_MAX = 51
        const val SLOT_CHECKOUT = 53
        const val MAX = 99L * 36

        /** 오른쪽 열 — 더하기, 왼쪽 열 — 빼기. */
        val ADD = listOf(8 to 1L, 17 to 8L, 26 to 32L, 35 to 64L)
        val SUB = listOf(0 to 1L, 9 to 8L, 18 to 32L, 27 to 64L)
    }
}
