package com.inmc.shop.gui

import com.inmc.shop.Shop
import com.inmc.shop.price.Market
import com.inmc.shop.price.Pricing
import com.inmc.shop.trade.ClickAction
import com.inmc.shop.trade.ClickKind
import com.inmc.shop.trade.ProductState
import com.inmc.shop.trade.TradeType
import com.inmc.shop.util.Inv
import com.inmc.shop.util.Ph
import com.inmc.shop.virtual.Layout
import com.inmc.shop.virtual.LayoutButton
import com.inmc.shop.virtual.Product
import com.inmc.shop.virtual.ProductType
import com.inmc.shop.virtual.VirtualShop
import kr.inmc.core.economy.Currencies
import kr.inmc.core.economy.Currency
import kr.inmc.core.gui.Icon
import kr.inmc.core.util.Durations
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack

/** 서버 상점 상품을 구매·판매 화면에 넘기는 어댑터. */
class VirtualTarget(private val shop: Shop, private val product: Product, override val type: TradeType) : TradeTarget {
    private val vshop get() = shop.shops.get(product.shopId)
    override val title: String get() = "<dark_gray>" + (vshop?.name ?: "") + " · " + type.label + "</dark_gray>"
    override fun icon(): ItemStack = Products.baseIcon(shop, product)
    override fun label(): String = shop.trades.label(product)
    override fun unit(): Int = product.unit
    override fun currency(): Currency? = shop.trades.currencyOf(product)
    override fun total(player: Player, units: Long): Long? {
        val price = shop.prices.price(product, type) ?: return null
        return if (type == TradeType.BUY) price * units else Math.floor(price * units * shop.trades.sellMultiplier(player)).toLong()
    }
    override fun maxUnits(player: Player): Long = shop.trades.maxUnits(player, product, type)
    override fun execute(player: Player, units: Long, then: (Boolean) -> Unit) {
        if (type == TradeType.BUY) shop.trades.buy(player, product, units) { then(it != null) }
        else shop.trades.sell(player, product, units) { then(it != null) }
    }
}

/** 상품 아이콘과 설명 — 상태(구매만/판매만/둘 다)마다 다르다. */
object Products {

    fun baseIcon(shop: Shop, product: Product): ItemStack {
        val stack = when (product.type) {
            ProductType.ITEM -> shop.trades.template(product)
            ProductType.COMMAND -> (product.preview ?: product.item)?.let { shop.resolver.create(it, 1) }
        } ?: ItemStack(Material.BARRIER)
        stack.amount = product.unit.coerceIn(1, 99)
        if (!product.name.isNullOrBlank() || product.lore.isNotEmpty()) {
            return Icon.relabel(stack, product.name?.takeIf { it.isNotBlank() }, product.lore)
        }
        return stack
    }

    fun state(shop: Shop, product: Product, vshop: VirtualShop): ProductState? =
        ProductState.of(
            vshop.buying && shop.prices.price(product, TradeType.BUY) != null,
            vshop.selling && shop.prices.price(product, TradeType.SELL) != null,
        )

