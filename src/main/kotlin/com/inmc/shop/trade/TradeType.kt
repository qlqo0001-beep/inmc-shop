package com.inmc.shop.trade

/** 플레이어 쪽에서 본 거래 방향 — BUY 는 플레이어가 산다(상점이 판다). */
enum class TradeType(val label: String) {
    BUY("구매"),
    SELL("판매"),
}
