package com.inmc.shop.config

import com.inmc.shop.price.MarketParams
import com.inmc.shop.trade.ClickMap
import com.inmc.shop.virtual.RankValues
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration

/** 한 모듈의 화폐 — 기본 화폐와 허용 목록(`*` = 전부). 기본 화폐는 언제나 허용. */
data class ModuleCurrency(val default: String = "", val allowed: List<String> = listOf("*")) {
    fun allows(id: String): Boolean = id.equals(default, true) || "*" in allowed || allowed.any { it.equals(id, true) }

    fun save(section: ConfigurationSection) {
        section.set("default", default)
        section.set("allowed", allowed)
    }

    companion object {
        fun load(section: ConfigurationSection?): ModuleCurrency =
            if (section == null) ModuleCurrency() else ModuleCurrency(section.getString("default").orEmpty(), section.getStringList("allowed").ifEmpty { listOf("*") })
    }
}

data class ChestSettings(
    val enabled: Boolean = true,
    val currency: ModuleCurrency = ModuleCurrency(),
    val clicks: ClickMap = ClickMap.CHEST,
    val defaultName: String = "상점",
    val maxNameLength: Int = 16,
    val adminName: String = "<red>관리자 상점</red>",
    /** 상점이 될 수 있는 블록(재질 이름). */
    val blocks: List<String> = listOf("CHEST", "TRAPPED_CHEST", "BARREL", "SHULKER_BOX"),
    val createCost: Long = 0,
    val removeCost: Long = 0,
    val maxShops: RankValues = RankValues(RankValues.Mode.RANK, "inmcshop.chestshop.shops.", 10.0, mapOf("vip" to 20.0, "admin" to -1.0)),
    val maxProducts: RankValues = RankValues(RankValues.Mode.RANK, "inmcshop.chestshop.products.", 5.0, mapOf("vip" to 9.0, "admin" to -1.0)),
    /** 상품 하나의 창고 용량(개). */
    val capacity: RankValues = RankValues(RankValues.Mode.RANK, "inmcshop.chestshop.capacity.", 1728.0, mapOf("vip" to 3456.0, "admin" to -1.0)),
    val maxPrice: Long = 10_000_000,
    val bannedMaterials: List<String> = listOf("BARRIER", "BEDROCK"),
    val bannedNames: List<String> = emptyList(),
    val bannedLores: List<String> = emptyList(),
    val checkBuild: Boolean = true,
    val landsOnly: Boolean = false,
    val creationItems: Boolean = false,
    val bank: Boolean = true,
    val bankMandatory: Boolean = false,
    val rent: Boolean = true,
    val rentMaxDays: Int = 30,
    /** 화폐 id → 최대 임대료. 없으면 제한 없음. */
    val rentMaxPrice: Map<String, Long> = mapOf("money" to 1_000_000),
    val hologram: Boolean = true,
    val viewDistance: Int = 16,
    val itemChangeSeconds: Int = 5,
    val safeTeleport: Boolean = true,
    val notifyOwner: Boolean = true,
) {
    fun save(section: ConfigurationSection) {
        section.set("enabled", enabled)
        currency.save(section.createSection("currency"))
        clicks.save(section.createSection("clicks"))
        section.set("default-name", defaultName)
        section.set("max-name-length", maxNameLength)
        section.set("admin-name", adminName)
        section.set("blocks", blocks)
        section.set("create-cost", createCost)
        section.set("remove-cost", removeCost)
        maxShops.save(section.createSection("max-shops"))
        maxProducts.save(section.createSection("max-products"))
        capacity.save(section.createSection("capacity"))
        section.set("max-price", maxPrice)
        section.set("banned.materials", bannedMaterials)
        section.set("banned.names", bannedNames)
        section.set("banned.lores", bannedLores)
        section.set("check-build", checkBuild)
        section.set("lands-only", landsOnly)
        section.set("creation-items", creationItems)
        section.set("bank.enabled", bank)
        section.set("bank.mandatory", bankMandatory)
        section.set("rent.enabled", rent)
        section.set("rent.max-days", rentMaxDays)
        val prices = section.createSection("rent.max-price")
        for ((k, v) in rentMaxPrice) prices.set(k, v)
        section.set("display.hologram", hologram)
        section.set("display.view-distance", viewDistance)
        section.set("display.item-change-seconds", itemChangeSeconds)
        section.set("safe-teleport", safeTeleport)
        section.set("notify-owner", notifyOwner)
    }

    companion object {
        fun load(section: ConfigurationSection?): ChestSettings {
            val d = ChestSettings()
            section ?: return d
            val rentPrices = LinkedHashMap<String, Long>()
            section.getConfigurationSection("rent.max-price")?.let { node -> for (k in node.getKeys(false)) rentPrices[k] = node.getLong(k) }
            return ChestSettings(
                enabled = section.getBoolean("enabled", true),
                currency = ModuleCurrency.load(section.getConfigurationSection("currency")),
                clicks = ClickMap.load(section.getConfigurationSection("clicks"), ClickMap.CHEST),
                defaultName = section.getString("default-name") ?: d.defaultName,
                maxNameLength = section.getInt("max-name-length", d.maxNameLength).coerceIn(1, 64),
                adminName = section.getString("admin-name") ?: d.adminName,
                blocks = section.getStringList("blocks").ifEmpty { d.blocks },
                createCost = section.getLong("create-cost", 0).coerceAtLeast(0),
                removeCost = section.getLong("remove-cost", 0).coerceAtLeast(0),
                maxShops = RankValues.load(section.getConfigurationSection("max-shops"), d.maxShops),
                maxProducts = RankValues.load(section.getConfigurationSection("max-products"), d.maxProducts),
                capacity = RankValues.load(section.getConfigurationSection("capacity"), d.capacity),
                maxPrice = section.getLong("max-price", d.maxPrice).coerceAtLeast(1),
                bannedMaterials = section.getStringList("banned.materials"),
                bannedNames = section.getStringList("banned.names"),
                bannedLores = section.getStringList("banned.lores"),
                checkBuild = section.getBoolean("check-build", true),
                landsOnly = section.getBoolean("lands-only", false),
                creationItems = section.getBoolean("creation-items", false),
                bank = section.getBoolean("bank.enabled", true),
                bankMandatory = section.getBoolean("bank.mandatory", false),
                rent = section.getBoolean("rent.enabled", true),
                rentMaxDays = section.getInt("rent.max-days", d.rentMaxDays).coerceIn(1, 3650),
                rentMaxPrice = if (section.contains("rent.max-price")) rentPrices else d.rentMaxPrice,
                hologram = section.getBoolean("display.hologram", true),
                viewDistance = section.getInt("display.view-distance", d.viewDistance).coerceIn(2, 64),
                itemChangeSeconds = section.getInt("display.item-change-seconds", d.itemChangeSeconds).coerceIn(1, 120),
                safeTeleport = section.getBoolean("safe-teleport", true),
                notifyOwner = section.getBoolean("notify-owner", true),
            )
        }
    }
}