    fun icon(shop: Shop, player: Player, product: Product, vshop: VirtualShop): ItemStack {
        val base = baseIcon(shop, product)
        val state = state(shop, product, vshop)
        val lore = ArrayList<String>()
        if (state == null) {
            lore += "<red>[ 준비 중 ]</red>"
            return Icon.annotate(base, lore = lore)
        }
        val currency = shop.trades.currencyOf(product)
        fun money(v: Long) = currency?.format(v) ?: v.toString()
        val market = product.pricing is Pricing.Market
        if (state != ProductState.SELLABLE) {
            val price = shop.prices.price(product, TradeType.BUY)!!
            lore += "<green>┃ 구매</green>"
            lore += "<dark_gray> » </dark_gray><gray>가격: <white>${money(price)}</white>" + trend(shop, product, TradeType.BUY) + "</gray>"
        }
        if (state != ProductState.BUYABLE) {
            val price = shop.prices.price(product, TradeType.SELL)!!
            val multiplier = shop.trades.sellMultiplier(player)
            lore += "<red>┃ 판매</red>"
            lore += "<dark_gray> » </dark_gray><gray>받음: <white>${money(Math.floor(price * multiplier).toLong())}</white>" + trend(shop, product, TradeType.SELL) + "</gray>" +
                if (multiplier != 1.0) " <gold>(x${"%.2f".format(multiplier).trimEnd('0').trimEnd('.')})</gold>" else ""
            if (product.type == ProductType.ITEM) {
                val have = Inv.count(player) { shop.trades.matches(product, it) } / product.unit
                if (have > 0) lore += "<dark_gray> » </dark_gray><gray>전부 팔면: <white>${money(Math.floor(price * have * multiplier).toLong())}</white> <dark_gray>(${have * product.unit}개)</dark_gray></gray>"
            }
        }
        if (market) {
            val activity = Market.activity(shop.prices.state(product), shop.config.virtual.market)
            lore += "<dark_gray> » </dark_gray><gray>시장: <white>${activity.label}</white>" + if (activity == com.inmc.shop.price.Activity.QUIET) " <dark_gray>(한산하면 값이 오릅니다)</dark_gray></gray>" else "</gray>"
        }
        if (product.stock.enabled) {
            val stock = shop.stocks.state(product)
            lore += "<aqua>┃ 재고</aqua>"
            lore += "<dark_gray> » </dark_gray><gray>남음: <white>${"%,d".format(stock?.units ?: 0)}</white>/<white>${"%,d".format(product.stock.capacity)}</white></gray>"
            if (stock != null && product.stock.restockSeconds > 0 && stock.restockAt > 0) {
                lore += "<dark_gray> » </dark_gray><gray>다시 채움: <white>${Durations.formatShort(((stock.restockAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0))}</white></gray>"
            }
        }
        if (product.limits.enabled && (product.limits.buy >= 0 || product.limits.sell >= 0)) {
            lore += "<gold>┃ 한도</gold>"
            if (product.limits.buy >= 0) lore += "<dark_gray> » </dark_gray><gray>구매: <gold>${shop.limits.used(player.uniqueId, product, TradeType.BUY)}/${product.limits.buy}</gold></gray>"
            if (product.limits.sell >= 0) lore += "<dark_gray> » </dark_gray><gray>판매: <gold>${shop.limits.used(player.uniqueId, product, TradeType.SELL)}/${product.limits.sell}</gold></gray>"
            val reset = shop.limits.resetAt(player.uniqueId, product)
            if (product.limits.resetSeconds > 0 && reset > 0) lore += "<dark_gray> » </dark_gray><gray>초기화: <white>${Durations.formatShort(((reset - System.currentTimeMillis()) / 1000).coerceAtLeast(0))}</white></gray>"
            else if (product.limits.resetSeconds < 0) lore += "<dark_gray> » </dark_gray><gray>평생 한 번</gray>"
        }
        if (!product.requirements.allows(player::hasPermission)) lore += "<red>✖ 조건이 맞지 않아 사고팔 수 없습니다</red>"
        lore += "<blue>➥ 조작</blue>"
        for ((kind, action) in shop.config.virtual.clicks.describe(state)) lore += "<dark_gray> » </dark_gray><blue>${kind.label}</blue> <gray>→ ${action.label}</gray>"
        return Icon.annotate(base, lore = lore)
    }

    private fun trend(shop: Shop, product: Product, type: TradeType): String {
        val t = shop.prices.trend(product, type) ?: return ""
        return if (t > 0) " <green>↑ ${"%.1f".format(t)}%</green>" else " <red>↓ ${"%.1f".format(-t)}%</red>"
    }

