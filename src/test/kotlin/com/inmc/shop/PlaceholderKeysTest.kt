package com.inmc.shop

import com.inmc.shop.hook.ShopPlaceholders
import com.inmc.shop.trade.ReturnService
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** `/papi info inmcshop` 의 목록과 `onRequest` 가 맞는지 + 돌려줄 물건 나누기. */
class PlaceholderKeysTest {

    @Test
    fun `고정 키는 onRequest 의 정확히 같은 갈래와 같다`() {
        // 소스에서 `p == "…"` 와 `"…" -> return` 갈래를 읽는다 — 갈래를 더하고 목록을 잊으면(또는 반대) 여기서 걸린다.
        val source = File("src/main/kotlin/com/inmc/shop/hook/PapiHook.kt").readText()
        val body = source.substringAfter("override fun onRequest").substringBefore("private fun income")
        val branches = Regex("""(?:p == "([a-z_]+)"|"([a-z_]+)" -> return)""").findAll(body)
            .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toSet()
        assertEquals(branches, ShopPlaceholders.FIXED.toSet())
    }

    @Test
    fun `수입 키는 화폐 id 로 되풀린다`() {
        val keys = ShopPlaceholders.keys(listOf("Money", "gold_coin"))
        assertEquals(ShopPlaceholders.FIXED.size + 2 * 4, keys.size)
        for (key in ShopPlaceholders.FIXED) assertNull(ShopPlaceholders.income(key), key)
        for (key in keys - ShopPlaceholders.FIXED.toSet()) {
            val income = assertNotNull(ShopPlaceholders.income(key), key)
            assert(income.currency == "money" || income.currency == "gold_coin") { key }
        }
        assertEquals(ShopPlaceholders.Income(claimed = false, raw = true, currency = "money"), ShopPlaceholders.income("auction_unclaimed_income_raw_money"))
        assertEquals(ShopPlaceholders.Income(claimed = true, raw = false, currency = "money"), ShopPlaceholders.income("auction_claimed_income_money"))
    }

    @Test
    fun `돌려줄 물건은 한 칸씩 나눈다`() {
        assertEquals(emptyList(), ReturnService.splitStacks(0, 64))
        assertEquals(listOf(64), ReturnService.splitStacks(64, 64))
        assertEquals(listOf(64, 1), ReturnService.splitStacks(65, 64))
        assertEquals(listOf(1, 1, 1), ReturnService.splitStacks(3, 1))
        assertEquals(listOf(1, 1), ReturnService.splitStacks(2, 0)) // 0 은 1 로
    }
}
