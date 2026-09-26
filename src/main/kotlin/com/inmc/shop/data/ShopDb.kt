package com.inmc.shop.data

import kr.inmc.core.store.SqlDialect
import kr.inmc.core.store.SqlSource
import kr.inmc.core.store.SqlWorker
import java.io.File
import java.sql.ResultSet
import java.util.UUID
import java.util.logging.Logger

data class PriceRow(val key: String, val state: String, val updated: Long)
data class StockRow(val key: String, val units: Long, val restockAt: Long, val updated: Long)
data class LimitRow(val key: String, val bought: Long, val sold: Long, val resetAt: Long)
data class RotationRow(val id: String, val nextAt: Long, val items: String)
data class HistoryRow(val period: Long, val buy: Long?, val sell: Long?, val multiplier: Double, val volume: Long, val users: Int)
data class TradeRecord(val key: String, val period: Long, val player: UUID, val bought: Long, val sold: Long)
data class ChestRow(val id: String, val world: String, val x: Int, val y: Int, val z: Int, val owner: UUID, val data: String)
data class ReturnRow(val id: Long, val item: String, val reason: String)
data class WarningRow(val id: Long, val player: UUID, val by: String, val reason: String, val at: Long)

/** 경매 한 건. [state]: ACTIVE(판매 중) · SOLD(팔림, 돈 안 받음) · CLAIMED(돈 받음) · EXPIRED(만료·내려짐, 물건 안 받음) · RETURNED(돌려받음). */
data class ListingRow(
    val id: String,
    val seller: UUID,
    val sellerName: String,
    val item: String,
    val amount: Int,
    val search: String,
    val groupKey: String,
    val currency: String,
    val price: Long,
    val created: Long,
    val expires: Long,
    val state: String,
    val buyer: UUID?,
    val buyerName: String?,
    val soldAt: Long,
    val payout: Long,
    val note: String?,
    val updated: Long,
)

/**
 * 상점의 DB. 로컬 SQLite 하나 + 적었으면 공용 DB — 연결과 쓰기 순서는 core [SqlWorker].
 *
 * **공용**(여러 서버가 같이): 가격 상태·전체 재고·한도·회전·시장 기록·시세·은행·경매·경고.
 * **로컬만**: 상자 상점과 그 창고, 돌려받을 물건 — 자리에 묶여 있다.
 *
 * 여러 서버가 같은 줄을 고치는 것은 **조건부 한 줄 갱신**으로 잡고, 바뀐 줄 수로 성공을 판단한다(재고 빼기·경매 사기·은행 빼기·
 * 회전·시장 계산 차례). 수량은 차이로 더한다.
 */
class ShopDb(logger: Logger) : SqlWorker(logger, "inmcshop-db") {

    fun open(file: File, networkUrl: String = "", networkUser: String = "", networkPassword: String = "") =
        open(file, networkUrl, networkUser, networkPassword, ::schema)

    val shared: SqlSource get() = source(network = true)

