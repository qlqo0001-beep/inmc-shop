package com.inmc.shop.trade

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.event.inventory.ClickType

/** 상품이 지금 어떤 상태인가 — 클릭 동작과 설명 줄이 이걸로 갈린다. */
enum class ProductState(val label: String) {
    BUYABLE("구매만"),
    SELLABLE("판매만"),
    BOTH("구매·판매"),
    ;

    companion object {
        fun of(buy: Boolean, sell: Boolean): ProductState? = when {
            buy && sell -> BOTH
            buy -> BUYABLE
            sell -> SELLABLE
            else -> null
        }
    }
}

enum class ClickKind(val label: String) {
    LEFT("좌클릭"),
    RIGHT("우클릭"),
    SHIFT_LEFT("Shift+좌클릭"),
    SHIFT_RIGHT("Shift+우클릭"),
    NUMBER_KEY("숫자키"),
    DROP("Q"),
    CONTROL_DROP("Ctrl+Q"),
    ;

    companion object {
        fun of(type: ClickType): ClickKind? = when (type) {
            ClickType.LEFT -> LEFT
            ClickType.RIGHT -> RIGHT
            ClickType.SHIFT_LEFT -> SHIFT_LEFT
            ClickType.SHIFT_RIGHT -> SHIFT_RIGHT
            ClickType.NUMBER_KEY -> NUMBER_KEY
            ClickType.DROP -> DROP
            ClickType.CONTROL_DROP -> CONTROL_DROP
            else -> null
        }
    }
}

enum class ClickAction(val label: String) {
    OPEN_BUY("구매 창 열기"),
    OPEN_SELL("판매 창 열기"),
    BUY_ONE("1개 구매"),
    SELL_ONE("1개 판매"),
    SELL_ALL("가진 것 전부 판매"),
    NONE("아무것도 안 함"),
}

/** 상품 상태 × 클릭 → 동작. 설정 화면에서 고친다. */
data class ClickMap(val map: Map<ProductState, Map<ClickKind, ClickAction>>) {

    fun actionFor(state: ProductState, click: ClickKind): ClickAction = map[state]?.get(click) ?: ClickAction.NONE

    fun with(state: ProductState, click: ClickKind, action: ClickAction): ClickMap {
        val inner = map[state].orEmpty().toMutableMap()
        if (action == ClickAction.NONE) inner.remove(click) else inner[click] = action
        return ClickMap(map + (state to inner))
    }

    /** 설명 줄 — "좌클릭 → 구매 창 열기". */
    fun describe(state: ProductState): List<Pair<ClickKind, ClickAction>> =
        ClickKind.entries.mapNotNull { kind -> map[state]?.get(kind)?.takeIf { it != ClickAction.NONE }?.let { kind to it } }

    fun save(section: ConfigurationSection) {
        for ((state, inner) in map) {
            val node = section.createSection(state.name)
            for ((kind, action) in inner) node.set(kind.name, action.name)
        }
    }

    companion object {
        /** 서버 상점 기본값 — ExcellentShop 과 같다. */
        val VIRTUAL = ClickMap(
            mapOf(
                ProductState.BOTH to mapOf(
                    ClickKind.LEFT to ClickAction.OPEN_BUY, ClickKind.RIGHT to ClickAction.OPEN_SELL,
                    ClickKind.SHIFT_LEFT to ClickAction.BUY_ONE, ClickKind.SHIFT_RIGHT to ClickAction.SELL_ONE,
                    ClickKind.CONTROL_DROP to ClickAction.SELL_ALL,
                ),
                ProductState.BUYABLE to mapOf(ClickKind.LEFT to ClickAction.OPEN_BUY, ClickKind.RIGHT to ClickAction.BUY_ONE, ClickKind.NUMBER_KEY to ClickAction.BUY_ONE),
                ProductState.SELLABLE to mapOf(
                    ClickKind.LEFT to ClickAction.OPEN_SELL, ClickKind.RIGHT to ClickAction.SELL_ONE,
                    ClickKind.NUMBER_KEY to ClickAction.SELL_ONE, ClickKind.CONTROL_DROP to ClickAction.SELL_ALL,
                ),
            ),
        )

        /** 상자 상점 기본값 — ExcellentShop 과 같다(바로 사고팔기가 좌·우). */
        val CHEST = ClickMap(
            mapOf(
                ProductState.BOTH to mapOf(
                    ClickKind.LEFT to ClickAction.BUY_ONE, ClickKind.RIGHT to ClickAction.SELL_ONE,
                    ClickKind.SHIFT_LEFT to ClickAction.OPEN_BUY, ClickKind.SHIFT_RIGHT to ClickAction.OPEN_SELL,
                    ClickKind.CONTROL_DROP to ClickAction.SELL_ALL,
                ),
                ProductState.BUYABLE to mapOf(ClickKind.LEFT to ClickAction.BUY_ONE, ClickKind.RIGHT to ClickAction.OPEN_BUY, ClickKind.NUMBER_KEY to ClickAction.BUY_ONE),
                ProductState.SELLABLE to mapOf(
                    ClickKind.LEFT to ClickAction.SELL_ONE, ClickKind.RIGHT to ClickAction.OPEN_SELL,
                    ClickKind.NUMBER_KEY to ClickAction.SELL_ONE, ClickKind.CONTROL_DROP to ClickAction.SELL_ALL,
                ),
            ),
        )

        fun load(section: ConfigurationSection?, fallback: ClickMap): ClickMap {
            section ?: return fallback
            val map = HashMap<ProductState, Map<ClickKind, ClickAction>>()
            for (state in ProductState.entries) {
                val node = section.getConfigurationSection(state.name)
                if (node == null) { map[state] = fallback.map[state].orEmpty(); continue }
                val inner = HashMap<ClickKind, ClickAction>()
                for (key in node.getKeys(false)) {
                    val kind = runCatching { ClickKind.valueOf(key.uppercase()) }.getOrNull() ?: continue
                    val action = runCatching { ClickAction.valueOf(node.getString(key)!!.uppercase()) }.getOrNull() ?: continue
                    inner[kind] = action
                }
                map[state] = inner
            }
            return ClickMap(map)
        }
    }
}
