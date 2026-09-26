package com.inmc.shop

import com.inmc.shop.price.Band
import com.inmc.shop.price.DynamicUnit
import com.inmc.shop.price.OnlineUnit
import com.inmc.shop.price.PriceState
import com.inmc.shop.price.Prices
import com.inmc.shop.price.Pricing
import com.inmc.shop.price.Schedule
import com.inmc.shop.trade.ClickAction
import com.inmc.shop.trade.ClickKind
import com.inmc.shop.trade.ClickMap
import com.inmc.shop.trade.ProductState
import com.inmc.shop.trade.TradeType
import com.inmc.shop.virtual.LimitOptions
import com.inmc.shop.virtual.LimitState
import com.inmc.shop.virtual.Product
import com.inmc.shop.virtual.Quantity
import com.inmc.shop.virtual.RankValues
import com.inmc.shop.virtual.Requirements
import com.inmc.shop.virtual.Rotation
import com.inmc.shop.virtual.Rotations
import com.inmc.shop.virtual.StockOptions
import com.inmc.shop.virtual.StockState
import com.inmc.shop.virtual.Stocks
import com.inmc.shop.virtual.VirtualShop
import org.bukkit.configuration.file.YamlConfiguration
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RulesTest {

    @Test
    fun `재고 — 사면 줄고 팔면 늘되 용량까지, 다시 채움은 최소~최대`() {
        val options = StockOptions(true, 10, 4, 6, 60)
        val state = StockState(3, 0)
        assertEquals(3, Stocks.room(options, state, TradeType.BUY))
        assertEquals(7, Stocks.room(options, state, TradeType.SELL))
        Stocks.apply(options, state, TradeType.SELL, 100)
        assertEquals(10, state.units, "용량을 넘지 않는다")
        Stocks.apply(options, state, TradeType.BUY, 100)
        assertEquals(0, state.units, "음수가 되지 않는다")
        repeat(50) { val v = Stocks.refillAmount(options) { Random.nextDouble() }; assertTrue(v in 4..6, "$v") }
        state.restockAt = 1000
        assertFalse(Stocks.restockIfDue(options, state, 999) { 0.0 })
        assertTrue(Stocks.restockIfDue(options, state, 1000) { 0.0 })
        assertEquals(4, state.units)
        assertEquals(1000 + 60_000, state.restockAt)
        assertEquals(Long.MAX_VALUE, Stocks.room(StockOptions(enabled = false), null, TradeType.BUY), "재고가 꺼지면 무제한")
    }

    @Test
    fun `한도 — 줄기만 하고, 처음 쓸 때 초기화 시각을 잡는다, -1 은 평생`() {
        val options = LimitOptions(true, 3, -1, 60)
        val state = LimitState()
        assertEquals(3, Stocks.remaining(options, state, TradeType.BUY))
        assertEquals(Long.MAX_VALUE, Stocks.remaining(options, state, TradeType.SELL))
        Stocks.use(options, state, TradeType.BUY, 2, 1000)
        assertEquals(1, Stocks.remaining(options, state, TradeType.BUY))
        assertEquals(61_000, state.resetAt)
        Stocks.use(options, state, TradeType.SELL, 5, 2000)
        assertEquals(1, Stocks.remaining(options, state, TradeType.BUY), "판매가 구매 한도를 되살리지 않는다")
        assertTrue(Stocks.resetIfDue(options, state, 61_000))
        assertEquals(3, Stocks.remaining(options, state, TradeType.BUY))
        val lifetime = LimitOptions(true, 1, -1, -1)
        val once = LimitState()
        Stocks.use(lifetime, once, TradeType.BUY, 1, 0)
        assertEquals(0, once.resetAt)
        assertFalse(Stocks.resetIfDue(lifetime, once, Long.MAX_VALUE), "평생 한도는 초기화되지 않는다")
    }

    @Test
    fun `회전 — 가중치에 비례, 겹치지 않고 칸 수만큼`() {
        val random = Random(7)
        val counts = HashMap<String, Int>()
        repeat(10_000) { for (id in Rotations.pick(listOf("a" to 1.0, "b" to 3.0), 1) { random.nextDouble() }) counts.merge(id, 1, Int::plus) }
        val ratio = counts.getValue("b").toDouble() / counts.getValue("a")
        assertTrue(ratio in 2.6..3.4, "가중치 3:1 이어야 한다: $ratio")
        val picks = Rotations.pick(listOf("a" to 1.0, "b" to 1.0, "c" to 1.0), 5) { random.nextDouble() }
        assertEquals(3, picks.size, "후보보다 많이 뽑지 않는다")
        assertEquals(3, picks.toSet().size, "겹치지 않는다")
        assertTrue(Rotations.pick(listOf("z" to 0.0), 1) { 0.5 }.isEmpty(), "가중치 0 은 안 뽑힌다")
    }

    @Test
    fun `등급별 값 — 맞는 것 중 가장 크고, -1 은 무제한`() {
        val values = RankValues(RankValues.Mode.RANK, "", 1.0, mapOf("vip" to 1.5, "gold" to 2.0, "admin" to -1.0))
        assertEquals(1.0, values.valueFor { false })
        assertEquals(2.0, values.valueFor { it == "group.vip" || it == "group.gold" })
        assertEquals(-1.0, values.valueFor { it == "group.vip" || it == "group.admin" })
        val perm = RankValues(RankValues.Mode.PERMISSION, "x.y.", 5.0, mapOf("big" to 9.0))
        assertEquals(9.0, perm.valueFor { it == "x.y.big" })
    }

    @Test
    fun `요구 조건 — 허용·금지 등급과 권한`() {
        val r = Requirements(ranks = listOf("vip"), forbiddenPermissions = listOf("no.buy"))
        assertFalse(r.allows { false })
        assertTrue(r.allows { it == "group.vip" })
        assertFalse(r.allows { it == "group.vip" || it == "no.buy" })
        assertTrue(Requirements().allows { false })
    }

    @Test
    fun `최대 수량 — 돈·재고·한도·공간 중 가장 작은 것`() {
        assertEquals(3, Quantity.max(Quantity.affordable(35, 10), 99, 99, 99))
        assertEquals(2, Quantity.max(99, 2, 99, 99))
        assertEquals(0, Quantity.max(99, 99, 0, 99))
        assertEquals(Long.MAX_VALUE, Quantity.affordable(0, 0), "공짜면 돈으로 막히지 않는다")
    }

    @Test
    fun `일정 — 간격과 요일·시각`() {
        val zone = ZoneId.of("Asia/Seoul")
        assertEquals(5_000 + 60_000, Schedule.Interval(60).next(5_000, zone))
        // 2026-09-25 는 금요일
        val friday10 = LocalDateTime.of(2026, 9, 25, 10, 0).atZone(zone).toInstant().toEpochMilli()
        val weekly = Schedule.Weekly(setOf(DayOfWeek.MONDAY), listOf(LocalTime.of(9, 0)))
        val next = weekly.next(friday10, zone)!!
        assertEquals(LocalDateTime.of(2026, 9, 28, 9, 0).atZone(zone).toInstant().toEpochMilli(), next)
        val daily = Schedule.Weekly(emptySet(), listOf(LocalTime.of(12, 0), LocalTime.of(9, 0)))
        assertEquals(LocalDateTime.of(2026, 9, 25, 12, 0).atZone(zone).toInstant().toEpochMilli(), daily.next(friday10, zone))
        assertNull(Schedule.Weekly(emptySet(), emptyList()).next(friday10, zone))
    }

    @Test
    fun `가격 — 고정·변동·수요·접속자`() {
        val state = PriceState()
        assertEquals(10, Prices.unitPrice(Pricing.Fixed(10, null), state, TradeType.BUY, 0))
        assertNull(Prices.unitPrice(Pricing.Fixed(10, null), state, TradeType.SELL, 0), "가격이 없으면 그 방향은 꺼짐")

        val floating = Pricing.Floating(10L..20L, 5L..30L, Schedule.Interval(60))
        repeat(100) {
            Prices.roll(floating, state) { Random.nextDouble() }
            val buy = Prices.unitPrice(floating, state, TradeType.BUY, 0)!!
            val sell = Prices.unitPrice(floating, state, TradeType.SELL, 0)!!
            assertTrue(buy in 10..20, "$buy")
            assertTrue(sell <= buy, "판매가가 구매가를 넘지 않는다 $sell > $buy")
        }

        val dynamic = Pricing.Dynamic(DynamicUnit(100, 1.0, -1.0, -10.0, 10.0), DynamicUnit(50, 1.0, -1.0, -10.0, 10.0), 60, 5.0)
        val d = PriceState()
        Prices.onTrade(dynamic, d, TradeType.BUY, 3, 0)
        assertEquals(103, Prices.unitPrice(dynamic, d, TradeType.BUY, 0))
        Prices.onTrade(dynamic, d, TradeType.BUY, 100, 0)
        assertEquals(110, Prices.unitPrice(dynamic, d, TradeType.BUY, 0), "최대 %에서 멈춘다")
        assertFalse(Prices.stabilize(dynamic, d, 59_999))
        assertTrue(Prices.stabilize(dynamic, d, 60_000))
        assertEquals(105, Prices.unitPrice(dynamic, d, TradeType.BUY, 0), "안정화는 한 번에 5%씩")

        val online = Pricing.Online(OnlineUnit(100, 2.0, -50.0, 20.0), null)
        assertEquals(110, Prices.unitPrice(online, state, TradeType.BUY, 5))
        assertEquals(120, Prices.unitPrice(online, state, TradeType.BUY, 100), "최대 %")
    }

    @Test
    fun `가격 방식은 저장해도 그대로 읽힌다`() {
        val all = listOf(
            Pricing.Fixed(10, 3), Pricing.Fixed(null, 7),
            Pricing.Market(Band(100, 50, 200), null, 1.5),
            Pricing.Floating(1L..9L, null, Schedule.Weekly(setOf(DayOfWeek.FRIDAY), listOf(LocalTime.of(21, 30)))),
            Pricing.Dynamic(DynamicUnit(5, 0.5, -0.5, -30.0, 30.0), null, 120, 2.5),
            Pricing.Online(null, OnlineUnit(8, -1.0, -50.0, 0.0)),
        )
        for (p in all) {
            val y = YamlConfiguration()
            p.save(y.createSection("price"))
            val back = YamlConfiguration().apply { loadFromString(y.saveToString()) }
            assertEquals(p, Pricing.load(back.getConfigurationSection("price")), p.kind.name)
        }
    }

    @Test
    fun `서버 상점 정의는 저장해도 그대로 읽힌다`() {
        val product = Product(
            "p1", "s", unit = 16, name = "<red>이름", lore = listOf("한 줄"), commands = listOf("say {player}"), currency = "cash",
            pricing = Pricing.Fixed(5, 2), stock = StockOptions(true, 50, 10, 20, 300), limits = LimitOptions(true, 3, 4, -1),
            requirements = Requirements(listOf("vip"), listOf("ban"), listOf("a.b"), listOf("c.d")), page = 2, slot = 13, rotating = true, weight = 2.5, hidden = true,
        )
        val shop = VirtualShop(
            "s", "<green>상점", listOf("설명"), permissionRequired = true, buying = false, pages = 3, menuSlot = 11, layout = "wide",
            pageLayouts = mapOf(2 to "tall"), aliases = listOf("블록"), currency = "money", products = mapOf("p1" to product),
            rotations = mapOf("daily" to Rotation("daily", Schedule.Interval(3600), mapOf(1 to setOf(10, 11)), listOf("p1"), false)),
        )
        val back = YamlConfiguration().apply { loadFromString(shop.toYaml().saveToString()) }
        assertEquals(shop, VirtualShop.load("s", back))
    }

    @Test
    fun `상품 칸 정리 — 보이는 상품이 앞, 숨긴 상품이 뒤, 각자의 순서는 그대로`() {
        fun p(id: String, page: Int, slot: Int, hidden: Boolean = false, rotating: Boolean = false) = Product(id, "s", page = page, slot = slot, hidden = hidden, rotating = rotating)
        val shop = VirtualShop("s", products = listOf(
            p("a", 1, 5, hidden = true), p("b", 1, 9), p("c", 2, 0), p("d", 1, 2, hidden = true), p("e", 1, 30), p("r", 1, -1, rotating = true),
        ).associateBy { it.id })
        val positions = listOf(1 to 0, 1 to 1, 1 to 3, 2 to 0, 2 to 1, 2 to 2)
        val organized = shop.organized(positions)!!
        fun at(id: String) = organized.products.getValue(id).let { it.page to it.slot }
        assertEquals(listOf(1 to 0, 1 to 1, 1 to 3), listOf(at("b"), at("e"), at("c")), "보이는 상품 — 원래 순서대로 앞에서부터")
        assertEquals(listOf(2 to 0, 2 to 1), listOf(at("d"), at("a")), "숨긴 상품 — 그 뒤에")
        assertEquals(1 to -1, at("r"), "회전 상품은 건드리지 않는다")
        assertNull(shop.organized(positions.take(4)), "칸이 모자라면 정리하지 않는다")
    }

    @Test
    fun `클릭 동작은 저장해도 그대로 읽히고, 없는 칸은 기본값`() {
        val changed = ClickMap.VIRTUAL.with(ProductState.BOTH, ClickKind.LEFT, ClickAction.BUY_ONE)
        val y = YamlConfiguration()
        changed.save(y.createSection("c"))
        val back = YamlConfiguration().apply { loadFromString(y.saveToString()) }
        assertEquals(changed, ClickMap.load(back.getConfigurationSection("c"), ClickMap.VIRTUAL))
        assertEquals(ClickAction.NONE, changed.actionFor(ProductState.BUYABLE, ClickKind.DROP))
    }
}
