package com.inmc.shop.virtual

import com.inmc.shop.Shop
import com.inmc.shop.price.Pricing
import kr.inmc.core.item.ItemRef
import kr.inmc.core.item.StoredItem
import org.bukkit.Material

/**
 * 기본 상점 여섯 개(사용자 결정 2026-09-25). **파일이 없을 때만** 만든다 — 관리자가 고친 것을 덮지 않는다.
 *
 * - 블록·광물·작물·전리품: 물건만 전부 올리고 **구매·판매는 꺼 둔다**(가격 없음). 관리자가 정한다.
 * - 특수상점(화폐 `inmc` = 근원) · 캐시샵(화폐 `cash`): 예제 몇 개. 명령어 예제는 서버에 맞게 고쳐야 한다(설명에 적어 둠).
 *
 * 블록상점은 서버에서 블록 아이템을 훑어 만든다 — 버전이 올라 새 블록이 생겨도 저절로 들어간다. 서바이벌로 못 얻는 것은 [UNOBTAINABLE].
 */
object DefaultShops {

    /** 서바이벌로 얻을 수 없는 블록 아이템. 이름으로 적는다(버전에 없는 이름은 그냥 넘어간다). */
    val UNOBTAINABLE: Set<String> = setOf(
        "BEDROCK", "BARRIER", "LIGHT", "STRUCTURE_BLOCK", "STRUCTURE_VOID", "JIGSAW", "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK",
        "SPAWNER", "TRIAL_SPAWNER", "VAULT", "END_PORTAL_FRAME", "REINFORCED_DEEPSLATE", "BUDDING_AMETHYST",
        "INFESTED_STONE", "INFESTED_COBBLESTONE", "INFESTED_STONE_BRICKS", "INFESTED_MOSSY_STONE_BRICKS", "INFESTED_CRACKED_STONE_BRICKS",
        "INFESTED_CHISELED_STONE_BRICKS", "INFESTED_DEEPSLATE", "PETRIFIED_OAK_SLAB", "PLAYER_HEAD", "FARMLAND", "DIRT_PATH", "FROGSPAWN",
        "CHORUS_PLANT", "SUSPICIOUS_SAND", "SUSPICIOUS_GRAVEL", "TEST_BLOCK", "TEST_INSTANCE_BLOCK", "DRAGON_EGG", "KNOWLEDGE_BOOK", "DEBUG_STICK",
    )

    val MINERALS = listOf(
        "COAL_ORE", "DEEPSLATE_COAL_ORE", "IRON_ORE", "DEEPSLATE_IRON_ORE", "COPPER_ORE", "DEEPSLATE_COPPER_ORE", "GOLD_ORE", "DEEPSLATE_GOLD_ORE",
        "REDSTONE_ORE", "DEEPSLATE_REDSTONE_ORE", "EMERALD_ORE", "DEEPSLATE_EMERALD_ORE", "LAPIS_ORE", "DEEPSLATE_LAPIS_ORE", "DIAMOND_ORE",
        "DEEPSLATE_DIAMOND_ORE", "NETHER_GOLD_ORE", "NETHER_QUARTZ_ORE", "ANCIENT_DEBRIS",
        "RAW_IRON", "RAW_COPPER", "RAW_GOLD", "COAL", "CHARCOAL", "IRON_NUGGET", "IRON_INGOT", "COPPER_NUGGET", "COPPER_INGOT", "GOLD_NUGGET", "GOLD_INGOT",
        "NETHERITE_SCRAP", "NETHERITE_INGOT", "DIAMOND", "EMERALD", "LAPIS_LAZULI", "REDSTONE", "QUARTZ", "AMETHYST_SHARD", "GLOWSTONE_DUST", "FLINT",
        "RAW_IRON_BLOCK", "RAW_COPPER_BLOCK", "RAW_GOLD_BLOCK", "COAL_BLOCK", "IRON_BLOCK", "COPPER_BLOCK", "GOLD_BLOCK", "DIAMOND_BLOCK", "EMERALD_BLOCK",
        "LAPIS_BLOCK", "REDSTONE_BLOCK", "AMETHYST_BLOCK", "QUARTZ_BLOCK", "NETHERITE_BLOCK",
    )

