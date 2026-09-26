package com.inmc.shop.scheduler

import com.inmc.shop.Shop
import kr.inmc.core.scheduler.TickerBase

/**
 * 1초에 한 번. 정의 저장 · 가격(수요 안정화·변동 굴림·시장 계산) · 재고 채움 · 회전 · 임대 만료 · 홀로그램 · 경매 만료.
 * [com.inmc.shop.config.ShopConfig.syncSeconds] 마다 DB 에 쓰고, 공용 DB 면 다른 서버의 변경을 읽는다.
 */
class Ticker(private val shop: Shop) : TickerBase(shop.plugin) {

    override val periodTicks = 20L

    private var seconds = 0L

    override fun ready(): Boolean = shop.ready

    override fun tick(now: Long) {
        seconds++
        step("flush") { shop.shops.flush(); shop.layouts.flush() }
        if (shop.config.virtual.enabled) {
            step("prices") { shop.prices.tick(now) }
            step("stocks") { shop.stocks.tick(now) }
            step("rotations") { shop.rotations.tick(now) }
        }
        if (shop.config.chest.enabled) {
            step("chests") { shop.chests.tick(now) }
            step("displays") { shop.displays.tick() }
        }
        if (shop.config.auction.enabled) step("auction") { shop.auction.tick(now) }
        if (seconds % shop.config.syncSeconds == 0L) {
            step("sync") {
                shop.prices.sync(now)
                shop.stocks.sync()
                shop.rotations.sync()
                shop.auction.sync()
            }
        }
    }
}
