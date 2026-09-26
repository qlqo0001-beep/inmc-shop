package com.inmc.shop.gui

import com.inmc.shop.Shop
import com.inmc.shop.ShopPlugin
import com.inmc.shop.auction.AuctionCategory
import com.inmc.shop.config.ModuleCurrency
import com.inmc.shop.config.PriceBound
import com.inmc.shop.price.MarketParams
import com.inmc.shop.trade.ClickAction
import com.inmc.shop.trade.ClickKind
import com.inmc.shop.trade.ClickMap
import com.inmc.shop.trade.ProductState
import com.inmc.shop.util.Inv
import com.inmc.shop.util.Ph
import com.inmc.shop.virtual.DefaultShops
import com.inmc.shop.virtual.Layout
import com.inmc.shop.virtual.LayoutButton
import kr.inmc.core.economy.Currencies
import kr.inmc.core.gui.ConfirmMenu
import kr.inmc.core.gui.DialogForm
import kr.inmc.core.gui.Icon
import kr.inmc.core.util.Text
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent

/** `/상점 관리` — 여기서 전부. */
class AdminHubMenu(shop: Shop, viewer: Player) : Menu(shop, viewer, 45, "<dark_red>상점 관리</dark_red>") {

    override fun draw() {
        clear()
        set(10, Icon.of(Material.CHEST, "<green><b>서버 상점</b></green>", "<gray>${shop.shops.all().size}개 — 만들기·상품·가격·재고·회전</gray>", "", "<yellow>▶ 클릭</yellow>")) { ShopListAdminMenu(shop, viewer).show() }
        set(11, Icon.of(Material.PAINTING, "<aqua>레이아웃</aqua>", "<gray>상점 화면의 모양(줄 수·장식·버튼 자리)</gray>", "", "<yellow>▶ 클릭</yellow>")) { LayoutListMenu(shop, viewer).show() }
        set(12, Icon.of(Material.COMPASS, "<aqua>메인 메뉴 모양</aqua>", "<gray>상점 목록 화면</gray>", "", "<yellow>▶ 클릭</yellow>")) { LayoutEditMenu(shop, viewer, "main-menu").show() }
        set(13, Icon.of(Material.COMPARATOR, "<yellow>공통 설정</yellow>", "<gray>허용 월드·판매 배수·클릭 동작·모듈·화폐·가방</gray>", "", "<yellow>▶ 클릭</yellow>")) { SettingsMenu(shop, viewer).show() }
        set(14, Icon.of(Material.RECOVERY_COMPASS, "<yellow>시장 가격 계수</yellow>", "<gray>주기·민감도·최대 변동폭·한산 보정 …</gray>", "", "<yellow>▶ 클릭</yellow>")) { MarketSettings.ask(this, shop, viewer) }
        set(15, Icon.of(Material.BARREL, "<gold>상자 상점 설정</gold>", "", "<yellow>▶ 클릭</yellow>")) { ChestSettingsMenu(shop, viewer).show() }
        set(16, Icon.of(Material.GOLD_BLOCK, "<gold>경매장 설정</gold>", "", "<yellow>▶ 클릭</yellow>")) { AuctionSettingsMenu(shop, viewer).show() }
        set(19, Icon.of(Material.BOOKSHELF, "<gold>경매 분류</gold>", "<gray>${shop.categories.all.size}개</gray>", "", "<yellow>▶ 클릭</yellow>")) { CategoryMenu(shop, viewer).show() }
        set(20, Icon.of(Material.PLAYER_HEAD, "<red>경매 경고 보기</red>", "<gray>플레이어 이름으로</gray>")) {
            if (!viewer.hasPermission("inmcshop.auction.warnings")) return@set shop.messages.send(viewer, "no-permission")
            ask(DialogForm("<red>경고 보기</red>").text("name", "플레이어 이름", "")) { v ->
                val id = shop.findPlayer(v.text("name").trim()) ?: return@ask shop.messages.send(viewer, "player-not-found", Ph.of().player(v.text("name")))
                WarningsMenu(shop, viewer, id, shop.nameOf(id)) { show() }.show()
            }
        }
        set(22, Icon.of(Material.BARREL, "<white>상자 상점 전체 목록</white>")) { ChestListMenu(shop, viewer, "<dark_gray>상자 상점 전체</dark_gray>", { shop.chests.all().map { it to null } }, back = { show() }).show() }
        set(24, Icon.of(Material.GRASS_BLOCK, "<white>기본 상점 다시 만들기</white>", "<gray>없는 기본 상점(블록·광물·작물·전리품·특수·캐시)만 만듭니다.</gray>", "<gray>있는 것은 건드리지 않습니다.</gray>")) {
            val made = DefaultShops.create(shop, onlyMissing = true)
            shop.messages.send(viewer, "defaults-created", Ph.of().count(made))
        }
        set(30, Icon.of(Material.OBSERVER, "<light_purple>검증</light_purple>", "<gray>서버 안에서 실제로 사고팔아 봅니다.</gray>")) {
            viewer.closeInventory(); Bukkit.dispatchCommand(viewer, "상점 검증")
        }
        set(32, Icon.of(Material.CLOCK, "<white>리로드</white>", "<gray>파일을 손으로 고쳤을 때</gray>")) {
            viewer.closeInventory()
            (shop.plugin as ShopPlugin).reload { shop.messages.send(viewer, "reloaded") }
        }
        fillEmpty(Icon.FILLER)
        set(44, Icon.close()) { viewer.closeInventory() }
    }
}

/** 공통 설정. */
class SettingsMenu(shop: Shop, viewer: Player) : Menu(shop, viewer, 54, "<dark_red>공통 설정</dark_red>") {

    override val back: (() -> Unit) = { AdminHubMenu(shop, viewer).show() }