    val CROPS = listOf(
        "WHEAT", "WHEAT_SEEDS", "CARROT", "POTATO", "POISONOUS_POTATO", "BEETROOT", "BEETROOT_SEEDS", "MELON_SLICE", "MELON", "MELON_SEEDS", "PUMPKIN",
        "PUMPKIN_SEEDS", "SUGAR_CANE", "CACTUS", "COCOA_BEANS", "NETHER_WART", "SWEET_BERRIES", "GLOW_BERRIES", "BAMBOO", "KELP", "SEA_PICKLE",
        "CHORUS_FRUIT", "CHORUS_FLOWER", "APPLE", "BROWN_MUSHROOM", "RED_MUSHROOM", "CRIMSON_FUNGUS", "WARPED_FUNGUS", "TORCHFLOWER_SEEDS", "TORCHFLOWER",
        "PITCHER_POD", "PITCHER_PLANT", "VINE", "LILY_PAD", "HONEYCOMB", "HONEY_BOTTLE",
    )

    val LOOT = listOf(
        "ROTTEN_FLESH", "BONE", "ARROW", "STRING", "SPIDER_EYE", "GUNPOWDER", "ENDER_PEARL", "BLAZE_ROD", "BREEZE_ROD", "GHAST_TEAR", "SLIME_BALL",
        "MAGMA_CREAM", "PHANTOM_MEMBRANE", "LEATHER", "RABBIT_HIDE", "RABBIT_FOOT", "BEEF", "PORKCHOP", "CHICKEN", "MUTTON", "RABBIT", "COD", "SALMON",
        "TROPICAL_FISH", "PUFFERFISH", "FEATHER", "EGG", "WHITE_WOOL", "INK_SAC", "GLOW_INK_SAC", "PRISMARINE_SHARD", "PRISMARINE_CRYSTALS", "SHULKER_SHELL",
        "NETHER_STAR", "TOTEM_OF_UNDYING", "SKELETON_SKULL", "WITHER_SKELETON_SKULL", "ZOMBIE_HEAD", "CREEPER_HEAD", "PIGLIN_HEAD", "DRAGON_HEAD", "TRIDENT",
        "NAUTILUS_SHELL", "TURTLE_SCUTE", "ARMADILLO_SCUTE", "GOAT_HORN", "SADDLE", "SNOWBALL", "DRAGON_BREATH", "HEAVY_CORE", "RESIN_CLUMP",
    )

    private fun stored(material: Material) = StoredItem(ItemRef.Vanilla(material), material)

    private fun materials(names: List<String>): List<Material> = names.mapNotNull { Material.matchMaterial(it) }.filter { it.isItem }.distinct()

    /** 서바이벌로 얻을 수 있는 블록 아이템 전부. 서버가 떠 있어야 한다(`isBlock`). */
    fun obtainableBlocks(): List<Material> = Material.entries.filter { m ->
        !m.isLegacy && m.isItem && m.isBlock && !m.isAir && m.name !in UNOBTAINABLE
    }

    /** 물건 목록 → 가격 없는 상품들(45칸씩 페이지). */
    private fun grid(shop: Shop, shopId: String, items: List<Material>): Pair<Map<String, Product>, Int> {
        val slots = shop.layouts.get("default").productSlots()
        val products = LinkedHashMap<String, Product>()
        for ((i, material) in items.withIndex()) {
            val id = "p%04d".format(i)
            products[id] = Product(id, shopId, item = stored(material), page = i / slots.size + 1, slot = slots[i % slots.size])
        }
        return products to ((items.size + slots.size - 1) / slots.size).coerceAtLeast(1)
    }

    private fun priced(shopId: String, index: Int, material: Material, unit: Int, buy: Long, name: String? = null) =
        Product("p%04d".format(index), shopId, item = stored(material), unit = unit, name = name, pricing = Pricing.Fixed(buy, null), page = 1, slot = 10 + index + (index / 7) * 2)

