package com.inmc.shop.gui

import com.inmc.shop.Shop
import com.inmc.shop.auction.AuctionService
import com.inmc.shop.data.ListingRow
import com.inmc.shop.util.Ph
import kr.inmc.core.economy.Currencies
import kr.inmc.core.gui.DialogForm
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.util.Durations
import org.bukkit.Material
import org.bukkit.block.ShulkerBox
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class AuctionSort(val label: String) {
    NEWEST("최신 순"), OLDEST("오래된 순"), CHEAP("싼 순(개당)"), EXPENSIVE("비싼 순(개당)"), NAME("이름 순"),
}

/** 사람마다 기억하는 보기 — 분류·정렬·화폐·검색·페이지. 구매 뒤 같은 자리로 돌아온다. */
class AuctionView(
    var category: String? = null,
    var sort: AuctionSort = AuctionSort.NEWEST,
    var currency: String? = null,
    var query: String? = null,
    var page: Int = 0,
    var tabPage: Int = 0,
) {
    companion object {
        private val views = ConcurrentHashMap<UUID, AuctionView>()
        fun of(player: UUID): AuctionView = views.getOrPut(player) { AuctionView() }
    }
}

/** 한 칸 — 같은 물건의 등록 묶음(가장 싼 것이 대표). */
class ListingGroup(val key: String, val rows: List<ListingRow>) {
    val cheapest: ListingRow get() = rows.first()
}

object Listings {

    fun unitPrice(row: ListingRow): Double = row.price.toDouble() / row.amount.coerceAtLeast(1)

    /** 등록 아이콘 — 물건 그대로 + 설명. */
    fun icon(shop: Shop, viewer: Player, row: ListingRow, extra: List<String> = emptyList()): ItemStack {
        val stack = shop.auction.item(row) ?: ItemStack(Material.BARRIER)
        val currency = shop.auction.currencyOf(row)
        val now = System.currentTimeMillis()
        val lore = buildList {
            add("<gray>판매자: <white>${row.sellerName}</white>" + if (row.seller == viewer.uniqueId) " <green>(내 물건)</green>" else "")
            add("<gray>가격: <gold>${currency?.format(row.price) ?: row.price}</gold>" + if (row.amount > 1) " <dark_gray>(개당 ${currency?.format(Math.round(unitPrice(row))) ?: ""})</dark_gray></gray>" else "</gray>")
            if (row.state == AuctionService.ACTIVE && row.expires > now) add("<gray>남은 시간: <white>${Durations.formatShort((row.expires - now) / 1000)}</white></gray>")
            addAll(extra)
        }
        return Icon.annotate(stack, lore = lore)
    }

    fun soldMarker(): ItemStack = Icon.of(Material.GRAY_DYE, "<dark_gray>판매 완료</dark_gray>", "<gray>누군가 먼저 샀습니다. 새로고침하면 사라집니다.</gray>")
}

/**
 * 경매장 메인 — **보는 동안 목록이 움직이지 않는다**(열 때 고정, 새로고침만 바꾼다). 같은 물건은 한 칸으로 묶고 가장 싼 값을 보인다.
 * 왼쪽 열 = 분류, 가운데 = 물건, 아래 줄 = 정렬·화폐·검색·올리기·내 것·새로고침·페이지.
 */
class AuctionMenu(shop: Shop, viewer: Player) : Menu(shop, viewer, 54, "<dark_gray>경매장</dark_gray>") {

    private val view = AuctionView.of(viewer.uniqueId)
    private var snapshot: List<ListingGroup> = emptyList()

    init {
        snapshot = build()
    }

    private fun build(): List<ListingGroup> {
        val now = System.currentTimeMillis()
        val category = view.category?.let { id -> shop.categories.all.firstOrNull { it.id == id } }
        val q = view.query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val rows = shop.auction.active(now).filter { row ->
            (view.currency == null || row.currency.equals(view.currency, true)) &&
                (q == null || row.search.contains(q)) &&
                (category == null || shop.auction.item(row)?.let { category.matches(it.type) } == true)
        }
        val groups = rows.groupBy { it.groupKey }.map { (key, list) -> ListingGroup(key, list.sortedBy { Listings.unitPrice(it) }) }
        return when (view.sort) {
            AuctionSort.NEWEST -> groups.sortedByDescending { g -> g.rows.maxOf { it.created } }
            AuctionSort.OLDEST -> groups.sortedBy { g -> g.rows.minOf { it.created } }
            AuctionSort.CHEAP -> groups.sortedBy { Listings.unitPrice(it.cheapest) }
            AuctionSort.EXPENSIVE -> groups.sortedByDescending { Listings.unitPrice(it.cheapest) }
            AuctionSort.NAME -> groups.sortedBy { it.cheapest.search }
        }
    }

