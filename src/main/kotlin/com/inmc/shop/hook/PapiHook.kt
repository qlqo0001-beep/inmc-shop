package com.inmc.shop.hook

import com.inmc.shop.Shop
import com.inmc.shop.auction.AuctionService
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player

/**
 * `%inmcshop_…%` — ExcellentShop 의 PAPI 식별자를 같은 뜻으로(접두사만 `inmcshop`).
 * PlaceholderAPI 클래스는 compileOnly 라 **플러그인이 있을 때만** 건드린다. [setup] 이 유일한 문이다.
 */
class PapiHook(private val shop: Shop) {

    private var expansion: ShopExpansion? = null

    /** 켰으면 true. */
    fun setup(): Boolean {
        teardown()
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return false
        return try {
            shop.papi = { player, text -> me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text) }
            expansion = ShopExpansion(shop).also { it.register() }
            shop.logger.info("PlaceholderAPI 연동 활성화 (%inmcshop_...%)")
            true
        } catch (t: Throwable) {
            shop.logger.warning("PlaceholderAPI 연동 실패: ${t.message}")
            teardown()
            false
        }
    }

    fun teardown() {
        expansion?.let { runCatching { it.unregister() } }
        expansion = null
        shop.papi = null
    }
}

/** 따로 둔 것은 PlaceholderAPI 가 있을 때만 이 클래스가 적재되게 하려는 것이다. */
private class ShopExpansion(private val shop: Shop) : PlaceholderExpansion() {

    override fun getIdentifier(): String = "inmcshop"
    override fun getAuthor(): String = "INMC"
    override fun getVersion(): String = shop.plugin.pluginMeta.version
    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        val p = params.lowercase()
        val auction = shop.auction
        when (p) {
            "auction_all_active_listings" -> return auction.active().size.toString()
            "auction_all_completed_listings" -> return auction.completedCount().toString()
        }
        val id = player?.uniqueId ?: return ""
        val online = player as? Player ?: Bukkit.getPlayer(id)
        return when {
            p == "auction_max_listings" -> online?.let { auction.maxListings(it).let { n -> if (n < 0) "무제한" else n.toString() } } ?: ""
            p == "auction_active_listings" -> auction.activeOf(id).size.toString()
            p == "auction_unclaimed_listings" -> auction.ofSeller(id, AuctionService.SOLD).size.toString()
            p == "auction_expired_listings" -> auction.expiredOf(id).size.toString()
            p == "auction_claimed_listings" -> auction.ofSeller(id, AuctionService.CLAIMED).size.toString()
            p.startsWith("auction_unclaimed_income_raw_") -> income(id, AuctionService.SOLD, p.removePrefix("auction_unclaimed_income_raw_"), raw = true)
            p.startsWith("auction_unclaimed_income_") -> income(id, AuctionService.SOLD, p.removePrefix("auction_unclaimed_income_"), raw = false)
            p.startsWith("auction_claimed_income_raw_") -> income(id, AuctionService.CLAIMED, p.removePrefix("auction_claimed_income_raw_"), raw = true)
            p.startsWith("auction_claimed_income_") -> income(id, AuctionService.CLAIMED, p.removePrefix("auction_claimed_income_"), raw = false)
            p == "chestshop_max_shops" -> online?.let { shop.config.chest.maxShops.valueFor(it::hasPermission).toLong().let { n -> if (n < 0) "무제한" else n.toString() } } ?: ""
            p == "chestshop_products_per_shop" -> online?.let { shop.config.chest.maxProducts.valueFor(it::hasPermission).toLong().let { n -> if (n < 0) "무제한" else n.toString() } } ?: ""
            p == "chestshop_shops" -> shop.chests.ownedBy(id).size.toString()
            p == "virtualshop_sell_multiplier" -> online?.let { "%.2f".format(shop.trades.sellMultiplier(it)).trimEnd('0').trimEnd('.') } ?: ""
            else -> null
        }
    }

    private fun income(id: java.util.UUID, state: String, currencyId: String, raw: Boolean): String {
        val total = shop.auction.ofSeller(id, state).filter { it.currency.equals(currencyId, true) }.sumOf { it.payout }
        if (raw) return total.toString()
        return shop.currency(currencyId, shop.config.auction.currency)?.format(total) ?: total.toString()
    }
}
