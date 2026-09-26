package com.inmc.shop.gui

import com.inmc.shop.Shop
import com.inmc.shop.price.Band
import com.inmc.shop.price.DynamicUnit
import com.inmc.shop.price.Market
import com.inmc.shop.price.OnlineUnit
import com.inmc.shop.price.PriceKind
import com.inmc.shop.price.Pricing
import com.inmc.shop.price.Schedule
import com.inmc.shop.trade.TradeType
import com.inmc.shop.util.Inv
import com.inmc.shop.util.Ph
import com.inmc.shop.virtual.ItemStorage
import com.inmc.shop.virtual.LayoutButton
import com.inmc.shop.virtual.LimitOptions
import com.inmc.shop.virtual.Product
import com.inmc.shop.virtual.ProductType
import com.inmc.shop.virtual.Requirements
import com.inmc.shop.virtual.Rotation
import com.inmc.shop.virtual.StockOptions
import com.inmc.shop.virtual.VirtualShop
import kr.inmc.core.economy.Currencies
import kr.inmc.core.gui.ConfirmMenu
import kr.inmc.core.gui.DialogForm
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.item.StorageMode
import kr.inmc.core.item.StoredItem
import kr.inmc.core.util.Durations
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import java.time.DayOfWeek

/** 서버 상점 목록(관리). */
class ShopListAdminMenu(shop: Shop, viewer: Player) : Menu(shop, viewer, 54, "<dark_red>서버 상점 관리</dark_red>") {

    override val back: (() -> Unit) = { AdminHubMenu(shop, viewer).show() }
    private var page = 0

    override fun draw() {
        clear()
        val list = shop.shops.all()
        page = Paging.clamp(page, list.size)
        for ((slot, vshop) in Paging.slice(list, page).withIndex()) {
            val icon = MainMenu.shopIcon(shop, vshop, true)
            set(slot, Icon.relabel(icon, vshop.name, listOf(
                "<gray>id: <white>${vshop.id}</white></gray>",
                "<gray>상품 ${vshop.products.size}개 · 페이지 ${vshop.pages} · 메인 칸 ${if (vshop.menuSlot < 0) "없음" else vshop.menuSlot}</gray>",
                "", "<yellow>▶ 클릭해서 편집</yellow>",
            ))) { ShopEditMenu(shop, viewer, vshop.id).show() }
        }
        fillEmpty(Icon.FILLER)
        set(49, Icon.of(Material.LIME_DYE, "<green>새 상점</green>", "<gray>id 는 영문 소문자·숫자·밑줄·한글</gray>")) {
            val form = DialogForm("<green>새 서버 상점</green>").text("id", "id(바꿀 수 없음)", "").text("name", "보이는 이름", "")
            ask(form) { v ->
                val id = v.text("id").trim().lowercase()
                if (!shop.shops.validId(id)) return@ask shop.messages.send(viewer, "bad-id", Ph.of().value(id))
                if (shop.shops.get(id) != null) return@ask shop.messages.send(viewer, "already-exists", Ph.of().value(id))
                shop.shops.put(VirtualShop(id, name = v.text("name").ifBlank { id }))
                ShopEditMenu(shop, viewer, id).show()
            }
        }
        pager(page, list.size) { page = it; refresh() }
        navigation()
    }
}

/** 서버 상점 하나의 설정. */
class ShopEditMenu(shop: Shop, viewer: Player, private val id: String) : Menu(shop, viewer, 54, "<dark_red>상점 편집 — $id</dark_red>") {

    override val back: (() -> Unit) = { ShopListAdminMenu(shop, viewer).show() }

    private fun mutate(change: (VirtualShop) -> VirtualShop) {
        shop.shops.get(id)?.let { shop.shops.put(change(it)) }
    }