    override fun draw() {
        clear()
        val c = shop.config
        set(10, Icon.of(Material.GRASS_BLOCK, "<yellow>허용 월드</yellow>", c.worlds.map { "<gray> · $it</gray>" }.ifEmpty { listOf("<red>없음 — 아무 데서도 안 됩니다</red>") } + listOf("", "<yellow>▶ 클릭해서 고르기</yellow>"))) {
            val worlds = (Bukkit.getWorlds().map { it.name } + c.worlds).distinct()
            PickMenu(shop, viewer, "<dark_red>허용 월드</dark_red>", worlds, { w -> Icon.of(Material.GRASS_BLOCK, w, if (Bukkit.getWorld(w) == null) "<red>지금 없는 월드</red>" else "<gray>불러온 월드</gray>") }, multi = true,
                selected = { shop.config.worlds.toSet() }, back = { show() }) { w ->
                shop.updateConfig { cfg -> cfg.copy(worlds = if (w in cfg.worlds) cfg.worlds - w else cfg.worlds + w) }
            }.show()
        }
        set(11, toggleIcon("서버 상점", c.virtual.enabled)) { shop.updateConfig { it.copy(virtual = it.virtual.copy(enabled = !it.virtual.enabled)) }; refresh() }
        set(12, toggleIcon("상자 상점", c.chest.enabled)) { shop.updateConfig { it.copy(chest = it.chest.copy(enabled = !it.chest.enabled)) }; refresh() }
        set(13, toggleIcon("경매장", c.auction.enabled)) { shop.updateConfig { it.copy(auction = it.auction.copy(enabled = !it.auction.enabled)) }; refresh() }
        set(14, toggleIcon("가방이 차도 구매(떨어뜨림)", c.buyWithFullInventory)) { shop.updateConfig { it.copy(buyWithFullInventory = !it.buyWithFullInventory) }; refresh() }
        set(15, toggleIcon("구매 뒤 창 닫기", c.closeAfterPurchase)) { shop.updateConfig { it.copy(closeAfterPurchase = !it.closeAfterPurchase) }; refresh() }
        set(16, toggleIcon("화폐마다 권한 필요", c.currencyNeedsPermission, "<gray>inmcshop.currency.<화폐></gray>")) { shop.updateConfig { it.copy(currencyNeedsPermission = !it.currencyNeedsPermission) }; refresh() }
        set(19, Icon.of(Material.EXPERIENCE_BOTTLE, "<yellow>판매 배수</yellow>", rankLore(c.virtual.sellMultiplier, false) + listOf("", "<yellow>▶ 클릭</yellow>"))) {
            askRankValues("<yellow>판매 배수</yellow>", c.virtual.sellMultiplier, false) { v -> shop.updateConfig { it.copy(virtual = it.virtual.copy(sellMultiplier = v)) } }
        }
        set(20, Icon.of(Material.TRIPWIRE_HOOK, "<yellow>클릭 동작 — 서버 상점</yellow>", "", "<yellow>▶ 클릭</yellow>")) { ClickMapMenu(shop, viewer, chest = false).show() }
        set(21, Icon.of(Material.TRIPWIRE_HOOK, "<yellow>클릭 동작 — 상자 상점</yellow>", "", "<yellow>▶ 클릭</yellow>")) { ClickMapMenu(shop, viewer, chest = true).show() }
        set(22, toggleIcon("메인 메뉴", c.virtual.mainMenu)) { shop.updateConfig { it.copy(virtual = it.virtual.copy(mainMenu = !it.virtual.mainMenu)) }; refresh() }
        set(23, toggleIcon("권한 없는 상점 숨기기", c.virtual.hideNoPermission)) { shop.updateConfig { it.copy(virtual = it.virtual.copy(hideNoPermission = !it.virtual.hideNoPermission)) }; refresh() }
        set(24, toggleIcon("상점 단축 명령어", c.virtual.shortcuts, "<gray>재시작 뒤 반영</gray>")) { shop.updateConfig { it.copy(virtual = it.virtual.copy(shortcuts = !it.virtual.shortcuts)) }; refresh() }
        set(25, toggleIcon("셜커 상자 속도 팔기", c.virtual.sellContainers)) { shop.updateConfig { it.copy(virtual = it.virtual.copy(sellContainers = !it.virtual.sellContainers)) }; refresh() }
        set(28, toggleIcon("판매 창(/판매)", c.virtual.sellGui)) { shop.updateConfig { it.copy(virtual = it.virtual.copy(sellGui = !it.virtual.sellGui)) }; refresh() }
        set(29, toggleIcon("전부 판매(/전부판매)", c.virtual.sellAll)) { shop.updateConfig { it.copy(virtual = it.virtual.copy(sellAll = !it.virtual.sellAll)) }; refresh() }
        set(30, toggleIcon("손 판매(/손판매)", c.virtual.sellHand)) { shop.updateConfig { it.copy(virtual = it.virtual.copy(sellHand = !it.virtual.sellHand)) }; refresh() }
        set(31, toggleIcon("같은 것 판매(/같은것판매)", c.virtual.sellHandAll)) { shop.updateConfig { it.copy(virtual = it.virtual.copy(sellHandAll = !it.virtual.sellHandAll)) }; refresh() }
        set(32, toggleIcon("거래 기록 — 파일", c.logToFile)) { shop.updateConfig { it.copy(logToFile = !it.logToFile) }; refresh() }
        set(33, toggleIcon("거래 기록 — 콘솔", c.logToConsole)) { shop.updateConfig { it.copy(logToConsole = !it.logToConsole) }; refresh() }
        set(37, Icon.of(Material.GOLD_NUGGET, "<yellow>화폐 — 서버 상점</yellow>", currencyLore(c.virtual.currency))) {
            askCurrency(c.virtual.currency) { m -> shop.updateConfig { it.copy(virtual = it.virtual.copy(currency = m)) } }
        }
        set(38, Icon.of(Material.GOLD_NUGGET, "<yellow>화폐 — 상자 상점</yellow>", currencyLore(c.chest.currency))) {
            askCurrency(c.chest.currency) { m -> shop.updateConfig { it.copy(chest = it.chest.copy(currency = m)) } }
        }
        set(39, Icon.of(Material.GOLD_NUGGET, "<yellow>화폐 — 경매장</yellow>", currencyLore(c.auction.currency))) {
            askCurrency(c.auction.currency) { m -> shop.updateConfig { it.copy(auction = it.auction.copy(currency = m)) } }
        }
        set(41, Icon.of(Material.ENDER_EYE, "<aqua>여러 서버(공용 DB)</aqua>", listOf(
            "<gray>이 서버 이름: <white>${c.serverName}</white></gray>",
            "<gray>공용 DB: <white>${if (c.networkUrl.isBlank()) "없음(이 서버만)" else "연결"}</white></gray>",
            "<gray>읽기 주기: <white>${c.syncSeconds}초</white></gray>",
            "<red>바꾸면 재시작해야 합니다.</red>", "", "<yellow>▶ 클릭</yellow>",
        ))) {
            ask(DialogForm("<aqua>여러 서버</aqua>").line("<gray>비우면 이 서버의 SQLite 만. 예: jdbc:mysql://127.0.0.1:3306/inmc</gray>")
                .text("server", "이 서버 이름", c.serverName).text("url", "공용 DB 주소", c.networkUrl).text("user", "사용자", c.networkUser).text("pw", "비밀번호", c.networkPassword)
                .long("sync", "다른 서버 변경을 읽는 주기(초)", c.syncSeconds.toLong(), 2, 600)) { v ->
                shop.updateConfig { it.copy(serverName = v.text("server").ifBlank { "main" }, networkUrl = v.text("url").trim(), networkUser = v.text("user"), networkPassword = v.text("pw"), syncSeconds = (v.long("sync") ?: 10).toInt()) }
                shop.messages.send(viewer, "restart-needed")
            }
        }
        fillEmpty(Icon.FILLER)
        navigation()
    }

