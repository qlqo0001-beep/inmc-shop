package com.inmc.shop.gui

import com.inmc.shop.Shop
import com.inmc.shop.virtual.RankValues
import kr.inmc.core.gui.DialogForm
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * core [kr.inmc.core.gui.Menu] 에 이 플러그인의 로케이터를 붙인 얇은 층. 리로드가 열린 화면을 닫을 때 [owner] 로 우리 것을 가려낸다.
 * 값 입력은 채팅이 아니라 **입력창(Dialog)** — [ask]. 입력창을 닫으면(확인·취소·Esc) 이 화면이 다시 열린다.
 */
abstract class Menu(
    protected val shop: Shop,
    protected val viewer: Player,
    size: Int,
    title: String,
) : kr.inmc.core.gui.Menu(size, Text.renderFlat(title)) {

    override val owner: Any get() = shop

    /** 뒤로 버튼이 여는 화면. null 이면 뒤로 버튼이 없다. */
    protected open val back: (() -> Unit)? = null

    protected fun navigation(backSlot: Int = Paging.SLOT_BACK, closeSlot: Int = Paging.SLOT_CLOSE) {
        back?.let { go -> set(backSlot, Icon.back()) { go() } }
        set(closeSlot, Icon.close()) { viewer.closeInventory() }
    }

    fun show() = open(viewer)

    /** 입력창을 띄운다. 확인하면 [onSubmit] 뒤에 이 화면을 다시 연다(그 안에서 다른 화면을 열면 그쪽이 이긴다). */
    protected fun ask(form: DialogForm, reopen: () -> Unit = { show() }, onSubmit: (DialogForm.Values) -> Unit) {
        form.show(shop.plugin, viewer, onCancel = { reopen() }) { _, values ->
            onSubmit(values)
            if (viewer.openInventory.topInventory.holder !is kr.inmc.core.gui.Menu) reopen()
        }
    }

    /** 페이지 버튼. */
    protected fun pager(page: Int, total: Int, perPage: Int = Paging.PER_PAGE, prevSlot: Int = Paging.SLOT_PREV, nextSlot: Int = Paging.SLOT_NEXT, go: (Int) -> Unit) {
        val pages = Paging.pageCount(total, perPage)
        if (page > 0) set(prevSlot, Icon.prevPage()) { go(page - 1) }
        if (page < pages - 1) set(nextSlot, Icon.nextPage()) { go(page + 1) }
    }

    protected fun toggleIcon(name: String, value: Boolean, vararg lore: String): ItemStack =
        Icon.of(Icon.toggleMaterial(value), "<yellow>$name: </yellow>" + Icon.toggle(value), lore.toList() + listOf("", "<gray>클릭해서 바꾸기</gray>"))

    protected fun valueIcon(material: Material, name: String, value: String, vararg lore: String): ItemStack =
        Icon.of(material, "<yellow>$name: </yellow><white>$value</white>", lore.toList() + listOf("", "<gray>클릭해서 바꾸기</gray>"))

    /** 등급별 값(판매 배수·최대 상점 수 …) 입력창. 값 줄은 `vip=1.5, gold=2`. */
    protected fun askRankValues(title: String, current: RankValues, integer: Boolean, apply: (RankValues) -> Unit) {
        val form = DialogForm(title)
            .line("<gray>RANK = LuckPerms 그룹(group.<이름>) · PERMISSION = 접두사+이름 권한</gray>")
            .line("<gray>값 -1 = 무제한. 여러 개가 맞으면 가장 큰 값.</gray>")
            .choice("mode", "방식", RankValues.Mode.entries.map { it.name to it.name }, current.mode.name)
            .text("prefix", "권한 접두사(PERMISSION 방식)", current.prefix)
            .decimal("default", "기본값", current.default)
            .text("values", "등급=값, …", current.values.entries.joinToString(", ") { (k, v) -> k + "=" + fmt(v, integer) }, maxLength = 1000)
        ask(form) { v ->
            val values = LinkedHashMap<String, Double>()
            for (part in v.text("values").split(',')) {
                val key = part.substringBefore('=').trim()
                val num = part.substringAfter('=', "").trim().toDoubleOrNull() ?: continue
                if (key.isNotEmpty()) values[key] = num
            }
            apply(RankValues(runCatching { RankValues.Mode.valueOf(v.choice("mode") ?: "RANK") }.getOrDefault(RankValues.Mode.RANK), v.text("prefix").trim(), v.decimal("default") ?: current.default, values))
        }
    }

    protected fun fmt(value: Double, integer: Boolean): String = if (integer || value == Math.floor(value)) value.toLong().toString() else value.toString()

    protected fun rankLore(values: RankValues, integer: Boolean): List<String> = buildList {
        add("<gray>방식: <white>${values.mode}</white>" + if (values.mode == RankValues.Mode.PERMISSION) " <dark_gray>(${values.prefix}…)</dark_gray>" else "")
        add("<gray>기본: <white>${fmt(values.default, integer)}</white>")
        for ((k, v) in values.values) add("<gray> · $k: <white>${if (v < 0) "무제한" else fmt(v, integer)}</white>")
    }
}

/**
 * 여러 보기 중 하나(또는 여럿)를 고르는 화면.
 */
class PickMenu<T>(
    shop: Shop,
    viewer: Player,
    title: String,
    private val options: List<T>,
    private val icon: (T) -> ItemStack,
    private val multi: Boolean = false,
    private val selected: () -> Set<T> = { emptySet() },
    override val back: (() -> Unit)?,
    private val onPick: (T) -> Unit,
) : Menu(shop, viewer, 54, title) {

    private var page = 0

    override fun draw() {
        clear()
        page = Paging.clamp(page, options.size)
        val chosen = selected()
        for ((slot, option) in Paging.slice(options, page).withIndex()) {
            val base = icon(option)
            val shown = if (!multi) base else Icon.annotate(
                base.clone().also { if (option in chosen) it.editMeta { meta -> meta.setEnchantmentGlintOverride(true) } },
                lore = listOf("", if (option in chosen) "<green>▶ 켜짐 - 클릭해서 끄기</green>" else "<gray>▶ 꺼짐 - 클릭해서 켜기</gray>"),
            )
            set(slot, shown) {
                onPick(option)
                if (multi) refresh()
            }
        }
        fillEmpty(Icon.FILLER)
        pager(page, options.size) { page = it; refresh() }
        navigation()
    }
}
