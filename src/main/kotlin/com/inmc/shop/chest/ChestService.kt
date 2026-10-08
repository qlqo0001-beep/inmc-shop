package com.inmc.shop.chest

import com.inmc.shop.Shop
import com.inmc.shop.data.ChestRow
import com.inmc.shop.hook.LandsHook
import com.inmc.shop.trade.TradeType
import com.inmc.shop.util.Inv
import com.inmc.shop.util.Labels
import com.inmc.shop.util.Ph
import com.inmc.shop.virtual.Quantity
import kr.inmc.core.economy.Currency
import kr.inmc.core.item.ItemRef
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.Chest
import org.bukkit.block.DoubleChest
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 상자 상점. 상자는 **간판**이고 물건은 가상 창고(로컬 DB `shop_chest_stock`)에 있다 — 호퍼·폭발·월드 편집으로 사라지거나
 * 복사되지 않는다. 창고가 바뀌면 그 자리에서 쓰기 스레드로 넘긴다(주기 저장이 아니다 — 게임에서 빠진 물건이 여기에만 있다).
 *
 * 불변식: 손님이 사면 **돈을 받고 → 창고를 줄이고 → 준다**. 손님이 팔면 **값을 먼저 확보하고(은행/지갑) → 가방에서 빼고 → 창고를
 * 늘리고 → 준다**. 뒤 단계가 실패하면 앞 단계를 되돌린다.
 */
class ChestService(private val shop: Shop) {

    private val shops = ConcurrentHashMap<String, ChestShop>()
    private val byKey = ConcurrentHashMap<BlockKey, String>()
    private val stock = ConcurrentHashMap<Pair<String, String>, Long>()
    private val templates = ConcurrentHashMap<String, ItemStack>()
    /** 운영자가 접속해 있을 때 본 창고 용량(등급) — 운영자가 없을 때 손님이 파는 경우에 쓴다. */
    private val capacityCache = ConcurrentHashMap<UUID, Long>()
    private val busy = com.inmc.shop.util.BusyLock()

    private val settings get() = shop.config.chest

    fun load() {
        val (rows, stocks) = shop.db.call { shop.db.chestShops(shop.db.local) to shop.db.chestStock(shop.db.local) }
        shops.clear(); byKey.clear(); stock.clear()
        for (row in rows) {
            val key = BlockKey(row.world, row.x, row.y, row.z)
            val value = runCatching { ChestShop.fromData(row.id, key, row.owner, row.data) }.getOrElse {
                shop.logger.severe("상자 상점을 읽지 못했습니다(${row.id}): ${it.message} - 건너뜁니다(지우지 않습니다)")
                null
            } ?: continue
            shops[value.id] = value
            byKey[key] = value.id
        }
        stock.putAll(stocks)
    }

    fun all(): List<ChestShop> = shops.values.sortedBy { it.created }
    fun get(id: String): ChestShop? = shops[id]
    fun ownedBy(player: UUID): List<ChestShop> = all().filter { it.owner == player }
    fun operatedBy(player: UUID): List<ChestShop> = all().filter { it.operator() == player }

    /** 이 블록의 상점 — 큰 상자면 다른 반쪽도 본다. */
    fun at(block: Block): ChestShop? {
        byKey[BlockKey.of(block)]?.let { shops[it] }?.let { return it }
        val state = block.state as? Chest ?: return null
        val holder = state.inventory.holder as? DoubleChest ?: return null
        for (side in listOf(holder.leftSide, holder.rightSide)) {
            val other = (side as? Chest)?.block ?: continue
            byKey[BlockKey.of(other)]?.let { shops[it] }?.let { return it }
        }
        return null
    }

    fun isShopBlock(block: Block): Boolean = at(block) != null

    private fun save(value: ChestShop) {
        shops[value.id] = value
        byKey[value.key] = value.id
        val row = ChestRow(value.id, value.key.world, value.key.x, value.key.y, value.key.z, value.owner, value.toData())
        shop.db.run("상자 상점 저장") { shop.db.saveChestShop(shop.db.local, row) }
        shop.displays.refresh(value)
    }

    /** 고친다 — 편집 화면·명령어가 부른다. */
    fun update(id: String, change: (ChestShop) -> ChestShop): ChestShop? {
        val current = shops[id] ?: return null
        val next = change(current)
        save(next)
        return next
    }