    private fun currencyLore(m: ModuleCurrency): List<String> = listOf(
        "<gray>기본: <white>${m.default.ifEmpty { "화폐 플러그인의 기본" }}</white></gray>",
        "<gray>허용: <white>${m.allowed.joinToString()}</white></gray>", "", "<yellow>▶ 클릭</yellow>",
    )

    private fun askCurrency(current: ModuleCurrency, apply: (ModuleCurrency) -> Unit) {
        val options = listOf("" to "화폐 플러그인의 기본") + Currencies.all().map { it.id to Text.plain(it.name) }
        ask(DialogForm("<yellow>모듈 화폐</yellow>").choice("default", "기본 화폐", options, current.default)
            .text("allowed", "허용 화폐(쉼표, * = 전부)", current.allowed.joinToString(", "))) { v ->
            apply(ModuleCurrency(v.choice("default").orEmpty(), v.text("allowed").split(',').map(String::trim).filter { it.isNotEmpty() }.ifEmpty { listOf("*") }))
        }
    }
}

/** 클릭 동작 — 상품 상태 × 클릭 → 동작. 누를 때마다 다음 동작. */
class ClickMapMenu(shop: Shop, viewer: Player, private val chest: Boolean) : Menu(shop, viewer, 54, "<dark_red>클릭 동작 — ${if (chest) "상자 상점" else "서버 상점"}</dark_red>") {

    override val back: (() -> Unit) = { SettingsMenu(shop, viewer).show() }

    override fun draw() {
        clear()
        val map = if (chest) shop.config.chest.clicks else shop.config.virtual.clicks
        for ((row, state) in ProductState.entries.withIndex()) {
            set(row * 18, Icon.of(Material.NAME_TAG, "<gold>${state.label}</gold>"))
            for ((col, kind) in ClickKind.entries.withIndex()) {
                val action = map.actionFor(state, kind)
                val slot = row * 18 + 1 + col
                set(slot, Icon.of(if (action == ClickAction.NONE) Material.GRAY_DYE else Material.LIME_DYE, "<white>${kind.label}</white>", "<gray>→ <white>${action.label}</white></gray>", "", "<yellow>클릭해서 바꾸기</yellow>")) {
                    val next = ClickAction.entries[(action.ordinal + 1) % ClickAction.entries.size]
                    update(map.with(state, kind, next))
                    refresh()
                }
            }
        }
        set(49, Icon.of(Material.BARRIER, "<red>기본값으로</red>")) { update(if (chest) ClickMap.CHEST else ClickMap.VIRTUAL); refresh() }
        fillEmpty(Icon.FILLER)
        navigation()
    }

    private fun update(map: ClickMap) = shop.updateConfig { if (chest) it.copy(chest = it.chest.copy(clicks = map)) else it.copy(virtual = it.virtual.copy(clicks = map)) }
}

