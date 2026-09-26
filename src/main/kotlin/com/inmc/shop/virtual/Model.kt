package com.inmc.shop.virtual

import com.inmc.shop.price.Pricing
import com.inmc.shop.price.Schedule
import com.inmc.shop.trade.TradeType
import kr.inmc.core.item.StoredItem
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration

/** 전체 재고 — 모두가 같이 쓴다. 사면 줄고 팔면 는다(용량까지). [restockSeconds] < 0 이면 사람이 파는 것으로만 찬다. */
data class StockOptions(
    val enabled: Boolean = false,
    val capacity: Long = 1000,
    val restockMin: Long = 1000,
    val restockMax: Long = 1000,
    val restockSeconds: Long = 3600,
) {
    fun save(section: ConfigurationSection) {
        section.set("enabled", enabled)
        section.set("capacity", capacity)
        section.set("restock-min", restockMin)
        section.set("restock-max", restockMax)
        section.set("restock-seconds", restockSeconds)
    }

    companion object {
        fun load(section: ConfigurationSection?): StockOptions {
            section ?: return StockOptions()
            val capacity = section.getLong("capacity", 1000).coerceAtLeast(1)
            val lo = section.getLong("restock-min", capacity).coerceIn(0, capacity)
            return StockOptions(
                section.getBoolean("enabled", false), capacity, lo,
                section.getLong("restock-max", capacity).coerceIn(lo, capacity),
                section.getLong("restock-seconds", 3600),
            )
        }
    }
}

/** 한 사람 한도 — 사고팔 때마다 줄기만 한다. [resetSeconds] < 0 이면 평생 한 번뿐. 한도 < 0 은 무제한. */
data class LimitOptions(
    val enabled: Boolean = false,
    val buy: Long = -1,
    val sell: Long = -1,
    val resetSeconds: Long = 86400,
) {
    fun limit(type: TradeType): Long = if (!enabled) -1 else if (type == TradeType.BUY) buy else sell

    fun save(section: ConfigurationSection) {
        section.set("enabled", enabled)
        section.set("buy", buy)
        section.set("sell", sell)
        section.set("reset-seconds", resetSeconds)
    }

    companion object {
        fun load(section: ConfigurationSection?): LimitOptions {
            section ?: return LimitOptions()
            return LimitOptions(section.getBoolean("enabled", false), section.getLong("buy", -1), section.getLong("sell", -1), section.getLong("reset-seconds", 86400))
        }
    }
}

/** 사고팔 수 있는 사람. 등급 = LuckPerms `group.<이름>` 권한. 목록이 비면 그 조건은 없음. */
data class Requirements(
    val ranks: List<String> = emptyList(),
    val forbiddenRanks: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val forbiddenPermissions: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = ranks.isEmpty() && forbiddenRanks.isEmpty() && permissions.isEmpty() && forbiddenPermissions.isEmpty()

    /** [has] = 그 권한을 가졌나. */
    fun allows(has: (String) -> Boolean): Boolean {
        if (ranks.isNotEmpty() && ranks.none { has("group.$it") }) return false
        if (forbiddenRanks.any { has("group.$it") }) return false
        if (permissions.isNotEmpty() && permissions.none(has)) return false
        if (forbiddenPermissions.any(has)) return false
        return true
    }

    fun save(section: ConfigurationSection) {
        section.set("ranks", ranks)
        section.set("forbidden-ranks", forbiddenRanks)
        section.set("permissions", permissions)
        section.set("forbidden-permissions", forbiddenPermissions)
    }

    companion object {
        fun load(section: ConfigurationSection?): Requirements {
            section ?: return Requirements()
            return Requirements(
                section.getStringList("ranks"), section.getStringList("forbidden-ranks"),
                section.getStringList("permissions"), section.getStringList("forbidden-permissions"),
            )
        }
    }
}

enum class ProductType(val label: String) { ITEM("아이템"), COMMAND("명령어") }

/**
 * 서버 상점의 상품 하나. 불변 — 고치면 새 객체로 갈아끼운다(편집 화면은 id 를 든다).
 *
 * [key] = `상점/상품` 이 DB(가격·재고·한도·시장 기록)의 열쇠다. 상품 id 는 만들 때 무작위로 정하고 바꾸지 않는다 —
 * 지웠다가 같은 이름으로 다시 만든 상품이 옛 재고를 물려받지 않게.
 */
