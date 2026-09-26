package com.inmc.shop.virtual

import com.inmc.shop.Shop
import kr.inmc.core.store.DefinitionKey
import kr.inmc.core.store.YamlFolder
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 서버 상점 정의 — `virtual/shops/<id>.yml`. 화면에서 고치면 dirty 로 찍고 1초 티커가 쓴다.
 * 읽지 못한 파일은 건너뛰되 **지우지 않는다**(다음 저장은 dirty 인 것만 쓴다).
 */
class ShopRegistry(private val shop: Shop) {

    private val folder = YamlFolder(shop.io, shop.logger, "virtual/shops", HEADER, "서버 상점")
    private val shops = ConcurrentHashMap<String, VirtualShop>()

    /** 상품 열쇠(`상점/상품`) → 상품. 가격·재고 서비스가 쓴다. 정의가 바뀔 때마다 다시 만든다. */
    @Volatile
    private var byKey: Map<String, Product> = emptyMap()

    val folderFile: File get() = folder.folder

    fun loadNow() {
        val read = folder.readAll { id, config -> VirtualShop.load(id, config) }
        replace(read.map { it.second })
    }

    fun reloadFrom(read: List<VirtualShop>) = replace(read)

    fun readAll(): List<VirtualShop> = folder.readAll { id, config -> VirtualShop.load(id, config) }.map { it.second }

    private fun replace(list: List<VirtualShop>) {
        shops.clear()
        for (s in list) shops[s.id] = s
        reindex()
    }

    private fun reindex() {
        byKey = shops.values.flatMap { s -> s.products.values }.associateBy { it.key }
    }

    fun all(): List<VirtualShop> = shops.values.sortedWith(compareBy({ if (it.menuSlot < 0) Int.MAX_VALUE else it.menuSlot }, { it.id }))

    fun get(id: String): VirtualShop? = shops[id] ?: shops.values.firstOrNull { it.id.equals(id, ignoreCase = true) }

    fun product(key: String): Product? = byKey[key]

    fun products(): Collection<Product> = byKey.values

    val isEmpty: Boolean get() = shops.isEmpty()

    fun put(value: VirtualShop) {
        shops[value.id] = value
        reindex()
        folder.markDirty(value.id)
    }

    /** 상품 하나를 고친다. */
    fun putProduct(product: Product) {
        val owner = shops[product.shopId] ?: return
        put(owner.copy(products = owner.products + (product.id to product)))
    }

    fun removeProduct(product: Product) {
        val owner = shops[product.shopId] ?: return
        put(owner.copy(products = owner.products - product.id))
    }

    fun remove(id: String) {
        shops.remove(id)
        reindex()
        folder.deleteFile(id)
    }

    fun validId(id: String): Boolean = DefinitionKey.isValid(id)

    /** 새 상품 id — 무작위, 이 상점에서 겹치지 않게. */
    fun newProductId(shop: VirtualShop): String {
        while (true) {
            val id = "p" + java.util.UUID.randomUUID().toString().replace("-", "").take(8)
            if (id !in shop.products) return id
        }
    }

    fun flush() = folder.flushDirty { id -> shops[id]?.toYaml() }

    fun flushBlocking() = folder.flushDirtyBlocking { id -> shops[id]?.toYaml() }

    companion object {
        const val HEADER = "서버 상점 하나. /상점 관리 에서 GUI 로 고치는 것을 권장합니다 — 화면에서 고치면 이 파일을 통째로 다시 씁니다.\n" +
            "가격 type: FIXED(고정) · MARKET(시장 가격) · FLOAT(변동) · DYNAMIC(수요) · ONLINE(접속자). 가격 -1 = 그 방향 거래 불가."
    }
}

/** 레이아웃 — `virtual/layouts/<id>.yml` + 메인 메뉴 `virtual/main-menu.yml`. */
class LayoutRegistry(private val shop: Shop) {

    private val folder = YamlFolder(shop.io, shop.logger, "virtual/layouts", "상점 화면의 모양. /상점 관리 → 레이아웃 에서 고칩니다.", "레이아웃")
    private val layouts = ConcurrentHashMap<String, Layout>()

    @Volatile
    var mainMenu: Layout = Layout.mainMenu()
        private set

    private var mainDirty = false

    fun loadNow() {
        replace(folder.readAll { id, config -> Layout.load(id, config) }.map { it.second }, readMain())
    }

    fun readAll(): Pair<List<Layout>, Layout> = folder.readAll { id, config -> Layout.load(id, config) }.map { it.second } to readMain()

    fun reloadFrom(read: Pair<List<Layout>, Layout>) = replace(read.first, read.second)

    private fun readMain(): Layout {
        val file = shop.io.file(MAIN_FILE)
        return if (file.exists()) Layout.load("main-menu", shop.io.load(file)) else Layout.mainMenu(pane())
    }

    private fun replace(list: List<Layout>, main: Layout) {
        layouts.clear()
        for (l in list) layouts[l.id] = l
        if (!layouts.containsKey("default")) {
            val def = Layout.default(decoration = pane())
            layouts["default"] = def
            folder.markDirty("default")
        }
        mainMenu = main
        if (!shop.io.file(MAIN_FILE).exists()) mainDirty = true
    }

    /** 이 레이아웃, 없거나 잘못됐으면 기본. */
    fun get(id: String?): Layout = id?.let { layouts[it] }?.takeIf { it.productSlots().isNotEmpty() } ?: layouts["default"] ?: Layout.default()

    fun find(id: String): Layout? = layouts[id]

    fun all(): List<Layout> = layouts.values.sortedBy { it.id }

    fun put(layout: Layout) {
        if (layout.id == "main-menu") {
            mainMenu = layout
            mainDirty = true
            return
        }
        layouts[layout.id] = layout
        folder.markDirty(layout.id)
    }

    fun remove(id: String) {
        if (id == "default") return
        layouts.remove(id)
        folder.deleteFile(id)
    }

    fun flush() {
        folder.flushDirty { id -> layouts[id]?.toYaml() }
        if (mainDirty) {
            mainDirty = false
            val y = mainMenu.toYaml()
            shop.io.asyncRun { shop.io.save(shop.io.file(MAIN_FILE), y) }
        }
    }

    fun flushBlocking() {
        folder.flushDirtyBlocking { id -> layouts[id]?.toYaml() }
        if (mainDirty) {
            mainDirty = false
            shop.io.save(shop.io.file(MAIN_FILE), mainMenu.toYaml())
        }
    }

    private fun pane(): String? = runCatching {
        com.inmc.shop.util.Inv.encode(ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply { editMeta { it.isHideTooltip = true } })
    }.getOrNull()

    companion object {
        const val MAIN_FILE = "virtual/main-menu.yml"
    }
}