    override fun draw() {
        clear()
        if (!shop.config.auction.enabled) return viewer.closeInventory()
        view.page = Paging.clamp(view.page, snapshot.size, PER_PAGE)
        for ((index, group) in Paging.slice(snapshot, view.page, PER_PAGE).withIndex()) {
            val slot = GRID[index]
            val live = group.rows.mapNotNull { shop.auction.get(it.id) }.filter { it.state == AuctionService.ACTIVE && it.expires > System.currentTimeMillis() }
            if (live.isEmpty()) { set(slot, Listings.soldMarker()); continue }
            val cheapest = live.minBy { Listings.unitPrice(it) }
            val extra = buildList {
                if (live.size > 1) add("<gray>같은 물건 <white>${live.size}</white>건 — 가장 싼 것</gray>")
                add("")
                add(if (live.size > 1) "<yellow>▶ 클릭해서 모두 보기</yellow>" else if (cheapest.seller == viewer.uniqueId) "<yellow>▶ 클릭해서 보기</yellow>" else "<yellow>▶ 클릭해서 사기</yellow>")
                if (viewer.hasPermission("inmcshop.auction.listing.remove.others")) add("<red>Shift+우클릭 관리자 내리기</red>")
            }
            set(slot, Listings.icon(shop, viewer, cheapest, extra)) { event -> open(live, event) }
        }
        if (snapshot.isEmpty()) set(GRID[13], Icon.of(Material.BARRIER, "<gray>물건이 없습니다</gray>", "<gray>분류·화폐·검색을 바꿔 보세요.</gray>"))
        tabs()
        bottom()
        fillEmpty(Icon.FILLER)
    }

    private fun open(live: List<ListingRow>, event: InventoryClickEvent) {
        val sorted = live.sortedBy { Listings.unitPrice(it) }
        if (event.isShiftClick && event.isRightClick && viewer.hasPermission("inmcshop.auction.listing.remove.others")) {
            return if (sorted.size == 1) AdminRemoveMenu(shop, viewer, sorted.first().id) { AuctionMenu(shop, viewer).show() }.show()
            else AuctionGroupMenu(shop, viewer, sorted.map { it.id }).show()
        }
        if (sorted.size == 1) AuctionBuyMenu(shop, viewer, sorted.first().id) { AuctionMenu(shop, viewer).also { it.snapshot = snapshot }.show() }.show()
        else AuctionGroupMenu(shop, viewer, sorted.map { it.id }).show()
    }

    private fun tabs() {
        val all = shop.categories.all
        set(SLOT_TAB_ALL, Icon.of(Material.CHEST, (if (view.category == null) "<green>▶ " else "<white>") + "전체</white>")) { view.category = null; view.page = 0; reload() }
        val visible = all.drop(view.tabPage * TAB_SLOTS.size).take(TAB_SLOTS.size)
        for ((i, category) in visible.withIndex()) {
            val selected = view.category == category.id
            set(TAB_SLOTS[i], Icon.of(category.icon, (if (selected) "<green>▶ " else "") + category.name, if (selected) "<green>보는 중</green>" else "<gray>클릭해서 보기</gray>")) {
                view.category = category.id; view.page = 0; reload()
            }
        }
        if (all.size > TAB_SLOTS.size) set(SLOT_TAB_MORE, Icon.of(Material.ARROW, "<yellow>다른 분류 ▼</yellow>", "<gray>${view.tabPage + 1} / ${(all.size + TAB_SLOTS.size - 1) / TAB_SLOTS.size}</gray>")) {
            view.tabPage = (view.tabPage + 1) % ((all.size + TAB_SLOTS.size - 1) / TAB_SLOTS.size); refresh()
        }
    }