data class Product(
    val id: String,
    val shopId: String,
    val type: ProductType = ProductType.ITEM,
    /** 아이템 상품의 아이템. 명령어 상품은 null 일 수 있다(그때는 [preview]). */
    val item: StoredItem? = null,
    /** 한 단위의 개수 — 구매 1회에 받는 개수. */
    val unit: Int = 1,
    /** 명령어 상품의 아이콘·이름. */
    val preview: StoredItem? = null,
    val name: String? = null,
    val lore: List<String> = emptyList(),
    val commands: List<String> = emptyList(),
    /** 빈 값 = 모듈 기본 화폐. */
    val currency: String = "",
    val pricing: Pricing = Pricing.OFF,
    val stock: StockOptions = StockOptions(),
    val limits: LimitOptions = LimitOptions(),
    val requirements: Requirements = Requirements(),
    /** 고정 상품의 자리(회전 상품은 쓰지 않는다). 페이지는 1부터. */
    val page: Int = 1,
    val slot: Int = -1,
    val rotating: Boolean = false,
    val weight: Double = 1.0,
) {
    val key: String get() = "$shopId/$id"

    /** 명령어 상품은 팔 수 없다(되돌릴 물건이 없다). */
    fun tradable(type: TradeType): Boolean = pricing.enabled(type) && (type == TradeType.BUY || this.type == ProductType.ITEM)

    /** 화면에 보이고 저장 방식이 걸리는 아이템 — 아이템 상품은 [item], 명령어 상품은 아이콘([preview], 없으면 [item]). */
    val shown: StoredItem? get() = if (type == ProductType.ITEM) item else preview ?: item

    fun withShown(stored: StoredItem): Product =
        if (type == ProductType.ITEM || preview == null && item != null) copy(item = stored) else copy(preview = stored)

    fun save(section: ConfigurationSection) {
        section.set("type", type.name)
        item?.save(section.createSection("item"))
        section.set("unit", unit)
        preview?.save(section.createSection("preview"))
        section.set("name", name)
        if (lore.isNotEmpty()) section.set("lore", lore)
        if (commands.isNotEmpty()) section.set("commands", commands)
        section.set("currency", currency.ifEmpty { null })
        pricing.save(section.createSection("price"))
        if (stock != StockOptions()) stock.save(section.createSection("stock"))
        if (limits != LimitOptions()) limits.save(section.createSection("limits"))
        if (!requirements.isEmpty) requirements.save(section.createSection("requirements"))
        section.set("page", page)
        section.set("slot", slot)
        if (rotating) {
            section.set("rotating", true)
            section.set("weight", weight)
        }
    }

    companion object {
        fun load(id: String, shopId: String, section: ConfigurationSection): Product = Product(
            id = id,
            shopId = shopId,
            type = runCatching { ProductType.valueOf(section.getString("type", "ITEM")!!.uppercase()) }.getOrDefault(ProductType.ITEM),
            item = section.getConfigurationSection("item")?.let(StoredItem::load),
            unit = section.getInt("unit", 1).coerceIn(1, 99 * 36),
            preview = section.getConfigurationSection("preview")?.let(StoredItem::load),
            name = section.getString("name"),
            lore = section.getStringList("lore"),
            commands = section.getStringList("commands"),
            currency = section.getString("currency").orEmpty(),
            pricing = Pricing.load(section.getConfigurationSection("price")),
            stock = StockOptions.load(section.getConfigurationSection("stock")),
            limits = LimitOptions.load(section.getConfigurationSection("limits")),
            requirements = Requirements.load(section.getConfigurationSection("requirements")),
            page = section.getInt("page", 1).coerceAtLeast(1),
            slot = section.getInt("slot", -1),
            rotating = section.getBoolean("rotating", false),
            weight = section.getDouble("weight", 1.0).coerceAtLeast(0.0),
        )
    }
}

/** 회전 — 정한 때마다 회전 칸에 회전 상품을 가중치로 뽑아 놓는다. */
data class Rotation(
    val id: String,
    val schedule: Schedule = Schedule.Interval(86400),
    /** 페이지 → 칸. */
    val slots: Map<Int, Set<Int>> = emptyMap(),
    /** 이 회전이 뽑는 상품 id(비면 상점의 회전 상품 전부). */
    val products: List<String> = emptyList(),
    val notify: Boolean = true,
) {
    val slotCount: Int get() = slots.values.sumOf { it.size }

    fun save(section: ConfigurationSection) {
        schedule.save(section.createSection("schedule"))
        val node = section.createSection("slots")
        for ((page, set) in slots) if (set.isNotEmpty()) node.set(page.toString(), set.sorted())
        section.set("products", products)
        section.set("notify", notify)
    }

    companion object {
        fun load(id: String, section: ConfigurationSection): Rotation {
            val slots = HashMap<Int, Set<Int>>()
            section.getConfigurationSection("slots")?.let { node ->
                for (key in node.getKeys(false)) {
                    val page = key.toIntOrNull() ?: continue
                    slots[page] = node.getIntegerList(key).toSet()
                }
            }
            return Rotation(id, Schedule.load(section.getConfigurationSection("schedule"), 86400), slots, section.getStringList("products"), section.getBoolean("notify", true))
        }
    }
}