    private fun schema(source: SqlSource) {
        val sqlite = source.dialect == SqlDialect.SQLITE
        val text = if (sqlite) "TEXT" else "VARCHAR(128)"
        val long = if (sqlite) "TEXT" else "MEDIUMTEXT"
        val big = if (sqlite) "INTEGER" else "BIGINT"
        val real = if (sqlite) "REAL" else "DOUBLE"
        val autoId = if (sqlite) "id INTEGER PRIMARY KEY AUTOINCREMENT" else "id BIGINT AUTO_INCREMENT PRIMARY KEY"
        source.connection().createStatement().use { st ->
            st.execute("CREATE TABLE IF NOT EXISTS shop_price (product $text PRIMARY KEY, state $long NOT NULL, period $big NOT NULL, updated $big NOT NULL)")
            st.execute("CREATE TABLE IF NOT EXISTS shop_stock (product $text PRIMARY KEY, units $big NOT NULL, restock_at $big NOT NULL, updated $big NOT NULL)")
            st.execute("CREATE TABLE IF NOT EXISTS shop_limit (player $text NOT NULL, product $text NOT NULL, bought $big NOT NULL, sold $big NOT NULL, reset_at $big NOT NULL, PRIMARY KEY (player, product))")
            st.execute("CREATE TABLE IF NOT EXISTS shop_rotation (id $text PRIMARY KEY, next_at $big NOT NULL, items $long NOT NULL)")
            st.execute("CREATE TABLE IF NOT EXISTS shop_market_trade (product $text NOT NULL, period $big NOT NULL, player $text NOT NULL, bought $big NOT NULL, sold $big NOT NULL, PRIMARY KEY (product, period, player))")
            st.execute("CREATE TABLE IF NOT EXISTS shop_market_history (product $text NOT NULL, period $big NOT NULL, buy $big, sell $big, m $real NOT NULL, volume $big NOT NULL, users $big NOT NULL, PRIMARY KEY (product, period))")
            st.execute("CREATE TABLE IF NOT EXISTS shop_bank (player $text NOT NULL, currency $text NOT NULL, amount $big NOT NULL, PRIMARY KEY (player, currency))")
            st.execute("CREATE TABLE IF NOT EXISTS shop_chest (id $text PRIMARY KEY, world $text NOT NULL, x $big NOT NULL, y $big NOT NULL, z $big NOT NULL, owner $text NOT NULL, data $long NOT NULL)")
            st.execute("CREATE TABLE IF NOT EXISTS shop_chest_stock (shop $text NOT NULL, product $text NOT NULL, items $big NOT NULL, PRIMARY KEY (shop, product))")
            st.execute("CREATE TABLE IF NOT EXISTS shop_return ($autoId, player $text NOT NULL, item $long NOT NULL, reason $text NOT NULL, at $big NOT NULL)")
            st.execute(
                "CREATE TABLE IF NOT EXISTS auction_listing (id $text PRIMARY KEY, seller $text NOT NULL, seller_name $text NOT NULL, item $long NOT NULL, amount $big NOT NULL, " +
                    "search ${if (sqlite) "TEXT" else "VARCHAR(255)"} NOT NULL, group_key $text NOT NULL, currency $text NOT NULL, price $big NOT NULL, created $big NOT NULL, " +
                    "expires $big NOT NULL, state $text NOT NULL, buyer $text, buyer_name $text, sold_at $big NOT NULL, payout $big NOT NULL, note ${if (sqlite) "TEXT" else "VARCHAR(255)"}, updated $big NOT NULL)",
            )
            st.execute("CREATE TABLE IF NOT EXISTS auction_warning ($autoId, player $text NOT NULL, by_name $text NOT NULL, reason ${if (sqlite) "TEXT" else "VARCHAR(255)"} NOT NULL, at $big NOT NULL)")
            st.execute("CREATE TABLE IF NOT EXISTS auction_ban (player $text PRIMARY KEY, until $big NOT NULL, reason ${if (sqlite) "TEXT" else "VARCHAR(255)"} NOT NULL)")
            if (sqlite) {
                st.execute("CREATE INDEX IF NOT EXISTS auction_listing_state ON auction_listing (state, updated)")
                st.execute("CREATE INDEX IF NOT EXISTS auction_listing_seller ON auction_listing (seller, state)")
            }
        }
    }

    // --- 가격 ------------------------------------------------------------------------------

    fun prices(source: SqlSource, since: Long = -1): List<PriceRow> =
        source.connection().prepareStatement("SELECT product, state, updated FROM shop_price WHERE updated > ?").use { ps ->
            ps.setLong(1, since)
            ps.executeQuery().use { rs -> rows(rs) { PriceRow(it.getString(1), it.getString(2), it.getLong(3)) } }
        }

    fun savePrice(source: SqlSource, key: String, state: String, period: Long, now: Long) {
        val sql = if (source.dialect == SqlDialect.SQLITE) {
            "INSERT INTO shop_price (product, state, period, updated) VALUES (?, ?, ?, ?) ON CONFLICT (product) DO UPDATE SET state = excluded.state, period = MAX(shop_price.period, excluded.period), updated = excluded.updated"
        } else {
            "INSERT INTO shop_price (product, state, period, updated) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE state = VALUES(state), period = GREATEST(period, VALUES(period)), updated = VALUES(updated)"
        }
        source.connection().prepareStatement(sql).use { ps ->
            ps.setString(1, key); ps.setString(2, state); ps.setLong(3, period); ps.setLong(4, now)
            ps.executeUpdate()
        }
    }

