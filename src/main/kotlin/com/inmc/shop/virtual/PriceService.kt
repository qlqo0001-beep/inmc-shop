package com.inmc.shop.virtual

import com.inmc.shop.Shop
import com.inmc.shop.data.HistoryRow
import com.inmc.shop.data.TradeRecord
import com.inmc.shop.price.Market
import com.inmc.shop.price.MarketTick
import com.inmc.shop.price.PriceState
import com.inmc.shop.price.Prices
import com.inmc.shop.price.Pricing
import com.inmc.shop.trade.TradeType
import org.bukkit.Bukkit
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

/**
 * 서버 상점 상품의 **지금 가격**.
 *
 * - 상태는 공용 DB(없으면 로컬)의 `shop_price` 한 줄. 메모리에 들고 있다가 바뀌면 쓴다. 다른 서버의 변경은 [sync] 가 읽어 온다.
 * - 시장 가격은 주기가 끝나면 **한 서버만** 계산한다(`claimPeriod`). 모든 서버는 자기가 본 거래를 `shop_market_trade` 에 더해 둔다.
 * - 변동 가격의 다시 굴림도 같은 방식으로 한 서버만(굴릴 시각을 차례 번호로).
 */
class PriceService(private val shop: Shop) {

    private val states = ConcurrentHashMap<String, PriceState>()

    /** (상품, 주기, 사람) → (산 개수, 판 개수). 아직 DB 에 안 더한 것. */
    private val pending = ConcurrentHashMap<Triple<String, Long, UUID>, LongArray>()

    /** 계산이 걸려 있는 상품 — 같은 주기를 두 번 걸지 않게. */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var lastSeen = -1L

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** 켜질 때(쓰기 스레드에서 기다린다). */
    fun load() {
        val rows = shop.db.call { shop.db.prices(shop.db.shared) }
        for (row in rows) if (row.state.isNotBlank()) states[row.key] = PriceState.decode(row.state)
        lastSeen = rows.maxOfOrNull { it.updated } ?: -1
    }

    fun state(product: Product): PriceState = states.getOrPut(product.key) {
        PriceState().also { fresh ->
            val pricing = product.pricing
            if (pricing is Pricing.Floating) {
                Prices.roll(pricing, fresh, ::random)
                fresh.nextRoll = pricing.schedule.next(System.currentTimeMillis(), zone) ?: 0L
            }
        }
    }

    /** 한 단위의 지금 가격. null = 그 방향 거래 불가. */
    fun price(product: Product, type: TradeType): Long? {
        if (!product.tradable(type)) return null
        return Prices.unitPrice(product.pricing, state(product), type, Bukkit.getOnlinePlayers().size)
    }

    /** 지난번 대비 % (추세 표시). 모르면 null. */
    fun trend(product: Product, type: TradeType): Double? {
        val now = price(product, type) ?: return null
        val state = state(product)
        val before = when (val pricing = product.pricing) {
            is Pricing.Dynamic -> pricing.unit(type)?.start
            is Pricing.Online -> (if (type == TradeType.BUY) pricing.buy else pricing.sell)?.start
            else -> if (type == TradeType.BUY) state.prevBuy else state.prevSell
        } ?: return null
        if (before <= 0 || before == now) return null
        return (now - before) * 100.0 / before
    }

    /** 거래가 끝났다. 수요 가격은 바로 움직이고, 시장 가격은 이번 주기 기록에 더한다. */
    fun onTrade(product: Product, type: TradeType, units: Long, player: UUID) {
        val now = System.currentTimeMillis()
        when (product.pricing) {
            is Pricing.Dynamic -> Prices.onTrade(product.pricing, state(product), type, units.toInt().coerceAtLeast(0), now)
            is Pricing.Market -> {
                val period = shop.config.virtual.market.periodOf(now)
                val cell = pending.getOrPut(Triple(product.key, period, player)) { LongArray(2) }
                synchronized(cell) { if (type == TradeType.BUY) cell[0] += units else cell[1] += units }
            }
            else -> Unit
        }
    }

    /** 관리자 — 시세를 처음으로. */
    fun reset(product: Product) {
        state(product).reset()
        state(product).dirty = true
        if (product.pricing is Pricing.Floating) {
            Prices.roll(product.pricing, state(product), ::random)
            state(product).nextRoll = product.pricing.schedule.next(System.currentTimeMillis(), zone) ?: 0L
        }
    }

    fun history(product: Product, limit: Int, then: (List<HistoryRow>) -> Unit) {
        val key = product.key
        shop.db.run("시세 읽기") {
            val rows = shop.db.history(shop.db.shared, key, limit)
            shop.main { then(rows) }
        }
    }

    // --- 티커 ------------------------------------------------------------------------------

    /** 1초마다. */
    fun tick(now: Long) {
        val params = shop.config.virtual.market
        for (product in shop.shops.products()) {
            val pricing = product.pricing
            when (pricing) {
                is Pricing.Dynamic -> Prices.stabilize(pricing, state(product), now)
                is Pricing.Floating -> rollIfDue(product, pricing, now)
                is Pricing.Market -> computeIfDue(product, pricing, now, params)
                else -> Unit
            }
        }
    }

