package com.inmc.shop.virtual

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration

enum class LayoutButton(val label: String) {
    BACK("돌아가기"),
    PREV("이전 페이지"),
    NEXT("다음 페이지"),
    BALANCE("잔고"),
    SELL_ALL("전부 판매"),
    CLOSE("닫기"),
}

/**
 * 상점 화면의 모양 — 줄 수·제목·장식 칸·버튼 자리. 장식·버튼이 없는 칸이 상품 칸이다.
 * 상점마다(페이지마다) 고를 수 있고, 잘못되면 `default` 로 돌아간다. 장식은 게임 안 편집기에서 아이템을 놓아 정한다.
 */
data class Layout(
    val id: String,
    val title: String = "<dark_gray>{shop} <gray>({page}/{pages})</gray></dark_gray>",
    val rows: Int = 6,
    /** 칸 → 아이템(Base64). */
    val decorations: Map<Int, String> = emptyMap(),
    val buttons: Map<LayoutButton, Int> = emptyMap(),
) {
    val size: Int get() = rows.coerceIn(1, 6) * 9

    /** 상품이 놓일 수 있는 칸. */
    fun productSlots(): List<Int> = (0 until size).filter { it !in decorations && it !in buttons.values }

    fun buttonAt(slot: Int): LayoutButton? = buttons.entries.firstOrNull { it.value == slot }?.key

    fun toYaml(): YamlConfiguration {
        val y = YamlConfiguration()
        y.set("title", title)
        y.set("rows", rows)
        val deco = y.createSection("decorations")
        for ((slot, item) in decorations.toSortedMap()) deco.set(slot.toString(), item)
        val node = y.createSection("buttons")
        for ((button, slot) in buttons) node.set(button.name, slot)
        return y
    }

    companion object {
        /** 기본 — 6줄, 맨 아래 줄이 버튼과 장식. */
        fun default(id: String = "default", decoration: String? = null): Layout {
            val buttons = mapOf(LayoutButton.BACK to 45, LayoutButton.PREV to 47, LayoutButton.BALANCE to 49, LayoutButton.NEXT to 51, LayoutButton.SELL_ALL to 53)
            val deco = if (decoration == null) emptyMap() else (45..53).filter { it !in buttons.values }.associateWith { decoration }
            return Layout(id, buttons = buttons, decorations = deco)
        }

        /** 메인 메뉴 기본. */
        fun mainMenu(decoration: String? = null): Layout {
            val buttons = mapOf(LayoutButton.BALANCE to 48, LayoutButton.SELL_ALL to 50, LayoutButton.CLOSE to 53)
            val deco = if (decoration == null) emptyMap() else (45..53).filter { it !in buttons.values }.associateWith { decoration }
            return Layout("main-menu", title = "<dark_gray>상점</dark_gray>", rows = 6, decorations = deco, buttons = buttons)
        }

        fun load(id: String, y: ConfigurationSection): Layout {
            val deco = HashMap<Int, String>()
            y.getConfigurationSection("decorations")?.let { node -> for (k in node.getKeys(false)) k.toIntOrNull()?.let { s -> node.getString(k)?.let { deco[s] = it } } }
            val buttons = HashMap<LayoutButton, Int>()
            y.getConfigurationSection("buttons")?.let { node ->
                for (k in node.getKeys(false)) {
                    val button = runCatching { LayoutButton.valueOf(k.uppercase()) }.getOrNull() ?: continue
                    buttons[button] = node.getInt(k)
                }
            }
            val rows = y.getInt("rows", 6).coerceIn(1, 6)
            val size = rows * 9
            return Layout(
                id, y.getString("title") ?: Layout(id).title, rows,
                deco.filterKeys { it in 0 until size }, buttons.filterValues { it in 0 until size },
            )
        }
    }
}
