package com.inmc.shop.util

import kr.inmc.core.util.TokenBag

/** 메시지 한 번 렌더링에 쓰이는 토큰 주머니. 한글/영문 둘 다 받는다 — 다른 INMC 플러그인들과 같은 관례. */
class Ph : TokenBag<Ph>() {

    override val aliases: Map<String, List<String>> get() = ALIASES

    fun player(name: String): Ph = put(PLAYER, name)
    fun shop(name: String): Ph = put(SHOP, name)
    fun item(name: String): Ph = put(ITEM, name)
    fun amount(value: Long): Ph = put(AMOUNT, "%,d".format(value))
    /** 화폐 형식을 입힌 금액 — "1,000원". */
    fun price(text: String): Ph = put(PRICE, text)
    fun currency(name: String): Ph = put(CURRENCY, name)
    fun value(text: String): Ph = put(VALUE, text)
    fun count(value: Int): Ph = put(COUNT, value.toString())
    fun count(value: Long): Ph = put(COUNT, "%,d".format(value))
    fun time(text: String): Ph = put(TIME, text)
    fun reason(text: String): Ph = put(REASON, text)
    fun max(value: Long): Ph = put(MAX, if (value < 0) "무제한" else "%,d".format(value))

    fun copy(): Ph = copyValuesInto(Ph())

    companion object {
        fun of(): Ph = Ph()

        const val PLAYER = "player"
        const val SHOP = "shop"
        const val ITEM = "item"
        const val AMOUNT = "amount"
        const val PRICE = "price"
        const val CURRENCY = "currency"
        const val VALUE = "value"
        const val COUNT = "count"
        const val TIME = "time"
        const val REASON = "reason"
        const val MAX = "max"

        private val ALIASES: Map<String, List<String>> = mapOf(
            PLAYER to listOf("{플레이어}", "{player}"),
            SHOP to listOf("{상점}", "{shop}"),
            ITEM to listOf("{물건}", "{item}"),
            AMOUNT to listOf("{수량}", "{amount}"),
            PRICE to listOf("{가격}", "{price}"),
            CURRENCY to listOf("{화폐}", "{currency}"),
            VALUE to listOf("{값}", "{value}"),
            COUNT to listOf("{개수}", "{count}"),
            TIME to listOf("{시간}", "{time}"),
            REASON to listOf("{사유}", "{reason}"),
            MAX to listOf("{최대}", "{max}"),
        )
    }
}