    private fun bottom() {
        if (view.page > 0) set(SLOT_PREV, Icon.prevPage()) { view.page--; refresh() }
        if (view.page < Paging.pageCount(snapshot.size, PER_PAGE) - 1) set(SLOT_NEXT, Icon.nextPage()) { view.page++; refresh() }
        set(SLOT_SORT, Icon.of(Material.HOPPER, "<yellow>정렬: <white>${view.sort.label}</white></yellow>", AuctionSort.entries.map { (if (it == view.sort) "<green>▶ " else "<gray>  ") + it.label })) {
            view.sort = AuctionSort.entries[(view.sort.ordinal + 1) % AuctionSort.entries.size]; view.page = 0; reload()
        }
        val currencies = Currencies.all().filter { shop.config.auction.currency.allows(it.id) }
        set(SLOT_CURRENCY, Icon.of(Material.GOLD_NUGGET, "<yellow>화폐: <white>${view.currency?.let { id -> currencies.firstOrNull { it.id == id }?.name } ?: "전부"}</white></yellow>", "<gray>클릭해서 바꾸기</gray>")) {
            val ids = listOf<String?>(null) + currencies.map { it.id }
            view.currency = ids[(ids.indexOf(view.currency) + 1) % ids.size]; view.page = 0; reload()
        }
        set(SLOT_SEARCH, Icon.of(Material.NAME_TAG, "<yellow>검색: <white>${view.query ?: "없음"}</white></yellow>", "<gray>좌클릭 이름으로 찾기 · 우클릭 지우기</gray>")) { event ->
            if (event.isRightClick) { view.query = null; view.page = 0; return@set reload() }
            ask(DialogForm("<yellow>경매 검색</yellow>").text("q", "이름 또는 영어 재질 이름", view.query.orEmpty()), reopen = { reload() }) { v -> view.query = v.text("q").takeIf { it.isNotBlank() }; view.page = 0 }
        }
        set(SLOT_SELL, Icon.of(Material.EMERALD, "<green><b>물건 올리기</b></green>", "<gray>가방의 물건을 올리고 값을 정합니다.</gray>", "", "<green>▶ 클릭</green>")) {
            if (!viewer.hasPermission("inmcshop.auction.command.sell")) return@set shop.messages.send(viewer, "no-permission")
            if (!shop.guard(viewer)) return@set
            AuctionSellMenu(shop, viewer).show()
        }
        set(SLOT_MINE, Icon.of(Material.ENDER_CHEST, "<aqua>내 경매</aqua>", listOf(
            "<gray>판매 중: <white>${shop.auction.activeOf(viewer.uniqueId).size}</white></gray>",
            "<gray>받을 돈: <white>${shop.auction.ofSeller(viewer.uniqueId, AuctionService.SOLD).size}</white>건</gray>",
            "<gray>돌려받을 물건: <white>${shop.auction.expiredOf(viewer.uniqueId).size}</white>건</gray>",
            "", "<aqua>▶ 클릭</aqua>",
        ))) { AuctionMineMenu(shop, viewer, AuctionMineMenu.Tab.SELLING).show() }
        set(SLOT_REFRESH, Icon.of(Material.CLOCK, "<white>새로고침</white>", "<gray>목록을 지금 것으로 바꿉니다.</gray>")) { reload() }
    }

    private fun reload() {
        snapshot = build()
        refresh()
    }

    companion object {
        /** 가운데 8칸 × 5줄. */
        val GRID: List<Int> = (0 until 5).flatMap { row -> (1..8).map { col -> row * 9 + col } }
        val PER_PAGE = GRID.size
        const val SLOT_TAB_ALL = 0
        val TAB_SLOTS = listOf(9, 18, 27)
        const val SLOT_TAB_MORE = 36
        const val SLOT_PREV = 45
        const val SLOT_SORT = 46
        const val SLOT_CURRENCY = 47
        const val SLOT_SEARCH = 48
        const val SLOT_SELL = 49
        const val SLOT_MINE = 50
        const val SLOT_REFRESH = 51
        const val SLOT_NEXT = 53
    }
}

/** 같은 물건의 등록들 — 개당 가격 순. */
class AuctionGroupMenu(shop: Shop, viewer: Player, private val ids: List<String>) : Menu(shop, viewer, 54, "<dark_gray>같은 물건 — 싼 순</dark_gray>") {

    override val back: (() -> Unit) = { AuctionMenu(shop, viewer).show() }
    private var page = 0