/** 시장 가격 전역 계수 — 한 입력창. */
object MarketSettings {
    fun ask(menu: Menu, shop: Shop, viewer: Player) {
        val m = shop.config.virtual.market
        DialogForm("<yellow>시장 가격 계수</yellow>")
            .line("<gray>압력 p = (구매−판매)/(거래량+기준 거래량) × min(1, 참여자/기준 참여자)</gray>")
            .line("<gray>Δ = 합친 압력 × 민감도 + 한산 보정, ±최대 변동폭. m ← m(1+Δ) + (1−m)×복원력</gray>")
            .long("period", "주기(분)", m.periodMinutes.toLong(), 1, 10080).long("grace", "주기 끝 대기(초)", m.graceSeconds.toLong(), 0, 600)
            .decimal("k", "기준 거래량 k", m.referenceVolume, 1.0).long("cap", "한 사람 한 주기 상한(개)", m.userCap, 1).long("users", "기준 참여자", m.referenceUsers.toLong(), 1)
            .decimal("sa", "단기 EMA 계수", m.shortAlpha, 0.0, 1.0).decimal("la", "장기 EMA 계수", m.longAlpha, 0.0, 1.0).decimal("sw", "단기 몫", m.shortWeight, 0.0, 1.0)
            .decimal("sens", "민감도", m.sensitivity, 0.0, 1.0).decimal("step", "최대 변동폭(비율)", m.maxStep, 0.0, 1.0).decimal("rev", "복원력", m.reversion, 0.0, 1.0)
            .decimal("aa", "활동량 EMA 계수", m.activityAlpha, 0.0, 1.0).decimal("low", "한산 기준(활동량)", m.lowActivity, 0.0).decimal("high", "활발 기준", m.highActivity, 0.0)
            .decimal("ds", "한산 보정 증가(주기당)", m.dormancyStep, 0.0, 1.0).decimal("dm", "한산 보정 상한", m.dormancyMax, 0.0, 1.0).decimal("dd", "활동 시 한산 보정 감쇠(곱)", m.dormancyDecay, 0.0, 1.0)
            .decimal("bn", "기본 흔들림", m.baseNoise, 0.0, 1.0).decimal("dn", "한산 흔들림", m.dormancyNoise, 0.0, 1.0).decimal("pn", "압력 흔들림", m.pressureNoise, 0.0, 1.0)
            .decimal("minm", "배수 바닥", m.minMultiplier, 0.001, 1.0).decimal("maxm", "배수 천장", m.maxMultiplier, 1.0, 1000.0)
            .show(shop.plugin, viewer, onCancel = { menu.show() }) { _, v ->
                val next = MarketParams(
                    (v.long("period") ?: 30).toInt(), (v.long("grace") ?: 30).toInt(), v.decimal("k") ?: m.referenceVolume, v.long("cap") ?: m.userCap,
                    (v.long("users") ?: 5).toInt(), v.decimal("sa") ?: m.shortAlpha, v.decimal("la") ?: m.longAlpha, v.decimal("sw") ?: m.shortWeight,
                    v.decimal("sens") ?: m.sensitivity, v.decimal("step") ?: m.maxStep, v.decimal("rev") ?: m.reversion, v.decimal("aa") ?: m.activityAlpha,
                    v.decimal("low") ?: m.lowActivity, v.decimal("high") ?: m.highActivity, v.decimal("ds") ?: m.dormancyStep, v.decimal("dm") ?: m.dormancyMax,
                    v.decimal("dd") ?: m.dormancyDecay, v.decimal("bn") ?: m.baseNoise, v.decimal("dn") ?: m.dormancyNoise, v.decimal("pn") ?: m.pressureNoise,
                    v.decimal("minm") ?: m.minMultiplier, v.decimal("maxm") ?: m.maxMultiplier,
                )
                shop.updateConfig { it.copy(virtual = it.virtual.copy(market = next)) }
                menu.show()
            }
    }
}

/** 상자 상점 설정. */
class ChestSettingsMenu(shop: Shop, viewer: Player) : Menu(shop, viewer, 54, "<dark_red>상자 상점 설정</dark_red>") {

    override val back: (() -> Unit) = { AdminHubMenu(shop, viewer).show() }

    private fun mutate(change: (com.inmc.shop.config.ChestSettings) -> com.inmc.shop.config.ChestSettings) = shop.updateConfig { it.copy(chest = change(it.chest)) }

