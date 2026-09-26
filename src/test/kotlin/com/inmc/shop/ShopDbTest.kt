package com.inmc.shop

import com.inmc.shop.data.ListingRow
import com.inmc.shop.data.ShopDb
import com.inmc.shop.data.TradeRecord
import java.nio.file.Files
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 진짜 SQLite 로 — 돈과 물건이 걸린 조건부 갱신이 한 번만 성공하는지. */
class ShopDbTest {

    private val db = ShopDb(Logger.getLogger("test")).also { it.open(Files.createTempDirectory("shopdb").toFile().resolve("t.db")) }
    private val src get() = db.local
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()

    @AfterTest
    fun close() = db.shutdown()

    private fun <T> q(block: () -> T): T = db.call(block)

    @Test
    fun `재고는 모자라면 안 빠지고, 용량을 넘으면 안 들어간다`() = q {
        db.setStock(src, "s/p", 5, 0, 1)
        assertTrue(db.takeStock(src, "s/p", 5, 2))
        assertFalse(db.takeStock(src, "s/p", 1, 3), "마지막 것을 두 번 팔면 안 된다")
        assertTrue(db.giveStock(src, "s/p", 10, 10, 4))
        assertFalse(db.giveStock(src, "s/p", 1, 10, 5))
        assertEquals(10, db.stockOf(src, "s/p")!!.units)
    }

    private fun listing(id: String, expires: Long = Long.MAX_VALUE) = ListingRow(
        id, a, "판매자", "item", 1, "흙", "g", "money", 100, 1, expires, "ACTIVE", null, null, 0, 0, null, 1,
    )

    @Test
    fun `경매 물건은 한 사람만 산다`() = q {
        db.insertListing(src, listing("l1"))
        assertTrue(db.buyListing(src, "l1", b, "구매자", 95, 10))
        assertFalse(db.buyListing(src, "l1", UUID.randomUUID(), "다른 사람", 95, 11), "두 번 팔리면 안 된다")
        assertEquals("SOLD", db.listing(src, "l1")!!.state)
        // 돈을 못 받으면 무른다 — 산 사람만
        assertFalse(db.unbuyListing(src, "l1", a, 12))
        assertTrue(db.unbuyListing(src, "l1", b, 12))
        assertEquals("ACTIVE", db.listing(src, "l1")!!.state)
    }

    @Test
    fun `만료된 물건은 못 사고, 만료 처리는 판매 중인 것만`() = q {
        db.insertListing(src, listing("l2", expires = 100))
        assertFalse(db.buyListing(src, "l2", b, "구매자", 95, 200))
        assertEquals(1, db.expireListings(src, 200))
        assertEquals("EXPIRED", db.listing(src, "l2")!!.state)
        assertTrue(db.moveListing(src, "l2", "EXPIRED", "RETURNED", 300))
        assertFalse(db.moveListing(src, "l2", "EXPIRED", "RETURNED", 301), "두 번 돌려받으면 안 된다")
    }

    @Test
    fun `정리는 끝난 기록만 지운다 — 받지 않은 물건과 돈은 남는다`() = q {
        db.insertListing(src, listing("sold"))
        db.buyListing(src, "sold", b, "구매자", 95, 10)
        db.insertListing(src, listing("claimed"))
        db.buyListing(src, "claimed", b, "구매자", 95, 10)
        db.moveListing(src, "claimed", "SOLD", "CLAIMED", 10)
        db.insertListing(src, listing("expired", expires = 5))
        db.expireListings(src, 10)
        assertEquals(1, db.purgeListings(src, Long.MAX_VALUE))
        assertNotNull(db.listing(src, "sold"))
        assertNotNull(db.listing(src, "expired"))
        assertNull(db.listing(src, "claimed"))
    }

    @Test
    fun `은행은 모자라면 안 빠진다`() = q {
        db.bankAdd(src, a, "money", 100)
        db.bankAdd(src, a, "money", 50)
        assertFalse(db.bankTake(src, a, "money", 151))
        assertTrue(db.bankTake(src, a, "money", 150))
        assertEquals(0, db.bank(src, a)["money"])
    }

    @Test
    fun `회전과 시장 계산은 한 서버만 잡는다`() = q {
        assertTrue(db.rollRotation(src, "s/r", null, 1000, "a,b"))
        assertFalse(db.rollRotation(src, "s/r", null, 1000, "c"), "이미 있는 회전을 새로 만들면 안 된다")
        assertTrue(db.rollRotation(src, "s/r", 1000, 2000, "c"))
        assertFalse(db.rollRotation(src, "s/r", 1000, 2000, "d"), "옛 시각으로 두 번 굴리면 안 된다")
        assertTrue(db.claimPeriod(src, "s/p", 7, 1))
        assertFalse(db.claimPeriod(src, "s/p", 7, 2))
        assertTrue(db.claimPeriod(src, "s/p", 8, 3))
    }

    @Test
    fun `시장 기록은 서버마다 더해진다`() = q {
        db.addTrades(src, listOf(TradeRecord("s/p", 3, a, 5, 0), TradeRecord("s/p", 3, b, 0, 7)))
        db.addTrades(src, listOf(TradeRecord("s/p", 3, a, 2, 1)))
        val rows = db.tradesOf(src, "s/p", 3).toSet()
        assertEquals(setOf(7L to 1L, 0L to 7L), rows)
    }

    @Test
    fun `돌려받을 물건은 한 번만 받는다`() = q {
        db.addReturn(src, a, "item", "상점 삭제", 1)
        val row = db.returns(src, a).single()
        assertTrue(db.takeReturn(src, row.id))
        assertFalse(db.takeReturn(src, row.id))
    }

    @Test
    fun `창고 수량 0 은 줄을 지운다`() = q {
        db.setChestStock(src, "c1", "p1", 64)
        assertEquals(64, db.chestStock(src)["c1" to "p1"])
        db.setChestStock(src, "c1", "p1", 0)
        assertNull(db.chestStock(src)["c1" to "p1"])
    }
}
