package com.inmc.shop.verify

import com.inmc.shop.Shop
import com.inmc.shop.auction.AuctionService
import com.inmc.shop.gui.AdminHubMenu
import com.inmc.shop.gui.AuctionMenu
import com.inmc.shop.gui.AuctionMineMenu
import com.inmc.shop.gui.AuctionSellMenu
import com.inmc.shop.gui.ChestManageMenu
import com.inmc.shop.gui.ChestShopMenu
import com.inmc.shop.gui.MainMenu
import com.inmc.shop.gui.ProductEditMenu
import com.inmc.shop.gui.SellMenu
import com.inmc.shop.gui.SettingsMenu
import com.inmc.shop.gui.ShopEditMenu
import com.inmc.shop.gui.ShopMenu
import com.inmc.shop.gui.TradeMenu
import com.inmc.shop.gui.VirtualTarget
import com.inmc.shop.price.Band
import com.inmc.shop.price.Pricing
import com.inmc.shop.trade.TradeType
import com.inmc.shop.util.Inv
import com.inmc.shop.util.Ph
import com.inmc.shop.virtual.LimitOptions
import com.inmc.shop.virtual.Product
import com.inmc.shop.virtual.ProductType
import com.inmc.shop.virtual.Requirements
import com.inmc.shop.virtual.StockOptions
import com.inmc.shop.virtual.VirtualShop
import kr.inmc.core.economy.Currencies
import kr.inmc.core.economy.Currency
import kr.inmc.core.item.ItemRef
import kr.inmc.core.item.StoredItem
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import org.bukkit.permissions.PermissionAttachment
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * `/상점 검증` — 서버 안에서 **진짜로** 사고판다. 검증하는 사람(보통 관리자)이 손님이 된다: 임시 서버 상점(`zzverify`)과
 * 발밑 근처에 임시 상자 상점을 만들고, 가방·잔고·게임모드·블록을 기록했다가 끝나면 되돌린다.
 *
 * 관리자는 우회 권한을 다 갖고 있어서, 월드·게임모드 검사는 `false` 권한 첨부로 우회를 끄고 본다.
 * 두 사람이 필요한 것(경매 사기·남의 상자 상점에서 사기)은 건너뛴다 — 실패가 아니다.
 */
class Verifier(private val shop: Shop) {

    enum class Status { PASS, FAIL, SKIP }

    private class Result(val name: String, val status: Status, val detail: String)

    private class Check(val name: String, val run: (done: (Status, String) -> Unit) -> Unit)

    private var running = false

    private inner class Session(val player: Player) {
        val results = ArrayList<Result>()
        val queue = ArrayDeque<Check>()
        val inventory: Array<ItemStack?> = player.inventory.contents.map { it?.clone() }.toTypedArray()
        val gameMode: GameMode = player.gameMode
        val currency: Currency? = Currencies.default()
        val balance: Long = currency?.balance(player) ?: 0
        var attachment: PermissionAttachment? = null
        var block: Block? = null
        var blockData: BlockData? = null
        val shopId = SHOP_ID
    }

    fun start(player: Player) {
        if (running) return shop.messages.send(player, "verify-running")
        running = true
        val s = Session(player)
        shop.messages.send(player, "verify-started")
        s.attachment = player.addAttachment(shop.plugin)
        player.gameMode = GameMode.SURVIVAL
        player.inventory.clear()
        s.currency?.deposit(player, FUNDS, "inmcshop:verify")
        setupShop()
        plan(s)
        next(s)
    }

    private fun product(id: String) = shop.shops.get(SHOP_ID)?.product(id)!!
    private fun stored(m: Material) = StoredItem(ItemRef.Vanilla(m), m)
    private fun count(p: Player, m: Material) = Inv.count(p) { it.type == m && !it.hasItemMeta() }