    // --- 창고 ------------------------------------------------------------------------------

    fun stock(value: ChestShop, product: ChestProduct): Long = if (value.admin) Long.MAX_VALUE else stock[value.id to product.id] ?: 0

    private fun setStock(value: ChestShop, product: ChestProduct, items: Long) {
        stock[value.id to product.id] = items
        val id = value.id
        val pid = product.id
        shop.db.run("창고 저장") { shop.db.setChestStock(shop.db.local, id, pid, items) }
    }

    /** 상품 하나의 창고 용량(개). 관리자 상점은 무한. */
    fun capacity(value: ChestShop): Long {
        if (value.admin) return Long.MAX_VALUE
        val operator = value.operator()
        val online = Bukkit.getPlayer(operator)
        if (online != null) {
            val cap = settings.capacity.valueFor(online::hasPermission).let { if (it < 0) Long.MAX_VALUE else it.toLong() }
            capacityCache[operator] = cap
            return cap
        }
        return capacityCache[operator] ?: settings.capacity.default.toLong()
    }

    fun template(product: ChestProduct): ItemStack? = templates.getOrPut(product.item) { Inv.decode(product.item) ?: return null }.clone()

    fun label(product: ChestProduct): String = Labels.of(template(product))

    /** 창고의 물건과 같은가 — 커스텀아이템·MMOItems 는 정체로(정의가 바뀌어도 같은 것), 나머지는 아이템 전체로. */
    fun matches(product: ChestProduct, stack: ItemStack): Boolean {
        if (stack.type.isAir) return false
        val template = templates.getOrPut(product.item) { Inv.decode(product.item) ?: return false }
        val ref = shop.resolver.identify(template)
        if (ref is ItemRef.Namespaced || ref is ItemRef.MMOItems) return shop.resolver.identify(stack) == ref
        return template.isSimilar(stack)
    }

    fun unitOf(product: ChestProduct): Int = (template(product)?.amount ?: 1).coerceAtLeast(1)

    fun currencyOf(product: ChestProduct): Currency? = shop.currency(product.currency, settings.currency)

    /** 주인이 가방에서 창고로 [amount] 개(0 = 가진 것 전부) 넣는다. 넣은 개수. */
    fun deposit(player: Player, value: ChestShop, product: ChestProduct, amount: Int): Int {
        if (value.admin) return 0
        val have = Inv.count(player) { matches(product, it) }
        val room = (capacity(value) - stock(value, product)).coerceAtLeast(0)
        val n = minOf((if (amount <= 0) have else amount).toLong(), have.toLong(), room).toInt()
        if (n <= 0) {
            shop.messages.send(player, if (room <= 0) "chest-stock-full" else "not-enough-items")
            return 0
        }
        // 빼고 나서 늘린다 — 반대로 하면 복사된다.
        val taken = Inv.take(player, { matches(product, it) }, n)
        val moved = taken.sumOf { it.amount }
        setStock(value, product, stock(value, product) + moved)
        shop.messages.send(player, "chest-deposited", Ph.of().item(label(product)).amount(moved.toLong()))
        shop.displays.refresh(value)
        return moved
    }

    /** 창고에서 가방으로 [amount] 개(0 = 전부). 안 들어가면 돌려받을 물건으로. */
    fun withdraw(player: Player, value: ChestShop, product: ChestProduct, amount: Int): Int {
        if (value.admin) return 0
        val have = stock(value, product)
        val n = minOf(if (amount <= 0) have else amount.toLong(), have).toInt()
        if (n <= 0) { shop.messages.send(player, "chest-empty"); return 0 }
        val template = template(product)?.apply { this.amount = 1 } ?: return 0
        // 줄이고 나서 준다.
        setStock(value, product, have - n)
        shop.returns.giveOrStore(player, template, n, "상자 상점 회수")
        shop.messages.send(player, "chest-withdrew", Ph.of().item(label(product)).amount(n.toLong()))
        shop.displays.refresh(value)
        return n
    }

    // --- 만들기·지우기 -----------------------------------------------------------------------