    override fun draw() {
        clear()
        page = Paging.clamp(page, ids.size)
        for ((slot, id) in Paging.slice(ids, page).withIndex()) {
            val row = shop.auction.get(id)
            if (row == null || row.state != AuctionService.ACTIVE || row.expires <= System.currentTimeMillis()) { set(slot, Listings.soldMarker()); continue }
            val extra = buildList {
                add(""); add(if (row.seller == viewer.uniqueId) "<yellow>▶ 클릭해서 보기</yellow>" else "<yellow>▶ 클릭해서 사기</yellow>")
                if (viewer.hasPermission("inmcshop.auction.listing.remove.others")) add("<red>Shift+우클릭 관리자 내리기</red>")
            }
            set(slot, Listings.icon(shop, viewer, row, extra)) { event ->
                if (event.isShiftClick && event.isRightClick && viewer.hasPermission("inmcshop.auction.listing.remove.others")) return@set AdminRemoveMenu(shop, viewer, id) { show() }.show()
                AuctionBuyMenu(shop, viewer, id) { show() }.show()
            }
        }
        fillEmpty(Icon.FILLER)
        pager(page, ids.size) { page = it; refresh() }
        navigation()
    }
}

/** 사기 전 확인 — 물건 전체 정보·판매자·가격(개당)·남은 시간. 셜커 상자면 속을 볼 수 있다. */
class AuctionBuyMenu(shop: Shop, viewer: Player, private val id: String, override val back: (() -> Unit)) : Menu(shop, viewer, 27, "<dark_gray>구매 확인</dark_gray>") {

    override fun draw() {
        clear()
        val row = shop.auction.get(id)
        if (row == null || row.state != AuctionService.ACTIVE || row.expires <= System.currentTimeMillis()) {
            shop.messages.send(viewer, "auction-gone")
            return back()
        }
        val currency = shop.auction.currencyOf(row)
        val affordable = currency?.has(viewer, row.price) == true
        set(13, Listings.icon(shop, viewer, row))
        if (row.seller == viewer.uniqueId) set(11, Icon.of(Material.GRAY_CONCRETE, "<gray><b>내 물건입니다</b></gray>", "<gray>내 물건은 살 수 없습니다.</gray>", "", "<yellow>▶ 클릭: 내 경매(내리기)</yellow>")) {
            AuctionMineMenu(shop, viewer, AuctionMineMenu.Tab.SELLING).show()
        }
        else set(11, Icon.of(if (affordable) Material.LIME_CONCRETE else Material.GRAY_CONCRETE, if (affordable) "<green><b>사기</b></green>" else "<red><b>돈이 모자랍니다</b></red>", listOf(
            "<gray>값: <gold>${currency?.format(row.price) ?: row.price}</gold></gray>",
            "<gray>내 잔고: <white>${currency?.format(currency.balance(viewer)) ?: "-"}</white></gray>",
            "<gray>구매 수수료는 없습니다(판매자 몫에서 뗍니다).</gray>",
        ))) {
            if (!affordable) return@set shop.messages.send(viewer, "not-enough-money", Ph.of().price(currency?.format(row.price) ?: row.price.toString()))
            shop.auction.buy(viewer, id) { if (viewer.isOnline) back() }
        }
        set(15, Icon.of(Material.RED_CONCRETE, "<red><b>취소</b></red>")) { back() }
        val stack = shop.auction.item(row)
        if (stack != null && (stack.itemMeta as? BlockStateMeta)?.blockState is ShulkerBox) {
            set(22, Icon.of(Material.SPYGLASS, "<white>속 보기</white>", "<gray>셜커 상자 안의 물건</gray>")) { PreviewMenu(shop, viewer, stack) { show() }.show() }
        }
        fillEmpty(Icon.FILLER)
    }
}

/** 셜커 상자 속 — 읽기만. */
class PreviewMenu(shop: Shop, viewer: Player, private val stack: ItemStack, override val back: (() -> Unit)) : Menu(shop, viewer, 36, "<dark_gray>미리보기</dark_gray>") {
    override fun draw() {
        clear()
        val box = (stack.itemMeta as? BlockStateMeta)?.blockState as? ShulkerBox
        box?.inventory?.contents?.forEachIndexed { i, s -> if (s != null && i < 27) set(i, s.clone()) }
        set(31, Icon.back()) { back() }
    }
}

/**
 * 물건 올리기 — 가방의 물건을 클릭해 고르고, 값·화폐·개수를 입력창으로 정한 뒤 확인. 물건은 올리는 순간까지 가방에 있다.
 */
