package com.inmc.shop

import com.inmc.shop.price.Activity
import com.inmc.shop.price.Band
import com.inmc.shop.price.Market
import com.inmc.shop.price.MarketParams
import com.inmc.shop.price.MarketTick
import com.inmc.shop.price.PriceState
import com.inmc.shop.price.Prices
import com.inmc.shop.price.Pricing
import com.inmc.shop.trade.TradeType
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 시장 가격의 성질 — 사용자 요구(2026-09-25)를 하나씩 단정한다. 난수는 고정 씨앗.
 */
class MarketTest {

    private val params = MarketParams()
    private val mid = { 0.5 }   // 흔들림 0

    private fun run(ticks: List<MarketTick>, start: PriceState = PriceState(), random: () -> Double = mid): PriceState {
        for (tick in ticks) Market.step(start, tick, params, 1.0, random)
        return start
    }

    @Test
    fun `고래 한 명은 cap 까지만 센다`() {
        val tick = MarketTick.of(listOf(0L to 100_000L, 0L to 10L), params.userCap)
        assertEquals(params.userCap + 10, tick.sold)
        assertEquals(2, tick.users)
    }

    @Test
    fun `참여자가 적으면 덜 움직인다`() {
        val alone = run(listOf(MarketTick(0, 500, 1)))
        val crowd = run(listOf(MarketTick(0, 500, 5)))
        assertTrue(1 - alone.m < 1 - crowd.m, "혼자 판 것(${alone.m})이 다섯이 판 것(${crowd.m})보다 덜 내려야 한다")
    }

    @Test
    fun `거래량을 그대로 싣지 않는다 — 조금 판 것은 조금 움직인다`() {
        val few = run(listOf(MarketTick(0, 4, 5)))
        val many = run(listOf(MarketTick(0, 4000, 5)))
        assertTrue(1 - few.m < (1 - many.m) / 3, "4개(${few.m})가 4000개(${many.m})의 1/3 보다 덜 움직여야 한다")
    }

    @Test
    fun `한 주기에 최대 변동폭을 넘지 않는다`() {
        for (tick in listOf(MarketTick(0, 1_000_000, 100), MarketTick(1_000_000, 0, 100))) {
            val state = run(listOf(tick))
            assertTrue(abs(state.m - 1) <= params.maxStep + 1e-9, "${state.m}")
        }
    }

    @Test
    fun `계속 팔면 내려가되 바닥에서 멈춘다`() {
        val state = PriceState()
        var last = state.m
        repeat(200) {
            Market.step(state, MarketTick(0, 2000, 10), params, 1.0, mid)
            assertTrue(state.m <= last + 1e-9, "파는데 오르면 안 된다")
            last = state.m
        }
        assertTrue(state.m >= params.minMultiplier)
        assertTrue(state.m < 0.6, "200주기 판매 뒤 ${state.m}")
        // 상품의 최저가 아래로는 안 간다
        val pricing = Pricing.Market(Band(100, 70, 300), Band(20, 15, 60))
        assertEquals(15, Prices.unitPrice(pricing, state, TradeType.SELL, 0))
    }

    @Test
    fun `아무도 안 사고팔면 값이 오르고 흔들림이 커진다`() {
        val busy = run(List(20) { MarketTick(300, 300, 10) })
        val quiet = run(List(200) { MarketTick.EMPTY })
        assertTrue(quiet.m > 1.5, "한산하면 올라야 한다: ${quiet.m}")
        assertTrue(quiet.m < 2.2, "복원력과 맞물려 약 2배에서 멈춰야 한다: ${quiet.m}")
        assertTrue(Market.noiseOf(quiet, params) > Market.noiseOf(busy, params), "한산하면 흔들림이 커야 한다")
        assertEquals(Activity.QUIET, Market.activity(quiet, params))
        assertEquals(Activity.BUSY, Market.activity(busy, params))
    }

    @Test
    fun `거래가 돌아오면 한산 보정이 풀린다`() {
        val state = run(List(50) { MarketTick.EMPTY })
        val before = state.dormancy
        run(List(10) { MarketTick(200, 200, 8) }, state)
        assertTrue(state.dormancy < before / 10, "${state.dormancy} vs $before")
    }

    @Test
    fun `장기 추세가 남는다 — 판매 폭주 뒤 잠잠해도 한동안 압력이 음수다`() {
        val state = run(List(30) { MarketTick(0, 3000, 10) })
        run(List(3) { MarketTick(100, 100, 10) }, state)
        assertTrue(state.emaShort > state.emaLong, "단기는 빨리 돌아오고")
        assertTrue(state.emaLong < -0.1, "장기는 기억한다: ${state.emaLong}")
    }

    @Test
    fun `흔들림은 σ 안에 있다`() {
        val random = Random(42)
        val state = PriceState()
        repeat(500) {
            Market.step(state, MarketTick(random.nextLong(0, 500), random.nextLong(0, 500), random.nextInt(0, 12)), params, 1.0) { random.nextDouble() }
            assertTrue(abs(state.noise) <= Market.noiseOf(state, params) + 1e-12)
        }
    }

    @Test
    fun `판매가는 구매가를 넘지 않는다`() {
        val state = PriceState().apply { m = 1.0; noise = 0.0 }
        val pricing = Pricing.Market(Band(100, 50, 200), Band(150, 50, 300))
        val buy = Prices.unitPrice(pricing, state, TradeType.BUY, 0)!!
        val sell = Prices.unitPrice(pricing, state, TradeType.SELL, 0)!!
        assertTrue(sell <= buy, "$sell > $buy")
    }

    @Test
    fun `상태는 적은 그대로 읽힌다`() {
        val state = run(List(7) { MarketTick(40, 90, 3) }).apply { prevBuy = 120; rolledSell = 7; computedPeriod = 99 }
        val back = PriceState.decode(state.encode())
        assertEquals(state.encode(), back.encode())
    }
}