    /**
     * 시장 가격 계산 차례를 잡는다 — [period] 를 아직 아무도 계산하지 않았을 때만 true. 여러 서버 중 한 곳만 계산한다.
     */
    fun claimPeriod(source: SqlSource, key: String, period: Long, now: Long): Boolean {
        val connection = source.connection()
        val insert = if (source.dialect == SqlDialect.SQLITE) "INSERT OR IGNORE INTO shop_price (product, state, period, updated) VALUES (?, '', -1, ?)"
        else "INSERT IGNORE INTO shop_price (product, state, period, updated) VALUES (?, '', -1, ?)"
        connection.prepareStatement(insert).use { ps -> ps.setString(1, key); ps.setLong(2, now); ps.executeUpdate() }
        return connection.prepareStatement("UPDATE shop_price SET period = ? WHERE product = ? AND period < ?").use { ps ->
            ps.setLong(1, period); ps.setString(2, key); ps.setLong(3, period)
            ps.executeUpdate() == 1
        }
    }

    fun deletePrice(source: SqlSource, key: String) {
        for (table in listOf("shop_price", "shop_stock", "shop_market_history")) {
            source.connection().prepareStatement("DELETE FROM $table WHERE product = ?").use { ps -> ps.setString(1, key); ps.executeUpdate() }
        }
    }

    // --- 전체 재고 --------------------------------------------------------------------------

    fun stocks(source: SqlSource, since: Long = -1): List<StockRow> =
        source.connection().prepareStatement("SELECT product, units, restock_at, updated FROM shop_stock WHERE updated > ?").use { ps ->
            ps.setLong(1, since)
            ps.executeQuery().use { rs -> rows(rs) { StockRow(it.getString(1), it.getLong(2), it.getLong(3), it.getLong(4)) } }
        }

    /** 값을 통째로(다시 채움·관리자 설정·처음 만들 때). */
    fun setStock(source: SqlSource, key: String, units: Long, restockAt: Long, now: Long) {
        val sql = "INSERT INTO shop_stock (product, units, restock_at, updated) VALUES (?, ?, ?, ?)" +
            if (source.dialect == SqlDialect.SQLITE) " ON CONFLICT (product) DO UPDATE SET units = excluded.units, restock_at = excluded.restock_at, updated = excluded.updated"
            else " ON DUPLICATE KEY UPDATE units = VALUES(units), restock_at = VALUES(restock_at), updated = VALUES(updated)"
        source.connection().prepareStatement(sql).use { ps ->
            ps.setString(1, key); ps.setLong(2, units); ps.setLong(3, restockAt); ps.setLong(4, now)
            ps.executeUpdate()
        }
    }

    /** 다시 채운다 — 적힌 다음 시각이 [expectedAt] 일 때만(CAS). 두 서버가 같은 때를 두 번 채우지 않는다. */
    fun restock(source: SqlSource, key: String, expectedAt: Long, units: Long, nextAt: Long, now: Long): Boolean =
        source.connection().prepareStatement("UPDATE shop_stock SET units = ?, restock_at = ?, updated = ? WHERE product = ? AND restock_at = ?").use { ps ->
            ps.setLong(1, units); ps.setLong(2, nextAt); ps.setLong(3, now); ps.setString(4, key); ps.setLong(5, expectedAt)
            ps.executeUpdate() == 1
        }

    /** 재고를 뺀다 — **남은 것이 모자라면 안 뺀다.** 두 서버가 마지막 하나를 동시에 팔지 못한다. */
    fun takeStock(source: SqlSource, key: String, units: Long, now: Long): Boolean =
        source.connection().prepareStatement("UPDATE shop_stock SET units = units - ?, updated = ? WHERE product = ? AND units >= ?").use { ps ->
            ps.setLong(1, units); ps.setLong(2, now); ps.setString(3, key); ps.setLong(4, units)
            ps.executeUpdate() == 1
        }

    /** 재고를 더한다 — **용량을 넘으면 안 더한다.** */
    fun giveStock(source: SqlSource, key: String, units: Long, capacity: Long, now: Long): Boolean =
        source.connection().prepareStatement("UPDATE shop_stock SET units = units + ?, updated = ? WHERE product = ? AND units + ? <= ?").use { ps ->
            ps.setLong(1, units); ps.setLong(2, now); ps.setString(3, key); ps.setLong(4, units); ps.setLong(5, capacity)
            ps.executeUpdate() == 1
        }