class AuctionSellMenu(shop: Shop, viewer: Player) : Menu(shop, viewer, 27, "<dark_gray>경매에 올리기</dark_gray>") {

    // 고른 물건은 가방에 그대로 둔다. 값 입력창이 이 화면을 닫았다가 다시 열고, 그 사이 접속이 끊길 수도 있다 —
    // 화면이 물건을 들고 있으면 닫힐 때 돌려주는 것과 입력을 이어가는 것이 부딪힌다. 올리는 순간 그 칸을 다시 보고 뺀다.
    private var slot = -1
    private var stack: ItemStack? = null
    private var count = 0
    private var price: Long? = null
    private var currencyId: String? = shop.config.auction.currency.default.ifEmpty { Currencies.default()?.id }

    override fun handleClick(event: InventoryClickEvent) {
        if (event.rawSlot >= size && event.clickedInventory == viewer.inventory) {
            event.isCancelled = true
            val clicked = event.currentItem?.takeIf { !it.type.isAir } ?: return
            shop.auction.banned(clicked)?.let { return shop.messages.send(viewer, it) }
            slot = event.slot
            stack = clicked.clone()
            count = clicked.amount
            refresh()
            return
        }
        super.handleClick(event)
    }

    private fun unpick() { slot = -1; stack = null; count = 0 }

    override fun draw() {
        clear()
        val currency = shop.currency(currencyId, shop.config.auction.currency)
        val held = stack
        if (held == null) set(SLOT_ITEM, Icon.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>올릴 물건</gray>", "<gray>아래 가방의 물건을 클릭하세요.</gray>"))
        else set(SLOT_ITEM, Icon.annotate(held.clone().apply { amount = count }, lore = listOf("", "<gray>가방에 그대로 있다가 올릴 때 빠집니다.</gray>", "<red>클릭해서 고르기 취소</red>"))) {
            unpick(); refresh()
        }
        val tax = price?.let { shop.auction.listingTax(viewer, it) } ?: 0
        set(SLOT_PRICE, Icon.of(Material.GOLD_INGOT, "<yellow>값: <white>${price?.let { currency?.format(it) ?: it.toString() } ?: "정하지 않음"}</white></yellow>", listOf(
            "<gray>개수: <white>${if (held == null) "-" else "${count}개"}</white></gray>",
            "<gray>화폐: <white>${currency?.name ?: "?"}</white></gray>",
            "<gray>등록 수수료: <white>${if (tax > 0) currency?.format(tax) ?: tax else "없음"}</white> <dark_gray>(${shop.config.auction.listingTaxPercent}%)</dark_gray></gray>",
            "<gray>팔리면 받을 돈: <white>${price?.let { currency?.format(it - shop.auction.purchaseTax(it)) } ?: "-"}</white></gray>",
            "<gray>기간: <white>${Durations.formatShort(shop.config.auction.expireHours * 3600L)}</white></gray>",
            "", "<yellow>▶ 클릭해서 값 정하기</yellow>",
        ))) {
            val currencies = Currencies.all().filter { shop.config.auction.currency.allows(it.id) }
            val form = DialogForm("<yellow>경매 값</yellow>").line("<gray>묶음 전체의 값입니다.</gray>")
                .long("price", "값", price, min = 1)
                .choice("currency", "화폐", currencies.map { it.id to it.name }, currencyId)
            if (held != null && held.amount > 1) form.long("count", "개수", count.toLong(), 1, held.amount.toLong())
            ask(form) { v ->
                price = v.long("price"); currencyId = v.choice("currency") ?: currencyId
                if (stack != null) v.long("count")?.let { count = it.toInt() }
            }
        }
        val ready = held != null && price != null && currency != null
        set(SLOT_CONFIRM, Icon.of(if (ready) Material.LIME_CONCRETE else Material.GRAY_CONCRETE, if (ready) "<green><b>올리기</b></green>" else "<gray>물건과 값을 정하세요</gray>")) {
            val picked = stack ?: return@set
            val value = price ?: return@set
            val cur = currency ?: return@set
            val current = viewer.inventory.getItem(slot)
            if (current == null || !current.isSimilar(picked) || current.amount < count) {
                unpick(); refresh()
                return@set shop.messages.send(viewer, "auction-item-changed")
            }
            val item = current.clone().apply { amount = count }
            shop.auction.check(viewer, item, value, cur)?.let { (key, ph) -> return@set shop.messages.send(viewer, key, ph) }
            // 빼는 쪽이 먼저 — 올리기가 실패하면 list 가 돌려준다.
            viewer.inventory.setItem(slot, if (current.amount == count) null else current.clone().apply { amount -= count })
            unpick()
            viewer.closeInventory()
            shop.auction.list(viewer, item, value, cur) {}
        }
        fillEmpty(Icon.FILLER)
        set(18, Icon.back()) { AuctionMenu(shop, viewer).show() }
    }

    companion object {
        const val SLOT_ITEM = 13
        const val SLOT_PRICE = 11
        const val SLOT_CONFIRM = 15
    }
}

/** 내 경매 — 판매 중(내리기) · 받을 돈 · 돌려받을 물건 · 판매 기록. */
class AuctionMineMenu(shop: Shop, viewer: Player, private var tab: Tab, private val target: UUID = viewer.uniqueId) : Menu(shop, viewer, 54, "<dark_gray>내 경매</dark_gray>") {

