package com.inmc.shop.price

import com.inmc.shop.trade.TradeType
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection

/**
 * 가격을 정하지 않은 상품의 **기본가**(사용자 결정 2026-10-08 "폴백 기본가"). 고정가인데 사고팔기 둘 다 꺼진(-1) 상품에만 — 한쪽이라도 정했으면
 * 관리자의 뜻이다. 재질 이름(`minecraft:` 뒤) glob 규칙을 위에서부터 보고, 맞는 것이 없으면 전역 [buy]/[sell]. -1 = 그 방향 없음.
 *
 * 구매 기본가는 기본으로 꺼 둔다 — 블록 하나를 싼 값에 사서 비싼 재료로 되파는 길이 되기 쉽다. 판매 기본가만 낮게 둔다.
 */
data class FallbackPrice(
    val enabled: Boolean = true,
    val buy: Long = -1,
    val sell: Long = 10,
    val rules: List<Rule> = DEFAULT_RULES,
) {

    data class Rule(val pattern: String, val buy: Long, val sell: Long) {
        private val regex: Regex = Regex(
            "^" + pattern.trim().lowercase().split('*').joinToString(".*") { Regex.escape(it) } + "$",
        )

        fun matches(key: String): Boolean = regex.matches(key)
    }

    /** 이 재질의 기본가. 없으면 null. */
    fun price(material: Material, type: TradeType): Long? {
        if (!enabled) return null
        val key = material.key.key
        val rule = rules.firstOrNull { it.matches(key) }
        val value = if (type == TradeType.BUY) rule?.buy ?: buy else rule?.sell ?: sell
        return value.takeIf { it >= 0 }
    }

    fun save(section: ConfigurationSection) {
        section.set("enabled", enabled)
        section.set("buy", buy)
        section.set("sell", sell)
        section.set("rules", rules.map { mapOf("pattern" to it.pattern, "buy" to it.buy, "sell" to it.sell) })
    }

    companion object {
        /** 처음 깔 때의 규칙 — 광석·보석 블록은 비싸게, 흔한 블록은 싸게. 관리자가 `config.yml` 에서 고친다. */
        val DEFAULT_RULES: List<Rule> = listOf(
            Rule("netherite_block", -1, 20000), Rule("ancient_debris", -1, 1500),
            Rule("diamond_block", -1, 2000), Rule("emerald_block", -1, 2000), Rule("gold_block", -1, 800), Rule("iron_block", -1, 500),
            Rule("lapis_block", -1, 300), Rule("redstone_block", -1, 200), Rule("*copper_block", -1, 200), Rule("coal_block", -1, 100),
            Rule("*diamond_ore", -1, 220), Rule("*emerald_ore", -1, 220), Rule("*gold_ore", -1, 80), Rule("*iron_ore", -1, 50),
            Rule("*copper_ore", -1, 25), Rule("*lapis_ore", -1, 40), Rule("*redstone_ore", -1, 30), Rule("*coal_ore", -1, 12),
            Rule("nether_quartz_ore", -1, 20), Rule("obsidian", -1, 40), Rule("crying_obsidian", -1, 60),
            Rule("*_log", -1, 8), Rule("*_wood", -1, 8), Rule("*_stem", -1, 8), Rule("*_hyphae", -1, 8), Rule("*_planks", -1, 3),
            Rule("*_leaves", -1, 1), Rule("*_sapling", -1, 5), Rule("*_wool", -1, 10), Rule("*_glass*", -1, 5),
            Rule("*_stairs", -1, 3), Rule("*_slab", -1, 2), Rule("*_fence*", -1, 3), Rule("*_wall", -1, 2),
            Rule("dirt", -1, 1), Rule("*dirt*", -1, 1), Rule("grass_block", -1, 2), Rule("sand", -1, 2), Rule("red_sand", -1, 2),
            Rule("gravel", -1, 2), Rule("cobblestone", -1, 1), Rule("*cobble*", -1, 1), Rule("stone", -1, 2), Rule("deepslate", -1, 2),
        )

        fun load(section: ConfigurationSection?): FallbackPrice {
            section ?: return FallbackPrice()
            val rules = section.getMapList("rules").mapNotNull { row ->
                val pattern = row["pattern"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Rule(pattern, (row["buy"] as? Number)?.toLong() ?: -1L, (row["sell"] as? Number)?.toLong() ?: -1L)
            }
            return FallbackPrice(
                enabled = section.getBoolean("enabled", true),
                buy = section.getLong("buy", -1L),
                sell = section.getLong("sell", 10L),
                rules = if (section.contains("rules")) rules else DEFAULT_RULES,
            )
        }
    }
}
