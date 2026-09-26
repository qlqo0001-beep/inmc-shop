package com.inmc.shop.price

import com.inmc.shop.trade.TradeType
import org.bukkit.configuration.ConfigurationSection
import kotlin.math.roundToLong

enum class PriceKind(val label: String, val summary: String) {
    FIXED("고정", "정한 값 그대로"),
    MARKET("시장 가격", "거래량·참여자·추세로 주기마다 움직인다"),
    FLOAT("변동", "최소~최대 사이에서 정해진 때마다 무작위"),
    DYNAMIC("수요", "살 때마다·팔 때마다 %씩 오르내린다"),
    ONLINE("접속자", "접속자 한 명당 %씩 오르내린다"),
}

/** 한쪽 가격의 기본·최저·최고(시장 가격). */
data class Band(val base: Long, val min: Long, val max: Long) {
    fun normalized(): Band {
        val lo = min.coerceAtLeast(0)
        val hi = max.coerceAtLeast(lo)
        return Band(base.coerceIn(lo, hi), lo, hi)
    }
}

/** 수요 가격의 한쪽. 거래 1개마다 [buyStep]%(누가 샀을 때)·[sellStep]%(누가 팔았을 때)씩 움직이고, [minPercent]~[maxPercent] 로 자른다. */
data class DynamicUnit(val start: Long, val buyStep: Double, val sellStep: Double, val minPercent: Double, val maxPercent: Double) {
    fun clamp(offset: Double): Double = offset.coerceIn(minOf(minPercent, maxPercent), maxOf(minPercent, maxPercent))
    fun step(type: TradeType): Double = if (type == TradeType.BUY) buyStep else sellStep
}

/** 접속자 가격의 한쪽. 접속자 한 명당 [perPlayer]%. */
data class OnlineUnit(val start: Long, val perPlayer: Double, val minPercent: Double, val maxPercent: Double) {
    fun clamp(offset: Double): Double = offset.coerceIn(minOf(minPercent, maxPercent), maxOf(minPercent, maxPercent))
}

/**
 * 상품 가격을 **어떻게 정하나.** 값(지금 가격)은 [PriceState] 에 있고, 계산은 [Prices].
 * 한쪽(구매/판매)이 null 이면 그 방향은 꺼져 있다 — "가격 없음 = 거래 불가" 한 가지 규칙.
 */
sealed interface Pricing {
    val kind: PriceKind

    fun enabled(type: TradeType): Boolean

    fun save(section: ConfigurationSection)

    /** 이 방향을 끈 사본(관리자가 "구매 끄기" 를 누를 때). */
    fun without(type: TradeType): Pricing

    data class Fixed(val buy: Long?, val sell: Long?) : Pricing {
        override val kind get() = PriceKind.FIXED
        override fun enabled(type: TradeType) = (if (type == TradeType.BUY) buy else sell) != null
        override fun without(type: TradeType) = if (type == TradeType.BUY) copy(buy = null) else copy(sell = null)
        override fun save(section: ConfigurationSection) {
            section.set("type", kind.name)
            section.set("buy", buy ?: -1L)
            section.set("sell", sell ?: -1L)
        }
    }

    data class Market(val buy: Band?, val sell: Band?, val sensitivity: Double = 1.0) : Pricing {
        override val kind get() = PriceKind.MARKET
        override fun enabled(type: TradeType) = (if (type == TradeType.BUY) buy else sell) != null
        override fun without(type: TradeType) = if (type == TradeType.BUY) copy(buy = null) else copy(sell = null)
        override fun save(section: ConfigurationSection) {
            section.set("type", kind.name)
            saveBand(section, "buy", buy)
            saveBand(section, "sell", sell)
            section.set("sensitivity", sensitivity)
        }
    }

    data class Floating(val buy: LongRange?, val sell: LongRange?, val schedule: Schedule) : Pricing {
        override val kind get() = PriceKind.FLOAT
        override fun enabled(type: TradeType) = (if (type == TradeType.BUY) buy else sell) != null
        override fun without(type: TradeType) = if (type == TradeType.BUY) copy(buy = null) else copy(sell = null)
        override fun save(section: ConfigurationSection) {
            section.set("type", kind.name)
            section.set("buy", buy?.let { "${it.first}-${it.last}" } ?: "-1")
            section.set("sell", sell?.let { "${it.first}-${it.last}" } ?: "-1")
            schedule.save(section.createSection("refresh"))
        }
    }