    enum class Tab(val label: String, val icon: Material) {
        SELLING("판매 중", Material.CHEST), UNCLAIMED("받을 돈", Material.GOLD_INGOT), EXPIRED("돌려받을 물건", Material.HOPPER), HISTORY("판매 기록", Material.BOOK)
    }

    private var page = 0
    private val date = SimpleDateFormat("MM-dd HH:mm")

    override fun draw() {
        clear()
        val rows = when (tab) {
            Tab.SELLING -> shop.auction.activeOf(target)
            Tab.UNCLAIMED -> shop.auction.ofSeller(target, AuctionService.SOLD)
            Tab.EXPIRED -> shop.auction.expiredOf(target)
            Tab.HISTORY -> shop.auction.history(target)
        }
        val own = target == viewer.uniqueId
        page = Paging.clamp(page, rows.size)
        for ((slot, row) in Paging.slice(rows, page).withIndex()) {
            val currency = shop.auction.currencyOf(row)
            val extra = when (tab) {
                Tab.SELLING -> listOf("", "<red>클릭해서 내리기(돌려받음)</red>")
                Tab.UNCLAIMED -> listOf("<gray>산 사람: <white>${row.buyerName}</white></gray>", "<gray>받을 돈: <gold>${currency?.format(row.payout) ?: row.payout}</gold></gray>", "", "<green>클릭해서 받기</green>")
                Tab.EXPIRED -> listOfNotNull(row.note?.let { "<gray>사유: <white>$it</white></gray>" }, "", "<green>클릭해서 돌려받기</green>")
                Tab.HISTORY -> listOf("<gray>산 사람: <white>${row.buyerName}</white></gray>", "<gray>팔린 때: <white>${date.format(Date(row.soldAt))}</white></gray>", "<gray>받은 돈: <white>${currency?.format(row.payout) ?: row.payout}</white></gray>")
            }
            set(slot, Listings.icon(shop, viewer, row, extra)) {
                if (!own) return@set
                when (tab) {
                    Tab.SELLING -> shop.auction.cancel(viewer, row.id) { refresh() }
                    Tab.UNCLAIMED -> shop.auction.claim(viewer, row.id) { refresh() }
                    Tab.EXPIRED -> shop.auction.takeBack(viewer, row.id) { refresh() }
                    Tab.HISTORY -> Unit
                }
            }
        }
        if (rows.isEmpty()) set(22, Icon.of(Material.BARRIER, "<gray>없습니다</gray>"))
        for ((i, t) in Tab.entries.withIndex()) {
            set(45 + i, Icon.of(t.icon, (if (t == tab) "<green>▶ " else "<white>") + t.label)) { tab = t; page = 0; refresh() }
        }
        if (own && tab == Tab.UNCLAIMED && rows.isNotEmpty()) set(SLOT_ALL, Icon.of(Material.EMERALD_BLOCK, "<green>모두 받기</green>")) { shop.auction.claimAll(viewer); viewer.scheduler.runDelayed(shop.plugin, { _ -> if (viewer.isOnline) refresh() }, null, 10L) }
        if (own && tab == Tab.EXPIRED && rows.isNotEmpty()) set(SLOT_ALL, Icon.of(Material.CHEST_MINECART, "<green>모두 돌려받기</green>")) {
            for (row in rows) shop.auction.takeBack(viewer, row.id) {}
            viewer.scheduler.runDelayed(shop.plugin, { _ -> if (viewer.isOnline) refresh() }, null, 10L)
        }
        fillEmpty(Icon.FILLER)
        if (page > 0) set(50, Icon.prevPage()) { page--; refresh() }
        if (page < Paging.pageCount(rows.size) - 1) set(51, Icon.nextPage()) { page++; refresh() }
        set(52, Icon.back()) { AuctionMenu(shop, viewer).show() }
        set(53, Icon.close()) { viewer.closeInventory() }
    }

