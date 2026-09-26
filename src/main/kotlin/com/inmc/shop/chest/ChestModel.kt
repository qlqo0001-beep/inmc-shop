package com.inmc.shop.chest

import com.inmc.shop.trade.TradeType
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID

/** 블록 자리 — 상자 상점을 찾는 열쇠. */
data class BlockKey(val world: String, val x: Int, val y: Int, val z: Int) {
    fun location(): Location? = Bukkit.getWorld(world)?.let { Location(it, x.toDouble(), y.toDouble(), z.toDouble()) }
    fun block(): Block? = Bukkit.getWorld(world)?.getBlockAt(x, y, z)

    companion object {
        fun of(block: Block) = BlockKey(block.world.name, block.x, block.y, block.z)
    }
}

/**
 * 상자 상점의 상품. [item] 은 **등록한 그 아이템**(Base64, 개수 = 한 단위). 가격이 null 이면 그 방향은 꺼짐.
 */
data class ChestProduct(
    val id: String,
    val item: String,
    val buyPrice: Long? = null,
    val sellPrice: Long? = null,
    /** 빈 값 = 모듈 기본 화폐. */
    val currency: String = "",
) {
    fun price(type: TradeType): Long? = if (type == TradeType.BUY) buyPrice else sellPrice

    fun save(section: ConfigurationSection) {
        section.set("item", item)
        section.set("buy", buyPrice ?: -1L)
        section.set("sell", sellPrice ?: -1L)
        section.set("currency", currency.ifEmpty { null })
    }

    companion object {
        fun load(id: String, section: ConfigurationSection): ChestProduct? {
            val item = section.getString("item") ?: return null
            return ChestProduct(
                id, item,
                section.getLong("buy", -1).takeIf { it >= 0 },
                section.getLong("sell", -1).takeIf { it >= 0 },
                section.getString("currency").orEmpty(),
            )
        }
    }
}

/** 임대. [renter] 가 있고 [until] 전이면 임대 중 — 그동안 운영자는 임차인이다. */
data class RentInfo(
    val enabled: Boolean = false,
    val days: Int = 7,
    val price: Long = 0,
    val currency: String = "",
    val renter: UUID? = null,
    val renterName: String? = null,
    val until: Long = 0,
) {
    fun active(now: Long): Boolean = renter != null && now < until

    fun save(section: ConfigurationSection) {
        section.set("enabled", enabled)
        section.set("days", days)
        section.set("price", price)
        section.set("currency", currency.ifEmpty { null })
        section.set("renter", renter?.toString())
        section.set("renter-name", renterName)
        section.set("until", until)
    }

    companion object {
        fun load(section: ConfigurationSection?): RentInfo {
            section ?: return RentInfo()
            return RentInfo(
                section.getBoolean("enabled", false), section.getInt("days", 7).coerceAtLeast(1), section.getLong("price", 0).coerceAtLeast(0),
                section.getString("currency").orEmpty(), section.getString("renter")?.let { runCatching { UUID.fromString(it) }.getOrNull() },
                section.getString("renter-name"), section.getLong("until", 0),
            )
        }
    }
}

/**
 * 상자 상점 하나. 불변 — 고치면 새 객체(관리 화면은 id 를 든다). 물건은 상자에 없다 — **가상 창고**(`shop_chest_stock`).
 */
data class ChestShop(
    val id: String,
    val key: BlockKey,
    val owner: UUID,
    val ownerName: String,
    val name: String,
    val admin: Boolean = false,
    val products: List<ChestProduct> = emptyList(),
    val trusted: Set<UUID> = emptySet(),
    val hologram: Boolean = true,
    /** 쇼케이스 블록(재질 이름). null = 없음. */
    val showcase: String? = null,
    val rent: RentInfo = RentInfo(),
    val created: Long = System.currentTimeMillis(),
) {
    /** 지금 운영하는 사람 — 임대 중이면 임차인. 돈(은행)과 창고의 주인이다. */
    fun operator(now: Long = System.currentTimeMillis()): UUID = if (rent.active(now)) rent.renter!! else owner

    fun operatorName(now: Long = System.currentTimeMillis()): String = if (rent.active(now)) rent.renterName ?: "?" else ownerName

    fun canManage(player: UUID, now: Long = System.currentTimeMillis()): Boolean = player == operator(now) || player in trusted

    fun product(id: String): ChestProduct? = products.firstOrNull { it.id == id }

    fun toData(): String {
        val y = YamlConfiguration()
        y.set("owner-name", ownerName)
        y.set("name", name)
        y.set("admin", admin)
        val node = y.createSection("products")
        for ((index, p) in products.withIndex()) p.save(node.createSection("%03d-%s".format(index, p.id)))
        y.set("trusted", trusted.map { it.toString() })
        y.set("hologram", hologram)
        y.set("showcase", showcase)
        rent.save(y.createSection("rent"))
        y.set("created", created)
        return y.saveToString()
    }

    companion object {
        fun fromData(id: String, key: BlockKey, owner: UUID, data: String): ChestShop {
            val y = YamlConfiguration()
            runCatching { y.loadFromString(data) }
            val products = ArrayList<ChestProduct>()
            y.getConfigurationSection("products")?.let { node ->
                for (k in node.getKeys(false).sorted()) {
                    val section = node.getConfigurationSection(k) ?: continue
                    ChestProduct.load(k.substringAfter('-', k), section)?.let(products::add)
                }
            }
            return ChestShop(
                id = id, key = key, owner = owner,
                ownerName = y.getString("owner-name") ?: "?",
                name = y.getString("name") ?: "상점",
                admin = y.getBoolean("admin", false),
                products = products,
                trusted = y.getStringList("trusted").mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.toSet(),
                hologram = y.getBoolean("hologram", true),
                showcase = y.getString("showcase"),
                rent = RentInfo.load(y.getConfigurationSection("rent")),
                created = y.getLong("created", System.currentTimeMillis()),
            )
        }
    }
}