    fun allowedBlock(block: Block): Boolean {
        val name = block.type.name
        return settings.blocks.any { allowed -> name == allowed || (allowed == "SHULKER_BOX" && name.endsWith("SHULKER_BOX")) }
    }

    /** `/상자상점 만들기`. */
    fun create(player: Player, block: Block): ChestShop? {
        if (!settings.enabled) { shop.messages.send(player, "module-disabled"); return null }
        if (!shop.guard(player)) return null
        if (!player.hasPermission("inmcshop.chestshop.create")) { shop.messages.send(player, "no-permission"); return null }
        if (!allowedBlock(block)) { shop.messages.send(player, "chest-not-container"); return null }
        if (at(block) != null) { shop.messages.send(player, "chest-already"); return null }
        val max = settings.maxShops.valueFor(player::hasPermission)
        if (max >= 0 && ownedBy(player.uniqueId).size >= max) { shop.messages.send(player, "chest-limit", Ph.of().max(max.toLong())); return null }
        if (settings.checkBuild && !canBuild(player, block)) { shop.messages.send(player, "chest-no-build"); return null }
        if (settings.landsOnly && !player.hasPermission("inmcshop.chestshop.bypass.creation.claims") && LandsHook.canCreate(player, block.location) == false) {
            shop.messages.send(player, "chest-lands-only"); return null
        }
        if (settings.createCost > 0) {
            val currency = shop.currency(null, settings.currency) ?: run { shop.messages.send(player, "currency-missing", Ph.of().currency("기본")); return null }
            if (!currency.withdraw(player, settings.createCost, "inmcshop:chest-create")) {
                shop.messages.send(player, "not-enough-money", Ph.of().price(currency.format(settings.createCost))); return null
            }
        }
        val value = ChestShop(
            id = UUID.randomUUID().toString().replace("-", "").take(12),
            key = BlockKey.of(block), owner = player.uniqueId, ownerName = player.name,
            name = settings.defaultName.take(settings.maxNameLength), hologram = settings.hologram,
        )
        save(value)
        capacity(value)
        shop.messages.send(player, "chest-created")
        return value
    }

    /** 이 자리에 블록을 놓을 수 있는 사람인가 — 가짜 놓기 사건. 보호 플러그인이 무엇이든 통한다. */
    private fun canBuild(player: Player, block: Block): Boolean {
        val event = BlockPlaceEvent(block, block.state, block, ItemStack(block.type), player, true, EquipmentSlot.HAND)
        Bukkit.getPluginManager().callEvent(event)
        return !event.isCancelled && event.canBuild()
    }

    /** 지운다 — 남은 창고는 운영자에게(가방, 넘치면 돌려받을 물건). */
    fun remove(value: ChestShop, by: Player?) {
        if (by != null && settings.removeCost > 0 && !by.hasPermission("inmcshop.chestshop.remove.others")) {
            val currency = shop.currency(null, settings.currency)
            if (currency != null && !currency.withdraw(by, settings.removeCost, "inmcshop:chest-remove")) {
                shop.messages.send(by, "not-enough-money", Ph.of().price(currency.format(settings.removeCost))); return
            }
        }
        val operator = value.operator()
        returnAll(value, operator, "상자 상점 삭제")
        shops.remove(value.id)
        byKey.remove(value.key)
        val id = value.id
        shop.db.run("상자 상점 지우기") { shop.db.deleteChestShop(shop.db.local, id) }
        shop.displays.remove(value)
        // 상점 블록 아이템으로만 만들 수 있는 서버면 그 아이템을 돌려준다(알려진 한계였다, 2026-10-08). 블록은 그 아이템이었으니 거둔다 —
        // 안 거두면 아이템과 상자 블록이 둘 다 남는다.
        if (settings.creationItems && !value.admin) {
            val block = value.key.block()
            val material = block?.type?.takeIf { it.isBlock && !it.isAir } ?: Material.CHEST
            if (block != null) {
                // 창고는 DB 라 상자 자체는 비어 있어야 하지만, 혹시 든 것이 있으면 버리지 않고 바닥에.
                (block.state as? Chest)?.blockInventory?.contents?.filterNotNull()?.forEach { block.world.dropItemNaturally(block.location, it) }
                block.type = Material.AIR
            }
            val item = creationItem(material, 1)
            val online = Bukkit.getPlayer(operator)
            if (online != null) shop.returns.giveOrStore(online, item, 1, "상자 상점 삭제") else shop.returns.storeSplit(operator, item, 1, "상자 상점 삭제")
        }
        by?.let { shop.messages.send(it, "chest-removed") }
    }