    private fun setupShop() {
        val products = listOf(
            Product("stone", SHOP_ID, item = stored(Material.STONE), pricing = Pricing.Fixed(10, 5), page = 1, slot = 0),
            Product("dirt", SHOP_ID, item = stored(Material.DIRT), pricing = Pricing.Fixed(3, 1), stock = StockOptions(true, 3, 2, 2, -1), page = 1, slot = 1),
            Product("cobble", SHOP_ID, item = stored(Material.COBBLESTONE), pricing = Pricing.Fixed(2, 1), limits = LimitOptions(true, 2, -1, 86400), page = 1, slot = 2),
            Product("cmd", SHOP_ID, type = ProductType.COMMAND, preview = stored(Material.PAPER), commands = listOf("say [inmcshop 검증] {player}"), pricing = Pricing.Fixed(1, null), page = 1, slot = 3),
            Product("gravel", SHOP_ID, item = stored(Material.GRAVEL), pricing = Pricing.Market(Band(100, 50, 200), Band(50, 25, 100)), page = 1, slot = 4),
            Product("sand", SHOP_ID, item = stored(Material.SAND), pricing = Pricing.Fixed(null, 7), requirements = Requirements(forbiddenPermissions = listOf(BLOCKED)), page = 1, slot = 5),
            Product("stone2", SHOP_ID, item = stored(Material.STONE), pricing = Pricing.Fixed(null, 8), page = 1, slot = 6),
        ).associateBy { it.id }
        shop.shops.put(VirtualShop(SHOP_ID, "<gray>검증</gray>", products = products))
    }