    override fun draw() {
        clear()
        val s = shop.shops.get(id) ?: return back()
        set(10, valueIcon(Material.NAME_TAG, "이름", Text.plain(s.name))) {
            ask(DialogForm("<yellow>이름·설명</yellow>").text("name", "이름(MiniMessage)", s.name).text("desc", "설명(줄마다 |)", s.description.joinToString("|"), maxLength = 2000)) { v ->
                mutate { it.copy(name = v.text("name").ifBlank { it.name }, description = v.text("desc").split('|').map(String::trim).filter { l -> l.isNotEmpty() }) }
            }
        }
        set(11, Icon.relabel(MainMenu.shopIcon(shop, s, true), "<yellow>아이콘</yellow>", listOf("<gray>손에 든 것으로 바꿉니다(커스텀 모델 그대로).</gray>", "<gray>우클릭: 기본(상자)</gray>"))) { event ->
            if (event.isRightClick) return@set mutate { it.copy(icon = null) }.also { refresh() }
            val hand = viewer.inventory.itemInMainHand.takeIf { !it.type.isAir } ?: return@set shop.messages.send(viewer, "hand-empty")
            mutate { it.copy(icon = shop.resolver.capture(hand), iconMaterial = hand.type) }; refresh()
        }
        set(12, toggleIcon("권한 필요", s.permissionRequired, "<gray>켜면 ${s.permission} 이 있어야 연다.</gray>")) { mutate { it.copy(permissionRequired = !it.permissionRequired) }; refresh() }
        set(13, toggleIcon("구매 허용", s.buying)) { mutate { it.copy(buying = !it.buying) }; refresh() }
        set(14, toggleIcon("판매 허용", s.selling)) { mutate { it.copy(selling = !it.selling) }; refresh() }
        set(15, valueIcon(Material.BOOK, "페이지 수", s.pages.toString())) {
            ask(DialogForm("<yellow>페이지 수</yellow>").long("pages", "페이지", s.pages.toLong(), 1, 100)) { v -> mutate { it.copy(pages = (v.long("pages") ?: 1).toInt()) } }
        }
        set(16, valueIcon(Material.COMPASS, "메인 메뉴 칸", if (s.menuSlot < 0) "없음" else s.menuSlot.toString(), "<gray>비우면 메인 메뉴에 안 보입니다.</gray>")) {
            ask(DialogForm("<yellow>메인 메뉴 칸</yellow>").long("slot", "칸 번호(0부터)", s.menuSlot.takeIf { it >= 0 }?.toLong(), 0, 53, optional = true)) { v ->
                mutate { it.copy(menuSlot = v.long("slot")?.toInt() ?: -1) }
            }
        }
        set(19, valueIcon(Material.PAINTING, "레이아웃", s.layout, "<gray>페이지별 레이아웃: ${s.pageLayouts.entries.joinToString { "${it.key}쪽=${it.value}" }.ifEmpty { "없음" }}</gray>", "<gray>좌클릭 기본 · 우클릭 페이지별</gray>")) { event ->
            val layouts = shop.layouts.all()
            if (event.isLeftClick) PickMenu(shop, viewer, "<dark_red>기본 레이아웃</dark_red>", layouts, { l -> Icon.of(if (l.id == s.layout) Material.LIME_STAINED_GLASS else Material.PAINTING, l.id, "<gray>${l.rows}줄 · 상품 칸 ${l.productSlots().size}</gray>") }, back = { show() }) { l ->
                mutate { it.copy(layout = l.id) }; show()
            }.show()
            else ask(DialogForm("<yellow>페이지별 레이아웃</yellow>").line("<gray>예: 2=wide, 3=wide · 비우면 전부 기본</gray>").text("map", "쪽=레이아웃", s.pageLayouts.entries.joinToString(", ") { "${it.key}=${it.value}" })) { v ->
                val map = v.text("map").split(',').mapNotNull { part -> part.substringBefore('=').trim().toIntOrNull()?.let { it to part.substringAfter('=').trim() } }.filter { it.second.isNotEmpty() }.toMap()
                mutate { it.copy(pageLayouts = map) }
            }
        }
        set(20, valueIcon(Material.COMMAND_BLOCK, "단축 명령어", s.aliases.joinToString(", ").ifEmpty { "없음" }, "<gray>/블록 처럼 바로 여는 명령어. 새로 만든 것은 재시작 뒤</gray>")) {
            ask(DialogForm("<yellow>단축 명령어</yellow>").text("aliases", "쉼표로(슬래시 없이)", s.aliases.joinToString(", "))) { v ->
                mutate { it.copy(aliases = v.text("aliases").split(',').map { a -> a.trim().removePrefix("/") }.filter { a -> a.isNotEmpty() }) }
            }
        }
        set(21, valueIcon(Material.GOLD_NUGGET, "상점 화폐", s.currency.ifEmpty { "모듈 기본" }, "<gray>상품이 비워 두면 이 화폐</gray>")) {
            val options = listOf("" to "모듈 기본") + Currencies.all().map { it.id to Text.plain(it.name) }
            ask(DialogForm("<yellow>상점 화폐</yellow>").choice("c", "화폐", options, s.currency)) { v -> mutate { it.copy(currency = v.choice("c").orEmpty()) } }
        }
        set(28, Icon.of(Material.CHEST, "<green>상품 (고정 칸)</green>", "<gray>${s.products.values.count { !it.rotating }}개</gray>", "", "<yellow>▶ 페이지별로 칸에 놓고 편집</yellow>")) { ProductGridMenu(shop, viewer, id, 0).show() }
        set(29, Icon.of(Material.ENDER_CHEST, "<light_purple>회전 상품</light_purple>", "<gray>${s.products.values.count { it.rotating }}개 — 회전이 뽑는 후보</gray>", "", "<yellow>▶ 클릭</yellow>")) { RotatingProductsMenu(shop, viewer, id).show() }
        set(30, Icon.of(Material.CLOCK, "<light_purple>회전</light_purple>", "<gray>${s.rotations.size}개</gray>", "", "<yellow>▶ 클릭</yellow>")) { RotationListMenu(shop, viewer, id).show() }
        set(32, Icon.of(Material.SPYGLASS, "<white>손님 화면 보기</white>")) { ShopMenu(shop, viewer, id, 0).show() }
        set(34, Icon.of(Material.LAVA_BUCKET, "<red>상점 지우기</red>", "<gray>되돌릴 수 없습니다. 재고·시세 기록도 지웁니다.</gray>")) {
            ConfirmMenu(shop, "<red>$id 을(를) 지울까요?</red>", onConfirm = {
                for (p in s.products.values) { shop.prices.forget(p); shop.stocks.forget(p) }
                for (r in s.rotations.values) shop.rotations.forget(id, r.id)
                shop.shops.remove(id); back()
            }, onCancel = { show() }).open(viewer)
        }
        fillEmpty(Icon.FILLER)
        navigation()
    }
}

/**
 * 페이지 하나의 상품 칸 편집 — 레이아웃 그대로. **가방의 물건을 클릭해 들고 빈 칸을 누르면** 그 물건이 상품이 된다(개수 = 한 단위).
 * 상품을 누르면 편집, Shift+클릭으로 들어 옮기기.
 */