/** 가격 한도(min/max). -1 = 없음. */
data class PriceBound(val min: Long = -1, val max: Long = -1) {
    fun allows(price: Long): Boolean = (min < 0 || price >= min) && (max < 0 || price <= max)
}

data class AuctionSettings(
    val enabled: Boolean = true,
    val currency: ModuleCurrency = ModuleCurrency(),
    val expireHours: Int = 168,
    /** 끝난 기록(돈 받음·돌려받음)을 며칠 뒤 지우나. 받지 않은 물건·돈은 지우지 않는다. */
    val purgeDays: Int = 7,
    val listingTaxPercent: Double = 10.0,
    val purchaseTaxPercent: Double = 0.0,
    val maxListings: RankValues = RankValues(RankValues.Mode.RANK, "inmcshop.auction.listings.", 10.0, mapOf("vip" to 15.0, "admin" to -1.0)),
    val currencyBounds: Map<String, PriceBound> = mapOf("money" to PriceBound(1, 10_000_000)),
    val materialBounds: Map<String, PriceBound> = emptyMap(),
    val bannedMaterials: List<String> = listOf("BARRIER", "BEDROCK"),
    val bannedNames: List<String> = emptyList(),
    val bannedLores: List<String> = emptyList(),
    /** 재질 → 막는 모델 번호. */
    val bannedModels: Map<String, List<Int>> = emptyMap(),
    val announce: Boolean = true,
    val autoClaim: Boolean = false,
    val notifyOnJoin: Boolean = true,
    val warningsToBan: Int = 3,
    val banDays: Int = 7,
    val warningExpireDays: Int = 30,
) {
    fun save(section: ConfigurationSection) {
        section.set("enabled", enabled)
        currency.save(section.createSection("currency"))
        section.set("expire-hours", expireHours)
        section.set("purge-days", purgeDays)
        section.set("tax.listing-percent", listingTaxPercent)
        section.set("tax.purchase-percent", purchaseTaxPercent)
        maxListings.save(section.createSection("max-listings"))
        val cur = section.createSection("price-bounds.currency")
        for ((k, v) in currencyBounds) { cur.set("$k.min", v.min); cur.set("$k.max", v.max) }
        val mat = section.createSection("price-bounds.material")
        for ((k, v) in materialBounds) { mat.set("$k.min", v.min); mat.set("$k.max", v.max) }
        section.set("banned.materials", bannedMaterials)
        section.set("banned.names", bannedNames)
        section.set("banned.lores", bannedLores)
        val models = section.createSection("banned.models")
        for ((k, v) in bannedModels) models.set(k, v)
        section.set("announce", announce)
        section.set("auto-claim", autoClaim)
        section.set("notify-on-join", notifyOnJoin)
        section.set("warnings.to-ban", warningsToBan)
        section.set("warnings.ban-days", banDays)
        section.set("warnings.expire-days", warningExpireDays)
    }

    companion object {
        private fun bounds(node: ConfigurationSection?): Map<String, PriceBound> {
            node ?: return emptyMap()
            val out = LinkedHashMap<String, PriceBound>()
            for (k in node.getKeys(false)) out[k.lowercase()] = PriceBound(node.getLong("$k.min", -1), node.getLong("$k.max", -1))
            return out
        }

        fun load(section: ConfigurationSection?): AuctionSettings {
            val d = AuctionSettings()
            section ?: return d
            val models = LinkedHashMap<String, List<Int>>()
            section.getConfigurationSection("banned.models")?.let { node -> for (k in node.getKeys(false)) models[k.uppercase()] = node.getIntegerList(k) }
            return AuctionSettings(
                enabled = section.getBoolean("enabled", true),
                currency = ModuleCurrency.load(section.getConfigurationSection("currency")),
                expireHours = section.getInt("expire-hours", d.expireHours).coerceIn(1, 24 * 365),
                purgeDays = section.getInt("purge-days", d.purgeDays).coerceIn(1, 3650),
                listingTaxPercent = section.getDouble("tax.listing-percent", d.listingTaxPercent).coerceIn(0.0, 100.0),
                purchaseTaxPercent = section.getDouble("tax.purchase-percent", d.purchaseTaxPercent).coerceIn(0.0, 100.0),
                maxListings = RankValues.load(section.getConfigurationSection("max-listings"), d.maxListings),
                currencyBounds = if (section.contains("price-bounds.currency")) bounds(section.getConfigurationSection("price-bounds.currency")) else d.currencyBounds,
                materialBounds = bounds(section.getConfigurationSection("price-bounds.material")).mapKeys { it.key.uppercase() },
                bannedMaterials = section.getStringList("banned.materials"),
                bannedNames = section.getStringList("banned.names"),
                bannedLores = section.getStringList("banned.lores"),
                bannedModels = models,
                announce = section.getBoolean("announce", true),
                autoClaim = section.getBoolean("auto-claim", false),
                notifyOnJoin = section.getBoolean("notify-on-join", true),
                warningsToBan = section.getInt("warnings.to-ban", d.warningsToBan).coerceIn(1, 100),
                banDays = section.getInt("warnings.ban-days", d.banDays).coerceIn(1, 3650),
                warningExpireDays = section.getInt("warnings.expire-days", d.warningExpireDays).coerceIn(1, 3650),
            )
        }
    }
}