    fun stockOf(source: SqlSource, key: String): StockRow? =
        source.connection().prepareStatement("SELECT product, units, restock_at, updated FROM shop_stock WHERE product = ?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs -> if (rs.next()) StockRow(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)) else null }
        }

    // --- 한도 ------------------------------------------------------------------------------

    fun limits(source: SqlSource, player: UUID): List<LimitRow> =
        source.connection().prepareStatement("SELECT product, bought, sold, reset_at FROM shop_limit WHERE player = ?").use { ps ->
            ps.setString(1, player.toString())
            ps.executeQuery().use { rs -> rows(rs) { LimitRow(it.getString(1), it.getLong(2), it.getLong(3), it.getLong(4)) } }
        }

    fun saveLimit(source: SqlSource, player: UUID, row: LimitRow) {
        val sql = "INSERT INTO shop_limit (player, product, bought, sold, reset_at) VALUES (?, ?, ?, ?, ?)" +
            if (source.dialect == SqlDialect.SQLITE) " ON CONFLICT (player, product) DO UPDATE SET bought = excluded.bought, sold = excluded.sold, reset_at = excluded.reset_at"
            else " ON DUPLICATE KEY UPDATE bought = VALUES(bought), sold = VALUES(sold), reset_at = VALUES(reset_at)"
        source.connection().prepareStatement(sql).use { ps ->
            ps.setString(1, player.toString()); ps.setString(2, row.key); ps.setLong(3, row.bought); ps.setLong(4, row.sold); ps.setLong(5, row.resetAt)
            ps.executeUpdate()
        }
    }

    fun clearLimits(source: SqlSource, key: String) {
        source.connection().prepareStatement("DELETE FROM shop_limit WHERE product = ?").use { ps -> ps.setString(1, key); ps.executeUpdate() }
    }

    // --- 회전 ------------------------------------------------------------------------------

    fun rotation(source: SqlSource, id: String): RotationRow? =
        source.connection().prepareStatement("SELECT id, next_at, items FROM shop_rotation WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) RotationRow(rs.getString(1), rs.getLong(2), rs.getString(3)) else null }
        }

    fun rotations(source: SqlSource): List<RotationRow> =
        source.connection().prepareStatement("SELECT id, next_at, items FROM shop_rotation").use { ps ->
            ps.executeQuery().use { rs -> rows(rs) { RotationRow(it.getString(1), it.getLong(2), it.getString(3)) } }
        }

    /**
     * 회전을 굴린다 — 지금 적힌 다음 시각이 [expectedNext] 일 때만(CAS). 줄이 없으면 만든다.
     * @return 이 서버가 굴렸으면 true
     */
    fun rollRotation(source: SqlSource, id: String, expectedNext: Long?, nextAt: Long, items: String): Boolean {
        val connection = source.connection()
        if (expectedNext == null) {
            val insert = if (source.dialect == SqlDialect.SQLITE) "INSERT OR IGNORE INTO shop_rotation (id, next_at, items) VALUES (?, ?, ?)"
            else "INSERT IGNORE INTO shop_rotation (id, next_at, items) VALUES (?, ?, ?)"
            return connection.prepareStatement(insert).use { ps -> ps.setString(1, id); ps.setLong(2, nextAt); ps.setString(3, items); ps.executeUpdate() == 1 }
        }
        return connection.prepareStatement("UPDATE shop_rotation SET next_at = ?, items = ? WHERE id = ? AND next_at = ?").use { ps ->
            ps.setLong(1, nextAt); ps.setString(2, items); ps.setString(3, id); ps.setLong(4, expectedNext)
            ps.executeUpdate() == 1
        }
    }

    fun deleteRotation(source: SqlSource, id: String) {
        source.connection().prepareStatement("DELETE FROM shop_rotation WHERE id = ?").use { ps -> ps.setString(1, id); ps.executeUpdate() }
    }

    // --- 시장 기록 --------------------------------------------------------------------------