class ProductGridMenu(shop: Shop, viewer: Player, private val shopId: String, private var page: Int) :
    Menu(shop, viewer, ShopMenu.layoutOf(shop, shopId, page).size, "<dark_red>상품 칸 — $shopId (${page + 1}쪽)</dark_red>") {

    private var picked: ItemStack? = null
    private var moving: String? = null

    override fun handleClick(event: InventoryClickEvent) {
        if (event.rawSlot >= size && event.clickedInventory == viewer.inventory) {
            event.isCancelled = true
            picked = event.currentItem?.takeIf { !it.type.isAir }?.clone()
            moving = null
            if (picked != null) shop.messages.send(viewer, "admin-picked")
            return
        }
        super.handleClick(event)
    }

    override fun draw() {
        clear()
        val vshop = shop.shops.get(shopId) ?: return viewer.closeInventory()
        val layout = ShopMenu.layoutOf(shop, shopId, page)
        for ((slot, raw) in layout.decorations) Inv.decode(raw)?.let { set(slot, it) }
        for ((button, slot) in layout.buttons) set(slot, Icon.of(Material.STRUCTURE_VOID, "<dark_gray>버튼: ${button.label}</dark_gray>"))
        for (slot in layout.productSlots()) {
            val product = vshop.fixedAt(page + 1, slot)
            if (product == null) {
                set(slot, null) { place(slot) }
                continue
            }
            set(slot, Icon.annotate(Products.baseIcon(shop, product), lore = listOf(
                "<gray>가격: <white>${product.pricing.kind.label}</white> · 구매 ${shop.prices.price(product, TradeType.BUY) ?: "꺼짐"} · 판매 ${shop.prices.price(product, TradeType.SELL) ?: "꺼짐"}</gray>",
                "<gray>저장: <white>${product.shown?.let(ItemStorage::label) ?: "-"}</white></gray>",
                if (moving == product.id) "<green>옮기는 중 — 빈 칸을 누르세요</green>" else "",
                "<yellow>클릭</yellow> <gray>편집</gray> · <yellow>Shift+클릭</yellow> <gray>옮기기</gray>",
            ))) { event ->
                if (event.isShiftClick) { moving = product.id; picked = null; refresh(); return@set }
                ProductEditMenu(shop, viewer, shopId, product.id) { ProductGridMenu(shop, viewer, shopId, page).show() }.show()
            }
        }
        // 아래 안내는 레이아웃의 버튼 자리를 덮지 않게 페이지 버튼만 따로.
        layout.buttons[LayoutButton.PREV]?.let { slot -> if (page > 0) set(slot, Icon.prevPage()) { ProductGridMenu(shop, viewer, shopId, page - 1).show() } }
        layout.buttons[LayoutButton.NEXT]?.let { slot -> if (page < vshop.pages - 1) set(slot, Icon.nextPage()) { ProductGridMenu(shop, viewer, shopId, page + 1).show() } }
        layout.buttons[LayoutButton.BACK]?.let { slot -> set(slot, Icon.back()) { ShopEditMenu(shop, viewer, shopId).show() } }
        layout.buttons[LayoutButton.CLOSE]?.let { slot -> set(slot, Icon.close()) { viewer.closeInventory() } }
        if (LayoutButton.BACK !in layout.buttons) viewer.sendActionBar(Text.render("<gray>이 레이아웃에 돌아가기 버튼이 없습니다 — /상점 관리 로 돌아가세요</gray>"))
    }

    private fun place(slot: Int) {
        val vshop = shop.shops.get(shopId) ?: return
        moving?.let { id ->
            vshop.product(id)?.let { shop.shops.putProduct(it.copy(page = page + 1, slot = slot)) }
            moving = null
            return refresh()
        }
        val stack = picked ?: return shop.messages.send(viewer, "admin-pick-first")
        val product = Product(
            id = shop.shops.newProductId(vshop), shopId = shopId, type = ProductType.ITEM,
            item = shop.resolver.capture(stack.clone().apply { amount = 1 }), unit = stack.amount.coerceAtLeast(1),
            page = page + 1, slot = slot,
        )
        shop.shops.putProduct(product)
        picked = null
        ProductEditMenu(shop, viewer, shopId, product.id) { ProductGridMenu(shop, viewer, shopId, page).show() }.show()
    }
}

/** 회전 상품(후보) 목록. */
class RotatingProductsMenu(shop: Shop, viewer: Player, private val shopId: String) : Menu(shop, viewer, 54, "<dark_red>회전 상품 — $shopId</dark_red>") {

    override val back: (() -> Unit) = { ShopEditMenu(shop, viewer, shopId).show() }

    override fun handleClick(event: InventoryClickEvent) {
        if (event.rawSlot >= size && event.clickedInventory == viewer.inventory) {
            event.isCancelled = true
            val stack = event.currentItem?.takeIf { !it.type.isAir } ?: return
            val vshop = shop.shops.get(shopId) ?: return
            val product = Product(
                id = shop.shops.newProductId(vshop), shopId = shopId, item = shop.resolver.capture(stack.clone().apply { amount = 1 }),
                unit = stack.amount, rotating = true,
            )
            shop.shops.putProduct(product)
            ProductEditMenu(shop, viewer, shopId, product.id) { show() }.show()
            return
        }
        super.handleClick(event)
    }

    override fun draw() {
        clear()
        val vshop = shop.shops.get(shopId) ?: return back()
        for ((i, product) in vshop.products.values.filter { it.rotating }.take(45).withIndex()) {
            set(i, Icon.annotate(Products.baseIcon(shop, product), lore = listOf("<gray>가중치: <white>${product.weight}</white></gray>", "<gray>저장: <white>${product.shown?.let(ItemStorage::label) ?: "-"}</white></gray>", "", "<yellow>▶ 클릭해서 편집</yellow>"))) {
                ProductEditMenu(shop, viewer, shopId, product.id) { show() }.show()
            }
        }
        fillEmpty(Icon.FILLER)
        set(49, Icon.of(Material.BOOK, "<yellow>회전 상품 더하기</yellow>", "<gray>아래 가방의 물건을 클릭하면 회전 후보가 됩니다.</gray>"))
        navigation()
    }
}

