package com.inmc.shop.command

import com.inmc.shop.Shop
import com.inmc.shop.ShopPlugin
import com.inmc.shop.gui.AdminHubMenu
import com.inmc.shop.gui.AuctionMenu
import com.inmc.shop.gui.AuctionMineMenu
import com.inmc.shop.gui.BankMenu
import com.inmc.shop.gui.ChestListMenu
import com.inmc.shop.gui.MainMenu
import com.inmc.shop.gui.OwnerBrowseMenu
import com.inmc.shop.gui.SellMenu
import com.inmc.shop.gui.ShopMenu
import com.inmc.shop.gui.WarningsMenu
import com.inmc.shop.util.Ph
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 명령어 — **한글 뿌리**(`/상점` · `/판매` · `/전부판매` · `/손판매` · `/같은것판매` · `/상자상점` · `/경매장`)와 **영어는 `/inmcshop` 하나의 뿌리 아래**
 * (사용자 결정). 같은 동작을 두 이름으로 — 동작은 아래 함수들이 한 벌만 갖는다.
 *
 * 상점 id 는 한글일 수 있어 맨 뒤의 `greedyString` 으로 받는다(Brigadier 의 `word()` 는 한글 첫 글자에서 멈춘다).
 */
class ShopCommand(private val shop: Shop, private val plugin: ShopPlugin) {