    /** 이 서버가 모은 거래를 더한다(차이로 — 다른 서버의 기록을 덮지 않는다). */
    fun addTrades(source: SqlSource, records: List<TradeRecord>) {
        if (records.isEmpty()) return
        val sql = "INSERT INTO shop_market_trade (product, period, player, bought, sold) VALUES (?, ?, ?, ?, ?)" +
            if (source.dialect == SqlDialect.SQLITE) " ON CONFLICT (product, period, player) DO UPDATE SET bought = shop_market_trade.bought + excluded.bought, sold = shop_market_trade.sold + excluded.sold"
            else " ON DUPLICATE KEY UPDATE bought = bought + VALUES(bought), sold = sold + VALUES(sold)"
        source.transaction { connection ->
            connection.prepareStatement(sql).use { ps ->
                for (r in records) {
                    ps.setString(1, r.key); ps.setLong(2, r.period); ps.setString(3, r.player.toString()); ps.setLong(4, r.bought); ps.setLong(5, r.sold)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    /** 한 주기의 사람별 (산 개수, 판 개수). */
    fun tradesOf(source: SqlSource, key: String, period: Long): List<Pair<Long, Long>> =
        source.connection().prepareStatement("SELECT bought, sold FROM shop_market_trade WHERE product = ? AND period = ?").use { ps ->
            ps.setString(1, key); ps.setLong(2, period)
            ps.executeQuery().use { rs -> rows(rs) { it.getLong(1) to it.getLong(2) } }
        }

    fun purgeTrades(source: SqlSource, beforePeriod: Long) {
        source.connection().prepareStatement("DELETE FROM shop_market_trade WHERE period < ?").use { ps -> ps.setLong(1, beforePeriod); ps.executeUpdate() }
    }

    fun addHistory(source: SqlSource, key: String, row: HistoryRow) {
        val sql = if (source.dialect == SqlDialect.SQLITE) "INSERT OR REPLACE INTO shop_market_history (product, period, buy, sell, m, volume, users) VALUES (?, ?, ?, ?, ?, ?, ?)"
        else "REPLACE INTO shop_market_history (product, period, buy, sell, m, volume, users) VALUES (?, ?, ?, ?, ?, ?, ?)"
        source.connection().prepareStatement(sql).use { ps ->
            ps.setString(1, key); ps.setLong(2, row.period)
            if (row.buy != null) ps.setLong(3, row.buy) else ps.setNull(3, java.sql.Types.BIGINT)
            if (row.sell != null) ps.setLong(4, row.sell) else ps.setNull(4, java.sql.Types.BIGINT)
            ps.setDouble(5, row.multiplier); ps.setLong(6, row.volume); ps.setLong(7, row.users.toLong())
            ps.executeUpdate()
        }
    }

    fun history(source: SqlSource, key: String, limit: Int): List<HistoryRow> =
        source.connection().prepareStatement("SELECT period, buy, sell, m, volume, users FROM shop_market_history WHERE product = ? ORDER BY period DESC LIMIT ?").use { ps ->
            ps.setString(1, key); ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                rows(rs) {
                    val buy = it.getLong(2).takeUnless { _ -> it.wasNull() }
                    val sell = it.getLong(3).takeUnless { _ -> it.wasNull() }
                    HistoryRow(it.getLong(1), buy, sell, it.getDouble(4), it.getLong(5), it.getInt(6))
                }
            }
        }

    fun purgeHistory(source: SqlSource, beforePeriod: Long) {
        source.connection().prepareStatement("DELETE FROM shop_market_history WHERE period < ?").use { ps -> ps.setLong(1, beforePeriod); ps.executeUpdate() }
    }

    // --- 은행 ------------------------------------------------------------------------------

    fun bank(source: SqlSource, player: UUID): Map<String, Long> =
        source.connection().prepareStatement("SELECT currency, amount FROM shop_bank WHERE player = ?").use { ps ->
            ps.setString(1, player.toString())
            ps.executeQuery().use { rs -> rows(rs) { it.getString(1) to it.getLong(2) }.toMap() }
        }

    fun bankAdd(source: SqlSource, player: UUID, currency: String, delta: Long) {
        val sql = "INSERT INTO shop_bank (player, currency, amount) VALUES (?, ?, ?)" +
            if (source.dialect == SqlDialect.SQLITE) " ON CONFLICT (player, currency) DO UPDATE SET amount = shop_bank.amount + excluded.amount"
            else " ON DUPLICATE KEY UPDATE amount = amount + VALUES(amount)"
        source.connection().prepareStatement(sql).use { ps ->
            ps.setString(1, player.toString()); ps.setString(2, currency); ps.setLong(3, delta)
            ps.executeUpdate()
        }
    }

    /** 은행에서 뺀다 — **모자라면 안 뺀다.** */
    fun bankTake(source: SqlSource, player: UUID, currency: String, amount: Long): Boolean =
        source.connection().prepareStatement("UPDATE shop_bank SET amount = amount - ? WHERE player = ? AND currency = ? AND amount >= ?").use { ps ->
            ps.setLong(1, amount); ps.setString(2, player.toString()); ps.setString(3, currency); ps.setLong(4, amount)
            ps.executeUpdate() == 1
        }

    // --- 상자 상점(로컬) --------------------------------------------------------------------

    fun chestShops(source: SqlSource): List<ChestRow> =
        source.connection().prepareStatement("SELECT id, world, x, y, z, owner, data FROM shop_chest").use { ps ->
            ps.executeQuery().use { rs ->
                rows(rs) { ChestRow(it.getString(1), it.getString(2), it.getInt(3), it.getInt(4), it.getInt(5), UUID.fromString(it.getString(6)), it.getString(7)) }
            }
        }

    fun saveChestShop(source: SqlSource, row: ChestRow) {
        val sql = if (source.dialect == SqlDialect.SQLITE) "INSERT OR REPLACE INTO shop_chest (id, world, x, y, z, owner, data) VALUES (?, ?, ?, ?, ?, ?, ?)"
        else "REPLACE INTO shop_chest (id, world, x, y, z, owner, data) VALUES (?, ?, ?, ?, ?, ?, ?)"
        source.connection().prepareStatement(sql).use { ps ->
            ps.setString(1, row.id); ps.setString(2, row.world); ps.setInt(3, row.x); ps.setInt(4, row.y); ps.setInt(5, row.z)
            ps.setString(6, row.owner.toString()); ps.setString(7, row.data)
            ps.executeUpdate()
        }
    }

    fun deleteChestShop(source: SqlSource, id: String) {
        source.transaction { c ->
            c.prepareStatement("DELETE FROM shop_chest WHERE id = ?").use { ps -> ps.setString(1, id); ps.executeUpdate() }
            c.prepareStatement("DELETE FROM shop_chest_stock WHERE shop = ?").use { ps -> ps.setString(1, id); ps.executeUpdate() }
        }
    }

    fun chestStock(source: SqlSource): Map<Pair<String, String>, Long> =
        source.connection().prepareStatement("SELECT shop, product, items FROM shop_chest_stock").use { ps ->
            ps.executeQuery().use { rs -> rows(rs) { (it.getString(1) to it.getString(2)) to it.getLong(3) }.toMap() }
        }

    /** 창고 수량을 그 값으로. 창고는 이 서버만 만지므로 메모리가 진실이다 — 순서대로 쓰기만 하면 된다. */
    fun setChestStock(source: SqlSource, shop: String, product: String, items: Long) {
        if (items <= 0) {
            source.connection().prepareStatement("DELETE FROM shop_chest_stock WHERE shop = ? AND product = ?").use { ps -> ps.setString(1, shop); ps.setString(2, product); ps.executeUpdate() }
            return
        }
        val sql = if (source.dialect == SqlDialect.SQLITE) "INSERT OR REPLACE INTO shop_chest_stock (shop, product, items) VALUES (?, ?, ?)"
        else "REPLACE INTO shop_chest_stock (shop, product, items) VALUES (?, ?, ?)"
        source.connection().prepareStatement(sql).use { ps -> ps.setString(1, shop); ps.setString(2, product); ps.setLong(3, items); ps.executeUpdate() }
    }

    // --- 돌려받을 물건(로컬) ------------------------------------------------------------------

    fun addReturn(source: SqlSource, player: UUID, item: String, reason: String, now: Long) {
        source.connection().prepareStatement("INSERT INTO shop_return (player, item, reason, at) VALUES (?, ?, ?, ?)").use { ps ->
            ps.setString(1, player.toString()); ps.setString(2, item); ps.setString(3, reason.take(120)); ps.setLong(4, now)
            ps.executeUpdate()
        }
    }

    fun returns(source: SqlSource, player: UUID): List<ReturnRow> =
        source.connection().prepareStatement("SELECT id, item, reason FROM shop_return WHERE player = ? ORDER BY id").use { ps ->
            ps.setString(1, player.toString())
            ps.executeQuery().use { rs -> rows(rs) { ReturnRow(it.getLong(1), it.getString(2), it.getString(3)) } }
        }

    /** 하나를 지운다 — 지웠으면 true(두 번 받기 방지). */
    fun takeReturn(source: SqlSource, id: Long): Boolean =
        source.connection().prepareStatement("DELETE FROM shop_return WHERE id = ?").use { ps -> ps.setLong(1, id); ps.executeUpdate() == 1 }

    // --- 경매 ------------------------------------------------------------------------------

    private val listingColumns = "id, seller, seller_name, item, amount, search, group_key, currency, price, created, expires, state, buyer, buyer_name, sold_at, payout, note, updated"

    private fun listing(rs: ResultSet): ListingRow = ListingRow(
        id = rs.getString(1), seller = UUID.fromString(rs.getString(2)), sellerName = rs.getString(3), item = rs.getString(4),
        amount = rs.getInt(5), search = rs.getString(6), groupKey = rs.getString(7), currency = rs.getString(8), price = rs.getLong(9),
        created = rs.getLong(10), expires = rs.getLong(11), state = rs.getString(12), buyer = rs.getString(13)?.let(UUID::fromString),
        buyerName = rs.getString(14), soldAt = rs.getLong(15), payout = rs.getLong(16), note = rs.getString(17), updated = rs.getLong(18),
    )

    fun insertListing(source: SqlSource, row: ListingRow) {
        source.connection().prepareStatement("INSERT INTO auction_listing ($listingColumns) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").use { ps ->
            ps.setString(1, row.id); ps.setString(2, row.seller.toString()); ps.setString(3, row.sellerName); ps.setString(4, row.item)
            ps.setInt(5, row.amount); ps.setString(6, row.search.take(250)); ps.setString(7, row.groupKey); ps.setString(8, row.currency)
            ps.setLong(9, row.price); ps.setLong(10, row.created); ps.setLong(11, row.expires); ps.setString(12, row.state)
            ps.setString(13, row.buyer?.toString()); ps.setString(14, row.buyerName); ps.setLong(15, row.soldAt); ps.setLong(16, row.payout)
            ps.setString(17, row.note); ps.setLong(18, row.updated)
            ps.executeUpdate()
        }
    }

    /** [since] 뒤에 바뀐 등록 전부 — 캐시를 맞춘다. */
    fun listingsSince(source: SqlSource, since: Long): List<ListingRow> =
        source.connection().prepareStatement("SELECT $listingColumns FROM auction_listing WHERE updated > ?").use { ps ->
            ps.setLong(1, since)
            ps.executeQuery().use { rs -> rows(rs) { listing(it) } }
        }

    fun listing(source: SqlSource, id: String): ListingRow? =
        source.connection().prepareStatement("SELECT $listingColumns FROM auction_listing WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) listing(rs) else null }
        }

    /** 산다 — **아직 판매 중이고 만료 전일 때만.** 두 서버가 같은 물건을 팔지 않는다. */
    fun buyListing(source: SqlSource, id: String, buyer: UUID, buyerName: String, payout: Long, now: Long): Boolean =
        source.connection().prepareStatement(
            "UPDATE auction_listing SET state = 'SOLD', buyer = ?, buyer_name = ?, sold_at = ?, payout = ?, updated = ? WHERE id = ? AND state = 'ACTIVE' AND expires > ?",
        ).use { ps ->
            ps.setString(1, buyer.toString()); ps.setString(2, buyerName); ps.setLong(3, now); ps.setLong(4, payout); ps.setLong(5, now)
            ps.setString(6, id); ps.setLong(7, now)
            ps.executeUpdate() == 1
        }

    /** 산 것을 무른다 — 돈을 못 받았을 때. 이 사람이 산 그 판일 때만. */
    fun unbuyListing(source: SqlSource, id: String, buyer: UUID, now: Long): Boolean =
        source.connection().prepareStatement(
            "UPDATE auction_listing SET state = 'ACTIVE', buyer = NULL, buyer_name = NULL, sold_at = 0, payout = 0, updated = ? WHERE id = ? AND state = 'SOLD' AND buyer = ?",
        ).use { ps ->
            ps.setLong(1, now); ps.setString(2, id); ps.setString(3, buyer.toString())
            ps.executeUpdate() == 1
        }

    /** 상태를 [from] → [to] 로 — 지금 [from] 일 때만. 성공하면 true. */
    fun moveListing(source: SqlSource, id: String, from: String, to: String, now: Long, note: String? = null): Boolean =
        source.connection().prepareStatement("UPDATE auction_listing SET state = ?, note = COALESCE(?, note), updated = ? WHERE id = ? AND state = ?").use { ps ->
            ps.setString(1, to); ps.setString(2, note); ps.setLong(3, now); ps.setString(4, id); ps.setString(5, from)
            ps.executeUpdate() == 1
        }

    /** 만료 시각이 지난 판매 중 물건을 만료로. 바뀐 것의 id 들. */
    fun expireListings(source: SqlSource, now: Long): Int =
        source.connection().prepareStatement("UPDATE auction_listing SET state = 'EXPIRED', updated = ? WHERE state = 'ACTIVE' AND expires <= ?").use { ps ->
            ps.setLong(1, now); ps.setLong(2, now)
            ps.executeUpdate()
        }

    /** 끝난 기록(돈 받음·돌려받음)만 지운다. 받지 않은 물건·돈은 지우지 않는다 — 지우면 플레이어의 것이 사라진다. */
    fun purgeListings(source: SqlSource, before: Long): Int =
        source.connection().prepareStatement("DELETE FROM auction_listing WHERE state IN ('CLAIMED', 'RETURNED') AND updated < ?").use { ps ->
            ps.setLong(1, before)
            ps.executeUpdate()
        }

    // --- 경고·벌칙 ---------------------------------------------------------------------------

    fun addWarning(source: SqlSource, player: UUID, by: String, reason: String, now: Long) {
        source.connection().prepareStatement("INSERT INTO auction_warning (player, by_name, reason, at) VALUES (?, ?, ?, ?)").use { ps ->
            ps.setString(1, player.toString()); ps.setString(2, by); ps.setString(3, reason.take(250)); ps.setLong(4, now)
            ps.executeUpdate()
        }
    }

    fun warnings(source: SqlSource, player: UUID, since: Long): List<WarningRow> =
        source.connection().prepareStatement("SELECT id, player, by_name, reason, at FROM auction_warning WHERE player = ? AND at >= ? ORDER BY at DESC").use { ps ->
            ps.setString(1, player.toString()); ps.setLong(2, since)
            ps.executeQuery().use { rs -> rows(rs) { WarningRow(it.getLong(1), UUID.fromString(it.getString(2)), it.getString(3), it.getString(4), it.getLong(5)) } }
        }

    fun clearWarnings(source: SqlSource, player: UUID) {
        source.connection().prepareStatement("DELETE FROM auction_warning WHERE player = ?").use { ps -> ps.setString(1, player.toString()); ps.executeUpdate() }
    }

    fun ban(source: SqlSource, player: UUID): Pair<Long, String>? =
        source.connection().prepareStatement("SELECT until, reason FROM auction_ban WHERE player = ?").use { ps ->
            ps.setString(1, player.toString())
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) to rs.getString(2) else null }
        }

    fun setBan(source: SqlSource, player: UUID, until: Long, reason: String) {
        val sql = if (source.dialect == SqlDialect.SQLITE) "INSERT OR REPLACE INTO auction_ban (player, until, reason) VALUES (?, ?, ?)"
        else "REPLACE INTO auction_ban (player, until, reason) VALUES (?, ?, ?)"
        source.connection().prepareStatement(sql).use { ps -> ps.setString(1, player.toString()); ps.setLong(2, until); ps.setString(3, reason.take(250)); ps.executeUpdate() }
    }

    fun clearBan(source: SqlSource, player: UUID) {
        source.connection().prepareStatement("DELETE FROM auction_ban WHERE player = ?").use { ps -> ps.setString(1, player.toString()); ps.executeUpdate() }
    }

    private inline fun <T> rows(rs: ResultSet, map: (ResultSet) -> T): List<T> = buildList { while (rs.next()) add(map(rs)) }
}