    private fun plan(s: Session) {
        val p = s.player
        val c = s.currency
        fun money() = c?.balance(p) ?: 0
        fun add(name: String, run: (done: (Status, String) -> Unit) -> Unit) { s.queue += Check(name, run) }

        add("화폐가 있다") { done -> if (c == null) done(Status.FAIL, "기본 화폐가 없다 — 화폐 플러그인(inmc-economy)을 확인") else done(Status.PASS, c.id) }

        add("허용 월드·서바이벌 — 크리에이티브면 막힌다") { done ->
            s.attachment!!.setPermission("inmcshop.bypass.gamemode", false)
            s.attachment!!.setPermission("inmcshop.bypass.worlds", false)
            p.gameMode = GameMode.CREATIVE
            val blockedInCreative = !shop.guard(p, silent = true)
            p.gameMode = GameMode.SURVIVAL
            val worldOk = p.world.name in shop.config.worlds
            val passInSurvival = shop.guard(p, silent = true)
            s.attachment!!.unsetPermission("inmcshop.bypass.gamemode")
            s.attachment!!.unsetPermission("inmcshop.bypass.worlds")
            when {
                !blockedInCreative -> done(Status.FAIL, "크리에이티브인데 통과했다")
                !worldOk -> done(Status.SKIP, "지금 월드(${p.world.name})가 허용 월드가 아니라 서바이벌 통과는 못 본다 — 막힌 것은 맞다")
                !passInSurvival -> done(Status.FAIL, "허용 월드·서바이벌인데 막혔다")
                else -> done(Status.PASS, "")
            }
        }

        if (c == null) return

        add("서버 상점 구매 — 돈이 빠지고 물건이 들어온다") { done ->
            val before = money()
            shop.trades.buy(p, product("stone"), 3, quiet = true) { out ->
                val paid = before - money()
                if (out == null) done(Status.FAIL, "구매가 실패했다")
                else if (paid != 30L || count(p, Material.STONE) != 3) done(Status.FAIL, "낸 돈 $paid(30이어야), 돌 ${count(p, Material.STONE)}(3이어야)")
                else done(Status.PASS, "")
            }
        }
        add("서버 상점 판매 — 가방에서 빠지고 돈이 들어온다(판매 배수 반영)") { done ->
            val before = money()
            val expected = Math.floor(5L * 2 * shop.trades.sellMultiplier(p)).toLong()
            shop.trades.sell(p, product("stone"), 2, quiet = true) { out ->
                val got = money() - before
                if (out == null) done(Status.FAIL, "판매가 실패했다")
                else if (got != expected || count(p, Material.STONE) != 1) done(Status.FAIL, "받은 돈 $got($expected 이어야), 돌 ${count(p, Material.STONE)}(1이어야)")
                else done(Status.PASS, "배수 x${shop.trades.sellMultiplier(p)}")
            }
        }
        add("전체 재고 — 남은 것보다 많이 살 수 없다") { done ->
            shop.stocks.set(product("dirt"), 2)
            shop.trades.buy(p, product("dirt"), 2, quiet = true) { first ->
                shop.trades.buy(p, product("dirt"), 1, quiet = true) { second ->
                    when {
                        first == null -> done(Status.FAIL, "재고 2인데 2개 구매가 실패")
                        second != null -> done(Status.FAIL, "재고 0인데 또 샀다")
                        shop.stocks.state(product("dirt"))?.units != 0L -> done(Status.FAIL, "재고가 ${shop.stocks.state(product("dirt"))?.units}")
                        else -> done(Status.PASS, "")
                    }
                }
            }
        }
        add("한 사람 한도 — 한도를 넘어 살 수 없다") { done ->
            shop.trades.buy(p, product("cobble"), 2, quiet = true) { first ->
                shop.trades.buy(p, product("cobble"), 1, quiet = true) { second ->
                    when {
                        first == null -> done(Status.FAIL, "한도 2인데 2개 구매가 실패")
                        second != null -> done(Status.FAIL, "한도를 넘어 샀다")
                        else -> done(Status.PASS, "")
                    }
                }
            }
        }
        add("명령어 상품 — 돈이 빠지고 명령어가 돈다") { done ->
            val before = money()
            shop.trades.buy(p, product("cmd"), 1, quiet = true) { out ->
                if (out == null || before - money() != 1L) done(Status.FAIL, "낸 돈 ${before - money()}") else done(Status.PASS, "")
            }
        }
        add("요구 조건 — 금지 권한이 있으면 못 판다") { done ->
            Inv.give(p, ItemStack(Material.SAND), 3, drop = true)
            s.attachment!!.setPermission(BLOCKED, true)
            shop.trades.sell(p, product("sand"), 1, quiet = true) { out ->
                s.attachment!!.unsetPermission(BLOCKED)
                if (out != null) done(Status.FAIL, "금지 권한인데 팔렸다") else done(Status.PASS, "")
            }
        }
        add("전부 판매 — 가장 비싸게 사주는 상품에 판다") { done ->
            Inv.give(p, ItemStack(Material.STONE), 4, drop = true)
            val have = count(p, Material.STONE)
            val best = shop.sell.best(p, ItemStack(Material.STONE), SHOP_ID)
            val before = money()
            shop.sell.sellEverything(p, listOf(com.inmc.shop.util.PlayerSource(p)), SHOP_ID) { _ ->
                val expected = Math.floor(8L * have * shop.trades.sellMultiplier(p)).toLong()
                when {
                    best?.id != "stone2" -> done(Status.FAIL, "최고가 상품이 ${best?.id}(stone2 여야)")
                    count(p, Material.STONE) != 0 -> done(Status.FAIL, "돌이 남았다 ${count(p, Material.STONE)}")
                    money() - before < expected -> done(Status.FAIL, "받은 돈 ${money() - before}($expected 이상이어야 — 모래 등 다른 것도 팔렸을 수 있다)")
                    else -> done(Status.PASS, "")
                }
            }
        }
        add("시장 가격 — 판 것이 이번 주기 기록에 들어간다") { done ->
            Inv.give(p, ItemStack(Material.GRAVEL), 2, drop = true)
            shop.trades.sell(p, product("gravel"), 2, quiet = true) { out ->
                if (out == null) return@sell done(Status.FAIL, "시장 가격 상품 판매 실패")
                shop.prices.flushTrades()
                val key = product("gravel").key
                val period = shop.config.virtual.market.periodOf(System.currentTimeMillis())
                val rows = runCatching { shop.db.call { shop.db.tradesOf(shop.db.shared, key, period) } }.getOrDefault(emptyList())
                if (rows.sumOf { it.second } < 2) done(Status.FAIL, "기록된 판매 ${rows.sumOf { it.second }}(2 이상이어야)") else done(Status.PASS, "")
            }
        }
        add("화면이 열린다 — 서버 상점") { done ->
            val opened = ArrayList<String>()
            fun check(name: String, open: () -> Unit, type: Class<*>) {
                open()
                if (!type.isInstance(p.openInventory.topInventory.holder)) opened += "$name 안 열림"
            }
            check("메인 메뉴", { MainMenu(shop, p).show() }, MainMenu::class.java)
            check("상점", { ShopMenu(shop, p, SHOP_ID, 0).show() }, ShopMenu::class.java)
            check("구매 화면", { TradeMenu(shop, p, VirtualTarget(shop, product("stone"), TradeType.BUY), null).show() }, TradeMenu::class.java)
            check("판매 창", { SellMenu(shop, p).show() }, SellMenu::class.java)
            check("관리", { AdminHubMenu(shop, p).show() }, AdminHubMenu::class.java)
            check("상점 편집", { ShopEditMenu(shop, p, SHOP_ID).show() }, ShopEditMenu::class.java)
            check("상품 편집", { ProductEditMenu(shop, p, SHOP_ID, "stone") {}.show() }, ProductEditMenu::class.java)
            check("공통 설정", { SettingsMenu(shop, p).show() }, SettingsMenu::class.java)
            p.closeInventory()
            if (opened.isEmpty()) done(Status.PASS, "") else done(Status.FAIL, opened.joinToString())
        }

        add("상자 상점 — 만들기·창고 넣기·회수·자기 상점은 못 사기·지우기") { done ->
            val block = freeBlock(p) ?: return@add done(Status.SKIP, "근처에 상자를 놓을 빈자리가 없다")
            s.block = block
            s.blockData = block.blockData
            block.type = Material.CHEST
            val value = shop.chests.create(p, block) ?: return@add done(Status.FAIL, "만들기 실패(건축 권한·한도·땅 설정 확인)")
            Inv.give(p, ItemStack(Material.STONE), 5, drop = true)
            val chestProduct = shop.chests.addProduct(p, value, ItemStack(Material.STONE)) ?: return@add done(Status.FAIL, "상품 올리기 실패")
            val live = shop.chests.get(value.id)!!
            shop.chests.updateProduct(live, chestProduct.copy(buyPrice = 10, sellPrice = 4, currency = c.id))
            val deposited = shop.chests.deposit(p, shop.chests.get(value.id)!!, chestProduct, 3)
            val stockAfterDeposit = shop.chests.stock(shop.chests.get(value.id)!!, chestProduct)
            val withdrew = shop.chests.withdraw(p, shop.chests.get(value.id)!!, chestProduct, 1)
            val stockAfterWithdraw = shop.chests.stock(shop.chests.get(value.id)!!, chestProduct)
            ChestManageMenu(shop, p, value.id).show()
            val manageOpened = p.openInventory.topInventory.holder is ChestManageMenu
            ChestShopMenu(shop, p, value.id).show()
            val viewOpened = p.openInventory.topInventory.holder is ChestShopMenu
            p.closeInventory()
            var ownBuyBlocked = false
            shop.chests.buy(p, shop.chests.get(value.id)!!, shop.chests.get(value.id)!!.product(chestProduct.id)!!, 1) { ok -> ownBuyBlocked = !ok }
            val stoneBefore = count(p, Material.STONE)
            shop.chests.remove(shop.chests.get(value.id)!!, p)
            val returned = count(p, Material.STONE) - stoneBefore
            val problems = buildList {
                if (deposited != 3 || stockAfterDeposit != 3L) add("넣기 $deposited · 창고 $stockAfterDeposit (3이어야)")
                if (withdrew != 1 || stockAfterWithdraw != 2L) add("회수 $withdrew · 창고 $stockAfterWithdraw (2여야)")
                if (!manageOpened) add("관리 화면 안 열림")
                if (!viewOpened) add("손님 화면 안 열림")
                if (!ownBuyBlocked) add("자기 상점에서 샀다")
                if (returned != 2) add("지울 때 돌려받은 돌 $returned (2여야)")
                if (shop.chests.get(value.id) != null) add("지워지지 않았다")
            }
            if (problems.isEmpty()) done(Status.PASS, "") else done(Status.FAIL, problems.joinToString(" · "))
        }
        add("상자 상점 — 남의 상점에서 사고팔기") { done -> done(Status.SKIP, "두 사람이 필요하다 — 게임 안에서 확인") }

        add("경매 — 올리고 내리면 물건이 돌아온다") { done ->
            if (!shop.config.auction.enabled) return@add done(Status.SKIP, "경매장이 꺼져 있다")
            val item = ItemStack(Material.DIAMOND, 2)
            val before = money()
            shop.auction.list(p, item, 1000, c) { ok ->
                if (!ok) return@list done(Status.FAIL, "올리기 실패(수수료·한도·금지 설정 확인)")
                val mine = shop.auction.activeOf(p.uniqueId).firstOrNull { it.price == 1000L } ?: return@list done(Status.FAIL, "올린 것이 목록에 없다")
                val tax = before - money()
                AuctionMenu(shop, p).show()
                val menuOpened = p.openInventory.topInventory.holder is AuctionMenu
                AuctionMineMenu(shop, p, AuctionMineMenu.Tab.SELLING).show()
                val mineOpened = p.openInventory.topInventory.holder is AuctionMineMenu
                p.closeInventory()
                shop.auction.cancel(p, mine.id) { back ->
                    val state = shop.auction.get(mine.id)?.state
                    when {
                        !back -> done(Status.FAIL, "내리기 실패")
                        count(p, Material.DIAMOND) != 2 -> done(Status.FAIL, "다이아가 ${count(p, Material.DIAMOND)}(2여야)")
                        state != AuctionService.RETURNED -> done(Status.FAIL, "상태 $state (RETURNED 여야)")
                        !menuOpened || !mineOpened -> done(Status.FAIL, "경매 화면이 안 열렸다")
                        tax != shop.auction.listingTax(p, 1000) -> done(Status.FAIL, "수수료 $tax (${shop.auction.listingTax(p, 1000)} 이어야)")
                        else -> done(Status.PASS, "수수료 $tax")
                    }
                }
            }
        }
        add("경매 — 고른 물건은 값 입력창을 오가도 고른 채로 가방에 있다") { done ->
            if (!shop.config.auction.enabled) return@add done(Status.SKIP, "경매장이 꺼져 있다")
            val at = p.inventory.firstEmpty().takeIf { it >= 0 } ?: return@add done(Status.SKIP, "가방에 빈칸이 없다")
            p.inventory.setItem(at, ItemStack(Material.EMERALD, 5))
            val menu = AuctionSellMenu(shop, p).also { it.show() }
            // 27칸 화면 아래: 가방 9~35 칸이 27~53, 핫바 0~8 칸이 54~62.
            val raw = menu.size + if (at < 9) 27 + at else at - 9
            Bukkit.getPluginManager().callEvent(InventoryClickEvent(p.openInventory, InventoryType.SlotType.CONTAINER, raw, ClickType.LEFT, InventoryAction.PICKUP_ALL))
            val picked = menu.inventory.getItem(AuctionSellMenu.SLOT_ITEM)?.type == Material.EMERALD
            p.closeInventory() // 입력창이 화면을 닫고
            menu.show() //       입력을 마치면 같은 화면을 다시 연다
            val kept = menu.inventory.getItem(AuctionSellMenu.SLOT_ITEM)?.type == Material.EMERALD
            p.closeInventory()
            val bag = p.inventory.getItem(at)
            val inBag = bag?.type == Material.EMERALD && bag.amount == 5
            val total = count(p, Material.EMERALD)
            p.inventory.setItem(at, null)
            val problems = buildList {
                if (!picked) add("클릭해도 골라지지 않았다")
                if (!kept) add("다시 열었더니 고른 것이 풀렸다")
                if (!inBag) add("가방 칸이 바뀌었다(${bag?.type} x${bag?.amount})")
                if (total != 5) add("에메랄드가 ${total}개(5여야 — 복사·증발)")
            }
            if (problems.isEmpty()) done(Status.PASS, "") else done(Status.FAIL, problems.joinToString(" · "))
        }
        add("경매 — 남의 물건 사기") { done -> done(Status.SKIP, "두 사람이 필요하다 — 게임 안에서 확인") }
    }

