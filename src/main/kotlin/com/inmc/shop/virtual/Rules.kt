package com.inmc.shop.virtual

import com.inmc.shop.trade.TradeType

/** 전체 재고의 지금 값. */
class StockState(var units: Long, var restockAt: Long) {
    @Transient var dirty: Boolean = false
}

/** 한 사람의 한도 사용량. */
class LimitState(var bought: Long = 0, var sold: Long = 0, var resetAt: Long = 0) {
    @Transient var dirty: Boolean = false
    fun used(type: TradeType): Long = if (type == TradeType.BUY) bought else sold
}

/** 재고·한도 규칙 — 서버 없이 돈다. */
object Stocks {

    /** 처음 값 · 다시 채울 값 — 최소~최대에서 무작위, 용량까지. */
    fun refillAmount(options: StockOptions, random: () -> Double): Long {
        val span = options.restockMax - options.restockMin
        return (options.restockMin + (random() * (span + 1)).toLong().coerceIn(0, span.coerceAtLeast(0))).coerceIn(0, options.capacity)
    }

    fun fresh(options: StockOptions, now: Long, random: () -> Double): StockState =
        StockState(refillAmount(options, random), nextRestock(options, now))

    fun nextRestock(options: StockOptions, now: Long): Long = if (options.restockSeconds > 0) now + options.restockSeconds * 1000L else 0L

    /** 다시 채울 때가 됐으면 채운다. 채웠으면 true. */
    fun restockIfDue(options: StockOptions, state: StockState, now: Long, random: () -> Double): Boolean {
        if (options.restockSeconds <= 0 || state.restockAt == 0L || now < state.restockAt) return false
        state.units = refillAmount(options, random)
        state.restockAt = nextRestock(options, now)
        state.dirty = true
        return true
    }

    /** 이 방향으로 지금 몇 단위까지 되나(재고 기준). 재고가 꺼졌으면 무제한. */
    fun room(options: StockOptions, state: StockState?, type: TradeType): Long {
        if (!options.enabled || state == null) return Long.MAX_VALUE
        return if (type == TradeType.BUY) state.units.coerceAtLeast(0) else (options.capacity - state.units).coerceAtLeast(0)
    }

    /** 거래를 재고에 반영. 사면 줄고 팔면 는다. */
    fun apply(options: StockOptions, state: StockState, type: TradeType, units: Long) {
        if (!options.enabled) return
        state.units = if (type == TradeType.BUY) (state.units - units).coerceAtLeast(0) else (state.units + units).coerceAtMost(options.capacity)
        state.dirty = true
    }

    /** 초기화 시각이 지났으면 비운다. 비웠으면 true. */
    fun resetIfDue(options: LimitOptions, state: LimitState, now: Long): Boolean {
        if (options.resetSeconds <= 0 || state.resetAt == 0L || now < state.resetAt) return false
        state.bought = 0
        state.sold = 0
        state.resetAt = 0
        state.dirty = true
        return true
    }

    /** 한도로 지금 몇 단위까지 되나. 한도가 없으면 무제한. */
    fun remaining(options: LimitOptions, state: LimitState?, type: TradeType): Long {
        val limit = options.limit(type)
        if (limit < 0) return Long.MAX_VALUE
        return (limit - (state?.used(type) ?: 0)).coerceAtLeast(0)
    }

    /** 한도 사용을 적는다. 처음 쓰는 순간 초기화 시각을 잡는다(-1 이면 평생). */
    fun use(options: LimitOptions, state: LimitState, type: TradeType, units: Long, now: Long) {
        if (!options.enabled) return
        if (type == TradeType.BUY) state.bought += units else state.sold += units
        if (state.resetAt == 0L && options.resetSeconds > 0) state.resetAt = now + options.resetSeconds * 1000L
        state.dirty = true
    }
}

/** 회전 뽑기 — 가중치에 비례, 겹치지 않게, 칸 수만큼. */
object Rotations {

    fun pick(candidates: List<Pair<String, Double>>, count: Int, random: () -> Double): List<String> {
        val pool = candidates.filter { it.second > 0 }.toMutableList()
        val chosen = ArrayList<String>()
        while (chosen.size < count && pool.isNotEmpty()) {
            val total = pool.sumOf { it.second }
            var roll = random() * total
            var index = pool.lastIndex
            for ((i, entry) in pool.withIndex()) {
                roll -= entry.second
                if (roll < 0) { index = i; break }
            }
            chosen += pool.removeAt(index).first
        }
        return chosen
    }
}

/** 등급·권한마다 다른 값(판매 배수·최대 상점 수·최대 등록 수…). -1 = 무제한. */
data class RankValues(
    val mode: Mode = Mode.RANK,
    val prefix: String = "",
    val default: Double = 1.0,
    val values: Map<String, Double> = emptyMap(),
) {
    enum class Mode { RANK, PERMISSION }

    /** 맞는 값 중 가장 큰 것(-1 은 무제한이라 가장 크다). 없으면 기본값. */
    fun valueFor(has: (String) -> Boolean): Double {
        val matched = values.filter { (name, _) -> has(if (mode == Mode.RANK) "group.$name" else prefix + name) }.values
        if (matched.isEmpty()) return default
        return if (matched.any { it < 0 }) -1.0 else matched.max()
    }

    fun save(section: org.bukkit.configuration.ConfigurationSection) {
        section.set("mode", mode.name)
        section.set("permission-prefix", prefix)
        section.set("default", default)
        val node = section.createSection("values")
        for ((k, v) in values) node.set(k, v)
    }

    companion object {
        fun load(section: org.bukkit.configuration.ConfigurationSection?, fallback: RankValues): RankValues {
            section ?: return fallback
            val values = LinkedHashMap<String, Double>()
            section.getConfigurationSection("values")?.let { node -> for (k in node.getKeys(false)) values[k] = node.getDouble(k) }
            return RankValues(
                runCatching { Mode.valueOf(section.getString("mode", "RANK")!!.uppercase()) }.getOrDefault(Mode.RANK),
                section.getString("permission-prefix") ?: fallback.prefix,
                section.getDouble("default", fallback.default),
                values,
            )
        }
    }
}

/** 한 번에 최대 몇 단위 — 돈·재고·한도·공간 중 가장 작은 것. */
object Quantity {
    fun max(affordable: Long, stockRoom: Long, limitRoom: Long, inventoryRoom: Long, cap: Long = 99 * 36L): Long =
        minOf(affordable, stockRoom, limitRoom, inventoryRoom, cap).coerceAtLeast(0)

    /** 돈으로 살 수 있는 단위 수. 가격 0 이면 무제한. */
    fun affordable(balance: Long, unitPrice: Long): Long = if (unitPrice <= 0) Long.MAX_VALUE else (balance / unitPrice).coerceAtLeast(0)
}