/** 상품 하나의 편집. */
class ProductEditMenu(shop: Shop, viewer: Player, private val shopId: String, private val productId: String, override val back: (() -> Unit)) :
    Menu(shop, viewer, 54, "<dark_red>상품 편집</dark_red>") {

    private fun product(): Product? = shop.shops.get(shopId)?.product(productId)
    private fun mutate(change: (Product) -> Product) { product()?.let { shop.shops.putProduct(change(it)) } }

    override fun draw() {
        clear()
        val p = product() ?: return back()
        set(4, Icon.annotate(Products.baseIcon(shop, p), lore = listOf("<gray>상품 id: ${p.key}</gray>")))
        set(10, valueIcon(Material.COMPARATOR, "종류", p.type.label, "<gray>아이템: 아이템을 준다(되팔 수 있다) · 명령어: 명령어를 돌린다(구매만)</gray>")) {
            mutate { it.copy(type = if (it.type == ProductType.ITEM) ProductType.COMMAND else ProductType.ITEM) }; refresh()
        }
        set(11, Icon.of(Material.ITEM_FRAME, "<yellow>아이템 바꾸기</yellow>", "<gray>손에 든 것으로(개수 = 한 단위).</gray>", "<gray>명령어 상품이면 아이콘.</gray>")) {
            val hand = viewer.inventory.itemInMainHand.takeIf { !it.type.isAir } ?: return@set shop.messages.send(viewer, "hand-empty")
            val captured = shop.resolver.capture(hand.clone().apply { amount = 1 })
            mutate { if (it.type == ProductType.ITEM) it.copy(item = ItemStorage.keepMode(it.item, captured), unit = hand.amount) else it.copy(preview = ItemStorage.keepMode(it.preview, captured)) }; refresh()
        }
        set(12, valueIcon(Material.PAPER, "한 단위", "${p.unit}개")) {
            ask(DialogForm("<yellow>한 단위</yellow>").long("unit", "개수", p.unit.toLong(), 1, 99L * 36)) { v -> mutate { it.copy(unit = (v.long("unit") ?: 1).toInt()) } }
        }
        set(13, valueIcon(Material.NAME_TAG, "보이는 이름·설명", p.name ?: "(아이템 그대로)")) {
            ask(DialogForm("<yellow>이름·설명</yellow>").text("name", "이름(비우면 아이템 그대로)", p.name.orEmpty()).text("lore", "설명(줄마다 |)", p.lore.joinToString("|"), maxLength = 2000)) { v ->
                mutate { it.copy(name = v.text("name").ifBlank { null }, lore = v.text("lore").split('|').map(String::trim).filter { l -> l.isNotEmpty() }) }
            }
        }
        set(14, valueIcon(Material.COMMAND_BLOCK, "명령어", "${p.commands.size}줄", *p.commands.take(5).map { "<gray>/$it</gray>" }.toTypedArray(), "<gray>{player} = 산 사람. PlaceholderAPI 가능.</gray>")) {
            ask(DialogForm("<yellow>명령어</yellow>").line("<gray>한 줄에 하나. 콘솔이 돌립니다. {player}</gray>").text("cmds", "명령어", p.commands.joinToString("\n"), maxLength = 4000, multiline = true)) { v ->
                mutate { it.copy(commands = v.text("cmds").lines().map { l -> l.trim().removePrefix("/") }.filter { l -> l.isNotEmpty() }) }
            }
        }
        set(15, valueIcon(Material.GOLD_NUGGET, "화폐", p.currency.ifEmpty { "상점 기본" })) {
            val options = listOf("" to "상점 기본") + Currencies.all().map { it.id to Text.plain(it.name) }
            ask(DialogForm("<yellow>화폐</yellow>").choice("c", "화폐", options, p.currency)) { v -> mutate { it.copy(currency = v.choice("c").orEmpty()) } }
        }
        p.shown?.let { stored ->
            if (ItemStorage.choice(stored) != ItemStorage.Choice.CHOOSABLE) set(16, storageIcon(stored))
            else set(16, storageIcon(stored)) {
                val next = stored.mode.toggle()
                // 손으로 적은 정의에는 스냅샷이 없을 수 있다 — 고정할 모습이 없으면 지금 모습을 찍는다.
                val frozen = if (next == StorageMode.SNAPSHOT && stored.snapshot == null)
                    stored.copy(snapshot = shop.resolver.create(stored, 1)?.let { s -> runCatching { s.serializeAsBytes() }.getOrNull() })
                else stored
                mutate { it.withShown(frozen.withMode(next)) }; refresh()
            }
        }
        val buy = shop.prices.price(p, TradeType.BUY)
        val sell = shop.prices.price(p, TradeType.SELL)
        set(19, Icon.of(Material.EMERALD, "<green>가격 방식: <white>${p.pricing.kind.label}</white></green>", listOf(
            "<gray>${p.pricing.kind.summary}</gray>",
            "<gray>지금 구매: <white>${buy ?: "꺼짐"}</white> · 판매: <white>${sell ?: "꺼짐"}</white></gray>",
            "", "<yellow>좌클릭</yellow> <gray>값 고치기</gray>", "<yellow>우클릭</yellow> <gray>방식 바꾸기</gray>",
        ))) { event ->
            if (event.isRightClick) PickMenu(shop, viewer, "<dark_red>가격 방식</dark_red>", PriceKind.entries, { k -> Icon.of(if (k == p.pricing.kind) Material.LIME_DYE else Material.GRAY_DYE, k.label, "<gray>${k.summary}</gray>") }, back = { show() }) { kind ->
                PriceForms.ask(this, shop, viewer, p, kind) { pricing -> mutate { it.copy(pricing = pricing) }; shop.prices.reset(product()!!) }
            }.show()
            else PriceForms.ask(this, shop, viewer, p, p.pricing.kind) { pricing -> mutate { it.copy(pricing = pricing) } }
        }
        set(20, Icon.of(Material.RECOVERY_COMPASS, "<aqua>시세</aqua>", "<gray>최근 가격·거래량·시장 상태</gray>", "", "<yellow>▶ 클릭</yellow>")) { MarketMenu(shop, viewer, p) { show() }.show() }
        set(21, Icon.of(Material.CHEST, "<aqua>재고: " + (if (p.stock.enabled) "<white>켜짐</white>" else "<gray>꺼짐</gray>") + "</aqua>", listOf(
            "<gray>용량 ${p.stock.capacity} · 다시 채움 ${p.stock.restockMin}~${p.stock.restockMax} · " + (if (p.stock.restockSeconds > 0) Durations.formatShort(p.stock.restockSeconds) + "마다" else "사람이 파는 것으로만") + "</gray>",
            "<gray>지금: <white>${shop.stocks.state(p)?.units ?: "-"}</white></gray>",
            "", "<yellow>좌클릭</yellow> <gray>설정</gray> · <yellow>우클릭</yellow> <gray>지금 값 정하기</gray>",
        ))) { event ->
            if (event.isRightClick && p.stock.enabled) return@set ask(DialogForm("<yellow>지금 재고</yellow>").long("n", "단위", shop.stocks.state(p)?.units, 0, p.stock.capacity)) { v -> shop.stocks.set(p, v.long("n") ?: 0) }
            val s = p.stock
            ask(DialogForm("<yellow>전체 재고</yellow>").line("<gray>모두가 같이 쓴다. 사면 줄고 팔면 는다.</gray>")
                .toggle("on", "켜기", s.enabled).long("cap", "용량", s.capacity, 1).long("min", "다시 채울 때 최소", s.restockMin, 0).long("max", "다시 채울 때 최대", s.restockMax, 0)
                .long("sec", "다시 채우는 간격(초, -1 = 안 채움)", s.restockSeconds, -1)) { v ->
                mutate { it.copy(stock = StockOptions(v.bool("on"), v.long("cap") ?: s.capacity, v.long("min") ?: s.restockMin, v.long("max") ?: s.restockMax, v.long("sec") ?: s.restockSeconds).let { o -> StockOptions.load(yaml(o::save)) }) }
            }
        }
        set(22, Icon.of(Material.CLOCK, "<gold>한 사람 한도: " + (if (p.limits.enabled) "<white>켜짐</white>" else "<gray>꺼짐</gray>") + "</gold>", listOf(
            "<gray>구매 ${if (p.limits.buy < 0) "무제한" else p.limits.buy} · 판매 ${if (p.limits.sell < 0) "무제한" else p.limits.sell} · " + (if (p.limits.resetSeconds > 0) Durations.formatShort(p.limits.resetSeconds) + "마다 초기화" else "평생") + "</gray>",
            "", "<yellow>▶ 클릭</yellow>",
        ))) {
            val l = p.limits
            ask(DialogForm("<yellow>한 사람 한도</yellow>").toggle("on", "켜기", l.enabled).long("buy", "구매 한도(-1 무제한)", l.buy, -1).long("sell", "판매 한도(-1 무제한)", l.sell, -1)
                .long("sec", "초기화 간격(초, -1 = 평생 한 번)", l.resetSeconds, -1)) { v ->
                mutate { it.copy(limits = LimitOptions(v.bool("on"), v.long("buy") ?: -1, v.long("sell") ?: -1, v.long("sec") ?: l.resetSeconds)) }
            }
        }
        set(23, Icon.of(Material.IRON_BARS, "<red>요구 조건</red>", listOf(
            "<gray>허용 등급: <white>${p.requirements.ranks.joinToString().ifEmpty { "-" }}</white></gray>",
            "<gray>금지 등급: <white>${p.requirements.forbiddenRanks.joinToString().ifEmpty { "-" }}</white></gray>",
            "<gray>필요 권한: <white>${p.requirements.permissions.joinToString().ifEmpty { "-" }}</white></gray>",
            "<gray>금지 권한: <white>${p.requirements.forbiddenPermissions.joinToString().ifEmpty { "-" }}</white></gray>",
            "", "<yellow>▶ 클릭</yellow>",
        ))) {
            val r = p.requirements
            fun split(s: String) = s.split(',').map(String::trim).filter { it.isNotEmpty() }
            ask(DialogForm("<yellow>요구 조건</yellow>").line("<gray>쉼표로. 등급 = LuckPerms 그룹 이름</gray>")
                .text("r", "허용 등급(하나라도)", r.ranks.joinToString(", ")).text("fr", "금지 등급", r.forbiddenRanks.joinToString(", "))
                .text("p", "필요 권한(하나라도)", r.permissions.joinToString(", ")).text("fp", "금지 권한", r.forbiddenPermissions.joinToString(", "))) { v ->
                mutate { it.copy(requirements = Requirements(split(v.text("r")), split(v.text("fr")), split(v.text("p")), split(v.text("fp")))) }
            }
        }
        set(24, toggleIcon("회전 상품", p.rotating, "<gray>켜면 고정 칸이 아니라 회전이 뽑는 후보가 됩니다.</gray>", "<gray>가중치: ${p.weight}</gray>")) { event ->
            if (event.isRightClick) return@set ask(DialogForm("<yellow>가중치</yellow>").decimal("w", "가중치(클수록 잘 뽑힘)", p.weight, 0.0)) { v -> mutate { it.copy(weight = v.decimal("w") ?: 1.0) } }
            mutate { it.copy(rotating = !it.rotating) }; refresh()
        }
        set(31, Icon.of(Material.BARRIER, "<red>시세·재고 초기화</red>", "<gray>가격 상태를 처음으로(시장 배수 1).</gray>")) {
            ConfirmMenu(shop, "<red>시세를 초기화할까요?</red>", onConfirm = { shop.prices.reset(p); show() }, onCancel = { show() }).open(viewer)
        }
        set(40, Icon.of(Material.LAVA_BUCKET, "<red>상품 지우기</red>")) {
            ConfirmMenu(shop, "<red>이 상품을 지울까요?</red>", onConfirm = { shop.prices.forget(p); shop.stocks.forget(p); shop.shops.removeProduct(p); back() }, onCancel = { show() }).open(viewer)
        }
        fillEmpty(Icon.FILLER)
        navigation()
    }

    private fun yaml(save: (org.bukkit.configuration.ConfigurationSection) -> Unit) = org.bukkit.configuration.file.YamlConfiguration().also(save)

    private fun storageIcon(stored: StoredItem): ItemStack {
        val snapshot = stored.mode == StorageMode.SNAPSHOT
        val name = "<yellow>저장 방식: <white>${ItemStorage.label(stored)}</white></yellow>"
        return when (ItemStorage.choice(stored)) {
            ItemStorage.Choice.SAME -> Icon.of(Material.COMPASS, name, "<gray>평범한 바닐라 아이템은 두 방식이 같습니다.</gray>")
            ItemStorage.Choice.FIXED -> Icon.of(Material.ITEM_FRAME, name, "<gray>원본 정의가 없는 아이템(이름·설명을 붙인 것)이라</gray>", "<gray>등록한 모습 그대로만 줄 수 있습니다.</gray>")
            ItemStorage.Choice.CHOOSABLE -> Icon.of(if (snapshot) Material.ITEM_FRAME else Material.COMPASS, name, listOf(
                "<gray><white>동적</white> - 원본(커스텀아이템·MMOItems)에서 매번 다시 만듭니다.</gray>",
                "<gray>  원본을 고치면 상품도 같이 바뀝니다.</gray>",
                "<gray><white>스냅샷</white> - 등록한 순간의 모습으로 고정합니다.</gray>",
                "<gray>  원본이 바뀌거나 사라져도 그대로 나옵니다.</gray>",
                "",
                "<dark_gray>지금 모습으로 다시 고정하려면 '아이템 바꾸기'로 다시 등록하세요.</dark_gray>",
                "<dark_gray>되팔기는 두 방식 모두 같은 아이템이면 받습니다.</dark_gray>",
                "<yellow>▶ 클릭: 전환</yellow>",
            ))
        }
    }
}

