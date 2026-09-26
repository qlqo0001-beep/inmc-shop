package com.inmc.shop.trade

import com.inmc.shop.Shop
import kr.inmc.core.event.InmcSignalEvent
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 거래 기록 — `logs/transactions-YYYY-MM-DD.log` 와 콘솔(설정). 그리고 거래마다 core 신호(`shop/…`) — ExcellentShop 의
 * 거래 이벤트 자리. 업적이 "상점에서 N번 판매" 를 만들 수 있다.
 */
class TradeLog(private val shop: Shop) {

    private val time = DateTimeFormatter.ofPattern("HH:mm:ss")

    /**
     * @param module virtual · chest · auction
     * @param action buy · sell · auction-list · auction-buy · auction-claim
     * @param subject 물건 id(커스텀아이템 참조 또는 재질)
     */
    fun record(module: String, action: String, player: UUID, playerName: String, subject: String, itemLabel: String, amount: Long, money: String, where: String, extra: Map<String, String> = emptyMap()) {
        val line = "[${LocalDateTime.now().format(time)}] [$module] $playerName ${action.uppercase()} $amount x $itemLabel ($subject) @ $where : $money"
        if (shop.config.logToConsole) shop.logger.info(line)
        if (shop.config.logToFile) {
            val file = File(shop.plugin.dataFolder, "logs/transactions-" + LocalDate.now() + ".log")
            shop.io.asyncRun {
                file.parentFile.mkdirs()
                file.appendText(line + System.lineSeparator(), Charsets.UTF_8)
            }
        }
        InmcSignalEvent.fire(SOURCE, action, player, subject, amount) {
            mapOf("module" to module, "where" to where, "money" to money) + extra
        }
    }

    companion object {
        const val SOURCE = "shop"
    }
}