    /** 놓으면 상자 상점이 되는 아이템(`creation-items` 가 켜진 서버에서 쓴다). `/상점 … 아이템지급` 과 삭제 때 돌려주기가 같은 것을 만든다. */
    fun creationItem(material: Material, amount: Int): ItemStack = ItemStack(material, amount).also { stack ->
        stack.editMeta { meta ->
            meta.displayName(kr.inmc.core.util.Text.renderFlat("<gold>상점 블록</gold>"))
            meta.lore(kr.inmc.core.util.Text.renderLore(listOf("<gray>놓으면 상자 상점이 됩니다.</gray>")))
            meta.persistentDataContainer.set(org.bukkit.NamespacedKey(com.inmc.shop.listener.ChestListener.TAG_NAMESPACE, "creation"), org.bukkit.persistence.PersistentDataType.BYTE, 1)
        }
    }

    /** 창고의 물건을 전부 [to] 에게 — 접속 중이면 가방(넘치면 보관), 아니면 보관. */
    private fun returnAll(value: ChestShop, to: UUID, reason: String) {
        if (value.admin) return
        val online = Bukkit.getPlayer(to)
        for (product in value.products) {
            val n = stock(value, product)
            if (n <= 0) continue
            val template = template(product)?.apply { amount = 1 } ?: continue
            setStock(value, product, 0)
            if (online != null) shop.returns.giveOrStore(online, template, n.toInt(), reason) else shop.returns.storeSplit(to, template, n.toInt(), reason)
        }
    }

    // --- 상품 ------------------------------------------------------------------------------

    /** 금지 재질·이름·설명 낱말. 걸리면 메시지 키. */
    fun banned(stack: ItemStack): String? {
        if (settings.bannedMaterials.any { it.equals(stack.type.name, true) }) return "chest-banned-item"
        val meta = stack.itemMeta ?: return null
        val name = meta.displayName()?.let { kr.inmc.core.util.Text.plain(it) }.orEmpty()
        if (settings.bannedNames.any { it.isNotBlank() && name.contains(it, true) }) return "chest-banned-item"
        val lore = meta.lore().orEmpty().joinToString("\n") { kr.inmc.core.util.Text.plain(it) }
        if (settings.bannedLores.any { it.isNotBlank() && lore.contains(it, true) }) return "chest-banned-item"
        return null
    }

    /** 가방의 [stack] 을 상품으로 올린다(개수 = 한 단위). 가격은 꺼진 채로 — 입력창에서 정한다. */
    fun addProduct(player: Player, value: ChestShop, stack: ItemStack): ChestProduct? {
        val max = settings.maxProducts.valueFor(player::hasPermission)
        if (max >= 0 && value.products.size >= max) { shop.messages.send(player, "chest-product-limit", Ph.of().max(max.toLong())); return null }
        banned(stack)?.let { shop.messages.send(player, it); return null }
        val unit = stack.clone()
        if (value.products.any { matches(it, unit) && unitOf(it) == unit.amount }) { shop.messages.send(player, "chest-product-exists"); return null }
        val product = ChestProduct(UUID.randomUUID().toString().replace("-", "").take(8), Inv.encode(unit))
        update(value.id) { it.copy(products = it.products + product) }
        shop.messages.send(player, "chest-product-added", Ph.of().item(Labels.of(unit)))
        return product
    }

    fun updateProduct(value: ChestShop, product: ChestProduct) {
        update(value.id) { s -> s.copy(products = s.products.map { if (it.id == product.id) product else it }) }
    }

    /** 내린다 — 창고의 그 물건은 운영자에게. */
    fun removeProduct(player: Player, value: ChestShop, product: ChestProduct) {
        val n = stock(value, product)
        if (n > 0 && !value.admin) {
            val template = template(product)?.apply { amount = 1 }
            setStock(value, product, 0)
            if (template != null) shop.returns.giveOrStore(player, template, n.toInt(), "상품 내리기")
        }
        update(value.id) { s -> s.copy(products = s.products.filter { it.id != product.id }) }
    }