/** 가격 방식마다 입력창. */
object PriceForms {

    fun ask(menu: Menu, shop: Shop, viewer: Player, p: Product, kind: PriceKind, apply: (Pricing) -> Unit) {
        val current = p.pricing
        val reopen = { menu.show() }
        fun done(pricing: Pricing) { apply(pricing); reopen() }
        when (kind) {
            PriceKind.FIXED -> {
                val c = current as? Pricing.Fixed
                DialogForm("<green>고정 가격</green>").line("<gray>비우면 그 방향은 꺼집니다(한 단위의 값).</gray>")
                    .long("buy", "구매가", c?.buy, 0, optional = true).long("sell", "판매가", c?.sell, 0, optional = true)
                    .show(shop.plugin, viewer, onCancel = { reopen() }) { _, v -> done(Pricing.Fixed(v.long("buy"), v.long("sell"))) }
            }
            PriceKind.MARKET -> {
                val c = current as? Pricing.Market
                DialogForm("<green>시장 가격</green>").line("<gray>기본가에서 시작해 거래·참여자·추세로 주기마다 움직입니다. 최저~최고 안에서만.</gray>").line("<gray>기본가를 비우면 그 방향은 꺼집니다.</gray>")
                    .long("bb", "구매 기본가", c?.buy?.base, 0, optional = true).long("bmin", "구매 최저", c?.buy?.min, 0, optional = true).long("bmax", "구매 최고", c?.buy?.max, 0, optional = true)
                    .long("sb", "판매 기본가", c?.sell?.base, 0, optional = true).long("smin", "판매 최저", c?.sell?.min, 0, optional = true).long("smax", "판매 최고", c?.sell?.max, 0, optional = true)
                    .decimal("sens", "민감도 배수(1 = 기본)", c?.sensitivity ?: 1.0, 0.0, 10.0)
                    .show(shop.plugin, viewer, onCancel = { reopen() }) { _, v ->
                        fun band(b: String, lo: String, hi: String): Band? = v.long(b)?.let { base -> Band(base, v.long(lo) ?: base / 2, v.long(hi) ?: base * 2).normalized() }
                        done(Pricing.Market(band("bb", "bmin", "bmax"), band("sb", "smin", "smax"), v.decimal("sens") ?: 1.0))
                    }
            }
            PriceKind.FLOAT -> {
                val c = current as? Pricing.Floating
                val weekly = c?.schedule as? Schedule.Weekly
                val interval = c?.schedule as? Schedule.Interval
                DialogForm("<green>변동 가격</green>").line("<gray>범위는 10-50 처럼. 비우면 그 방향은 꺼집니다.</gray>")
                    .text("buy", "구매 범위", c?.buy?.let { "${it.first}-${it.last}" }.orEmpty())
                    .text("sell", "판매 범위", c?.sell?.let { "${it.first}-${it.last}" }.orEmpty())
                    .choice("mode", "다시 굴리는 때", listOf("INTERVAL" to "간격", "WEEKLY" to "요일·시각"), if (weekly != null) "WEEKLY" else "INTERVAL")
                    .long("sec", "간격(초)", interval?.seconds ?: 3600, 1)
                    .text("days", "요일(월,화,… 비우면 매일)", weekly?.days?.sortedBy { it.value }?.joinToString(",") { Schedule.DAY_LABEL.getValue(it) }.orEmpty())
                    .text("times", "시각(09:00, 21:00)", weekly?.times?.joinToString(", ") { "%02d:%02d".format(it.hour, it.minute) }.orEmpty())
                    .show(shop.plugin, viewer, onCancel = { reopen() }) { _, v -> done(Pricing.Floating(Pricing.range(v.text("buy")), Pricing.range(v.text("sell")), schedule(v))) }
            }
            PriceKind.DYNAMIC -> {
                val c = current as? Pricing.Dynamic
                DialogForm("<green>수요 가격</green>").line("<gray>한 단위 거래마다 %씩. 시작가를 비우면 그 방향은 꺼집니다.</gray>")
                    .long("bs", "구매 시작가", c?.buy?.start, 0, optional = true)
                    .decimal("bbs", "구매가 — 누가 사면 %", c?.buy?.buyStep ?: 0.5).decimal("bss", "구매가 — 누가 팔면 %", c?.buy?.sellStep ?: -0.5)
                    .decimal("bmin", "구매가 최저 %", c?.buy?.minPercent ?: -50.0).decimal("bmax", "구매가 최고 %", c?.buy?.maxPercent ?: 50.0)
                    .long("ss", "판매 시작가", c?.sell?.start, 0, optional = true)
                    .decimal("sbs", "판매가 — 누가 사면 %", c?.sell?.buyStep ?: 0.5).decimal("sss", "판매가 — 누가 팔면 %", c?.sell?.sellStep ?: -0.5)
                    .decimal("smin", "판매가 최저 %", c?.sell?.minPercent ?: -50.0).decimal("smax", "판매가 최고 %", c?.sell?.maxPercent ?: 50.0)
                    .long("stab", "안정화 — 거래 없이 몇 초 뒤", c?.stabilizeSeconds ?: 3600, 0).decimal("stabp", "안정화 — 한 번에 몇 %", c?.stabilizePercent ?: 5.0, 0.0, 100.0)
                    .show(shop.plugin, viewer, onCancel = { reopen() }) { _, v ->
                        val b = v.long("bs")?.let { DynamicUnit(it, v.decimal("bbs") ?: 0.0, v.decimal("bss") ?: 0.0, v.decimal("bmin") ?: -50.0, v.decimal("bmax") ?: 50.0) }
                        val s = v.long("ss")?.let { DynamicUnit(it, v.decimal("sbs") ?: 0.0, v.decimal("sss") ?: 0.0, v.decimal("smin") ?: -50.0, v.decimal("smax") ?: 50.0) }
                        done(Pricing.Dynamic(b, s, v.long("stab") ?: 3600, v.decimal("stabp") ?: 5.0))
                    }
            }
            PriceKind.ONLINE -> {
                val c = current as? Pricing.Online
                DialogForm("<green>접속자 가격</green>").line("<gray>접속자 한 명당 %. 시작가를 비우면 그 방향은 꺼집니다.</gray>")
                    .long("bs", "구매 시작가", c?.buy?.start, 0, optional = true).decimal("bp", "구매가 — 한 명당 %", c?.buy?.perPlayer ?: 1.0)
                    .decimal("bmin", "구매가 최저 %", c?.buy?.minPercent ?: -50.0).decimal("bmax", "구매가 최고 %", c?.buy?.maxPercent ?: 50.0)
                    .long("ss", "판매 시작가", c?.sell?.start, 0, optional = true).decimal("sp", "판매가 — 한 명당 %", c?.sell?.perPlayer ?: -1.0)
                    .decimal("smin", "판매가 최저 %", c?.sell?.minPercent ?: -50.0).decimal("smax", "판매가 최고 %", c?.sell?.maxPercent ?: 50.0)
                    .show(shop.plugin, viewer, onCancel = { reopen() }) { _, v ->
                        val b = v.long("bs")?.let { OnlineUnit(it, v.decimal("bp") ?: 0.0, v.decimal("bmin") ?: -50.0, v.decimal("bmax") ?: 50.0) }
                        val s = v.long("ss")?.let { OnlineUnit(it, v.decimal("sp") ?: 0.0, v.decimal("smin") ?: -50.0, v.decimal("smax") ?: 50.0) }
                        done(Pricing.Online(b, s))
                    }
            }
        }
    }

