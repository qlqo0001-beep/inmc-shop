package com.inmc.shop.price

import org.bukkit.configuration.ConfigurationSection
import kotlin.math.abs

/**
 * 시장 가격의 전역 계수. 설정 화면에서 고친다(`config.yml` 의 `market`).
 *
 * 기본값은 "주기 30분, 한 사람이 한 주기에 넣을 수 있는 양 256개, 참여자 5명이면 온전히 반영, 한 주기 최대 ±5%" 에 맞췄다.
 */
data class MarketParams(
    /** 가격을 다시 정하는 주기(분). */
    val periodMinutes: Int = 30,
    /** 주기가 끝나고 몇 초 기다렸다가 계산하나 — 다른 서버의 거래 기록이 DB 에 닿을 시간. */
    val graceSeconds: Int = 30,
    /** 기준 거래량 k. 압력 p = (B−S)/(B+S+k) — 거래가 적으면 p 가 작다. */
    val referenceVolume: Double = 64.0,
    /** 한 사람이 한 주기에 넣을 수 있는 양(구매·판매 각각). 고래 한 명이 시장을 흔들지 못하게. */
    val userCap: Long = 256,
    /** 참여자가 이만큼이면 온전히 반영. 적으면 그 비율만큼 약하게. */
    val referenceUsers: Int = 5,
    /** 단기 추세 EMA 계수(클수록 최근을 크게). */
    val shortAlpha: Double = 0.5,
    /** 장기 추세 EMA 계수. */
    val longAlpha: Double = 0.05,
    /** 합친 압력에서 단기의 몫. */
    val shortWeight: Double = 0.6,
    /** 압력 1 이 가격을 몇 % 움직이나(비율). */
    val sensitivity: Double = 0.1,
    /** 한 주기 최대 변동(비율). */
    val maxStep: Double = 0.05,
    /** 기본가로 돌아가려는 힘(비율, 주기당). */
    val reversion: Double = 0.02,
    /** 활동량 EMA 계수. */
    val activityAlpha: Double = 0.3,
    /** 이 아래면 한산. */
    val lowActivity: Double = 16.0,
    /** 이 위면 활발. */
    val highActivity: Double = 256.0,
    /** 한산한 주기마다 한산 보정이 이만큼 쌓인다(비율). */
    val dormancyStep: Double = 0.002,
    /** 한산 보정의 상한(주기당 비율). 복원력과 맞물려 아무도 안 사고팔면 약 reversion/(reversion−dormancyMax) 배에서 멈춘다. */
    val dormancyMax: Double = 0.01,
    /** 활동이 돌아오면 한산 보정이 주기마다 이만큼 곱해져 줄어든다. */
    val dormancyDecay: Double = 0.5,
    /** 기본 흔들림(비율). */
    val baseNoise: Double = 0.01,
    /** 한산 보정이 가득일 때 더해지는 흔들림. */
    val dormancyNoise: Double = 0.03,
    /** 압력 1 일 때 더해지는 흔들림. */
    val pressureNoise: Double = 0.02,
    /** 배수의 바닥·천장(상품의 최저·최고가와 별개로 한 번 더). */
    val minMultiplier: Double = 0.1,
    val maxMultiplier: Double = 10.0,
) {
    val periodMillis: Long get() = periodMinutes.coerceAtLeast(1) * 60_000L

    fun periodOf(time: Long): Long = time / periodMillis

    fun save(section: ConfigurationSection) {
        section.set("period-minutes", periodMinutes)
        section.set("grace-seconds", graceSeconds)
        section.set("reference-volume", referenceVolume)
        section.set("user-cap", userCap)
        section.set("reference-users", referenceUsers)
        section.set("short-alpha", shortAlpha)
        section.set("long-alpha", longAlpha)
        section.set("short-weight", shortWeight)
        section.set("sensitivity", sensitivity)
        section.set("max-step", maxStep)
        section.set("reversion", reversion)
        section.set("activity-alpha", activityAlpha)
        section.set("low-activity", lowActivity)
        section.set("high-activity", highActivity)
        section.set("dormancy-step", dormancyStep)
        section.set("dormancy-max", dormancyMax)
        section.set("dormancy-decay", dormancyDecay)
        section.set("base-noise", baseNoise)
        section.set("dormancy-noise", dormancyNoise)
        section.set("pressure-noise", pressureNoise)
        section.set("min-multiplier", minMultiplier)
        section.set("max-multiplier", maxMultiplier)
    }

    companion object {
        fun load(section: ConfigurationSection?): MarketParams {
            val d = MarketParams()
            if (section == null) return d
            fun dbl(key: String, def: Double, lo: Double, hi: Double) = section.getDouble(key, def).coerceIn(lo, hi)
            return MarketParams(
                periodMinutes = section.getInt("period-minutes", d.periodMinutes).coerceIn(1, 24 * 60 * 7),
                graceSeconds = section.getInt("grace-seconds", d.graceSeconds).coerceIn(0, 600),
                referenceVolume = dbl("reference-volume", d.referenceVolume, 1.0, 1e9),
                userCap = section.getLong("user-cap", d.userCap).coerceIn(1, 1_000_000_000),
                referenceUsers = section.getInt("reference-users", d.referenceUsers).coerceIn(1, 10_000),
                shortAlpha = dbl("short-alpha", d.shortAlpha, 0.0, 1.0),
                longAlpha = dbl("long-alpha", d.longAlpha, 0.0, 1.0),
                shortWeight = dbl("short-weight", d.shortWeight, 0.0, 1.0),
                sensitivity = dbl("sensitivity", d.sensitivity, 0.0, 1.0),
                maxStep = dbl("max-step", d.maxStep, 0.0, 1.0),
                reversion = dbl("reversion", d.reversion, 0.0, 1.0),
                activityAlpha = dbl("activity-alpha", d.activityAlpha, 0.0, 1.0),
                lowActivity = dbl("low-activity", d.lowActivity, 0.0, 1e9),
                highActivity = dbl("high-activity", d.highActivity, 0.0, 1e9),
                dormancyStep = dbl("dormancy-step", d.dormancyStep, 0.0, 1.0),
                dormancyMax = dbl("dormancy-max", d.dormancyMax, 0.0, 1.0),
                dormancyDecay = dbl("dormancy-decay", d.dormancyDecay, 0.0, 1.0),
                baseNoise = dbl("base-noise", d.baseNoise, 0.0, 1.0),
                dormancyNoise = dbl("dormancy-noise", d.dormancyNoise, 0.0, 1.0),
                pressureNoise = dbl("pressure-noise", d.pressureNoise, 0.0, 1.0),
                minMultiplier = dbl("min-multiplier", d.minMultiplier, 0.001, 1.0),
                maxMultiplier = dbl("max-multiplier", d.maxMultiplier, 1.0, 1000.0),
            )
        }
    }
}