    // --- 손님 거래 ---------------------------------------------------------------------------

    fun maxUnits(player: Player, value: ChestShop, product: ChestProduct, type: TradeType): Long {
        val price = product.price(type) ?: return 0
        val unit = unitOf(product)
        return if (type == TradeType.BUY) {
            val currency = currencyOf(product) ?: return 0
            val stockUnits = if (value.admin) Long.MAX_VALUE else stock(value, product) / unit
            val space = template(product)?.let { Inv.space(player, it.apply { amount = 1 }).toLong() / unit } ?: 0
            Quantity.max(Quantity.affordable(currency.balance(player), price), stockUnits, Long.MAX_VALUE, if (shop.config.buyWithFullInventory) Long.MAX_VALUE else space)
        } else {
            val have = Inv.count(player) { matches(product, it) }.toLong() / unit
            val room = if (value.admin) Long.MAX_VALUE else (capacity(value) - stock(value, product)).coerceAtLeast(0) / unit
            Quantity.max(Long.MAX_VALUE, Long.MAX_VALUE, room, have)
        }
    }

    private fun common(player: Player, value: ChestShop, product: ChestProduct, type: TradeType): Pair<Long, Currency>? {
        if (!settings.enabled) { shop.messages.send(player, "module-disabled"); return null }
        if (!shop.guard(player)) return null
        if (value.canManage(player.uniqueId)) { shop.messages.send(player, "chest-own-shop"); return null }
        val price = product.price(type) ?: run { shop.messages.send(player, if (type == TradeType.BUY) "buy-disabled" else "sell-disabled"); return null }
        val currency = currencyOf(product) ?: run { shop.messages.send(player, "currency-missing", Ph.of().currency(product.currency.ifEmpty { "기본" })); return null }
        if (!shop.canUse(player, currency, settings.currency)) { shop.messages.send(player, "currency-not-allowed", Ph.of().currency(currency.name)); return null }
        return price to currency
    }

    fun buy(player: Player, value: ChestShop, product: ChestProduct, units: Long, then: (Boolean) -> Unit = {}) {
        if (units <= 0) return then(false)
        val (price, currency) = common(player, value, product, TradeType.BUY) ?: return then(false)
        val unit = unitOf(product)
        val items = units * unit
        if (!value.admin && stock(value, product) < items) { shop.messages.send(player, "chest-out-of-stock"); return then(false) }
        val template = template(product)?.apply { amount = 1 } ?: return then(false)
        if (!shop.config.buyWithFullInventory && Inv.space(player, template) < items) { shop.messages.send(player, "inventory-full"); return then(false) }
        val total = runCatching { Math.multiplyExact(price, units) }.getOrElse { shop.messages.send(player, "too-much"); return then(false) }
        if (!busy.acquire(player.uniqueId)) { shop.messages.send(player, "busy"); return then(false) }
        try {
            if (!currency.withdraw(player, total, "inmcshop:chest-buy:" + value.id)) {
                shop.messages.send(player, "not-enough-money", Ph.of().price(currency.format(total))); return then(false)
            }
            if (!value.admin) setStock(value, product, stock(value, product) - items)
            Inv.give(player, template, items.toInt(), drop = true)
            if (!value.admin) pay(value.operator(), currency, total)
            val label = label(product)
            shop.log.record("chest", "buy", player.uniqueId, player.name, subject(product), label, items, currency.format(total), value.id + "@" + value.key.world + "," + value.key.x + "," + value.key.y + "," + value.key.z)
            shop.messages.send(player, "chest-bought", Ph.of().item(label).amount(items).price(currency.format(total)).shop(value.name))
            notifyOperator(value, "chest-owner-sold-to", Ph.of().player(kr.inmc.core.integration.TitleForgeNames.displayName(player.uniqueId, player.name)).item(label).amount(items).price(currency.format(total)).shop(value.name))
            shop.displays.refresh(value)
            then(true)
        } finally {
            busy.release(player.uniqueId)
        }
    }