/** 서버 상점 하나. 파일 `virtual/shops/<id>.yml`. */
data class VirtualShop(
    val id: String,
    val name: String = id,
    val description: List<String> = emptyList(),
    val icon: StoredItem? = null,
    val iconMaterial: Material = Material.CHEST,
    val permissionRequired: Boolean = false,
    val buying: Boolean = true,
    val selling: Boolean = true,
    val pages: Int = 1,
    /** 메인 메뉴 칸. -1 = 메인 메뉴에 안 보임. */
    val menuSlot: Int = -1,
    val layout: String = "default",
    val pageLayouts: Map<Int, String> = emptyMap(),
    val aliases: List<String> = emptyList(),
    /** 이 상점의 기본 화폐(상품이 비워 두면). 빈 값 = 모듈 기본. */
    val currency: String = "",
    val products: Map<String, Product> = emptyMap(),
    val rotations: Map<String, Rotation> = emptyMap(),
) {
    val permission: String get() = "inmcshop.virtual.shop.$id"

    fun layoutFor(page: Int): String = pageLayouts[page] ?: layout

    fun product(id: String): Product? = products[id]

    fun fixedAt(page: Int, slot: Int): Product? = products.values.firstOrNull { !it.rotating && it.page == page && it.slot == slot }

    fun toYaml(): YamlConfiguration {
        val y = YamlConfiguration()
        y.set("name", name)
        y.set("description", description)
        icon?.save(y.createSection("icon"))
        y.set("icon-material", iconMaterial.key().toString())
        y.set("permission-required", permissionRequired)
        y.set("buying", buying)
        y.set("selling", selling)
        y.set("pages", pages)
        y.set("menu-slot", menuSlot)
        y.set("layout", layout)
        if (pageLayouts.isNotEmpty()) {
            val node = y.createSection("page-layouts")
            for ((page, layout) in pageLayouts) node.set(page.toString(), layout)
        }
        y.set("aliases", aliases)
        y.set("currency", currency.ifEmpty { null })
        val productNode = y.createSection("products")
        for ((id, product) in products) product.save(productNode.createSection(id))
        if (rotations.isNotEmpty()) {
            val node = y.createSection("rotations")
            for ((id, rotation) in rotations) rotation.save(node.createSection(id))
        }
        return y
    }

    companion object {
        fun load(id: String, y: ConfigurationSection): VirtualShop {
            val products = LinkedHashMap<String, Product>()
            y.getConfigurationSection("products")?.let { node ->
                for (key in node.getKeys(false)) node.getConfigurationSection(key)?.let { products[key] = Product.load(key, id, it) }
            }
            val rotations = LinkedHashMap<String, Rotation>()
            y.getConfigurationSection("rotations")?.let { node ->
                for (key in node.getKeys(false)) node.getConfigurationSection(key)?.let { rotations[key] = Rotation.load(key, it) }
            }
            val pageLayouts = HashMap<Int, String>()
            y.getConfigurationSection("page-layouts")?.let { node ->
                for (key in node.getKeys(false)) key.toIntOrNull()?.let { page -> node.getString(key)?.let { pageLayouts[page] = it } }
            }
            return VirtualShop(
                id = id,
                name = y.getString("name") ?: id,
                description = y.getStringList("description"),
                icon = y.getConfigurationSection("icon")?.let(StoredItem::load),
                iconMaterial = y.getString("icon-material")?.let { Material.matchMaterial(it) } ?: Material.CHEST,
                permissionRequired = y.getBoolean("permission-required", false),
                buying = y.getBoolean("buying", true),
                selling = y.getBoolean("selling", true),
                pages = y.getInt("pages", 1).coerceIn(1, 100),
                menuSlot = y.getInt("menu-slot", -1),
                layout = y.getString("layout") ?: "default",
                pageLayouts = pageLayouts,
                aliases = y.getStringList("aliases"),
                currency = y.getString("currency").orEmpty(),
                products = products,
                rotations = rotations,
            )
        }
    }
}