    /** 발밑 근처의 빈자리(아래가 단단한 공기). */
    private fun freeBlock(p: Player): Block? {
        val base = p.location.block
        for (dx in -3..3) for (dz in -3..3) {
            if (dx == 0 && dz == 0) continue
            for (dy in -1..1) {
                val b = base.getRelative(dx, dy, dz)
                if (b.type.isAir && b.getRelative(0, -1, 0).type.isSolid && b.getRelative(0, 1, 0).type.isAir) return b
            }
        }
        return null
    }

    private fun next(s: Session) {
        val check = s.queue.removeFirstOrNull() ?: return finish(s)
        if (!s.player.isOnline) return finish(s)
        var answered = false
        val done: (Status, String) -> Unit = { status, detail ->
            if (!answered) {
                answered = true
                s.results += Result(check.name, status, detail)
                s.player.scheduler.runDelayed(shop.plugin, { _ -> next(s) }, { finish(s) }, 2L)
            }
        }
        try {
            check.run(done)
        } catch (t: Throwable) {
            shop.logger.log(java.util.logging.Level.WARNING, "검증 '${check.name}' 중 오류", t)
            done(Status.FAIL, "오류: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    private fun finish(s: Session) {
        if (!running) return
        running = false
        val p = s.player
        // 되돌리기 — 임시 상점·블록·가방·잔고·게임모드·권한.
        shop.shops.get(SHOP_ID)?.let { v ->
            for (product in v.products.values) { shop.prices.forget(product); shop.stocks.forget(product) }
            shop.shops.remove(SHOP_ID)
        }
        s.block?.let { b -> shop.chests.at(b)?.let { shop.chests.remove(it, null) }; s.blockData?.let { b.blockData = it } }
        runCatching { s.attachment?.remove() }
        if (p.isOnline) {
            p.closeInventory()
            p.inventory.contents = s.inventory
            p.gameMode = s.gameMode
            s.currency?.let { c ->
                val delta = c.balance(p) - s.balance
                if (delta > 0) c.withdraw(p, delta, "inmcshop:verify-restore") else if (delta < 0) c.deposit(p, -delta, "inmcshop:verify-restore")
            }
        }
        val file = File(shop.plugin.dataFolder, "verify/report-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt")
        val text = buildString {
            appendLine("# inmcshop 검증 - " + LocalDateTime.now())
            for (status in Status.entries) {
                val rows = s.results.filter { it.status == status }
                appendLine()
                appendLine("## " + mapOf(Status.PASS to "통과", Status.FAIL to "실패", Status.SKIP to "건너뜀").getValue(status) + " (${rows.size})")
                for (r in rows) appendLine("- " + r.name + if (r.detail.isNotEmpty()) " — " + r.detail else "")
            }
        }
        shop.io.asyncRun { file.parentFile.mkdirs(); file.writeText(text, Charsets.UTF_8) }
        if (p.isOnline) shop.messages.send(p, "verify-finished", Ph.of()
            .count(s.results.count { it.status == Status.PASS }).value(s.results.count { it.status == Status.FAIL }.toString())
            .max(s.results.count { it.status == Status.SKIP }.toLong()).reason("plugins/inmcshop/verify/" + file.name))
    }

    companion object {
        const val SHOP_ID = "zzverify"
        const val BLOCKED = "inmcshop.verify.blocked"
        const val FUNDS = 100_000L
    }
}