/** 한 주기의 거래 — 사람마다 [MarketParams.userCap] 으로 자른 뒤의 합. */
data class MarketTick(val bought: Long, val sold: Long, val users: Int) {
    val volume: Long get() = bought + sold

    companion object {
        val EMPTY = MarketTick(0, 0, 0)

        /** 사람별 (산 개수, 판 개수) → 한 사람의 몫을 잘라 합친다. */
        fun of(perUser: Collection<Pair<Long, Long>>, cap: Long): MarketTick {
            var bought = 0L
            var sold = 0L
            var users = 0
            for ((b, s) in perUser) {
                if (b <= 0 && s <= 0) continue
                users++
                bought += b.coerceIn(0, cap)
                sold += s.coerceIn(0, cap)
            }
            return MarketTick(bought, sold, users)
        }
    }
}

enum class Activity(val label: String) { BUSY("활발"), NORMAL("보통"), QUIET("한산") }

/**
 * 시장 가격 — **거래가 가격을 정하고, 가격이 다시 거래를 움직인다.** 서버 없이 돈다(난수는 주입).
 *
 * ```
 * 주기의 거래(사람마다 cap 으로 자름) → 압력 p = (B−S)/(B+S+k) × 신뢰도 min(1, U/U기준)
 *   → 단기·장기 EMA → 합친 압력 P → Δ = P·민감도·상품 배수 + 한산 보정 → ±최대폭으로 자름
 *   → m ← m(1+Δ) + (1−m)·복원력 → 흔들림 σ 안에서 무작위 → 가격 = 기본가·m·(1+흔들림)
 * ```
 *
 * - 많이 사면 오르고 많이 팔면 내린다. 판매가 몰린 물건은 값이 떨어져 다른 것을 캐게 한다.
 * - 아무도 안 사고팔면 한산 보정이 쌓여 값이 오르고 흔들림이 커진다 — "지금 팔면 돈이 된다".
 * - 거래가 돌아오면 한산 보정이 풀리고 압력이 다시 가격을 잡는다.
 */
object Market {

    fun step(state: PriceState, tick: MarketTick, params: MarketParams, productSensitivity: Double, random: () -> Double) {
        val volume = tick.volume.toDouble()
        val raw = (tick.bought - tick.sold).toDouble() / (volume + params.referenceVolume)
        val confidence = (tick.users.toDouble() / params.referenceUsers).coerceIn(0.0, 1.0)
        val p = raw * confidence

        state.emaShort += params.shortAlpha * (p - state.emaShort)
        state.emaLong += params.longAlpha * (p - state.emaLong)
        val pressure = params.shortWeight * state.emaShort + (1 - params.shortWeight) * state.emaLong
        state.pressure = pressure

        state.activity = if (state.activity < 0) volume else state.activity + params.activityAlpha * (volume - state.activity)
        state.dormancy = if (state.activity < params.lowActivity) {
            (state.dormancy + params.dormancyStep).coerceAtMost(params.dormancyMax)
        } else {
            state.dormancy * params.dormancyDecay
        }

        val delta = (pressure * params.sensitivity * productSensitivity + state.dormancy).coerceIn(-params.maxStep, params.maxStep)
        val next = state.m * (1 + delta) + (1 - state.m) * params.reversion
        state.m = next.coerceIn(params.minMultiplier, params.maxMultiplier)

        val sigma = noiseOf(state, params)
        state.noise = (random() * 2 - 1) * sigma
        state.dirty = true
    }

    /** 지금 흔들림의 폭. */
    fun noiseOf(state: PriceState, params: MarketParams): Double {
        val dormancyShare = if (params.dormancyMax > 0) state.dormancy / params.dormancyMax else 0.0
        return params.baseNoise + dormancyShare * params.dormancyNoise + abs(state.pressure) * params.pressureNoise
    }

    fun activity(state: PriceState, params: MarketParams): Activity = when {
        state.activity < 0 || state.activity < params.lowActivity -> Activity.QUIET
        state.activity >= params.highActivity -> Activity.BUSY
        else -> Activity.NORMAL
    }
}