    /** 입력창의 간격·요일·시각 → 일정. */
    fun schedule(v: DialogForm.Values): Schedule {
        if (v.choice("mode") != "WEEKLY") return Schedule.Interval((v.long("sec") ?: 3600).coerceAtLeast(1))
        val byLabel = Schedule.DAY_LABEL.entries.associate { (d, l) -> l to d }
        val days = v.text("days").split(',').mapNotNull { part -> part.trim().let { t -> byLabel[t] ?: runCatching { DayOfWeek.valueOf(t.uppercase()) }.getOrNull() } }.toSet()
        val times = v.text("times").split(',').mapNotNull { Schedule.parseTime(it) }
        return Schedule.Weekly(days, times)
    }
}

/** 시세 — 최근 주기의 가격·거래량·참여자, 지금 시장 상태. */
class MarketMenu(shop: Shop, viewer: Player, private val product: Product, override val back: (() -> Unit)) : Menu(shop, viewer, 54, "<dark_aqua>시세</dark_aqua>") {

    private var rows: List<com.inmc.shop.data.HistoryRow>? = null

    override fun draw() {
        clear()
        val known = rows
        if (known == null) {
            set(22, Icon.of(Material.CLOCK, "<gray>불러오는 중…</gray>"))
            shop.prices.history(product, 24) { rows = it; if (viewer.openInventory.topInventory == inventory) refresh() }
            navigation(); return
        }
        val params = shop.config.virtual.market
        val state = shop.prices.state(product)
        for ((i, row) in known.reversed().withIndex()) {
            if (i >= 36) break
            val rising = i > 0 && (row.sell ?: row.buy ?: 0) > ((known.reversed()[i - 1].sell ?: known.reversed()[i - 1].buy) ?: 0)
            val material = if (i == 0) Material.WHITE_STAINED_GLASS_PANE else if (rising) Material.LIME_STAINED_GLASS_PANE else Material.RED_STAINED_GLASS_PANE
            set(i, Icon.of(material, "<white>${Durations.formatShort(((System.currentTimeMillis() - (row.period + 1) * params.periodMillis) / 1000).coerceAtLeast(0))} 전</white>", listOf(
                "<gray>구매가: <white>${row.buy ?: "-"}</white> · 판매가: <white>${row.sell ?: "-"}</white></gray>",
                "<gray>배수: <white>${"%.3f".format(row.multiplier)}</white></gray>",
                "<gray>거래량: <white>${row.volume}</white> · 참여자: <white>${row.users}</white></gray>",
            )))
        }
        set(45, Icon.of(Material.KNOWLEDGE_BOOK, "<aqua>지금 시장</aqua>", listOf(
            "<gray>방식: <white>${product.pricing.kind.label}</white></gray>",
            "<gray>배수 m: <white>${"%.3f".format(state.m)}</white> · 흔들림 ${"%.2f".format(state.noise * 100)}%</gray>",
            "<gray>압력: <white>${"%.3f".format(state.pressure)}</white> (단기 ${"%.3f".format(state.emaShort)} · 장기 ${"%.3f".format(state.emaLong)})</gray>",
            "<gray>활동량: <white>${if (state.activity < 0) "-" else "%.1f".format(state.activity)}</white> · ${Market.activity(state, params).label}</gray>",
            "<gray>한산 보정: <white>${"%.2f".format(state.dormancy * 100)}%/주기</white></gray>",
            "<gray>주기: <white>${params.periodMinutes}분</white></gray>",
        )))
        fillEmpty(Icon.FILLER)
        navigation()
    }
}