data class VirtualSettings(
    val enabled: Boolean = true,
    val currency: ModuleCurrency = ModuleCurrency(),
    val clicks: ClickMap = ClickMap.VIRTUAL,
    val mainMenu: Boolean = true,
    val hideNoPermission: Boolean = true,
    val shortcuts: Boolean = true,
    val sellGui: Boolean = true,
    val sellAll: Boolean = true,
    val sellHand: Boolean = true,
    val sellHandAll: Boolean = true,
    val sellContainers: Boolean = true,
    val sellMultiplier: RankValues = RankValues(RankValues.Mode.RANK, "inmcshop.sellmultiplier.", 1.0, mapOf("vip" to 1.5, "gold" to 2.0)),
    val market: MarketParams = MarketParams(),
) {
    fun save(section: ConfigurationSection) {
        section.set("enabled", enabled)
        currency.save(section.createSection("currency"))
        clicks.save(section.createSection("clicks"))
        section.set("main-menu.enabled", mainMenu)
        section.set("main-menu.hide-no-permission", hideNoPermission)
        section.set("shortcuts", shortcuts)
        section.set("sell.gui", sellGui)
        section.set("sell.all", sellAll)
        section.set("sell.hand", sellHand)
        section.set("sell.hand-all", sellHandAll)
        section.set("sell.containers", sellContainers)
        sellMultiplier.save(section.createSection("sell-multiplier"))
        market.save(section.createSection("market"))
    }

    companion object {
        fun load(section: ConfigurationSection?): VirtualSettings {
            val d = VirtualSettings()
            section ?: return d
            return VirtualSettings(
                enabled = section.getBoolean("enabled", true),
                currency = ModuleCurrency.load(section.getConfigurationSection("currency")),
                clicks = ClickMap.load(section.getConfigurationSection("clicks"), ClickMap.VIRTUAL),
                mainMenu = section.getBoolean("main-menu.enabled", true),
                hideNoPermission = section.getBoolean("main-menu.hide-no-permission", true),
                shortcuts = section.getBoolean("shortcuts", true),
                sellGui = section.getBoolean("sell.gui", true),
                sellAll = section.getBoolean("sell.all", true),
                sellHand = section.getBoolean("sell.hand", true),
                sellHandAll = section.getBoolean("sell.hand-all", true),
                sellContainers = section.getBoolean("sell.containers", true),
                sellMultiplier = RankValues.load(section.getConfigurationSection("sell-multiplier"), d.sellMultiplier),
                market = MarketParams.load(section.getConfigurationSection("market")),
            )
        }
    }
}