    /** 수익을 운영자에게 — 은행이 켜져 있으면 은행, 아니면 지갑(접속 안 해도 된다). */
    private fun pay(operator: UUID, currency: Currency, amount: Long) {
        if (settings.bank) shop.bank.deposit(operator, currency.id, amount)
        else currency.deposit(Bukkit.getOfflinePlayer(operator), amount, "inmcshop:chest-income")
    }

    fun sell(player: Player, value: ChestShop, product: ChestProduct, units: Long, then: (Boolean) -> Unit = {}) {
        if (units <= 0) return then(false)
        val (price, currency) = common(player, value, product, TradeType.SELL) ?: return then(false)
        val unit = unitOf(product)
        val items = units * unit
        if (Inv.count(player) { matches(product, it) } < items) { shop.messages.send(player, "not-enough-items"); return then(false) }
        if (!value.admin && capacity(value) - stock(value, product) < items) { shop.messages.send(player, "chest-stock-full"); return then(false) }
        val total = runCatching { Math.multiplyExact(price, units) }.getOrElse { shop.messages.send(player, "too-much"); return then(false) }
        if (!busy.acquire(player.uniqueId)) { shop.messages.send(player, "busy"); return then(false) }
        val operator = value.operator()
        // 값을 먼저 확보한다 — 관리자 상점은 무한, 아니면 은행(모자라고 은행 필수가 아니면 운영자 지갑).
        fun secured(ok: Boolean, fromBank: Boolean) {
            try {
                if (!ok) { shop.messages.send(player, "chest-no-money"); return then(false) }
                val refund = { if (value.admin) Unit else if (fromBank) shop.bank.deposit(operator, currency.id, total) else { currency.deposit(Bukkit.getOfflinePlayer(operator), total, "inmcshop:chest-refund"); Unit } }
                val current = shops[value.id] ?: run { refund(); return then(false) }
                if (!player.isOnline || Inv.count(player) { matches(product, it) } < items) { refund(); shop.messages.send(player, "not-enough-items"); return then(false) }
                if (!current.admin && capacity(current) - stock(current, product) < items) { refund(); shop.messages.send(player, "chest-stock-full"); return then(false) }
                val taken = Inv.take(player, { matches(product, it) }, items.toInt())
                if (!currency.deposit(player, total, "inmcshop:chest-sell:" + value.id)) {
                    Inv.restore(player, taken); refund(); shop.messages.send(player, "deposit-failed"); return then(false)
                }
                if (!current.admin) setStock(current, product, stock(current, product) + items)
                val label = label(product)
                shop.log.record("chest", "sell", player.uniqueId, player.name, subject(product), label, items, currency.format(total), value.id)
                shop.messages.send(player, "chest-sold", Ph.of().item(label).amount(items).price(currency.format(total)).shop(value.name))
                notifyOperator(value, "chest-owner-bought-from", Ph.of().player(kr.inmc.core.integration.TitleForgeNames.displayName(player.uniqueId, player.name)).item(label).amount(items).price(currency.format(total)).shop(value.name))
                shop.displays.refresh(current)
                then(true)
            } finally {
                busy.release(player.uniqueId)
            }
        }
        when {
            value.admin -> secured(true, false)
            settings.bank -> shop.bank.take(operator, currency.id, total) { ok ->
                if (ok) secured(true, true)
                else if (!settings.bankMandatory && currency.withdraw(Bukkit.getOfflinePlayer(operator), total, "inmcshop:chest-pay")) secured(true, false)
                else secured(false, false)
            }
            else -> secured(currency.withdraw(Bukkit.getOfflinePlayer(operator), total, "inmcshop:chest-pay"), false)
        }
    }

    private fun notifyOperator(value: ChestShop, key: String, ph: Ph) {
        if (!settings.notifyOwner || value.admin) return
        Bukkit.getPlayer(value.operator())?.let { shop.messages.send(it, key, ph) }
    }

    fun subject(product: ChestProduct): String = template(product)?.let { shop.resolver.identify(it).serialize() } ?: "?"

    // --- 임대 ------------------------------------------------------------------------------

    /** 임대 가능한가 — 켜졌고 지금 비었고 상품이 없다. */
    fun rentable(value: ChestShop, now: Long = System.currentTimeMillis()): Boolean =
        settings.rent && value.rent.enabled && !value.rent.active(now) && value.products.isEmpty()