    /** 클릭 → 동작. 상점 화면(서버 상점)이 쓴다. */
    fun click(shop: Shop, player: Player, product: Product, vshop: VirtualShop, event: InventoryClickEvent, reopen: () -> Unit) {
        val state = state(shop, product, vshop) ?: return shop.messages.send(player, "product-not-ready")
        val kind = ClickKind.of(event.click) ?: return
        when (shop.config.virtual.clicks.actionFor(state, kind)) {
            ClickAction.OPEN_BUY -> if (state != ProductState.SELLABLE) TradeMenu(shop, player, VirtualTarget(shop, product, TradeType.BUY), reopen).show()
            ClickAction.OPEN_SELL -> if (state != ProductState.BUYABLE) TradeMenu(shop, player, VirtualTarget(shop, product, TradeType.SELL), reopen).show()
            ClickAction.BUY_ONE -> shop.trades.buy(player, product, 1) { if (player.isOnline) reopen() }
            ClickAction.SELL_ONE -> shop.trades.sell(player, product, 1) { if (player.isOnline) reopen() }
            ClickAction.SELL_ALL -> shop.trades.sellAll(player, product) { if (player.isOnline) reopen() }
            ClickAction.NONE -> Unit
        }
    }
}

/** 메인 메뉴 — 모든 서버 상점을 한 화면에. 칸은 상점 편집에서 정한다. */
class MainMenu(shop: Shop, viewer: Player) : Menu(shop, viewer, shop.layouts.mainMenu.size, render(shop, shop.layouts.mainMenu)) {

    override fun draw() {
        clear()
        val layout = shop.layouts.mainMenu
        decorate(layout)
        for (vshop in shop.shops.all()) {
            if (vshop.menuSlot !in 0 until size || vshop.menuSlot in layout.buttons.values) continue
            val canOpen = shop.trades.canOpen(viewer, vshop)
            if (!canOpen && shop.config.virtual.hideNoPermission) continue
            set(vshop.menuSlot, shopIcon(shop, vshop, canOpen)) {
                if (!canOpen) return@set shop.messages.send(viewer, "shop-no-permission")
                ShopMenu(shop, viewer, vshop.id, 0).show()
            }
        }
        buttons(layout, null)
    }

    private fun decorate(layout: Layout) {
        for ((slot, raw) in layout.decorations) Inv.decode(raw)?.let { set(slot, it) }
    }

    private fun buttons(layout: Layout, onlyShop: String?) {
        for ((button, slot) in layout.buttons) when (button) {
            LayoutButton.BALANCE -> set(slot, balanceIcon(shop, viewer))
            LayoutButton.SELL_ALL -> if (viewer.hasPermission("inmcshop.key.sellall")) set(slot, sellAllIcon()) { shop.sell.sellEverything(viewer, listOf(com.inmc.shop.util.PlayerSource(viewer)), onlyShop) { if (viewer.isOnline) refresh() } }
            LayoutButton.CLOSE -> set(slot, Icon.close()) { viewer.closeInventory() }
            else -> Unit
        }
    }

    companion object {
        fun render(shop: Shop, layout: Layout): String = layout.title.replace("{shop}", "상점").replace("{page}", "1").replace("{pages}", "1")

        fun shopIcon(shop: Shop, vshop: VirtualShop, canOpen: Boolean): ItemStack {
            val base = vshop.icon?.let { shop.resolver.create(it, 1) } ?: ItemStack(vshop.iconMaterial)
            val lore = vshop.description + listOf("") + if (canOpen) listOf("<green>▶ 클릭해서 열기</green>") else listOf("<red>✖ 권한이 없습니다</red>")
            return Icon.relabel(base, vshop.name, lore)
        }

        fun balanceIcon(shop: Shop, viewer: Player): ItemStack {
            val lines = Currencies.all().map { c -> "<gray>${c.name}<gray>: <white>${c.format(c.balance(viewer))}</white>" }
            return Icon.of(Material.GOLD_INGOT, "<gold><b>잔고</b></gold>", lines.ifEmpty { listOf("<gray>화폐 플러그인이 없습니다</gray>") } +
                listOf("<gray>판매 배수: <white>x${"%.2f".format(shop.trades.sellMultiplier(viewer)).trimEnd('0').trimEnd('.')}</white></gray>"))
        }

        fun sellAllIcon(): ItemStack = Icon.of(Material.HOPPER, "<yellow><b>전부 판매</b></yellow>", "<gray>가방에서 팔 수 있는 것을 전부 팝니다.</gray>", "", "<yellow>▶ 클릭</yellow>")
    }
}