    override fun draw() {
        clear()
        val c = shop.config.chest
        set(10, Icon.of(Material.CHEST, "<yellow>상점이 될 수 있는 블록</yellow>", c.blocks.map { "<gray> · $it</gray>" } + listOf("", "<yellow>▶ 클릭</yellow>"))) {
            PickMenu(shop, viewer, "<dark_red>블록 종류</dark_red>", CONTAINERS, { m -> Icon.of(m, m.name.lowercase().replace('_', ' ')) }, multi = true,
                selected = { CONTAINERS.filter { it.name in shop.config.chest.blocks }.toSet() }, back = { show() }) { m ->
                mutate { it.copy(blocks = if (m.name in it.blocks) it.blocks - m.name else it.blocks + m.name) }
            }.show()
        }
        set(11, Icon.of(Material.GOLD_INGOT, "<yellow>만들기·지우기 비용</yellow>", "<gray>만들기 ${c.createCost} · 지우기 ${c.removeCost} (기본 화폐)</gray>", "", "<yellow>▶ 클릭</yellow>")) {
            ask(DialogForm("<yellow>비용</yellow>").long("c", "만들기", c.createCost, 0).long("r", "지우기", c.removeCost, 0)) { v -> mutate { it.copy(createCost = v.long("c") ?: 0, removeCost = v.long("r") ?: 0) } }
        }
        set(12, Icon.of(Material.BARREL, "<yellow>최대 상점 수</yellow>", rankLore(c.maxShops, true))) { askRankValues("<yellow>최대 상점 수</yellow>", c.maxShops, true) { v -> mutate { it.copy(maxShops = v) } } }
        set(13, Icon.of(Material.ITEM_FRAME, "<yellow>상점당 최대 상품 수</yellow>", rankLore(c.maxProducts, true))) { askRankValues("<yellow>최대 상품 수</yellow>", c.maxProducts, true) { v -> mutate { it.copy(maxProducts = v) } } }
        set(14, Icon.of(Material.CHEST_MINECART, "<yellow>상품당 창고 용량(개)</yellow>", rankLore(c.capacity, true))) { askRankValues("<yellow>창고 용량</yellow>", c.capacity, true) { v -> mutate { it.copy(capacity = v) } } }
        set(15, valueIcon(Material.GOLD_BLOCK, "최고가", "%,d".format(c.maxPrice))) { ask(DialogForm("<yellow>최고가</yellow>").long("m", "한 단위 최고가", c.maxPrice, 1)) { v -> mutate { it.copy(maxPrice = v.long("m") ?: c.maxPrice) } } }
        set(16, Icon.of(Material.BARRIER, "<red>금지 물건</red>", listOf("<gray>재질: ${c.bannedMaterials.joinToString()}</gray>", "<gray>이름: ${c.bannedNames.joinToString()}</gray>", "<gray>설명: ${c.bannedLores.joinToString()}</gray>", "", "<yellow>▶ 클릭</yellow>"))) {
            ask(DialogForm("<red>금지 물건</red>").text("m", "재질(쉼표)", c.bannedMaterials.joinToString(", ")).text("n", "이름 낱말(쉼표)", c.bannedNames.joinToString(", ")).text("l", "설명 낱말(쉼표)", c.bannedLores.joinToString(", "))) { v ->
                fun split(s: String) = s.split(',').map(String::trim).filter { it.isNotEmpty() }
                mutate { it.copy(bannedMaterials = split(v.text("m")).map(String::uppercase), bannedNames = split(v.text("n")), bannedLores = split(v.text("l"))) }
            }
        }
        set(19, toggleIcon("건축 권한 확인", c.checkBuild, "<gray>그 자리에 블록을 놓을 수 있는 사람만(보호 플러그인 무관)</gray>")) { mutate { it.copy(checkBuild = !it.checkBuild) }; refresh() }
        set(20, toggleIcon("자기 Lands 땅에서만", c.landsOnly)) { mutate { it.copy(landsOnly = !it.landsOnly) }; refresh() }
        set(21, toggleIcon("은행", c.bank, "<gray>판매 수익이 상점 은행으로</gray>")) { mutate { it.copy(bank = !it.bank) }; refresh() }
        set(22, toggleIcon("은행 필수", c.bankMandatory, "<gray>끄면 은행이 모자랄 때 주인 지갑에서 냅니다</gray>")) { mutate { it.copy(bankMandatory = !it.bankMandatory) }; refresh() }
        set(23, toggleIcon("임대", c.rent)) { mutate { it.copy(rent = !it.rent) }; refresh() }
        set(24, Icon.of(Material.CLOCK, "<yellow>임대 한도</yellow>", listOf("<gray>최대 ${c.rentMaxDays}일</gray>") + c.rentMaxPrice.map { (k, v) -> "<gray> · $k 최대 ${"%,d".format(v)}</gray>" } + listOf("", "<yellow>▶ 클릭</yellow>"))) {
            ask(DialogForm("<yellow>임대 한도</yellow>").long("d", "최대 기간(일)", c.rentMaxDays.toLong(), 1, 3650).text("p", "화폐=최대 임대료, …", c.rentMaxPrice.entries.joinToString(", ") { "${it.key}=${it.value}" })) { v ->
                val prices = v.text("p").split(',').mapNotNull { part -> part.substringAfter('=', "").trim().toLongOrNull()?.let { part.substringBefore('=').trim() to it } }.filter { it.first.isNotEmpty() }.toMap()
                mutate { it.copy(rentMaxDays = (v.long("d") ?: 30).toInt(), rentMaxPrice = prices) }
            }
        }
        set(25, toggleIcon("홀로그램", c.hologram)) { mutate { it.copy(hologram = !it.hologram) }; shop.displays.shutdown(); shop.displays.spawnAll(); refresh() }
        set(28, Icon.of(Material.SPYGLASS, "<yellow>표시</yellow>", "<gray>보이는 거리 ${c.viewDistance} · 상품 바뀜 ${c.itemChangeSeconds}초</gray>", "", "<yellow>▶ 클릭</yellow>")) {
            ask(DialogForm("<yellow>표시</yellow>").long("d", "보이는 거리(칸)", c.viewDistance.toLong(), 2, 64).long("s", "상품이 바뀌는 간격(초)", c.itemChangeSeconds.toLong(), 1, 120)) { v ->
                mutate { it.copy(viewDistance = (v.long("d") ?: 16).toInt(), itemChangeSeconds = (v.long("s") ?: 5).toInt()) }
                shop.displays.shutdown(); shop.displays.spawnAll()
            }
        }
        set(29, Icon.of(Material.NAME_TAG, "<yellow>이름</yellow>", "<gray>기본 이름: ${c.defaultName} · 최대 ${c.maxNameLength}자</gray>", "<gray>관리자 상점 이름: </gray>${c.adminName}", "", "<yellow>▶ 클릭</yellow>")) {
            ask(DialogForm("<yellow>이름</yellow>").text("d", "새 상점 이름", c.defaultName).long("l", "최대 글자", c.maxNameLength.toLong(), 1, 64).text("a", "관리자 상점 이름(MiniMessage)", c.adminName)) { v ->
                mutate { it.copy(defaultName = v.text("d").ifBlank { "상점" }, maxNameLength = (v.long("l") ?: 16).toInt(), adminName = v.text("a").ifBlank { it.adminName }) }
            }
        }
        set(30, toggleIcon("안전한 곳으로만 이동", c.safeTeleport)) { mutate { it.copy(safeTeleport = !it.safeTeleport) }; refresh() }
        set(31, toggleIcon("주인에게 거래 알림", c.notifyOwner)) { mutate { it.copy(notifyOwner = !it.notifyOwner) }; refresh() }
        set(32, toggleIcon("상점 블록 아이템", c.creationItems, "<gray>/상자상점 지급 으로 준 블록을 놓으면 상점</gray>")) { mutate { it.copy(creationItems = !it.creationItems) }; refresh() }
        fillEmpty(Icon.FILLER)
        navigation()
    }