    private fun rollIfDue(product: Product, pricing: Pricing.Floating, now: Long) {
        val state = state(product)
        if (state.nextRoll == 0L || now < state.nextRoll || !inFlight.add(product.key)) return
        val turn = state.nextRoll
        val key = product.key
        shop.db.run("변동 가격 굴리기") {
            // 차례 번호가 시각이라 시장 가격의 주기 번호와 섞이면 안 된다 — 따로 적는 줄.
            val mine = shop.db.claimPeriod(shop.db.shared, "$key#roll", turn, now)
            shop.main {
                inFlight.remove(key)
                val current = shop.shops.product(key) ?: return@main
                val s = state(current)
                if (!mine) {
                    // 다른 서버가 굴렸다 — 값은 sync 가 가져온다. 다음 차례만 맞춰 둔다(안 그러면 매초 다시 잡으려 든다).
                    s.nextRoll = pricing.schedule.next(now, zone) ?: 0L
                    return@main
                }
                s.prevBuy = price(current, TradeType.BUY)
                s.prevSell = price(current, TradeType.SELL)
                Prices.roll(pricing, s, ::random)
                s.nextRoll = pricing.schedule.next(now, zone) ?: 0L
            }
        }
    }

    private fun computeIfDue(product: Product, pricing: Pricing.Market, now: Long, params: com.inmc.shop.price.MarketParams) {
        val current = params.periodOf(now)
        val target = current - 1
        val state = state(product)
        if (state.computedPeriod >= target) return
        if (now < current * params.periodMillis + params.graceSeconds * 1000L) return
        if (!inFlight.add(product.key)) return
        val key = product.key
        flushTrades()
        val sensitivity = pricing.sensitivity
        val prevBuy = price(product, TradeType.BUY)
        val prevSell = price(product, TradeType.SELL)
        val snapshot = PriceState.decode(state.encode())
        shop.db.run("시장 가격 계산") {
            val source = shop.db.shared
            if (!shop.db.claimPeriod(source, key, target, now)) {
                // 다른 서버가 계산했다(또는 이미 했다) — 이 주기는 본 것으로 두고 다음 sync 가 새 값을 가져온다.
                // 안 그러면 매초 다시 차례를 잡으려 든다.
                shop.main {
                    inFlight.remove(key)
                    shop.shops.product(key)?.let { live -> state(live).computedPeriod = maxOf(state(live).computedPeriod, target) }
                }
                return@run
            }
            val tick = MarketTick.of(shop.db.tradesOf(source, key, target), params.userCap)
            Market.step(snapshot, tick, params, sensitivity, ::random)
            snapshot.computedPeriod = target
            snapshot.prevBuy = prevBuy
            snapshot.prevSell = prevSell
            val encoded = snapshot.encode()
            shop.db.savePrice(source, key, encoded, target, now)
            shop.main {
                inFlight.remove(key)
                val live = shop.shops.product(key) ?: return@main
                state(live).copyFrom(snapshot)
                state(live).dirty = false
                val buy = price(live, TradeType.BUY)
                val sell = price(live, TradeType.SELL)
                val m = snapshot.m
                shop.db.run("시세 기록") {
                    shop.db.addHistory(source, key, HistoryRow(target, buy, sell, m, tick.volume, tick.users))
                    shop.db.purgeTrades(source, target - 2)
                    shop.db.purgeHistory(source, target - 24 * 14)
                }
            }
        }
    }

    /** 몇 초마다 — 바뀐 상태와 모은 거래를 DB 에 쓰고, 공용 DB 면 다른 서버의 변경을 읽는다. */
    fun sync(now: Long) {
        flushTrades()
        for ((key, state) in states) {
            if (!state.dirty || key in inFlight) continue
            state.dirty = false
            val encoded = state.encode()
            val period = state.computedPeriod
            shop.db.run("가격 저장") { shop.db.savePrice(shop.db.shared, key, encoded, period, now) }
        }
        if (shop.db.network == null) return
        val since = lastSeen
        shop.db.run("가격 읽기") {
            val rows = shop.db.prices(shop.db.shared, since)
            if (rows.isEmpty()) return@run
            shop.main {
                lastSeen = maxOf(lastSeen, rows.maxOf { it.updated })
                for (row in rows) {
                    if (row.state.isBlank() || row.key in inFlight) continue
                    val mine = states[row.key]
                    if (mine != null && mine.dirty) continue
                    val theirs = PriceState.decode(row.state)
                    if (mine == null) states[row.key] = theirs else mine.copyFrom(theirs)
                }
            }
        }
    }

    fun flushTrades() {
        if (pending.isEmpty()) return
        val records = ArrayList<TradeRecord>()
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val (key, cell) = it.next()
            it.remove()
            val (b, s) = synchronized(cell) { cell[0] to cell[1] }
            if (b > 0 || s > 0) records += TradeRecord(key.first, key.second, key.third, b, s)
        }
        if (records.isNotEmpty()) shop.db.run("시장 기록") { shop.db.addTrades(shop.db.shared, records) }
    }

    /** 끌 때 — 줄 선 것까지 다 쓴다(쓰기 스레드가 닫히기 전에). */
    fun shutdown() = sync(System.currentTimeMillis())

    /** 상품이 지워졌다 — 가격·재고·시세를 지운다. */
    fun forget(product: Product) {
        states.remove(product.key)
        val key = product.key
        shop.db.run("상품 지우기") {
            shop.db.deletePrice(shop.db.shared, key)
            shop.db.clearLimits(shop.db.shared, key)
        }
    }

    private fun random(): Double = ThreadLocalRandom.current().nextDouble()
}