/** 회전 목록. */
class RotationListMenu(shop: Shop, viewer: Player, private val shopId: String) : Menu(shop, viewer, 54, "<dark_red>회전 — $shopId</dark_red>") {

    override val back: (() -> Unit) = { ShopEditMenu(shop, viewer, shopId).show() }

    override fun draw() {
        clear()
        val vshop = shop.shops.get(shopId) ?: return back()
        for ((i, r) in vshop.rotations.values.take(45).withIndex()) {
            val state = shop.rotations.state(shopId, r.id)
            set(i, Icon.of(Material.CLOCK, "<light_purple>${r.id}</light_purple>", listOf(
                "<gray>${r.schedule.describe()} · 칸 ${r.slotCount}개</gray>",
                "<gray>다음: <white>${state?.let { Durations.formatShort(((it.nextAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)) + " 뒤" } ?: "-"}</white></gray>",
                "", "<yellow>▶ 클릭해서 편집</yellow>",
            ))) { RotationEditMenu(shop, viewer, shopId, r.id).show() }
        }
        set(49, Icon.of(Material.LIME_DYE, "<green>새 회전</green>")) {
            ask(DialogForm("<green>새 회전</green>").text("id", "id(영문)", "")) { v ->
                val id = v.text("id").trim().lowercase()
                if (!shop.shops.validId(id) || id in vshop.rotations) return@ask shop.messages.send(viewer, "bad-id", Ph.of().value(id))
                shop.shops.put(vshop.copy(rotations = vshop.rotations + (id to Rotation(id))))
                RotationEditMenu(shop, viewer, shopId, id).show()
            }
        }
        fillEmpty(Icon.FILLER)
        navigation()
    }
}