    companion object {
        val CONTAINERS: List<Material> = listOf(
            Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL, Material.SHULKER_BOX, Material.BLAST_FURNACE, Material.BREWING_STAND,
            Material.CHISELED_BOOKSHELF, Material.CRAFTER, Material.DECORATED_POT, Material.DISPENSER, Material.DROPPER, Material.FURNACE,
            Material.HOPPER, Material.JUKEBOX, Material.LECTERN, Material.SMOKER,
        )
    }
}

/** 경매장 설정. */
class AuctionSettingsMenu(shop: Shop, viewer: Player) : Menu(shop, viewer, 54, "<dark_red>경매장 설정</dark_red>") {

    override val back: (() -> Unit) = { AdminHubMenu(shop, viewer).show() }

    private fun mutate(change: (com.inmc.shop.config.AuctionSettings) -> com.inmc.shop.config.AuctionSettings) = shop.updateConfig { it.copy(auction = change(it.auction)) }

    override fun draw() {
        clear()
        val a = shop.config.auction
        set(10, Icon.of(Material.CLOCK, "<yellow>기간</yellow>", "<gray>등록 기간 ${a.expireHours}시간 · 끝난 기록 ${a.purgeDays}일 뒤 정리</gray>", "<gray>받지 않은 물건·돈은 정리하지 않습니다.</gray>", "", "<yellow>▶ 클릭</yellow>")) {
            ask(DialogForm("<yellow>기간</yellow>").long("h", "등록 기간(시간)", a.expireHours.toLong(), 1, 24L * 365).long("p", "끝난 기록 정리(일)", a.purgeDays.toLong(), 1, 3650)) { v ->
                mutate { it.copy(expireHours = (v.long("h") ?: 168).toInt(), purgeDays = (v.long("p") ?: 7).toInt()) }
            }
        }
        set(11, Icon.of(Material.GOLD_NUGGET, "<yellow>수수료</yellow>", "<gray>등록 ${a.listingTaxPercent}% · 판매 ${a.purchaseTaxPercent}%</gray>", "", "<yellow>▶ 클릭</yellow>")) {
            ask(DialogForm("<yellow>수수료</yellow>").decimal("l", "등록 수수료 %(올릴 때 판매자가 냄)", a.listingTaxPercent, 0.0, 100.0).decimal("p", "판매 수수료 %(판매 대금에서 뗌)", a.purchaseTaxPercent, 0.0, 100.0)) { v ->
                mutate { it.copy(listingTaxPercent = v.decimal("l") ?: 0.0, purchaseTaxPercent = v.decimal("p") ?: 0.0) }
            }
        }
        set(12, Icon.of(Material.CHEST, "<yellow>최대 등록 수</yellow>", rankLore(a.maxListings, true))) { askRankValues("<yellow>최대 등록 수</yellow>", a.maxListings, true) { v -> mutate { it.copy(maxListings = v) } } }
        set(13, Icon.of(Material.COMPARATOR, "<yellow>가격 한도</yellow>", listOf("<gray>화폐별(묶음 값):</gray>") + a.currencyBounds.map { (k, v) -> "<gray> · $k ${v.min}~${v.max}</gray>" } +
            listOf("<gray>재질별(개당):</gray>") + a.materialBounds.map { (k, v) -> "<gray> · $k ${v.min}~${v.max}</gray>" } + listOf("", "<yellow>▶ 클릭</yellow>"))) {
            fun text(map: Map<String, PriceBound>) = map.entries.joinToString(", ") { "${it.key}=${it.value.min}~${it.value.max}" }
            fun parse(s: String) = s.split(',').mapNotNull { part ->
                val key = part.substringBefore('=').trim()
                val range = part.substringAfter('=', "")
                val lo = range.substringBefore('~').trim().toLongOrNull() ?: return@mapNotNull null
                val hi = range.substringAfter('~', "-1").trim().toLongOrNull() ?: -1
                if (key.isEmpty()) null else key to PriceBound(lo, hi)
            }.toMap()
            ask(DialogForm("<yellow>가격 한도</yellow>").line("<gray>-1 = 없음. 예: money=1~10000000</gray>")
                .text("c", "화폐=최저~최고, …", text(a.currencyBounds), maxLength = 2000).text("m", "재질=개당 최저~최고, …", text(a.materialBounds), maxLength = 2000)) { v ->
                mutate { it.copy(currencyBounds = parse(v.text("c")).mapKeys { e -> e.key.lowercase() }, materialBounds = parse(v.text("m")).mapKeys { e -> e.key.uppercase() }) }
            }
        }
        set(14, Icon.of(Material.BARRIER, "<red>금지 물건</red>", listOf("<gray>재질: ${a.bannedMaterials.joinToString()}</gray>", "<gray>모델: ${a.bannedModels.entries.joinToString { "${it.key}=${it.value.joinToString("/")}" }}</gray>", "", "<yellow>▶ 클릭</yellow>"))) {
            ask(DialogForm("<red>금지 물건</red>").text("m", "재질(쉼표)", a.bannedMaterials.joinToString(", ")).text("n", "이름 낱말(쉼표)", a.bannedNames.joinToString(", "))
                .text("l", "설명 낱말(쉼표)", a.bannedLores.joinToString(", ")).text("md", "재질=모델번호/모델번호, …", a.bannedModels.entries.joinToString(", ") { "${it.key}=${it.value.joinToString("/")}" })) { v ->
                fun split(s: String) = s.split(',').map(String::trim).filter { it.isNotEmpty() }
                val models = split(v.text("md")).mapNotNull { part -> part.substringBefore('=').trim().uppercase().takeIf { it.isNotEmpty() }?.let { it to part.substringAfter('=', "").split('/').mapNotNull { n -> n.trim().toIntOrNull() } } }.toMap()
                mutate { it.copy(bannedMaterials = split(v.text("m")).map(String::uppercase), bannedNames = split(v.text("n")), bannedLores = split(v.text("l")), bannedModels = models) }
            }
        }
        set(15, toggleIcon("등록 공지", a.announce)) { mutate { it.copy(announce = !it.announce) }; refresh() }
        set(16, toggleIcon("팔린 돈 자동 받기", a.autoClaim)) { mutate { it.copy(autoClaim = !it.autoClaim) }; refresh() }
        set(19, toggleIcon("접속 알림", a.notifyOnJoin)) { mutate { it.copy(notifyOnJoin = !it.notifyOnJoin) }; refresh() }
        set(20, Icon.of(Material.REDSTONE_TORCH, "<red>경고와 벌칙</red>", "<gray>${a.warningExpireDays}일 안 경고 ${a.warningsToBan}회 → ${a.banDays}일 등록 금지</gray>", "", "<yellow>▶ 클릭</yellow>")) {
            ask(DialogForm("<red>경고와 벌칙</red>").long("n", "몇 번이면 금지", a.warningsToBan.toLong(), 1, 100).long("b", "금지 기간(일)", a.banDays.toLong(), 1, 3650).long("e", "경고가 사라지는 기간(일)", a.warningExpireDays.toLong(), 1, 3650)) { v ->
                mutate { it.copy(warningsToBan = (v.long("n") ?: 3).toInt(), banDays = (v.long("b") ?: 7).toInt(), warningExpireDays = (v.long("e") ?: 30).toInt()) }
            }
        }
        fillEmpty(Icon.FILLER)
        navigation()
    }
}

