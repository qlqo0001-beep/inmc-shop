package com.inmc.shop.auction

import com.inmc.shop.Shop
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration

/**
 * 경매 분류. [match] 는 재질 이름 패턴 — `*_SWORD` 처럼 별표, 또는 `#BLOCK`(블록) · `#EDIBLE`(먹을 것) · `#ALL`.
 */
data class AuctionCategory(val id: String, val name: String, val icon: Material, val match: List<String>, val order: Int) {

    private val patterns: List<Regex> by lazy { match.filter { !it.startsWith("#") }.map { Regex("^" + Regex.escape(it.uppercase()).replace("\\*", ".*") + "$") } }

    fun matches(material: Material): Boolean {
        for (token in match) when (token.uppercase()) {
            "#ALL" -> return true
            "#BLOCK" -> if (material.isBlock) return true
            "#EDIBLE" -> if (material.isEdible) return true
        }
        val name = material.name
        return patterns.any { it.matches(name) }
    }
}

class CategoryRegistry(private val shop: Shop) {

    @Volatile
    var all: List<AuctionCategory> = emptyList()
        private set

    private val file get() = shop.io.file(FILE)

    fun loadNow() {
        if (!file.exists()) shop.io.copyDefault(FILE, file)
        all = read(shop.io.load(file))
    }

    fun read(y: YamlConfiguration): List<AuctionCategory> = y.getKeys(false).mapNotNull { id ->
        val node = y.getConfigurationSection(id) ?: return@mapNotNull null
        AuctionCategory(
            id, node.getString("name") ?: id,
            node.getString("icon")?.let { Material.matchMaterial(it) } ?: Material.CHEST,
            node.getStringList("match"), node.getInt("order", 100),
        )
    }.sortedWith(compareBy({ it.order }, { it.id }))

    fun reloadFrom(y: YamlConfiguration) {
        all = read(y)
    }

    fun put(category: AuctionCategory) {
        all = (all.filter { it.id != category.id } + category).sortedWith(compareBy({ it.order }, { it.id }))
        save()
    }

    fun remove(id: String) {
        all = all.filter { it.id != id }
        save()
    }

    private fun save() {
        val y = YamlConfiguration()
        y.options().setHeader(listOf("경매장 분류. /상점 관리 → 경매 분류 에서 고칩니다.", "match: 재질 이름 패턴(*_SWORD) 또는 #BLOCK · #EDIBLE · #ALL"))
        for (c in all) {
            y.set("${c.id}.name", c.name)
            y.set("${c.id}.icon", c.icon.name)
            y.set("${c.id}.match", c.match)
            y.set("${c.id}.order", c.order)
        }
        shop.io.asyncRun { shop.io.save(file, y) }
    }

    companion object {
        const val FILE = "auction/categories.yml"
    }
}