class RotationEditMenu(shop: Shop, viewer: Player, private val shopId: String, private val rotationId: String) : Menu(shop, viewer, 27, "<dark_red>회전 — $rotationId</dark_red>") {

    override val back: (() -> Unit) = { RotationListMenu(shop, viewer, shopId).show() }

    private fun rotation() = shop.shops.get(shopId)?.rotations?.get(rotationId)
    private fun mutate(change: (Rotation) -> Rotation) {
        val vshop = shop.shops.get(shopId) ?: return
        val r = vshop.rotations[rotationId] ?: return
        shop.shops.put(vshop.copy(rotations = vshop.rotations + (rotationId to change(r))))
    }

    override fun draw() {
        clear()
        val r = rotation() ?: return back()
        set(10, valueIcon(Material.CLOCK, "언제", r.schedule.describe())) {
            val weekly = r.schedule as? Schedule.Weekly
            val interval = r.schedule as? Schedule.Interval
            ask(DialogForm("<yellow>회전 일정</yellow>")
                .choice("mode", "방식", listOf("INTERVAL" to "간격", "WEEKLY" to "요일·시각"), if (weekly != null) "WEEKLY" else "INTERVAL")
                .long("sec", "간격(초)", interval?.seconds ?: 86400, 1)
                .text("days", "요일(월,화,… 비우면 매일)", weekly?.days?.sortedBy { it.value }?.joinToString(",") { Schedule.DAY_LABEL.getValue(it) }.orEmpty())
                .text("times", "시각(00:00, 12:00)", weekly?.times?.joinToString(", ") { "%02d:%02d".format(it.hour, it.minute) }.orEmpty())) { v ->
                mutate { it.copy(schedule = PriceForms.schedule(v)) }
            }
        }
        set(11, valueIcon(Material.ITEM_FRAME, "회전 칸", "${r.slotCount}개", "<gray>페이지마다 칸을 눌러 켜고 끕니다.</gray>")) { RotationSlotsMenu(shop, viewer, shopId, rotationId, 0).show() }
        set(12, valueIcon(Material.ENDER_CHEST, "후보", if (r.products.isEmpty()) "회전 상품 전부" else "${r.products.size}개")) {
            val candidates = shop.shops.get(shopId)?.products?.values?.filter { it.rotating }.orEmpty()
            PickMenu(shop, viewer, "<dark_red>후보(비우면 전부)</dark_red>", candidates, { p -> Products.baseIcon(shop, p) }, multi = true,
                selected = { rotation()?.products?.mapNotNull { id -> candidates.firstOrNull { it.id == id } }?.toSet().orEmpty() }, back = { show() }) { p ->
                mutate { it.copy(products = if (p.id in it.products) it.products - p.id else it.products + p.id) }
            }.show()
        }
        set(13, toggleIcon("바뀔 때 알림", r.notify)) { mutate { it.copy(notify = !it.notify) }; refresh() }
        set(14, Icon.of(Material.FIREWORK_ROCKET, "<green>지금 굴리기</green>")) {
            val vshop = shop.shops.get(shopId) ?: return@set
            shop.rotations.force(vshop, r); shop.messages.send(viewer, "rotation-forced")
        }
        set(16, Icon.of(Material.LAVA_BUCKET, "<red>회전 지우기</red>")) {
            val vshop = shop.shops.get(shopId) ?: return@set
            shop.shops.put(vshop.copy(rotations = vshop.rotations - rotationId)); shop.rotations.forget(shopId, rotationId); back()
        }
        fillEmpty(Icon.FILLER)
        navigation(18, 26)
    }
}

/** 회전 칸 고르기 — 레이아웃 그대로, 상품 칸을 눌러 켜고 끈다. */
class RotationSlotsMenu(shop: Shop, viewer: Player, private val shopId: String, private val rotationId: String, private val page: Int) :
    Menu(shop, viewer, ShopMenu.layoutOf(shop, shopId, page).size, "<dark_red>회전 칸 — ${page + 1}쪽</dark_red>") {

    override fun draw() {
        clear()
        val vshop = shop.shops.get(shopId) ?: return viewer.closeInventory()
        val r = vshop.rotations[rotationId] ?: return viewer.closeInventory()
        val layout = ShopMenu.layoutOf(shop, shopId, page)
        val chosen = r.slots[page + 1].orEmpty()
        for (slot in layout.productSlots()) {
            val fixed = vshop.fixedAt(page + 1, slot)
            if (fixed != null) { set(slot, Icon.annotate(Products.baseIcon(shop, fixed), lore = listOf("<gray>고정 상품 칸</gray>"))); continue }
            val on = slot in chosen
            set(slot, Icon.of(if (on) Material.LIME_STAINED_GLASS_PANE else Material.LIGHT_GRAY_STAINED_GLASS_PANE, if (on) "<green>회전 칸</green>" else "<gray>빈 칸</gray>")) {
                val set = if (on) chosen - slot else chosen + slot
                shop.shops.put(vshop.copy(rotations = vshop.rotations + (rotationId to r.copy(slots = r.slots + (page + 1 to set)))))
                refresh()
            }
        }
        for ((button, slot) in layout.buttons) when (button) {
            LayoutButton.PREV -> if (page > 0) set(slot, Icon.prevPage()) { RotationSlotsMenu(shop, viewer, shopId, rotationId, page - 1).show() }
            LayoutButton.NEXT -> if (page < vshop.pages - 1) set(slot, Icon.nextPage()) { RotationSlotsMenu(shop, viewer, shopId, rotationId, page + 1).show() }
            LayoutButton.BACK -> set(slot, Icon.back()) { RotationEditMenu(shop, viewer, shopId, rotationId).show() }
            else -> set(slot, Icon.FILLER)
        }
    }
}