/** 경매 분류 편집. */
class CategoryMenu(shop: Shop, viewer: Player) : Menu(shop, viewer, 54, "<dark_red>경매 분류</dark_red>") {

    override val back: (() -> Unit) = { AdminHubMenu(shop, viewer).show() }

    override fun draw() {
        clear()
        for ((i, c) in shop.categories.all.take(45).withIndex()) {
            set(i, Icon.of(c.icon, c.name, listOf("<gray>id: ${c.id} · 순서 ${c.order}</gray>", "<gray>${c.match.joinToString()}</gray>", "", "<yellow>좌클릭</yellow> <gray>고치기</gray> · <yellow>Shift+우클릭</yellow> <gray>지우기</gray>"))) { event ->
                if (event.isShiftClick && event.isRightClick) { shop.categories.remove(c.id); return@set refresh() }
                edit(c)
            }
        }
        set(49, Icon.of(Material.LIME_DYE, "<green>새 분류</green>", "<gray>손에 든 것이 아이콘</gray>")) {
            ask(DialogForm("<green>새 분류</green>").text("id", "id(영문)", "")) { v ->
                val id = v.text("id").trim().lowercase()
                if (id.isEmpty() || shop.categories.all.any { it.id == id }) return@ask shop.messages.send(viewer, "bad-id", Ph.of().value(id))
                val icon = viewer.inventory.itemInMainHand.type.takeIf { !it.isAir } ?: Material.CHEST
                edit(AuctionCategory(id, id, icon, emptyList(), shop.categories.all.size + 1))
            }
        }
        fillEmpty(Icon.FILLER)
        navigation()
    }

    private fun edit(c: AuctionCategory) {
        ask(DialogForm("<yellow>분류 ${c.id}</yellow>").line("<gray>패턴: *_SWORD 처럼 별표, 또는 #BLOCK · #EDIBLE · #ALL</gray>")
            .text("name", "이름(MiniMessage)", c.name).text("icon", "아이콘 재질", c.icon.name).text("match", "패턴(쉼표)", c.match.joinToString(", "), maxLength = 2000)
            .long("order", "순서", c.order.toLong())) { v ->
            shop.categories.put(c.copy(
                name = v.text("name").ifBlank { c.id }, icon = Material.matchMaterial(v.text("icon")) ?: c.icon,
                match = v.text("match").split(',').map(String::trim).filter { it.isNotEmpty() }, order = (v.long("order") ?: 100).toInt(),
            ))
        }
    }
}

/** 레이아웃 목록. */
class LayoutListMenu(shop: Shop, viewer: Player) : Menu(shop, viewer, 54, "<dark_red>레이아웃</dark_red>") {

    override val back: (() -> Unit) = { AdminHubMenu(shop, viewer).show() }

    override fun draw() {
        clear()
        for ((i, l) in shop.layouts.all().take(45).withIndex()) {
            set(i, Icon.of(Material.PAINTING, "<aqua>${l.id}</aqua>", "<gray>${l.rows}줄 · 상품 칸 ${l.productSlots().size} · 장식 ${l.decorations.size}</gray>", "", "<yellow>▶ 클릭해서 편집</yellow>")) {
                LayoutEditMenu(shop, viewer, l.id).show()
            }
        }
        set(49, Icon.of(Material.LIME_DYE, "<green>새 레이아웃</green>", "<gray>기본 레이아웃을 복사합니다.</gray>")) {
            ask(DialogForm("<green>새 레이아웃</green>").text("id", "id(영문)", "")) { v ->
                val id = v.text("id").trim().lowercase()
                if (!shop.shops.validId(id) || shop.layouts.find(id) != null || id == "main-menu") return@ask shop.messages.send(viewer, "bad-id", Ph.of().value(id))
                shop.layouts.put(shop.layouts.get("default").copy(id = id))
                LayoutEditMenu(shop, viewer, id).show()
            }
        }
        fillEmpty(Icon.FILLER)
        navigation()
    }
}

