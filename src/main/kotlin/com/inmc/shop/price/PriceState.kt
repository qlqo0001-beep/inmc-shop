package com.inmc.shop.price

/**
 * 상품 하나의 가격 **값**(정의는 [Pricing]). DB 에 한 줄로 적힌다(`key=value;…`).
 *
 * 가격 방식마다 쓰는 칸이 다르다 — 수요: 오프셋·안정화 시각 / 변동: 굴린 값·다음 굴림 / 시장: 배수·추세·활동량·한산 보정·흔들림.
 * 가격 방식을 바꿔도 옛 칸이 남아 해가 없게, 읽는 쪽이 자기 칸만 본다.
 */
class PriceState {
    // 수요
    var buyOffset: Double = 0.0
    var sellOffset: Double = 0.0
    var stabilizeAt: Long = 0L

    // 변동
    var rolledBuy: Long? = null
    var rolledSell: Long? = null
    var nextRoll: Long = 0L

    // 시장
    var m: Double = 1.0
    var emaShort: Double = 0.0
    var emaLong: Double = 0.0
    /** 활동량 EMA. 음수 = 아직 모름(처음 계산 때 이번 거래량으로 시작). */
    var activity: Double = -1.0
    /** 한산 보정 — 주기당 가격을 밀어 올리는 비율. */
    var dormancy: Double = 0.0
    var noise: Double = 0.0
    /** 마지막으로 계산한 주기 번호. 여러 서버 중 한 곳만 계산하는 CAS 의 기준. */
    var computedPeriod: Long = -1L
    /** 지난 가격(추세 표시). */
    var prevBuy: Long? = null
    var prevSell: Long? = null
    /** 마지막 계산의 합친 압력(시세 화면). */
    var pressure: Double = 0.0

    /** 메모리에서 바뀌어 DB 에 써야 한다. */
    @Transient
    var dirty: Boolean = false

    fun encode(): String = buildList {
        if (buyOffset != 0.0) add("bo=$buyOffset")
        if (sellOffset != 0.0) add("so=$sellOffset")
        if (stabilizeAt != 0L) add("sa=$stabilizeAt")
        rolledBuy?.let { add("rb=$it") }
        rolledSell?.let { add("rs=$it") }
        if (nextRoll != 0L) add("nr=$nextRoll")
        if (m != 1.0) add("m=$m")
        if (emaShort != 0.0) add("es=$emaShort")
        if (emaLong != 0.0) add("el=$emaLong")
        if (activity >= 0) add("ac=$activity")
        if (dormancy != 0.0) add("dm=$dormancy")
        if (noise != 0.0) add("nz=$noise")
        if (computedPeriod >= 0) add("cp=$computedPeriod")
        prevBuy?.let { add("pb=$it") }
        prevSell?.let { add("ps=$it") }
        if (pressure != 0.0) add("pr=$pressure")
    }.joinToString(";")

    fun copyFrom(other: PriceState) {
        buyOffset = other.buyOffset; sellOffset = other.sellOffset; stabilizeAt = other.stabilizeAt
        rolledBuy = other.rolledBuy; rolledSell = other.rolledSell; nextRoll = other.nextRoll
        m = other.m; emaShort = other.emaShort; emaLong = other.emaLong; activity = other.activity
        dormancy = other.dormancy; noise = other.noise; computedPeriod = other.computedPeriod
        prevBuy = other.prevBuy; prevSell = other.prevSell; pressure = other.pressure
    }

    /** 처음으로 — 관리자가 시세를 초기화할 때. */
    fun reset() = copyFrom(PriceState())

    companion object {
        fun decode(raw: String?): PriceState {
            val state = PriceState()
            if (raw.isNullOrBlank()) return state
            for (part in raw.split(';')) {
                val key = part.substringBefore('=', "").trim()
                val value = part.substringAfter('=', "").trim()
                when (key) {
                    "bo" -> value.toDoubleOrNull()?.let { state.buyOffset = it }
                    "so" -> value.toDoubleOrNull()?.let { state.sellOffset = it }
                    "sa" -> value.toLongOrNull()?.let { state.stabilizeAt = it }
                    "rb" -> state.rolledBuy = value.toLongOrNull()
                    "rs" -> state.rolledSell = value.toLongOrNull()
                    "nr" -> value.toLongOrNull()?.let { state.nextRoll = it }
                    "m" -> value.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }?.let { state.m = it }
                    "es" -> value.toDoubleOrNull()?.let { state.emaShort = it }
                    "el" -> value.toDoubleOrNull()?.let { state.emaLong = it }
                    "ac" -> value.toDoubleOrNull()?.let { state.activity = it }
                    "dm" -> value.toDoubleOrNull()?.let { state.dormancy = it }
                    "nz" -> value.toDoubleOrNull()?.let { state.noise = it }
                    "cp" -> value.toLongOrNull()?.let { state.computedPeriod = it }
                    "pb" -> state.prevBuy = value.toLongOrNull()
                    "ps" -> state.prevSell = value.toLongOrNull()
                    "pr" -> value.toDoubleOrNull()?.let { state.pressure = it }
                }
            }
            return state
        }
    }
}