/** 서버 상점 한 페이지. 모양은 레이아웃이 정한다. */
class ShopMenu(shop: Shop, viewer: Player, private val shopId: String, private var page: Int) :
    Menu(shop, viewer, layoutOf(shop, shopId, page).size, titleOf(shop, shopId, page)) {

    override val back: (() -> Unit)? get() = if (shop.config.virtual.mainMenu) ({ MainMenu(shop, viewer).show() }) else null

    override fun draw() {
        clear()
        val vshop = shop.shops.get(shopId) ?: return viewer.closeInventory()
        val layout = layoutOf(shop, shopId, page)
        for ((slot, raw) in layout.decorations) Inv.decode(raw)?.let { set(slot, it) }
        val slots = layout.productSlots().toSet()
        for ((slot, product) in shop.rotations.productsAt(vshop, page + 1)) {
            if (slot !in slots) continue
            set(slot, Products.icon(shop, viewer, product, vshop)) { event ->
                if (!shop.guard(viewer)) return@set
                Products.click(shop, viewer, product, vshop, event) { ShopMenu(shop, viewer, shopId, page).show() }
            }
        }
        for ((button, slot) in layout.buttons) when (button) {
            LayoutButton.PREV -> if (page > 0) set(slot, Icon.prevPage()) { go(page - 1) }
            LayoutButton.NEXT -> if (page < vshop.pages - 1) set(slot, Icon.nextPage()) { go(page + 1) }
            LayoutButton.BACK -> back?.let { go -> set(slot, Icon.of(Material.ARROW, "<green><b>상점 목록</b></green>")) { go() } }
            LayoutButton.BALANCE -> set(slot, MainMenu.balanceIcon(shop, viewer))
            LayoutButton.SELL_ALL -> if (viewer.hasPermission("inmcshop.key.sellall") && vshop.selling) {
                set(slot, Icon.of(Material.HOPPER, "<yellow><b>전부 판매</b></yellow>", "<gray>이 상점이 사는 것을 가방에서 전부 팝니다.</gray>", "", "<yellow>▶ 클릭</yellow>")) {
                    shop.sell.sellEverything(viewer, listOf(com.inmc.shop.util.PlayerSource(viewer)), shopId) { if (viewer.isOnline) refresh() }
                }
            }
            LayoutButton.CLOSE -> set(slot, Icon.close()) { viewer.closeInventory() }
        }
    }

    private fun go(next: Int) {
        // 페이지마다 레이아웃(크기·제목)이 다를 수 있어 새 화면으로 연다.
        ShopMenu(shop, viewer, shopId, next).show()
    }

    companion object {
        fun layoutOf(shop: Shop, shopId: String, page: Int): Layout = shop.layouts.get(shop.shops.get(shopId)?.layoutFor(page + 1))

        fun titleOf(shop: Shop, shopId: String, page: Int): String {
            val vshop = shop.shops.get(shopId)
            return layoutOf(shop, shopId, page).title
                .replace("{shop}", Text.plain(vshop?.name ?: shopId))
                .replace("{page}", (page + 1).toString())
                .replace("{pages}", (vshop?.pages ?: 1).toString())
        }

        /** 열기 — 권한·허용 월드·게임모드를 본다. */
        fun open(shop: Shop, player: Player, vshop: VirtualShop, force: Boolean = false) {
            if (!shop.config.virtual.enabled) return shop.messages.send(player, "module-disabled")
            if (!force && !shop.guard(player)) return
            if (!force && !shop.trades.canOpen(player, vshop)) return shop.messages.send(player, "shop-no-permission", Ph.of().shop(vshop.name))
            ShopMenu(shop, player, vshop.id, 0).show()
        }
    }
}
