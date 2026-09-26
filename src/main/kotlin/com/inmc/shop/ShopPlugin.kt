package com.inmc.shop

import com.inmc.shop.command.ShopCommand
import com.inmc.shop.config.Messages
import com.inmc.shop.config.ShopConfig
import com.inmc.shop.hook.LandsHook
import com.inmc.shop.hook.PapiHook
import com.inmc.shop.listener.ChestListener
import com.inmc.shop.listener.PlayerListener
import com.inmc.shop.scheduler.Ticker
import com.inmc.shop.trade.TradeLog
import com.inmc.shop.virtual.DefaultShops
import kr.inmc.core.event.SignalCatalog
import kr.inmc.core.gui.Menu
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/**
 * 켜질 때는 **전부 그 자리에서 읽는다**(정의·DB 상태). 서버가 틱을 돌기 전이라 괜찮고, 반쯤 읽힌 상태로 거래가 도는 구간이 없다.
 */
class ShopPlugin : JavaPlugin() {

    private lateinit var shop: Shop
    private lateinit var ticker: Ticker
    private var papi: PapiHook? = null

    override fun onEnable() {
        shop = Shop(this)
        for (name in RESOURCES) shop.io.copyDefault(name, shop.io.file(name))
        shop.config = ShopConfig.from(shop.io.load(shop.io.file("config.yml")))
        shop.messages = Messages.from(shop.io.load(shop.io.file("messages.yml")))
        shop.customItems.setup()
        shop.mmoItems.setup()
        LandsHook.setup(this, logger)

        try {
            shop.db.open(File(dataFolder, "shop.db"), shop.config.networkUrl, shop.config.networkUser, shop.config.networkPassword)
        } catch (t: Throwable) {
            logger.severe("DB 를 열지 못했습니다 - 상점을 끕니다: ${t.message}")
            server.pluginManager.disablePlugin(this)
            return
        }

        shop.layouts.loadNow()
        val first = !shop.shops.folderFile.exists() || (shop.shops.folderFile.listFiles()?.isEmpty() ?: true)
        shop.shops.loadNow()
        if (first) {
            val made = DefaultShops.create(shop)
            logger.info("기본 상점 ${made}개를 만들었습니다(블록·광물·작물·전리품은 가격을 정해야 거래됩니다).")
        }
        shop.categories.loadNow()
        shop.prices.load()
        shop.stocks.load()
        shop.rotations.load()
        shop.chests.load()
        shop.auction.load()
        for (player in server.onlinePlayers) shop.limits.load(player.uniqueId)

        server.pluginManager.registerEvents(PlayerListener(shop), this)
        server.pluginManager.registerEvents(ChestListener(shop), this)
        server.pluginManager.registerEvents(kr.inmc.core.listener.MenuListener(shop), this)
        papi = PapiHook(shop).takeIf { it.setup() }
        ShopCommand(shop, this).register(this)
        registerSignals()

        shop.displays.spawnAll()
        ticker = Ticker(shop)
        shop.markReady()
        ticker.start()
        logger.info("inmcshop 활성화 - 서버 상점 ${shop.shops.all().size}개 · 상자 상점 ${shop.chests.all().size}개 · 경매 ${shop.auction.active().size}건" +
            if (shop.db.network != null) " · 공용 DB 연결" else "")
    }

    /** 거래 신호를 업적 편집기에 알린다(무엇이 오는지). ExcellentShop 의 거래 이벤트 자리. */
    private fun registerSignals() {
        val subjects = { shop.shops.products().mapNotNull { p -> p.item?.let { it.ref.serialize() to kr.inmc.core.util.Text.plain(shop.trades.label(p)) } }.distinct() }
        val keys = listOf("module" to "모듈(virtual·chest·auction)", "where" to "어디서", "money" to "금액")
        SignalCatalog.register(TradeLog.SOURCE, "buy", "물건", subjects, keys, "상점에서 샀다(서버 상점·상자 상점)")
        SignalCatalog.register(TradeLog.SOURCE, "sell", "물건", subjects, keys, "상점에 팔았다(서버 상점·상자 상점)")
        SignalCatalog.register(TradeLog.SOURCE, "auction-list", "물건", { emptyList() }, keys, "경매에 올렸다")
        SignalCatalog.register(TradeLog.SOURCE, "auction-buy", "물건", { emptyList() }, keys, "경매에서 샀다")
    }

    override fun onDisable() {
        if (!::shop.isInitialized) return
        if (::ticker.isInitialized) ticker.stop()
        papi?.teardown()
        SignalCatalog.unregisterAll(TradeLog.SOURCE)
        closeMenus()
        shop.displays.shutdown()
        runCatching { shop.shops.flushBlocking() }
        runCatching { shop.layouts.flushBlocking() }
        runCatching { shop.prices.shutdown() }
        // 줄 선 쓰기(창고·은행·경매·가격)를 다 하고 닫는다.
        runCatching { shop.db.shutdown() }
        shop.io.shutdown()
    }

    /** `/상점 리로드` — 정의·설정·메시지. DB 상태(가격·재고·창고·경매)는 다시 읽지 않는다(메모리가 진짜다). */
    fun reload(then: () -> Unit) {
        shop.shops.flush()
        shop.layouts.flush()
        shop.io.async({
            data class Read(val config: org.bukkit.configuration.file.YamlConfiguration, val messages: org.bukkit.configuration.file.YamlConfiguration)
            Triple(Read(shop.io.load(shop.io.file("config.yml")), shop.io.load(shop.io.file("messages.yml"))), shop.shops.readAll(), shop.layouts.readAll()) to
                shop.io.load(shop.io.file(com.inmc.shop.auction.CategoryRegistry.FILE))
        }) { (read, categories) ->
            val (files, shops, layouts) = read
            shop.config = ShopConfig.from(files.config)
            shop.messages = Messages.from(files.messages)
            shop.shops.reloadFrom(shops)
            shop.layouts.reloadFrom(layouts)
            shop.categories.reloadFrom(categories)
            closeMenus()
            shop.displays.shutdown()
            shop.displays.spawnAll()
            ticker.start()
            then()
        }
    }

    private fun closeMenus() {
        for (player in server.onlinePlayers) {
            val holder = player.openInventory.topInventory.holder
            if (holder is Menu && holder.owner === shop) player.closeInventory()
        }
    }

    private companion object {
        val RESOURCES = listOf("config.yml", "messages.yml")
    }
}