    fun register(owner: org.bukkit.plugin.java.JavaPlugin) {
        owner.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val r = event.registrar()
            r.register(shopTree(K).build(), "INMC 상점 — 서버 상점")
            r.register(simple("판매", "inmcshop.virtual.command.sellmenu", ::sellMenu).build(), "판매 창")
            r.register(simple("전부판매", "inmcshop.virtual.command.sellall", ::sellAll).build(), "가방에서 팔 수 있는 것 전부")
            r.register(simple("손판매", "inmcshop.virtual.command.sellhand", ::sellHand).build(), "손에 든 것 팔기")
            r.register(simple("같은것판매", "inmcshop.virtual.command.sellhandall", ::sellHandAll).build(), "손에 든 것과 같은 것 전부 팔기")
            r.register(chestTree(K).build(), "INMC 상점 — 상자 상점")
            r.register(auctionTree(K).build(), "INMC 상점 — 경매장")
            r.register(english().build(), "INMC 상점 (영어 명령어)")
            if (shop.config.virtual.shortcuts) {
                for (vshop in shop.shops.all()) for (alias in vshop.aliases) {
                    runCatching {
                        r.register(Commands.literal(alias).requires { it.sender.hasPermission("inmcshop.virtual.command.shop") }
                            .executes { ctx -> player(ctx)?.let { p -> shop.shops.get(vshop.id)?.let { ShopMenu.open(shop, p, it) } }; 1 }.build(), "${vshop.id} 상점 바로 열기")
                    }.onFailure { shop.logger.warning("단축 명령어 /$alias 를 등록하지 못했습니다: ${it.message}") }
                }
            }
        }
    }

    /** 한 트리의 낱말들 — 한글과 영어. */
    private class Names(
        val shop: String, val open: String, val openFor: String, val admin: String, val rotate: String, val reload: String, val verify: String,
        val chest: String, val create: String, val list: String, val listAll: String, val browse: String, val search: String, val owner: String,
        val bank: String, val give: String, val returns: String,
        val auction: String, val selling: String, val unclaimed: String, val expired: String, val history: String, val warnings: String,
    )

    private val K = Names(
        "상점", "열기", "열어주기", "관리", "회전", "리로드", "검증",
        "상자상점", "만들기", "목록", "전체목록", "둘러보기", "검색", "주인검색", "은행", "지급", "돌려받기",
        "경매장", "판매중", "미수령", "만료", "기록", "경고",
    )
    private val E = Names(
        "open", "open", "openfor", "admin", "rotate", "reload", "verify",
        "chest", "create", "list", "listall", "browse", "search", "owner", "bank", "give", "returns",
        "auction", "selling", "unclaimed", "expired", "history", "warnings",
    )

    private val shopIds = SuggestionProvider<CommandSourceStack> { _, b ->
        shop.shops.all().map { it.id }.filter { it.startsWith(b.remaining, ignoreCase = true) }.forEach(b::suggest); b.buildFuture()
    }
    private val players = SuggestionProvider<CommandSourceStack> { _, b ->
        Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(b.remaining, ignoreCase = true) }.forEach(b::suggest); b.buildFuture()
    }

    private fun sender(ctx: CommandContext<CommandSourceStack>): CommandSender = ctx.source.sender
    private fun player(ctx: CommandContext<CommandSourceStack>): Player? =
        (ctx.source.executor as? Player ?: ctx.source.sender as? Player) ?: null.also { shop.messages.send(sender(ctx), "player-only") }

    private fun other(ctx: CommandContext<CommandSourceStack>, arg: String = "플레이어"): Player? {
        val name = StringArgumentType.getString(ctx, arg)
        return Bukkit.getPlayerExact(name) ?: null.also { shop.messages.send(sender(ctx), "player-not-found", Ph.of().player(name)) }
    }

    // --- 서버 상점 ----------------------------------------------------------------------------

    private fun shopTree(n: Names): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(n.shop)
        .requires { it.sender.hasPermission("inmcshop.virtual.command.menu") }
        .executes { ctx -> player(ctx)?.let(::mainMenu); 1 }
        .then(Commands.literal(n.open).requires { it.sender.hasPermission("inmcshop.virtual.command.open") }
            .then(Commands.argument("상점", StringArgumentType.greedyString()).suggests(shopIds).executes { ctx -> player(ctx)?.let { openShop(it, StringArgumentType.getString(ctx, "상점"), false) }; 1 }))
        .then(Commands.literal(n.openFor).requires { it.sender.hasPermission("inmcshop.virtual.command.open.others") }
            .then(Commands.argument("플레이어", StringArgumentType.word()).suggests(players)
                .then(Commands.argument("상점", StringArgumentType.greedyString()).suggests(shopIds).executes { ctx -> other(ctx)?.let { openShop(it, StringArgumentType.getString(ctx, "상점"), true) }; 1 })))
        .then(Commands.literal(n.admin).requires { it.sender.hasPermission("inmcshop.virtual.command.editor") }.executes { ctx -> player(ctx)?.let { AdminHubMenu(shop, it).show() }; 1 })
        .then(Commands.literal(n.rotate).requires { it.sender.hasPermission("inmcshop.virtual.command.rotate") }
            .then(Commands.argument("상점", StringArgumentType.greedyString()).suggests(shopIds).executes { ctx -> rotate(sender(ctx), StringArgumentType.getString(ctx, "상점")); 1 }))
        .then(Commands.literal(n.reload).requires { it.sender.hasPermission("inmcshop.reload") }.executes { ctx ->
            val s = sender(ctx); plugin.reload { shop.messages.send(s, "reloaded") }; 1
        })
        .then(Commands.literal(n.verify).requires { it.sender.hasPermission("inmcshop.reload") }.executes { ctx -> player(ctx)?.let { shop.verifier.start(it) }; 1 })
        .then(Commands.argument("상점", StringArgumentType.greedyString()).suggests(shopIds).requires { it.sender.hasPermission("inmcshop.virtual.command.open") }
            .executes { ctx -> player(ctx)?.let { openShop(it, StringArgumentType.getString(ctx, "상점"), false) }; 1 })

    private fun mainMenu(player: Player) {
        if (!shop.config.virtual.enabled) return shop.messages.send(player, "module-disabled")
        if (!shop.config.virtual.mainMenu) return shop.messages.send(player, "main-menu-disabled")
        if (!shop.guard(player)) return
        MainMenu(shop, player).show()
    }

    private fun openShop(player: Player, id: String, force: Boolean) {
        val vshop = shop.shops.get(id.trim()) ?: return shop.messages.send(player, "unknown-shop", Ph.of().shop(id))
        ShopMenu.open(shop, player, vshop, force)
    }

    private fun rotate(sender: CommandSender, id: String) {
        val vshop = shop.shops.get(id.trim()) ?: return shop.messages.send(sender, "unknown-shop", Ph.of().shop(id))
        if (vshop.rotations.isEmpty()) return shop.messages.send(sender, "no-rotations")
        for (r in vshop.rotations.values) shop.rotations.force(vshop, r)
        shop.messages.send(sender, "rotation-forced")
    }

    // --- 판매 --------------------------------------------------------------------------------

    /** `/판매 [플레이어]` 꼴 — 남에게는 `.others` 권한. */
    private fun simple(name: String, permission: String, action: (Player) -> Unit): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(name)
        .requires { it.sender.hasPermission(permission) }
        .executes { ctx -> player(ctx)?.let(action); 1 }
        .then(Commands.argument("플레이어", StringArgumentType.word()).suggests(players).requires { it.sender.hasPermission("$permission.others") }
            .executes { ctx -> other(ctx)?.let(action); 1 })

    private fun sellMenu(player: Player) {
        if (!shop.config.virtual.sellGui) return shop.messages.send(player, "feature-disabled")
        if (!shop.guard(player)) return
        SellMenu(shop, player).show()
    }

    private fun sellAll(player: Player) = if (!shop.config.virtual.sellAll) shop.messages.send(player, "feature-disabled") else shop.sell.sellAll(player)
    private fun sellHand(player: Player) = if (!shop.config.virtual.sellHand) shop.messages.send(player, "feature-disabled") else shop.sell.sellHand(player)
    private fun sellHandAll(player: Player) = if (!shop.config.virtual.sellHandAll) shop.messages.send(player, "feature-disabled") else shop.sell.sellHandAll(player)

    // --- 상자 상점 ----------------------------------------------------------------------------

    private fun chestTree(n: Names): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(n.chest)
        .executes { ctx -> player(ctx)?.let(::ownList); 1 }
        .then(Commands.literal(n.create).requires { it.sender.hasPermission("inmcshop.chestshop.create") }.executes { ctx -> player(ctx)?.let(::create); 1 })
        .then(Commands.literal(n.list).requires { it.sender.hasPermission("inmcshop.chestshop.command.list") }.executes { ctx -> player(ctx)?.let(::ownList); 1 })
        .then(Commands.literal(n.listAll).requires { it.sender.hasPermission("inmcshop.chestshop.command.browse") }.executes { ctx ->
            player(ctx)?.let { p -> ChestListMenu(shop, p, "<dark_gray>상자 상점 전체</dark_gray>", { shop.chests.all().map { it to null } }).show() }; 1
        })
        .then(Commands.literal(n.browse).requires { it.sender.hasPermission("inmcshop.chestshop.command.browse") }.executes { ctx -> player(ctx)?.let { OwnerBrowseMenu(shop, it).show() }; 1 })
        .then(Commands.literal(n.search).requires { it.sender.hasPermission("inmcshop.chestshop.command.search") }
            .executes { ctx -> player(ctx)?.let { OwnerBrowseMenu.search(shop, it, null) }; 1 }
            .then(Commands.argument("이름", StringArgumentType.greedyString()).executes { ctx ->
                val q = StringArgumentType.getString(ctx, "이름")
                player(ctx)?.let { p -> ChestListMenu(shop, p, "<dark_gray>검색: $q</dark_gray>", { shop.chests.search(q, null) }).show() }; 1
            }))
        .then(Commands.literal(n.owner).requires { it.sender.hasPermission("inmcshop.chestshop.command.search") }
            .then(Commands.argument("이름", StringArgumentType.word()).suggests(players).executes { ctx ->
                val name = StringArgumentType.getString(ctx, "이름")
                val p = player(ctx) ?: return@executes 1
                val id = shop.findPlayer(name) ?: return@executes 1.also { shop.messages.send(p, "player-not-found", Ph.of().player(name)) }
                ChestListMenu(shop, p, "<dark_gray>$name 의 상점</dark_gray>", { shop.chests.all().filter { it.operator() == id }.map { it to null } }).show(); 1
            }))
        .then(Commands.literal(n.bank).requires { it.sender.hasPermission("inmcshop.chestshop.command.bank") }
            .executes { ctx -> player(ctx)?.let { BankMenu(shop, it, it.uniqueId, null).show() }; 1 }
            .then(Commands.argument("이름", StringArgumentType.word()).suggests(players).requires { it.sender.hasPermission("inmcshop.chestshop.command.bank.others") }.executes { ctx ->
                val name = StringArgumentType.getString(ctx, "이름")
                val p = player(ctx) ?: return@executes 1
                val id = shop.findPlayer(name) ?: return@executes 1.also { shop.messages.send(p, "player-not-found", Ph.of().player(name)) }
                BankMenu(shop, p, id, null).show(); 1
            }))
        .then(Commands.literal(n.returns).executes { ctx ->
            player(ctx)?.let { p -> shop.returns.claim(p) { got, left -> shop.messages.send(p, if (got + left == 0) "returns-none" else "returns-claimed", Ph.of().count(got).value(left.toString())) } }; 1
        })
        .then(Commands.literal(n.give).requires { it.sender.hasPermission("inmcshop.chestshop.command.giveitem") }
            .then(Commands.argument("플레이어", StringArgumentType.word()).suggests(players)
                .then(Commands.argument("블록", StringArgumentType.word()).suggests { _, b -> shop.config.chest.blocks.map { it.lowercase() }.filter { it.startsWith(b.remainingLowerCase) }.forEach(b::suggest); b.buildFuture() }
                    .executes { ctx -> giveItem(ctx, 1); 1 }
                    .then(Commands.argument("개수", IntegerArgumentType.integer(1, 64)).executes { ctx -> giveItem(ctx, IntegerArgumentType.getInteger(ctx, "개수")); 1 }))))

    private fun ownList(player: Player) {
        ChestListMenu(shop, player, "<dark_gray>내 상자 상점</dark_gray>", { shop.chests.all().filter { it.canManage(player.uniqueId) }.map { it to null } }).show()
    }

    private fun create(player: Player) {
        val block = player.getTargetBlockExact(5) ?: return shop.messages.send(player, "chest-look-at-container")
        shop.chests.create(player, block)
    }

    private fun giveItem(ctx: CommandContext<CommandSourceStack>, amount: Int) {
        val target = other(ctx) ?: return
        val material = Material.matchMaterial(StringArgumentType.getString(ctx, "블록")) ?: return shop.messages.send(sender(ctx), "chest-not-container")
        val stack = shop.chests.creationItem(material, amount)
        target.inventory.addItem(stack).values.forEach { target.world.dropItemNaturally(target.location, it) }
        shop.messages.send(sender(ctx), "given", Ph.of().player(target.name).amount(amount.toLong()))
    }

    // --- 경매장 ------------------------------------------------------------------------------

    private fun auctionTree(n: Names): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(n.auction)
        .requires { it.sender.hasPermission("inmcshop.auction.command.open") }
        .executes { ctx -> player(ctx)?.let(::openAuction); 1 }
        .then(mine(n.selling, "selling", AuctionMineMenu.Tab.SELLING))
        .then(mine(n.unclaimed, "unclaimed", AuctionMineMenu.Tab.UNCLAIMED))
        .then(mine(n.expired, "expired", AuctionMineMenu.Tab.EXPIRED))
        .then(mine(n.history, "history", AuctionMineMenu.Tab.HISTORY))
        .then(Commands.literal(n.warnings).requires { it.sender.hasPermission("inmcshop.auction.warnings") }
            .then(Commands.argument("플레이어", StringArgumentType.word()).suggests(players).executes { ctx ->
                val name = StringArgumentType.getString(ctx, "플레이어")
                val p = player(ctx) ?: return@executes 1
                val id = shop.findPlayer(name) ?: return@executes 1.also { shop.messages.send(p, "player-not-found", Ph.of().player(name)) }
                WarningsMenu(shop, p, id, shop.nameOf(id), null).show(); 1
            }))

    private fun mine(name: String, permission: String, tab: AuctionMineMenu.Tab) = Commands.literal(name)
        .requires { it.sender.hasPermission("inmcshop.auction.command.$permission") }
        .executes { ctx -> player(ctx)?.let { if (shop.config.auction.enabled) AuctionMineMenu(shop, it, tab).show() else shop.messages.send(it, "module-disabled") }; 1 }
        .then(Commands.argument("플레이어", StringArgumentType.word()).suggests(players).requires { it.sender.hasPermission("inmcshop.auction.command.$permission.others") }
            .executes { ctx ->
                val name = StringArgumentType.getString(ctx, "플레이어")
                val p = player(ctx) ?: return@executes 1
                val id = shop.findPlayer(name) ?: return@executes 1.also { shop.messages.send(p, "player-not-found", Ph.of().player(name)) }
                AuctionMineMenu(shop, p, tab, id).show(); 1
            })

    private fun openAuction(player: Player) {
        if (!shop.config.auction.enabled) return shop.messages.send(player, "module-disabled")
        if (!shop.guard(player)) return
        AuctionMenu(shop, player).show()
    }

    // --- 영어 --------------------------------------------------------------------------------

    private fun english(): LiteralArgumentBuilder<CommandSourceStack> {
        val root = Commands.literal("inmcshop")
        // /inmcshop open [shop] · admin · rotate · reload · verify 는 서버 상점 트리를 그대로 붙인다.
        val shopNode = shopTree(E).build()
        for (child in shopNode.children) root.then(child)
        root.executes { ctx -> player(ctx)?.let(::mainMenu); 1 }
        root.then(simple("sell", "inmcshop.virtual.command.sellmenu", ::sellMenu))
        root.then(simple("sellall", "inmcshop.virtual.command.sellall", ::sellAll))
        root.then(simple("sellhand", "inmcshop.virtual.command.sellhand", ::sellHand))
        root.then(simple("sellhandall", "inmcshop.virtual.command.sellhandall", ::sellHandAll))
        root.then(chestTree(E))
        root.then(auctionTree(E))
        return root
    }
}