    /** 빌린다(또는 연장). 임대료는 주인에게(은행이 켜져 있으면 은행). */
    fun rent(player: Player, value: ChestShop): Boolean {
        val now = System.currentTimeMillis()
        val extending = value.rent.active(now) && value.rent.renter == player.uniqueId
        if (!extending && !rentable(value, now)) { shop.messages.send(player, "rent-unavailable"); return false }
        if (player.uniqueId == value.owner) { shop.messages.send(player, "chest-own-shop"); return false }
        if (!shop.guard(player)) return false
        val currency = shop.currency(value.rent.currency, settings.currency) ?: run { shop.messages.send(player, "currency-missing", Ph.of().currency(value.rent.currency)); return false }
        val maxUntil = now + settings.rentMaxDays * 86_400_000L
        val base = if (extending) value.rent.until else now
        val until = base + value.rent.days * 86_400_000L
        if (until > maxUntil + 60_000L) { shop.messages.send(player, "rent-too-long", Ph.of().value(settings.rentMaxDays.toString())); return false }
        if (!currency.withdraw(player, value.rent.price, "inmcshop:rent:" + value.id)) {
            shop.messages.send(player, "not-enough-money", Ph.of().price(currency.format(value.rent.price))); return false
        }
        if (settings.bank) shop.bank.deposit(value.owner, currency.id, value.rent.price)
        else currency.deposit(Bukkit.getOfflinePlayer(value.owner), value.rent.price, "inmcshop:rent-income")
        update(value.id) { it.copy(rent = it.rent.copy(renter = player.uniqueId, renterName = player.name, until = until)) }
        capacity(shops[value.id]!!)
        shop.messages.send(player, if (extending) "rent-extended" else "rent-started", Ph.of().shop(value.name).time(kr.inmc.core.util.Durations.formatShort((until - now) / 1000)))
        Bukkit.getPlayer(value.owner)?.let { shop.messages.send(it, "rent-owner-notice", Ph.of().player(kr.inmc.core.integration.TitleForgeNames.displayName(player.uniqueId, player.name)).shop(value.name).price(currency.format(value.rent.price))) }
        return true
    }

    /** 임대를 끝낸다 — 임차인의 창고 물건은 임차인에게, 상품은 비운다. */
    fun endRent(value: ChestShop, reason: String) {
        val renter = value.rent.renter ?: return
        returnAll(value, renter, reason)
        update(value.id) { it.copy(products = emptyList(), trusted = emptySet(), rent = it.rent.copy(renter = null, renterName = null, until = 0)) }
        Bukkit.getPlayer(renter)?.let { shop.messages.send(it, "rent-ended", Ph.of().shop(value.name)) }
    }

    /** 1초마다 — 임대가 끝난 상점. */
    fun tick(now: Long) {
        for (value in shops.values) if (value.rent.renter != null && now >= value.rent.until) endRent(value, "임대 종료")
    }

    // --- 찾기 ------------------------------------------------------------------------------

    /** 물건으로 찾기 — [query] 가 아이템이면 그 물건, 글자면 이름·재질에 들어 있는지. */
    fun search(query: String?, item: ItemStack?): List<Pair<ChestShop, ChestProduct>> {
        val q = query?.trim()?.lowercase()?.replace(" ", "")?.replace("_", "")
        return all().flatMap { s -> s.products.map { s to it } }.filter { (_, product) ->
            when {
                item != null && !item.type.isAir -> matches(product, item) || template(product)?.type == item.type
                !q.isNullOrEmpty() -> {
                    val t = template(product) ?: return@filter false
                    val name = t.itemMeta?.displayName()?.let { kr.inmc.core.util.Text.plain(it) }.orEmpty().lowercase().replace(" ", "")
                    name.contains(q) || t.type.name.lowercase().replace("_", "").contains(q) ||
                        // 바닐라 한글 이름(core VanillaNames, 2026-10-08) — 디스코드가 받아 둔 번역이 있을 때.
                        (kr.inmc.core.util.VanillaNames.of(t.type)?.replace(" ", "")?.contains(q) == true)
                }
                else -> false
            }
        }
    }

    /** 운영자 → 그 사람의 상점들. */
    fun byOperator(): Map<UUID, List<ChestShop>> = all().groupBy { it.operator() }
}