/** 레이아웃 하나 — 제목·줄 수(입력창), 장식 편집, 버튼 자리. */
class LayoutEditMenu(shop: Shop, viewer: Player, private val id: String) : Menu(shop, viewer, 27, "<dark_red>레이아웃 — $id</dark_red>") {

    override val back: (() -> Unit) = { if (id == "main-menu") AdminHubMenu(shop, viewer).show() else LayoutListMenu(shop, viewer).show() }

    private fun layout(): Layout = if (id == "main-menu") shop.layouts.mainMenu else shop.layouts.find(id) ?: shop.layouts.get(id)

    override fun draw() {
        clear()
        val l = layout()
        set(10, valueIcon(Material.NAME_TAG, "제목·줄 수", "${l.rows}줄", "<gray>제목: ${Text.plain(l.title)}</gray>", "<gray>{shop} {page} {pages}</gray>")) {
            ask(DialogForm("<yellow>레이아웃</yellow>").text("title", "제목(MiniMessage)", l.title).long("rows", "줄 수", l.rows.toLong(), 1, 6)) { v ->
                val rows = (v.long("rows") ?: 6).toInt()
                val size = rows * 9
                shop.layouts.put(l.copy(title = v.text("title").ifBlank { l.title }, rows = rows, decorations = l.decorations.filterKeys { it < size }, buttons = l.buttons.filterValues { it < size }))
            }
        }
        set(12, Icon.of(Material.GLASS_PANE, "<yellow>장식 편집</yellow>", "<gray>화면 크기 그대로 열립니다. 아이템을 놓고 닫으면 저장.</gray>", "<gray>버튼 자리는 막혀 있습니다.</gray>")) { DecorationEditMenu(shop, viewer, id).show() }
        set(14, Icon.of(Material.STONE_BUTTON, "<yellow>버튼 자리</yellow>", l.buttons.map { (b, s) -> "<gray> · ${b.label}: <white>$s</white></gray>" } + listOf("", "<yellow>▶ 클릭</yellow>"))) {
            val buttons = if (id == "main-menu") listOf(LayoutButton.BALANCE, LayoutButton.SELL_ALL, LayoutButton.CLOSE) else LayoutButton.entries
            ask(DialogForm("<yellow>버튼 자리</yellow>").line("<gray>칸 번호(0부터). 비우면 그 버튼이 없습니다.</gray>").apply {
                for (b in buttons) long(b.name, b.label, l.buttons[b]?.toLong(), 0, l.size - 1L, optional = true)
            }) { v ->
                val map = buttons.mapNotNull { b -> v.long(b.name)?.let { b to it.toInt() } }.toMap()
                shop.layouts.put(l.copy(buttons = map, decorations = l.decorations.filterKeys { it !in map.values }))
            }
        }
        if (id != "default" && id != "main-menu") set(16, Icon.of(Material.LAVA_BUCKET, "<red>지우기</red>")) {
            ConfirmMenu(shop, "<red>$id 을(를) 지울까요?</red>", onConfirm = { shop.layouts.remove(id); back() }, onCancel = { show() }).open(viewer)
        }
        fillEmpty(Icon.FILLER)
        navigation(18, 26)
    }
}

/**
 * 장식 편집 — 레이아웃 크기 그대로. **아이템을 옮기지 않는다**(장식은 사본이라 옮기면 복사된다): 아래 가방의 물건을 클릭해
 * 고르고(사본) 칸을 누르면 그 칸의 장식이 된다. 장식을 누르면 지운다. 누를 때마다 저장.
 */
class DecorationEditMenu(shop: Shop, viewer: Player, private val id: String) : Menu(shop, viewer, sizeOf(shop, id), "<dark_red>장식 — 가방에서 고르고 칸을 누르기</dark_red>") {

    private var picked: org.bukkit.inventory.ItemStack? = null

    private fun layout(): Layout = if (id == "main-menu") shop.layouts.mainMenu else shop.layouts.find(id) ?: shop.layouts.get(id)

    override fun handleClick(event: InventoryClickEvent) {
        if (event.rawSlot >= size && event.clickedInventory == viewer.inventory) {
            event.isCancelled = true
            picked = event.currentItem?.takeIf { !it.type.isAir }?.clone()?.apply { amount = 1 }
            return
        }
        super.handleClick(event)
    }

    override fun draw() {
        clear()
        val l = layout()
        for (slot in 0 until size) {
            val button = l.buttonAt(slot)
            if (button != null) { set(slot, Icon.of(Material.STRUCTURE_VOID, "<dark_gray>버튼: ${button.label}</dark_gray>")); continue }
            val deco = l.decorations[slot]?.let { Inv.decode(it) }
            if (deco != null) set(slot, Icon.annotate(deco, lore = listOf("<red>클릭해서 지우기</red>"))) {
                shop.layouts.put(layout().let { it.copy(decorations = it.decorations - slot) }); refresh()
            } else set(slot, null) {
                val stack = picked ?: return@set shop.messages.send(viewer, "admin-pick-first")
                shop.layouts.put(layout().let { it.copy(decorations = it.decorations + (slot to Inv.encode(stack))) }); refresh()
            }
        }
    }

    override fun onClose(event: InventoryCloseEvent) {
        shop.messages.send(viewer, "layout-saved")
    }

    companion object {
        fun sizeOf(shop: Shop, id: String): Int = (if (id == "main-menu") shop.layouts.mainMenu else shop.layouts.find(id) ?: shop.layouts.get(id)).size
    }
}
