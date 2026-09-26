package com.inmc.shop

import com.inmc.shop.auction.AuctionService
import com.inmc.shop.chest.BankService
import com.inmc.shop.chest.ChestService
import com.inmc.shop.chest.DisplayService
import com.inmc.shop.config.Messages
import com.inmc.shop.config.ModuleCurrency
import com.inmc.shop.config.ShopConfig
import com.inmc.shop.data.ShopDb
import com.inmc.shop.sell.SellService
import com.inmc.shop.trade.ReturnService
import com.inmc.shop.trade.TradeLog
import com.inmc.shop.util.Ph
import com.inmc.shop.virtual.LayoutRegistry
import com.inmc.shop.virtual.LimitService
import com.inmc.shop.virtual.PriceService
import com.inmc.shop.virtual.RotationService
import com.inmc.shop.virtual.ShopRegistry
import com.inmc.shop.virtual.StockService
import com.inmc.shop.virtual.VirtualTrades
import kr.inmc.core.CorePlugin
import kr.inmc.core.InmcHost
import kr.inmc.core.config.ConfigService
import kr.inmc.core.economy.Currencies
import kr.inmc.core.economy.Currency
import kr.inmc.core.integration.CustomItemHook
import kr.inmc.core.integration.MMOItemsHook
import kr.inmc.core.item.ItemMatcher
import kr.inmc.core.item.ItemResolver
import kr.inmc.core.store.Profile
import kr.inmc.core.util.Placeholders
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/** 플러그인을 엮는 서비스 로케이터. */
class Shop(override val plugin: JavaPlugin) : InmcHost {

    val logger: java.util.logging.Logger = plugin.logger

    override val io = ConfigService(plugin)

    override fun tell(target: CommandSender, key: String, ph: Placeholders?) = messages.send(target, key, ph as? Ph)

    @Volatile
    var config: ShopConfig = ShopConfig()

    @Volatile
    var messages: Messages = Messages.from(YamlConfiguration())

    val customItems = CustomItemHook(logger)
    val mmoItems = MMOItemsHook(logger)
    val resolver = ItemResolver(mmoItems, customItems, logger)
    val matcher = ItemMatcher(mmoItems, customItems)

    val db = ShopDb(logger)
    val log = TradeLog(this)
    val returns = ReturnService(this)

    val shops = ShopRegistry(this)
    val layouts = LayoutRegistry(this)
    val prices = PriceService(this)
    val stocks = StockService(this)
    val limits = LimitService(this)
    val rotations = RotationService(this)
    val trades = VirtualTrades(this)
    val sell = SellService(this)

    val bank = BankService(this)
    val chests = ChestService(this)
    val displays = DisplayService(this)

    val auction = AuctionService(this)

    val categories = com.inmc.shop.auction.CategoryRegistry(this)

    val verifier = com.inmc.shop.verify.Verifier(this)

    /** 설정 화면이 고친다 — 통째로 바꿔 끼우고 `config.yml` 을 다시 쓴다(머리말 유지). */
    fun updateConfig(change: (ShopConfig) -> ShopConfig) {
        config = change(config)
        val y = config.toYaml()
        y.options().setHeader(CONFIG_HEADER)
        io.asyncRun { io.save(io.file("config.yml"), y) }
    }

    // --- 공통 규칙 ---------------------------------------------------------------------------

    /** 허용 월드 · 서바이벌. 막히면 메시지를 보내고 false. */
    fun guard(player: Player, silent: Boolean = false): Boolean {
        if (player.world.name !in config.worlds && !player.hasPermission("inmcshop.bypass.worlds")) {
            if (!silent) messages.send(player, "world-not-allowed")
            return false
        }
        if (player.gameMode != GameMode.SURVIVAL && !player.hasPermission("inmcshop.bypass.gamemode")) {
            if (!silent) messages.send(player, "survival-only")
            return false
        }
        return true
    }

    /** 화폐 — 빈 id 는 모듈 기본, 그것도 비면 core 의 기본 화폐. */
    fun currency(id: String?, module: ModuleCurrency): Currency? {
        val wanted = id?.takeIf { it.isNotBlank() } ?: module.default.takeIf { it.isNotBlank() }
        return Currencies.get(wanted)
    }

    /** 이 사람이 이 화폐를 쓸 수 있나(화폐 권한 옵션). */
    fun canUse(player: Player, currency: Currency, module: ModuleCurrency): Boolean {
        if (!module.allows(currency.id) && currency.id != Currencies.default()?.id) return false
        if (!config.currencyNeedsPermission || currency.id.equals(module.default, true)) return true
        return player.hasPermission("inmcshop.currency." + currency.id) || player.hasPermission("inmcshop.currency.*")
    }

    fun nameOf(player: UUID): String =
        Bukkit.getPlayer(player)?.name
            ?: runCatching { Profile.nameOf(CorePlugin.get().players, player) }.getOrNull()
            ?: Bukkit.getOfflinePlayer(player).name
            ?: player.toString().take(8)

    fun findPlayer(name: String): UUID? {
        Bukkit.getPlayerExact(name)?.let { return it.uniqueId }
        val store = runCatching { CorePlugin.get().players }.getOrNull()
        if (store != null) for (id in store.knownPlayers()) if (Profile.nameOf(store, id).equals(name, ignoreCase = true)) return id
        return Bukkit.getOfflinePlayerIfCached(name)?.uniqueId
    }

    /** 메인 스레드에서 — DB 콜백을 돌려보낼 때. */
    fun main(block: () -> Unit) {
        if (Bukkit.isPrimaryThread()) block() else Bukkit.getGlobalRegionScheduler().run(plugin) { block() }
    }

    /** PlaceholderAPI 가 있으면 [com.inmc.shop.hook.PapiHook] 이 꽂는다(명령어 상품의 %…%). PAPI 클래스는 훅 밖에서 건드리지 않는다. */
    @Volatile
    var papi: ((Player, String) -> String)? = null

    @Volatile
    var ready: Boolean = false
        private set

    fun markReady() {
        ready = true
    }

    companion object {
        val CONFIG_HEADER: List<String> = listOf(
            "INMC 상점 설정. /상점 관리 → 설정 에서 GUI 로 고치는 것을 권장합니다 — 화면에서 고치면 이 파일을 통째로 다시 씁니다.",
            "network.url 을 적으면(jdbc:mysql://…) 여러 서버가 재고·시세·경매·은행을 같이 씁니다. 바꾸면 재시작하세요.",
            "worlds: 상점이 동작하는 월드(화이트리스트). 게임모드는 서바이벌만(관리자는 inmcshop.bypass.gamemode).",
        )
    }
}