    data class Dynamic(val buy: DynamicUnit?, val sell: DynamicUnit?, val stabilizeSeconds: Long, val stabilizePercent: Double) : Pricing {
        override val kind get() = PriceKind.DYNAMIC
        override fun enabled(type: TradeType) = (if (type == TradeType.BUY) buy else sell) != null
        override fun without(type: TradeType) = if (type == TradeType.BUY) copy(buy = null) else copy(sell = null)
        override fun unit(type: TradeType): DynamicUnit? = if (type == TradeType.BUY) buy else sell
        override fun save(section: ConfigurationSection) {
            section.set("type", kind.name)
            for ((key, unit) in listOf("buy" to buy, "sell" to sell)) {
                if (unit == null) { section.set(key, -1L); continue }
                val node = section.createSection(key)
                node.set("start", unit.start)
                node.set("buy-step", unit.buyStep)
                node.set("sell-step", unit.sellStep)
                node.set("min-percent", unit.minPercent)
                node.set("max-percent", unit.maxPercent)
            }
            section.set("stabilize-seconds", stabilizeSeconds)
            section.set("stabilize-percent", stabilizePercent)
        }
    }

    data class Online(val buy: OnlineUnit?, val sell: OnlineUnit?) : Pricing {
        override val kind get() = PriceKind.ONLINE
        override fun enabled(type: TradeType) = (if (type == TradeType.BUY) buy else sell) != null
        override fun without(type: TradeType) = if (type == TradeType.BUY) copy(buy = null) else copy(sell = null)
        override fun save(section: ConfigurationSection) {
            section.set("type", kind.name)
            for ((key, unit) in listOf("buy" to buy, "sell" to sell)) {
                if (unit == null) { section.set(key, -1L); continue }
                val node = section.createSection(key)
                node.set("start", unit.start)
                node.set("per-player", unit.perPlayer)
                node.set("min-percent", unit.minPercent)
                node.set("max-percent", unit.maxPercent)
            }
        }
    }

    /** 수요 가격만 쓴다. */
    fun unit(type: TradeType): DynamicUnit? = null

    companion object {
        /** 새 상품의 기본 — 구매·판매 모두 꺼짐. */
        val OFF: Pricing = Fixed(null, null)

        fun load(section: ConfigurationSection?): Pricing {
            if (section == null) return OFF
            return when (runCatching { PriceKind.valueOf(section.getString("type", "FIXED")!!.uppercase()) }.getOrDefault(PriceKind.FIXED)) {
                PriceKind.FIXED -> Fixed(price(section, "buy"), price(section, "sell"))
                PriceKind.MARKET -> Market(band(section, "buy"), band(section, "sell"), section.getDouble("sensitivity", 1.0).coerceIn(0.0, 10.0))
                PriceKind.FLOAT -> Floating(range(section.getString("buy")), range(section.getString("sell")), Schedule.load(section.getConfigurationSection("refresh"), 3600))
                PriceKind.DYNAMIC -> Dynamic(
                    dynamicUnit(section.getConfigurationSection("buy")), dynamicUnit(section.getConfigurationSection("sell")),
                    section.getLong("stabilize-seconds", 3600).coerceAtLeast(0), section.getDouble("stabilize-percent", 5.0).coerceIn(0.0, 100.0),
                )
                PriceKind.ONLINE -> Online(onlineUnit(section.getConfigurationSection("buy")), onlineUnit(section.getConfigurationSection("sell")))
            }
        }

        private fun price(section: ConfigurationSection, key: String): Long? {
            if (!section.contains(key)) return null
            val value = section.getDouble(key, -1.0)
            return if (value < 0) null else value.roundToLong()
        }

        private fun band(section: ConfigurationSection, key: String): Band? {
            val node = section.getConfigurationSection(key) ?: return null
            val base = node.getLong("base", -1)
            if (base < 0) return null
            return Band(base, node.getLong("min", base / 2), node.getLong("max", base * 2)).normalized()
        }

        private fun saveBand(section: ConfigurationSection, key: String, band: Band?) {
            if (band == null) { section.set(key, -1L); return }
            val node = section.createSection(key)
            node.set("base", band.base)
            node.set("min", band.min)
            node.set("max", band.max)
        }

        /** "10-50" · "30" → 범위. "-1" 이나 못 읽으면 null(꺼짐). */
        fun range(raw: String?): LongRange? {
            val text = raw?.trim() ?: return null
            if (text.isEmpty() || text.startsWith("-")) return null
            val lo = text.substringBefore('-').trim().toLongOrNull() ?: return null
            val hi = if ('-' in text) text.substringAfter('-').trim().toLongOrNull() ?: return null else lo
            return minOf(lo, hi)..maxOf(lo, hi)
        }

        private fun dynamicUnit(node: ConfigurationSection?): DynamicUnit? {
            node ?: return null
            val start = node.getLong("start", -1)
            if (start < 0) return null
            return DynamicUnit(start, node.getDouble("buy-step", 0.5), node.getDouble("sell-step", -0.5), node.getDouble("min-percent", -50.0), node.getDouble("max-percent", 50.0))
        }

        private fun onlineUnit(node: ConfigurationSection?): OnlineUnit? {
            node ?: return null
            val start = node.getLong("start", -1)
            if (start < 0) return null
            return OnlineUnit(start, node.getDouble("per-player", 1.0), node.getDouble("min-percent", -50.0), node.getDouble("max-percent", 50.0))
        }
    }
}