    companion object {
        const val SLOT_ALL = 49
    }
}

/** 관리자 내리기 — 그냥 / 경고와 함께(사유). */
class AdminRemoveMenu(shop: Shop, viewer: Player, private val id: String, override val back: (() -> Unit)) : Menu(shop, viewer, 27, "<dark_red>관리자 내리기</dark_red>") {

    override fun draw() {
        clear()
        val row = shop.auction.get(id) ?: return back()
        set(13, Listings.icon(shop, viewer, row))
        set(11, Icon.of(Material.YELLOW_CONCRETE, "<yellow><b>그냥 내리기</b></yellow>", "<gray>물건은 판매자의 만료함으로 갑니다.</gray>", "<gray>경고는 남기지 않습니다.</gray>")) {
            shop.auction.adminRemove(viewer, id, warn = false, reason = "관리자가 내림") { back() }
        }
        val s = shop.config.auction
        set(15, Icon.of(Material.RED_CONCRETE, "<red><b>경고와 함께 내리기</b></red>", listOf(
            "<gray>사유를 적습니다. 판매자에게 알립니다.</gray>",
            "<gray>${s.warningExpireDays}일 안의 경고가 <white>${s.warningsToBan}</white>회면 <red>${s.banDays}일 등록 금지</red>.</gray>",
        ))) {
            ask(DialogForm("<red>경고 사유</red>").text("reason", "사유", "", maxLength = 200), reopen = { show() }) { v ->
                val reason = v.text("reason").trim().ifEmpty { "사유 없음" }
                shop.auction.adminRemove(viewer, id, warn = true, reason = reason) { back() }
            }
        }
        set(22, Icon.of(Material.PLAYER_HEAD, "<white>${row.sellerName} 의 경고 보기</white>")) { WarningsMenu(shop, viewer, row.seller, row.sellerName) { show() }.show() }
        fillEmpty(Icon.FILLER)
        set(18, Icon.back()) { back() }
    }
}

/** 한 사람의 경고와 등록 금지. */
class WarningsMenu(shop: Shop, viewer: Player, private val target: UUID, private val name: String, override val back: (() -> Unit)?) : Menu(shop, viewer, 54, "<dark_red>$name 의 경고</dark_red>") {

    private var rows: List<com.inmc.shop.data.WarningRow>? = null
    private var ban: Pair<Long, String>? = null
    private val date = SimpleDateFormat("yyyy-MM-dd HH:mm")

    override fun draw() {
        clear()
        val known = rows
        if (known == null) {
            set(22, Icon.of(Material.CLOCK, "<gray>불러오는 중…</gray>"))
            shop.auction.warnings(target) { list, b -> rows = list; ban = b; if (viewer.openInventory.topInventory == inventory) refresh() }
            navigation(); return
        }
        for ((i, w) in known.take(45).withIndex()) {
            set(i, Icon.of(Material.PAPER, "<yellow>${date.format(Date(w.at))}</yellow>", "<gray>사유: <white>${w.reason}</white></gray>", "<gray>누가: <white>${w.by}</white></gray>"))
        }
        val now = System.currentTimeMillis()
        val b = ban
        set(49, Icon.of(if (b != null && b.first > now) Material.RED_CONCRETE else Material.LIME_CONCRETE,
            if (b != null && b.first > now) "<red>등록 금지 중 — ${Durations.formatShort((b.first - now) / 1000)} 남음</red>" else "<green>등록 가능</green>",
            "<gray>기간 안 경고: <white>${known.size}</white> / ${shop.config.auction.warningsToBan}</gray>"))
        set(47, Icon.of(Material.MILK_BUCKET, "<green>경고·금지 모두 지우기</green>")) {
            shop.auction.clearWarnings(target) { rows = null; ban = null; shop.messages.send(viewer, "auction-warnings-cleared", Ph.of().player(name)); refresh() }
        }
        fillEmpty(Icon.FILLER)
        navigation()
    }
}
