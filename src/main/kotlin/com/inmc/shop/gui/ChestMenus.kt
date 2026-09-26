package com.inmc.shop.gui

import com.inmc.shop.Shop
import com.inmc.shop.chest.ChestProduct
import com.inmc.shop.chest.ChestShop
import com.inmc.shop.trade.ClickAction
import com.inmc.shop.trade.ClickKind
import com.inmc.shop.trade.ProductState
import com.inmc.shop.trade.TradeType
import com.inmc.shop.util.Inv
import com.inmc.shop.util.Ph
import kr.inmc.core.economy.Currencies
import kr.inmc.core.economy.Currency
import kr.inmc.core.gui.ConfirmMenu
import kr.inmc.core.gui.DialogForm
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.util.Durations
import kr.inmc.core.util.Text
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta

/** 상자 상점 상품을 구매·판매 화면에 넘기는 어댑터. */
class ChestTarget(private val shop: Shop, private val shopId: String, private val productId: String, override val type: TradeType) : TradeTarget {
    private fun value() = shop.chests.get(shopId)
    private fun product() = value()?.product(productId)
    override val title: String get() = "<dark_gray>" + (value()?.name ?: "") + " · " + type.label + "</dark_gray>"
    override fun icon(): ItemStack = product()?.let { shop.chests.template(it) } ?: ItemStack(Material.BARRIER)
    override fun label(): String = product()?.let { shop.chests.label(it) } ?: "?"
    override fun unit(): Int = product()?.let { shop.chests.unitOf(it) } ?: 1
    override fun currency(): Currency? = product()?.let { shop.chests.currencyOf(it) }
    override fun total(player: Player, units: Long): Long? = product()?.price(type)?.let { it * units }
    override fun maxUnits(player: Player): Long {
        val v = value() ?: return 0
        val p = product() ?: return 0
        return shop.chests.maxUnits(player, v, p, type)
    }
    override fun execute(player: Player, units: Long, then: (Boolean) -> Unit) {
        val v = value() ?: return then(false)
        val p = product() ?: return then(false)
        if (type == TradeType.BUY) shop.chests.buy(player, v, p, units, then) else shop.chests.sell(player, v, p, units, then)
    }
}

/** 손님이 보는 상자 상점. */
class ChestShopMenu(shop: Shop, viewer: Player, private val shopId: String) : Menu(shop, viewer, 54, titleOf(shop, shopId)) {