/** 가격 계산 — 서버 없이 돈다. */
object Prices {

    /** 지금 한 개(단위)의 가격. null 이면 그 방향은 거래 불가. */
    fun unitPrice(pricing: Pricing, state: PriceState, type: TradeType, online: Int): Long? {
        val raw = when (pricing) {
            is Pricing.Fixed -> if (type == TradeType.BUY) pricing.buy else pricing.sell
            is Pricing.Market -> {
                val band = (if (type == TradeType.BUY) pricing.buy else pricing.sell) ?: return null
                val value = (band.base * state.m * (1.0 + state.noise)).roundToLong().coerceIn(band.min, band.max)
                if (type == TradeType.SELL) {
                    // 판매가가 구매가를 넘으면 사서 되파는 무한 돈 복사가 된다.
                    val buy = pricing.buy?.let { (it.base * state.m * (1.0 + state.noise)).roundToLong().coerceIn(it.min, it.max) }
                    if (buy != null) minOf(value, buy) else value
                } else value
            }
            is Pricing.Floating -> {
                val range = (if (type == TradeType.BUY) pricing.buy else pricing.sell) ?: return null
                (if (type == TradeType.BUY) state.rolledBuy else state.rolledSell) ?: range.first
            }
            is Pricing.Dynamic -> {
                val unit = (if (type == TradeType.BUY) pricing.buy else pricing.sell) ?: return null
                val offset = unit.clamp(if (type == TradeType.BUY) state.buyOffset else state.sellOffset)
                (unit.start * (1.0 + offset / 100.0)).roundToLong()
            }
            is Pricing.Online -> {
                val unit = (if (type == TradeType.BUY) pricing.buy else pricing.sell) ?: return null
                (unit.start * (1.0 + unit.clamp(online * unit.perPlayer) / 100.0)).roundToLong()
            }
        }
        return raw?.coerceAtLeast(0)
    }

    /** 거래가 끝난 뒤 가격 상태에 반영한다(수요 가격). 시장 가격은 거래 기록으로 따로 모은다. */
    fun onTrade(pricing: Pricing, state: PriceState, type: TradeType, units: Int, now: Long) {
        if (pricing !is Pricing.Dynamic || units <= 0) return
        pricing.buy?.let { state.buyOffset = it.clamp(state.buyOffset + it.step(type) * units) }
        pricing.sell?.let { state.sellOffset = it.clamp(state.sellOffset + it.step(type) * units) }
        state.stabilizeAt = now + pricing.stabilizeSeconds * 1000L
        state.dirty = true
    }

    /**
     * 수요 가격의 안정화 — 한동안 거래가 없으면 오프셋이 [Pricing.Dynamic.stabilizePercent] 씩 0 으로 돌아간다.
     * @return 바뀌었으면 true
     */
    fun stabilize(pricing: Pricing.Dynamic, state: PriceState, now: Long): Boolean {
        if (pricing.stabilizeSeconds <= 0 || state.stabilizeAt == 0L || now < state.stabilizeAt) return false
        if (state.buyOffset == 0.0 && state.sellOffset == 0.0) return false
        val step = pricing.stabilizePercent
        state.buyOffset = toward(state.buyOffset, step)
        state.sellOffset = toward(state.sellOffset, step)
        state.stabilizeAt = if (state.buyOffset == 0.0 && state.sellOffset == 0.0) 0L else now + pricing.stabilizeSeconds * 1000L
        state.dirty = true
        return true
    }

    private fun toward(value: Double, step: Double): Double = when {
        value > 0 -> maxOf(0.0, value - step)
        value < 0 -> minOf(0.0, value + step)
        else -> 0.0
    }

    /** 변동 가격을 다시 굴린다. */
    fun roll(pricing: Pricing.Floating, state: PriceState, random: () -> Double) {
        state.rolledBuy = pricing.buy?.let { pick(it, random) }
        state.rolledSell = pricing.sell?.let { pick(it, random) }
        state.rolledBuy?.let { buy -> state.rolledSell = state.rolledSell?.let { minOf(it, buy) } }
        state.dirty = true
    }

    private fun pick(range: LongRange, random: () -> Double): Long =
        (range.first + (random() * (range.last - range.first + 1)).toLong()).coerceIn(range.first, range.last)
}