    private fun command(shopId: String, index: Int, icon: Material, name: String, commands: List<String>, buy: Long) = Product(
        "p%04d".format(index), shopId, type = ProductType.COMMAND, preview = stored(icon), name = name,
        lore = listOf("<red>예제 — 명령어를 이 서버에 맞게 고치세요(/상점 관리)</red>"), commands = commands,
        pricing = Pricing.Fixed(buy, null), page = 1, slot = 10 + index + (index / 7) * 2,
    )

    /** 없는 것만 만든다. 만든 개수. */
    fun create(shop: Shop, onlyMissing: Boolean = true): Int {
        var made = 0
        fun add(value: VirtualShop) {
            if (onlyMissing && shop.shops.get(value.id) != null) return
            shop.shops.put(value)
            made++
        }
        run {
            val (products, pages) = grid(shop, "blocks", obtainableBlocks())
            add(VirtualShop("blocks", "<green><b>블록상점</b></green>", listOf("<gray>서바이벌로 얻을 수 있는 블록</gray>"), iconMaterial = Material.GRASS_BLOCK, pages = pages, menuSlot = 10, products = products))
        }
        run {
            val (products, pages) = grid(shop, "minerals", materials(MINERALS))
            add(VirtualShop("minerals", "<aqua><b>광물상점</b></aqua>", listOf("<gray>광석·원석·주괴·보석</gray>"), iconMaterial = Material.DIAMOND, pages = pages, menuSlot = 12, products = products))
        }
        run {
            val (products, pages) = grid(shop, "crops", materials(CROPS))
            add(VirtualShop("crops", "<yellow><b>작물상점</b></yellow>", listOf("<gray>기르고 거두는 것</gray>"), iconMaterial = Material.WHEAT, pages = pages, menuSlot = 14, products = products))
        }
        run {
            val (products, pages) = grid(shop, "loot", materials(LOOT))
            add(VirtualShop("loot", "<red><b>전리품상점</b></red>", listOf("<gray>몬스터가 떨구는 것</gray>"), iconMaterial = Material.ROTTEN_FLESH, pages = pages, menuSlot = 16, products = products))
        }
        run {
            val examples = listOf(
                priced("special", 0, Material.TOTEM_OF_UNDYING, 1, 30),
                priced("special", 1, Material.ELYTRA, 1, 200),
                priced("special", 2, Material.NETHERITE_INGOT, 1, 50),
                priced("special", 3, Material.ENCHANTED_GOLDEN_APPLE, 1, 40),
                priced("special", 4, Material.HEART_OF_THE_SEA, 1, 30),
                priced("special", 5, Material.NETHER_STAR, 1, 80),
                priced("special", 6, Material.SHULKER_BOX, 1, 25),
            ).associateBy { it.id }
            add(VirtualShop("special", "<light_purple><b>특수상점</b></light_purple>", listOf("<gray>근원으로 사는 귀한 물건</gray>"), iconMaterial = Material.RABBIT_HIDE,
                selling = false, menuSlot = 30, currency = "inmc", products = examples))
        }
        run {
            val examples = listOf(
                command("cash", 0, Material.TOTEM_OF_UNDYING, "<aqua>인벤 보호권 1장</aqua>", listOf("인벤키퍼 지급 {player} 1 보호권"), 100),
                command("cash", 1, Material.NAME_TAG, "<gold>칭호: 후원자 (30일)</gold>", listOf("titleforge give {player} title 후원자 30d"), 300),
                priced("cash", 2, Material.EXPERIENCE_BOTTLE, 64, 50, "<green>경험치 병 64개</green>"),
                priced("cash", 3, Material.FIREWORK_ROCKET, 64, 30, "<white>폭죽 64개</white>"),
                priced("cash", 4, Material.ELYTRA, 1, 500, "<aqua>겉날개</aqua>"),
            ).associateBy { it.id }
            add(VirtualShop("cash", "<aqua><b>캐시샵</b></aqua>", listOf("<gray>캐시로 사는 상품</gray>"), iconMaterial = Material.DIAMOND,
                selling = false, menuSlot = 32, currency = "cash", products = examples))
        }
        return made
    }
}