/** `config.yml` 의 불변 스냅샷. 리로드·설정 화면은 통째로 바꿔 끼운다. */
data class ShopConfig(
    val serverName: String = "main",
    val networkUrl: String = "",
    val networkUser: String = "",
    val networkPassword: String = "",
    val syncSeconds: Int = 10,
    /**
     * 세 모듈이 동작하는 월드(화이트리스트). 비면 아무 데도 안 된다. 게임모드는 설정이 없다 — **서바이벌만**(사용자 결정,
     * 관리자는 `inmcshop.bypass.gamemode`).
     */
    val worlds: List<String> = listOf("world", "world_nether", "world_the_end"),
    val currencyNeedsPermission: Boolean = false,
    val buyWithFullInventory: Boolean = false,
    val closeAfterPurchase: Boolean = false,
    val logToFile: Boolean = true,
    val logToConsole: Boolean = false,
    val virtual: VirtualSettings = VirtualSettings(),
    val chest: ChestSettings = ChestSettings(),
    val auction: AuctionSettings = AuctionSettings(),
) {
    fun toYaml(): YamlConfiguration {
        val y = YamlConfiguration()
        y.set("server-name", serverName)
        y.set("network.url", networkUrl)
        y.set("network.user", networkUser)
        y.set("network.password", networkPassword)
        y.set("network.sync-seconds", syncSeconds)
        y.set("worlds", worlds)
        y.set("currency-needs-permission", currencyNeedsPermission)
        y.set("buy-with-full-inventory", buyWithFullInventory)
        y.set("close-after-purchase", closeAfterPurchase)
        y.set("logs.file", logToFile)
        y.set("logs.console", logToConsole)
        virtual.save(y.createSection("virtual"))
        chest.save(y.createSection("chest"))
        auction.save(y.createSection("auction"))
        return y
    }

    companion object {
        fun from(y: ConfigurationSection): ShopConfig = ShopConfig(
            serverName = y.getString("server-name")?.takeIf { it.isNotBlank() } ?: "main",
            networkUrl = y.getString("network.url").orEmpty().trim(),
            networkUser = y.getString("network.user").orEmpty(),
            networkPassword = y.getString("network.password").orEmpty(),
            syncSeconds = y.getInt("network.sync-seconds", 10).coerceIn(2, 600),
            worlds = if (y.contains("worlds")) y.getStringList("worlds") else ShopConfig().worlds,
            currencyNeedsPermission = y.getBoolean("currency-needs-permission", false),
            buyWithFullInventory = y.getBoolean("buy-with-full-inventory", false),
            closeAfterPurchase = y.getBoolean("close-after-purchase", false),
            logToFile = y.getBoolean("logs.file", true),
            logToConsole = y.getBoolean("logs.console", false),
            virtual = VirtualSettings.load(y.getConfigurationSection("virtual")),
            chest = ChestSettings.load(y.getConfigurationSection("chest")),
            auction = AuctionSettings.load(y.getConfigurationSection("auction")),
        )
    }
}