    override fun draw() {
        clear()
        val value = shop.chests.get(shopId) ?: return viewer.closeInventory()
        val now = System.currentTimeMillis()
        for ((index, product) in value.products.take(45).withIndex()) {
            set(index, icon(value, product)) { event -> click(value, product, event) }
        }
        if (value.products.isEmpty()) set(22, Icon.of(Material.BARRIER, "<red>팔고 사는 물건이 없습니다</red>"))
        fillEmpty(Icon.FILLER)
        set(SLOT_INFO, Icon.of(Material.PLAYER_HEAD, "<yellow>" + value.name + "</yellow>", buildList {
            add("<gray>운영: <white>" + (if (value.admin) Text.plain(shop.config.chest.adminName) else value.operatorName(now)) + "</white></gray>")
            if (value.rent.active(now)) add("<gray>임대 끝: <white>" + Durations.formatShort((value.rent.until - now) / 1000) + " 뒤</white></gray>")
        }).also { head -> if (!value.admin) (head.itemMeta as? SkullMeta)?.let { meta -> meta.owningPlayer = Bukkit.getOfflinePlayer(value.operator(now)); head.itemMeta = meta } })
        if (shop.chests.rentable(value, now) && viewer.uniqueId != value.owner) {
            val currency = shop.currency(value.rent.currency, shop.config.chest.currency)
            set(SLOT_RENT, Icon.of(Material.NAME_TAG, "<green><b>이 상점 빌리기</b></green>", listOf(
                "<gray>기간: <white>${value.rent.days}일</white></gray>",
                "<gray>임대료: <white>${currency?.format(value.rent.price) ?: value.rent.price}</white></gray>",
                "", "<green>▶ 클릭해서 빌리기</green>",
            ))) {
                ConfirmMenu(shop, "<green>${value.name} 을(를) 빌릴까요?</green>", onConfirm = { shop.chests.rent(viewer, value); viewer.closeInventory() }, onCancel = { show() }).open(viewer)
            }
        }
        set(SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    private fun icon(value: ChestShop, product: ChestProduct): ItemStack {
        val base = shop.chests.template(product) ?: ItemStack(Material.BARRIER)
        val currency = shop.chests.currencyOf(product)
        fun money(v: Long) = currency?.format(v) ?: v.toString()
        val lore = ArrayList<String>()
        product.buyPrice?.let { lore += "<green>┃ 구매</green>"; lore += "<dark_gray> » </dark_gray><gray>가격: <white>${money(it)}</white></gray>" }
        product.sellPrice?.let {
            lore += "<red>┃ 판매</red>"; lore += "<dark_gray> » </dark_gray><gray>받음: <white>${money(it)}</white></gray>"
            val have = Inv.count(viewer) { s -> shop.chests.matches(product, s) } / shop.chests.unitOf(product)
            if (have > 0) lore += "<dark_gray> » </dark_gray><gray>전부 팔면: <white>${money(it * have)}</white></gray>"
        }
        if (!value.admin) {
            lore += "<aqua>┃ 재고</aqua>"
            val stock = shop.chests.stock(value, product)
            val capacity = shop.chests.capacity(value)
            val room = if (capacity == Long.MAX_VALUE) "무제한" else "%,d".format((capacity - stock).coerceAtLeast(0))
            lore += "<dark_gray> » </dark_gray><gray>남음: <white>${"%,d".format(stock)}</white> <dark_gray>(+$room)</dark_gray></gray>"
        }
        val state = ProductState.of(product.buyPrice != null, product.sellPrice != null)
        if (state == null) lore += "<red>[ 준비 중 ]</red>"
        else {
            lore += "<blue>➥ 조작</blue>"
            for ((kind, action) in shop.config.chest.clicks.describe(state)) lore += "<dark_gray> » </dark_gray><blue>${kind.label}</blue> <gray>→ ${action.label}</gray>"
        }
        return Icon.annotate(base, lore = lore)
    }

    private fun click(value: ChestShop, product: ChestProduct, event: InventoryClickEvent) {
        val state = ProductState.of(product.buyPrice != null, product.sellPrice != null) ?: return
        val kind = ClickKind.of(event.click) ?: return
        val reopen = { ChestShopMenu(shop, viewer, shopId).show() }
        when (shop.config.chest.clicks.actionFor(state, kind)) {
            ClickAction.OPEN_BUY -> if (product.buyPrice != null) TradeMenu(shop, viewer, ChestTarget(shop, shopId, product.id, TradeType.BUY), reopen).show()
            ClickAction.OPEN_SELL -> if (product.sellPrice != null) TradeMenu(shop, viewer, ChestTarget(shop, shopId, product.id, TradeType.SELL), reopen).show()
            ClickAction.BUY_ONE -> shop.chests.buy(viewer, value, product, 1) { refresh() }
            ClickAction.SELL_ONE -> shop.chests.sell(viewer, value, product, 1) { refresh() }
            ClickAction.SELL_ALL -> shop.chests.sell(viewer, value, product, shop.chests.maxUnits(viewer, value, product, TradeType.SELL)) { refresh() }
            ClickAction.NONE -> Unit
        }
    }

    companion object {
        const val SLOT_INFO = 49
        const val SLOT_RENT = 47
        const val SLOT_CLOSE = 53

        fun titleOf(shop: Shop, id: String): String = "<dark_gray>" + Text.plain(shop.chests.get(id)?.name ?: "상점") + "</dark_gray>"
    }
}

/**
 * 주인(운영자·신뢰하는 사람) 관리 화면. 위 네 줄 = 상품(클릭 → 상품 편집). **가방의 물건을 클릭하면** 상품으로 올린다.
 */
class ChestManageMenu(shop: Shop, viewer: Player, private val shopId: String) : Menu(shop, viewer, 54, "<dark_gray>상점 관리 — " + Text.plain(shop.chests.get(shopId)?.name ?: "") + "</dark_gray>") {

    override fun acceptsShiftInsert(): Boolean = false

    override fun handleClick(event: InventoryClickEvent) {
        // 아래(자기 가방)를 누르면 그 물건을 상품으로 올린다.
        if (event.rawSlot >= size && event.clickedInventory == viewer.inventory) {
            event.isCancelled = true
            val stack = event.currentItem?.takeIf { !it.type.isAir } ?: return
            val value = shop.chests.get(shopId) ?: return
            val product = shop.chests.addProduct(viewer, value, stack) ?: return
            ChestProductMenu(shop, viewer, shopId, product.id).askPrices()
            return
        }
        super.handleClick(event)
    }

    override fun draw() {
        clear()
        val value = shop.chests.get(shopId) ?: return viewer.closeInventory()
        val now = System.currentTimeMillis()
        for ((index, product) in value.products.take(SLOT_PRODUCTS).withIndex()) {
            val base = shop.chests.template(product) ?: ItemStack(Material.BARRIER)
            val currency = shop.chests.currencyOf(product)
            set(index, Icon.annotate(base, lore = listOf(
                "<gray>구매가: <white>" + (product.buyPrice?.let { currency?.format(it) ?: it.toString() } ?: "꺼짐") + "</white></gray>",
                "<gray>판매가: <white>" + (product.sellPrice?.let { currency?.format(it) ?: it.toString() } ?: "꺼짐") + "</white></gray>",
                "<gray>창고: <white>" + (if (value.admin) "무한" else "%,d".format(shop.chests.stock(value, product))) + "</white></gray>",
                "", "<yellow>▶ 클릭해서 편집(가격·넣기·회수·내리기)</yellow>",
            ))) { ChestProductMenu(shop, viewer, shopId, product.id).show() }
        }
        for (slot in value.products.size until SLOT_PRODUCTS) set(slot, Icon.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>빈 칸</gray>", "<gray>아래 가방의 물건을 클릭하면 상품으로 올립니다.</gray>"))
        for (slot in SLOT_PRODUCTS until size) set(slot, Icon.FILLER)

        set(SLOT_NAME, valueIcon(Material.NAME_TAG, "이름", value.name)) {
            val form = DialogForm("<yellow>상점 이름</yellow>").text("name", "이름(최대 ${shop.config.chest.maxNameLength}자)", value.name, maxLength = shop.config.chest.maxNameLength)
            ask(form) { v -> v.text("name").trim().takeIf { it.isNotEmpty() }?.let { n -> shop.chests.update(shopId) { it.copy(name = n) } } }
        }
        if (shop.config.chest.bank) set(SLOT_BANK, Icon.of(Material.GOLD_INGOT, "<gold>은행</gold>", "<gray>판매 수익이 들어갑니다. 넣고 뺄 수 있습니다.</gray>", "", "<yellow>▶ 클릭</yellow>")) {
            BankMenu(shop, viewer, value.operator(now), back = { show() }).show()
        }
        set(SLOT_DISPLAY, Icon.of(Material.GLASS, "<aqua>표시</aqua>", "<gray>홀로그램: </gray>" + Icon.toggle(value.hologram), "<gray>쇼케이스: <white>${value.showcase ?: "없음"}</white></gray>", "", "<yellow>▶ 클릭</yellow>")) {
            if (!viewer.hasPermission("inmcshop.chestshop.display.customization")) return@set shop.messages.send(viewer, "no-permission")
            DisplayMenu(shop, viewer, shopId).show()
        }
        if (shop.config.chest.rent && viewer.uniqueId == value.owner) set(SLOT_RENT, Icon.of(Material.CLOCK, "<gold>임대</gold>", buildList {
            add("<gray>임대: </gray>" + Icon.toggle(value.rent.enabled))
            if (value.rent.active(now)) add("<gray>빌린 사람: <white>${value.rent.renterName}</white> · 끝: ${Durations.formatShort((value.rent.until - now) / 1000)} 뒤</gray>")
            add(""); add("<yellow>▶ 클릭</yellow>")
        })) {
            if (!viewer.hasPermission("inmcshop.chestshop.rent")) return@set shop.messages.send(viewer, "no-permission")
            RentMenu(shop, viewer, shopId).show()
        }
        if (value.rent.active(now) && value.rent.renter == viewer.uniqueId) set(SLOT_RENT, Icon.of(Material.CLOCK, "<gold>임대 연장</gold>", "<gray>끝: ${Durations.formatShort((value.rent.until - now) / 1000)} 뒤</gray>", "", "<yellow>▶ 클릭해서 ${value.rent.days}일 연장</yellow>")) {
            shop.chests.rent(viewer, value); refresh()
        }
        set(SLOT_TRUSTED, Icon.of(Material.PLAYER_HEAD, "<light_purple>신뢰하는 사람</light_purple>", "<gray>${value.trusted.size}명 — 이 상점을 관리할 수 있습니다.</gray>", "", "<yellow>▶ 클릭</yellow>")) {
            if (!value.canManage(viewer.uniqueId) && !viewer.hasPermission("inmcshop.chestshop.edit.others")) return@set
            TrustedMenu(shop, viewer, shopId).show()
        }
        if (viewer.hasPermission("inmcshop.chestshop.type.admin")) set(SLOT_ADMIN, toggleIcon("관리자 상점(재고·돈 무한)", value.admin)) {
            shop.chests.update(shopId) { it.copy(admin = !it.admin) }; refresh()
        }
        set(SLOT_PREVIEW, Icon.of(Material.SPYGLASS, "<white>손님 화면 보기</white>")) { ChestShopMenu(shop, viewer, shopId).show() }
        set(SLOT_RETURNS, Icon.of(Material.CHEST_MINECART, "<white>돌려받을 물건</white>", "<gray>가방이 차서 못 받은 것</gray>", "", "<yellow>▶ 클릭해서 받기</yellow>")) {
            shop.returns.claim(viewer) { got, left -> shop.messages.send(viewer, if (got + left == 0) "returns-none" else "returns-claimed", Ph.of().count(got).value(left.toString())) }
        }
        if (viewer.uniqueId == value.owner || viewer.hasPermission("inmcshop.chestshop.remove.others")) set(SLOT_REMOVE, Icon.of(Material.LAVA_BUCKET, "<red>상점 지우기</red>", "<gray>창고에 남은 물건은 돌려받습니다.</gray>")) {
            if (value.rent.active(now) && viewer.uniqueId == value.owner) return@set shop.messages.send(viewer, "rent-active")
            ConfirmMenu(shop, "<red>이 상점을 지울까요?</red>", onConfirm = { shop.chests.remove(value, viewer); viewer.closeInventory() }, onCancel = { show() }).open(viewer)
        }
        set(SLOT_HELP, Icon.of(Material.BOOK, "<yellow>상품 올리기</yellow>", "<gray>아래 가방의 물건을 클릭하면 그 개수가 한 단위인 상품이 됩니다.</gray>", "<gray>그다음 가격을 적고, 상품 편집에서 창고에 넣으세요.</gray>"))
        set(SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    companion object {
        const val SLOT_PRODUCTS = 36
        const val SLOT_NAME = 36
        const val SLOT_BANK = 37
        const val SLOT_DISPLAY = 38
        const val SLOT_RENT = 39
        const val SLOT_TRUSTED = 40
        const val SLOT_ADMIN = 41
        const val SLOT_PREVIEW = 42
        const val SLOT_RETURNS = 43
        const val SLOT_REMOVE = 44
        const val SLOT_HELP = 49
        const val SLOT_CLOSE = 53
    }
}

/** 상품 하나 — 가격·화폐 · 창고에 넣기 · 회수 · 내리기. */
class ChestProductMenu(shop: Shop, viewer: Player, private val shopId: String, private val productId: String) :
    Menu(shop, viewer, 27, "<dark_gray>상품 편집</dark_gray>") {

    override val back: (() -> Unit) = { ChestManageMenu(shop, viewer, shopId).show() }

    private fun value() = shop.chests.get(shopId)
    private fun product() = value()?.product(productId)

    /** 가격 입력창 — 올린 직후에도 부른다. */
    fun askPrices() {
        val product = product() ?: return back()
        val settings = shop.config.chest
        val currencies = Currencies.all().filter { settings.currency.allows(it.id) }
        val template = shop.chests.template(product)
        val unit = shop.chests.unitOf(product)
        val form = DialogForm("<yellow>가격·단위</yellow>")
            .line("<gray>비우면 그 방향은 꺼집니다. 최고 ${"%,d".format(settings.maxPrice)}.</gray>")
            .long("unit", "한 단위(개) — 한 번에 사고파는 개수", unit.toLong(), 1, (template?.maxStackSize ?: 64).toLong())
            .long("buy", "손님이 사는 값(한 단위)", product.buyPrice, min = 0, max = settings.maxPrice, optional = true)
            .long("sell", "손님이 파는 값(한 단위)", product.sellPrice, min = 0, max = settings.maxPrice, optional = true)
            .choice("currency", "화폐", currencies.map { it.id to it.name }, product.currency.ifEmpty { settings.currency.default.ifEmpty { Currencies.default()?.id } })
        form.show(shop.plugin, viewer, onCancel = { show() }) { _, v ->
            val buy = v.long("buy")
            val sell = v.long("sell")
            if (buy == null && product.buyPrice != null && !viewer.hasPermission("inmcshop.chestshop.disable.buying")) shop.messages.send(viewer, "no-permission")
            else if (sell == null && product.sellPrice != null && !viewer.hasPermission("inmcshop.chestshop.disable.selling")) shop.messages.send(viewer, "no-permission")
            else value()?.let {
                // 창고는 개수로 세므로 단위를 바꿔도 남은 물건은 그대로다.
                val newUnit = v.long("unit")?.toInt() ?: unit
                val item = if (template != null && newUnit != unit) Inv.encode(template.apply { amount = newUnit }) else product.item
                shop.chests.updateProduct(it, product.copy(item = item, buyPrice = buy, sellPrice = sell, currency = v.choice("currency").orEmpty()))
            }
            show()
        }
    }

    override fun draw() {
        clear()
        val value = value() ?: return viewer.closeInventory()
        val product = product() ?: return back()
        val currency = shop.chests.currencyOf(product)
        set(4, Icon.annotate(shop.chests.template(product) ?: ItemStack(Material.BARRIER), lore = listOf("<gray>한 단위: <white>${shop.chests.unitOf(product)}개</white> <dark_gray>(가격·단위에서 바꿈)</dark_gray></gray>")))
        set(SLOT_PRICE, Icon.of(Material.GOLD_NUGGET, "<yellow>가격·단위·화폐</yellow>", listOf(
            "<gray>한 단위: <white>${shop.chests.unitOf(product)}개</white></gray>",
            "<gray>구매가: <white>" + (product.buyPrice?.let { currency?.format(it) ?: it.toString() } ?: "꺼짐") + "</white></gray>",
            "<gray>판매가: <white>" + (product.sellPrice?.let { currency?.format(it) ?: it.toString() } ?: "꺼짐") + "</white></gray>",
            "<gray>화폐: <white>" + (currency?.name ?: "?") + "</white></gray>", "", "<yellow>▶ 클릭</yellow>",
        ))) { askPrices() }
        if (!value.admin) {
            val stock = shop.chests.stock(value, product)
            set(SLOT_DEPOSIT, Icon.of(Material.HOPPER, "<green>창고에 넣기</green>", listOf(
                "<gray>창고: <white>${"%,d".format(stock)}</white> / ${shop.chests.capacity(value).let { if (it == Long.MAX_VALUE) "무제한" else "%,d".format(it) }}</gray>",
                "", "<green>좌클릭</green> <gray>가진 것 전부</gray>", "<green>우클릭</green> <gray>개수 적기</gray>",
            ))) { event ->
                if (event.isLeftClick) { shop.chests.deposit(viewer, value, product, 0); refresh() }
                else ask(DialogForm("<green>넣을 개수</green>").long("n", "개수", 64, min = 1)) { v -> shop.chests.deposit(viewer, value, product, (v.long("n") ?: 0).toInt()) }
            }
            set(SLOT_WITHDRAW, Icon.of(Material.CHEST, "<gold>회수</gold>", listOf(
                "<gray>창고: <white>${"%,d".format(stock)}</white></gray>",
                "", "<gold>좌클릭</gold> <gray>전부</gray>", "<gold>우클릭</gold> <gray>개수 적기</gray>",
            ))) { event ->
                if (event.isLeftClick) { shop.chests.withdraw(viewer, value, product, 0); refresh() }
                else ask(DialogForm("<gold>뺄 개수</gold>").long("n", "개수", 64, min = 1)) { v -> shop.chests.withdraw(viewer, value, product, (v.long("n") ?: 0).toInt()) }
            }
        }
        set(SLOT_REMOVE, Icon.of(Material.BARRIER, "<red>상품 내리기</red>", "<gray>창고의 이 물건은 돌려받습니다.</gray>")) {
            ConfirmMenu(shop, "<red>이 상품을 내릴까요?</red>", onConfirm = { shop.chests.removeProduct(viewer, value, product); back() }, onCancel = { show() }).open(viewer)
        }
        fillEmpty(Icon.FILLER)
        set(18, Icon.back()) { back() }
        set(26, Icon.close()) { viewer.closeInventory() }
    }

    companion object {
        const val SLOT_PRICE = 10
        const val SLOT_DEPOSIT = 12
        const val SLOT_WITHDRAW = 14
        const val SLOT_REMOVE = 16
    }
}

/** 상점 은행 — 화폐마다 잔고, 넣기·빼기(입력창). */
class BankMenu(shop: Shop, viewer: Player, private val holder: java.util.UUID, override val back: (() -> Unit)?) : Menu(shop, viewer, 27, "<dark_gray>상점 은행</dark_gray>") {

    private var balances: Map<String, Long>? = null

    override fun draw() {
        clear()
        val known = balances
        if (known == null) {
            set(13, Icon.of(Material.CLOCK, "<gray>불러오는 중…</gray>"))
            shop.bank.balances(holder) { balances = it; if (viewer.openInventory.topInventory == inventory) refresh() }
            fillEmpty(Icon.FILLER)
            navigation(18, 26)
            return
        }
        val currencies = Currencies.all().filter { shop.config.chest.currency.allows(it.id) }
        for ((index, currency) in currencies.take(9).withIndex()) {
            val amount = known[currency.id] ?: 0
            set(9 + index, Icon.of(Material.GOLD_INGOT, currency.name, listOf(
                "<gray>은행: <white>${currency.format(amount)}</white></gray>",
                "<gray>지갑: <white>${currency.format(currency.balance(viewer))}</white></gray>",
                "", "<gold>좌클릭</gold> <gray>은행 → 지갑</gray>", "<gold>우클릭</gold> <gray>지갑 → 은행</gray>",
            ))) { event ->
                if (holder != viewer.uniqueId && !viewer.hasPermission("inmcshop.chestshop.command.bank.others")) return@set
                val out = event.isLeftClick
                ask(DialogForm(if (out) "<gold>은행에서 빼기</gold>" else "<gold>은행에 넣기</gold>").long("n", currency.name, if (out) amount else null, min = 1)) { v ->
                    val n = v.long("n") ?: return@ask
                    if (out) {
                        shop.bank.take(holder, currency.id, n) { ok ->
                            if (!ok) shop.messages.send(viewer, "bank-not-enough")
                            else if (!currency.deposit(viewer, n, "inmcshop:bank-out")) { shop.bank.deposit(holder, currency.id, n); shop.messages.send(viewer, "deposit-failed") }
                            else shop.messages.send(viewer, "bank-withdrew", Ph.of().price(currency.format(n)))
                            balances = null; if (viewer.isOnline) show()
                        }
                    } else {
                        if (!currency.withdraw(viewer, n, "inmcshop:bank-in")) shop.messages.send(viewer, "not-enough-money", Ph.of().price(currency.format(n)))
                        else { shop.bank.deposit(holder, currency.id, n); shop.messages.send(viewer, "bank-deposited", Ph.of().price(currency.format(n))) }
                        balances = null
                    }
                }
            }
        }
        fillEmpty(Icon.FILLER)
        navigation(18, 26)
    }
}

/** 표시 — 홀로그램 · 쇼케이스 블록. */
class DisplayMenu(shop: Shop, viewer: Player, private val shopId: String) : Menu(shop, viewer, 54, "<dark_gray>표시</dark_gray>") {

    override val back: (() -> Unit) = { ChestManageMenu(shop, viewer, shopId).show() }

    override fun draw() {
        clear()
        val value = shop.chests.get(shopId) ?: return viewer.closeInventory()
        set(4, toggleIcon("홀로그램", value.hologram)) { shop.chests.update(shopId) { it.copy(hologram = !it.hologram) }; refresh() }
        set(8, Icon.of(Material.BARRIER, "<red>쇼케이스 없음</red>")) { shop.chests.update(shopId) { it.copy(showcase = null) }; refresh() }
        for ((index, material) in SHOWCASES.withIndex()) {
            val slot = 18 + index
            if (slot >= 45) break
            val chosen = value.showcase == material.name
            set(slot, Icon.of(material, (if (chosen) "<green>▶ " else "<white>") + material.name.lowercase().replace('_', ' '), if (chosen) "<green>지금 쓰는 것</green>" else "<gray>클릭해서 고르기</gray>")) {
                shop.chests.update(shopId) { it.copy(showcase = material.name) }; refresh()
            }
        }
        fillEmpty(Icon.FILLER)
        navigation()
    }

    companion object {
        val SHOWCASES: List<Material> = listOf(
            Material.GLASS, Material.TINTED_GLASS, Material.ICE, Material.WHITE_STAINED_GLASS, Material.LIGHT_GRAY_STAINED_GLASS, Material.GRAY_STAINED_GLASS,
            Material.BLACK_STAINED_GLASS, Material.RED_STAINED_GLASS, Material.ORANGE_STAINED_GLASS, Material.YELLOW_STAINED_GLASS, Material.LIME_STAINED_GLASS,
            Material.GREEN_STAINED_GLASS, Material.CYAN_STAINED_GLASS, Material.LIGHT_BLUE_STAINED_GLASS, Material.BLUE_STAINED_GLASS, Material.PURPLE_STAINED_GLASS,
            Material.MAGENTA_STAINED_GLASS, Material.PINK_STAINED_GLASS, Material.BROWN_STAINED_GLASS, Material.COPPER_GRATE, Material.EXPOSED_COPPER_GRATE,
            Material.WEATHERED_COPPER_GRATE, Material.OXIDIZED_COPPER_GRATE,
        )
    }
}

/** 임대 설정(주인). */
class RentMenu(shop: Shop, viewer: Player, private val shopId: String) : Menu(shop, viewer, 27, "<dark_gray>임대</dark_gray>") {

    override val back: (() -> Unit) = { ChestManageMenu(shop, viewer, shopId).show() }

    override fun draw() {
        clear()
        val value = shop.chests.get(shopId) ?: return viewer.closeInventory()
        val now = System.currentTimeMillis()
        val settings = shop.config.chest
        val currency = shop.currency(value.rent.currency, settings.currency)
        set(10, toggleIcon("임대", value.rent.enabled, "<gray>켜면 다른 사람이 돈을 내고 기간 동안 운영합니다.</gray>", "<gray>상품이 비어 있어야 빌려줄 수 있습니다.</gray>")) {
            shop.chests.update(shopId) { it.copy(rent = it.rent.copy(enabled = !it.rent.enabled)) }; refresh()
        }
        set(12, Icon.of(Material.GOLD_INGOT, "<yellow>기간·임대료</yellow>", listOf(
            "<gray>기간: <white>${value.rent.days}일</white> <dark_gray>(최대 ${settings.rentMaxDays}일)</dark_gray></gray>",
            "<gray>임대료: <white>${currency?.format(value.rent.price) ?: value.rent.price}</white></gray>", "", "<yellow>▶ 클릭</yellow>",
        ))) {
            val currencies = Currencies.all().filter { settings.currency.allows(it.id) }
            val form = DialogForm("<yellow>임대 조건</yellow>")
                .long("days", "기간(일)", value.rent.days.toLong(), min = 1, max = settings.rentMaxDays.toLong())
                .long("price", "임대료", value.rent.price, min = 0)
                .choice("currency", "화폐", currencies.map { it.id to it.name }, value.rent.currency.ifEmpty { settings.currency.default.ifEmpty { Currencies.default()?.id } })
            ask(form) { v ->
                val cur = v.choice("currency").orEmpty()
                val price = v.long("price") ?: 0
                val max = settings.rentMaxPrice[cur]
                if (max != null && price > max) return@ask shop.messages.send(viewer, "rent-price-too-high", Ph.of().price(shop.currency(cur, settings.currency)?.format(max) ?: max.toString()))
                shop.chests.update(shopId) { it.copy(rent = it.rent.copy(days = (v.long("days") ?: 7).toInt(), price = price, currency = cur)) }
            }
        }
        if (value.rent.active(now)) set(14, Icon.of(Material.BARRIER, "<red>임대 끝내기</red>", "<gray>빌린 사람: <white>${value.rent.renterName}</white></gray>", "<gray>그 사람의 창고 물건은 돌려줍니다. 임대료는 돌려주지 않습니다.</gray>")) {
            ConfirmMenu(shop, "<red>임대를 끝낼까요?</red>", onConfirm = { shop.chests.endRent(value, "주인이 임대를 끝냄"); show() }, onCancel = { show() }).open(viewer)
        }
        fillEmpty(Icon.FILLER)
        navigation(18, 26)
    }
}

/** 신뢰하는 사람 — 이 상점을 같이 관리한다. */
class TrustedMenu(shop: Shop, viewer: Player, private val shopId: String) : Menu(shop, viewer, 54, "<dark_gray>신뢰하는 사람</dark_gray>") {

    override val back: (() -> Unit) = { ChestManageMenu(shop, viewer, shopId).show() }

    override fun draw() {
        clear()
        val value = shop.chests.get(shopId) ?: return viewer.closeInventory()
        for ((index, id) in value.trusted.take(45).withIndex()) {
            val head = ItemStack(Material.PLAYER_HEAD).apply { editMeta(SkullMeta::class.java) { it.owningPlayer = Bukkit.getOfflinePlayer(id) } }
            set(index, Icon.relabel(head, "<white>" + shop.nameOf(id) + "</white>", listOf("<red>클릭해서 빼기</red>"))) {
                shop.chests.update(shopId) { it.copy(trusted = it.trusted - id) }; refresh()
            }
        }
        set(49, Icon.of(Material.LIME_DYE, "<green>사람 더하기</green>")) {
            ask(DialogForm("<green>신뢰하는 사람</green>").text("name", "플레이어 이름", "")) { v ->
                val id = shop.findPlayer(v.text("name").trim()) ?: return@ask shop.messages.send(viewer, "player-not-found", Ph.of().player(v.text("name")))
                shop.chests.update(shopId) { it.copy(trusted = it.trusted + id) }
            }
        }
        fillEmpty(Icon.FILLER)
        navigation()
    }
}

/** 상점 목록 — 내 상점·전체·어떤 주인의 것·검색 결과. 누르면 이동(권한) 또는 관리. */
class ChestListMenu(
    shop: Shop,
    viewer: Player,
    title: String,
    private val entries: () -> List<Pair<ChestShop, ChestProduct?>>,
    override val back: (() -> Unit)? = null,
) : Menu(shop, viewer, 54, title) {

    private var page = 0

    override fun draw() {
        clear()
        val list = entries()
        page = Paging.clamp(page, list.size)
        for ((slot, entry) in Paging.slice(list, page).withIndex()) {
            val (value, product) = entry
            val base = product?.let { shop.chests.template(it) } ?: value.products.firstOrNull()?.let { shop.chests.template(it) } ?: ItemStack(Material.CHEST)
            val lore = buildList {
                add("<gray>운영: <white>${value.operatorName()}</white></gray>")
                add("<gray>위치: <white>${value.key.world} ${value.key.x}, ${value.key.y}, ${value.key.z}</white></gray>")
                if (product != null) {
                    val currency = shop.chests.currencyOf(product)
                    product.buyPrice?.let { add("<green>구매 ${currency?.format(it) ?: it}</green>") }
                    product.sellPrice?.let { add("<red>판매 ${currency?.format(it) ?: it}</red>") }
                    if (!value.admin) add("<gray>재고 ${"%,d".format(shop.chests.stock(value, product))}</gray>")
                } else add("<gray>상품 ${value.products.size}개</gray>")
                add("")
                if (value.canManage(viewer.uniqueId)) add("<yellow>좌클릭</yellow> <gray>관리</gray>")
                add("<yellow>" + (if (value.canManage(viewer.uniqueId)) "우클릭" else "클릭") + "</yellow> <gray>이동</gray>")
            }
            set(slot, Icon.relabel(base.clone().apply { amount = 1 }, "<yellow>" + value.name + "</yellow>", lore)) { event ->
                if (value.canManage(viewer.uniqueId) && event.isLeftClick) return@set ChestManageMenu(shop, viewer, value.id).show()
                teleport(value)
            }
        }
        if (list.isEmpty()) set(22, Icon.of(Material.BARRIER, "<red>없습니다</red>"))
        fillEmpty(Icon.FILLER)
        pager(page, list.size) { page = it; refresh() }
        navigation()
    }

    private fun teleport(value: ChestShop) {
        val own = value.operator() == viewer.uniqueId
        if (!viewer.hasPermission(if (own) "inmcshop.chestshop.teleport" else "inmcshop.chestshop.teleport.others")) return shop.messages.send(viewer, "no-permission")
        val block = value.key.block() ?: return
        val target = block.location.add(0.5, 1.0, 0.5)
        if (shop.config.chest.safeTeleport && !safe(target)) return shop.messages.send(viewer, "chest-teleport-unsafe")
        viewer.closeInventory()
        viewer.teleportAsync(target)
        shop.messages.send(viewer, "chest-teleported", Ph.of().shop(value.name))
    }

    private fun safe(location: org.bukkit.Location): Boolean {
        val feet = location.block
        val head = feet.getRelative(0, 1, 0)
        return feet.isPassable && head.isPassable && !feet.isLiquid && !head.isLiquid
    }
}

/** 둘러보기 — 운영자(머리) 목록 → 그 사람의 상점들. */
class OwnerBrowseMenu(shop: Shop, viewer: Player) : Menu(shop, viewer, 54, "<dark_gray>플레이어 상점 둘러보기</dark_gray>") {

    private var page = 0

    override fun draw() {
        clear()
        val owners = shop.chests.byOperator().entries.sortedByDescending { it.value.size }.map { it.key to it.value }
        page = Paging.clamp(page, owners.size)
        for ((slot, entry) in Paging.slice(owners, page).withIndex()) {
            val (id, shops) = entry
            val head = ItemStack(Material.PLAYER_HEAD).apply { editMeta(SkullMeta::class.java) { it.owningPlayer = Bukkit.getOfflinePlayer(id) } }
            set(slot, Icon.relabel(head, "<yellow>" + shop.nameOf(id) + "</yellow>", listOf("<gray>상점 ${shops.size}개</gray>", "", "<yellow>▶ 클릭</yellow>"))) {
                val name = shop.nameOf(id)
                ChestListMenu(shop, viewer, "<dark_gray>$name 의 상점</dark_gray>", { shop.chests.all().filter { it.operator() == id }.map { it to null } }, back = { show() }).show()
            }
        }
        if (owners.isEmpty()) set(22, Icon.of(Material.BARRIER, "<red>상점이 없습니다</red>"))
        fillEmpty(Icon.FILLER)
        set(49, Icon.of(Material.COMPASS, "<yellow>물건으로 찾기</yellow>", "<gray>손에 든 물건 또는 이름으로</gray>")) {
            search(shop, viewer) { show() }
        }
        pager(page, owners.size) { page = it; refresh() }
        navigation()
    }

    companion object {
        /** 손에 물건이 있으면 그것, 없으면 이름을 묻는다. */
        fun search(shop: Shop, viewer: Player, back: (() -> Unit)?) {
            val hand = viewer.inventory.itemInMainHand.takeIf { !it.type.isAir }
            if (hand != null) {
                val item = hand.clone()
                ChestListMenu(shop, viewer, "<dark_gray>검색 결과</dark_gray>", { shop.chests.search(null, item) }, back).show()
                return
            }
            DialogForm("<yellow>물건 찾기</yellow>").line("<gray>아이템 이름(붙인 이름) 또는 영어 재질 이름(diamond 등). 손에 들고 부르면 그 물건으로 찾습니다.</gray>")
                .text("q", "찾을 것", "")
                .show(shop.plugin, viewer, onCancel = { back?.invoke() }) { _, v ->
                    val q = v.text("q")
                    ChestListMenu(shop, viewer, "<dark_gray>검색: $q</dark_gray>", { shop.chests.search(q, null) }, back).show()
                }
        }
    }
}
