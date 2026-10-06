package com.inmc.shop.chest

import com.inmc.shop.Shop
import com.inmc.shop.trade.TradeType
import kr.inmc.core.util.Text
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.TextDisplay
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 상자 상점 위의 홀로그램·떠 있는 상품·쇼케이스 — Paper Display 엔티티(패킷 라이브러리 없이). **저장하지 않는다**(`isPersistent = false`)
 * — 서버를 껐다 켜도 남지 않고, 청크가 읽힐 때 다시 세운다. 상품이 여럿이면 몇 초마다 번갈아 보인다.
 */
class DisplayService(private val shop: Shop) {

    private class Spawned(val text: UUID?, val item: UUID?, val showcase: UUID?)

    private val spawned = ConcurrentHashMap<String, Spawned>()
    private val cursor = ConcurrentHashMap<String, Int>()
    private val tag by lazy { NamespacedKey(TAG_NAMESPACE, "display") }
    private var seconds = 0L

    private val settings get() = shop.config.chest

    fun spawnAll() {
        for (value in shop.chests.all()) if (loaded(value)) spawn(value)
    }

    private fun loaded(value: ChestShop): Boolean {
        val world = Bukkit.getWorld(value.key.world) ?: return false
        return world.isChunkLoaded(value.key.x shr 4, value.key.z shr 4)
    }

    fun refresh(value: ChestShop) {
        if (!Bukkit.isPrimaryThread()) return shop.main { refresh(value) }
        remove(value)
        if (loaded(value)) spawn(value)
    }

    fun remove(value: ChestShop) {
        val old = spawned.remove(value.id) ?: return
        for (id in listOfNotNull(old.text, old.item, old.showcase)) Bukkit.getEntity(id)?.remove()
    }

    fun onChunkLoad(chunk: Chunk) {
        for (value in shop.chests.all()) {
            if (value.key.world != chunk.world.name || value.key.x shr 4 != chunk.x || value.key.z shr 4 != chunk.z) continue
            // 무조건 다시 세운다. 맵에 있다고 건너뛰면 — 월드 언로드처럼 청크 사건 없이 엔티티가
            // 사라진 뒤에는 — 영영 안 돌아온다(테섭 2026-10-04 "멀리 갔다 오면 디스플레이가 없음").
            // refresh 는 tracked-사라짐을 치우고 로드된 청크에만 세우므로 멱등하다.
            refresh(value)
        }
        sweepChunk(chunk)
    }

    fun onChunkUnload(chunk: Chunk) {
        for (entity in chunk.entities) {
            val id = entity.persistentDataContainer.get(tag, PersistentDataType.STRING) ?: continue
            spawned.remove(id)
            entity.remove()
        }
    }

    /** 1초마다 — 상품이 여럿인 상점은 [ChestSettings.itemChangeSeconds] 마다 다음 상품. 고아는 60초마다. */
    fun tick() {
        seconds++
        if (seconds % settings.itemChangeSeconds == 0L) {
            for (value in shop.chests.all()) {
                if (value.products.size < 2 || !spawned.containsKey(value.id)) continue
                cursor.merge(value.id, 1, Int::plus)
                refresh(value)
            }
        }
        if (seconds % SWEEP_SECONDS == 0L) sweepUnknown()
    }

    fun shutdown() {
        for (value in shop.chests.all()) remove(value)
        spawned.clear()
    }

    /**
     * 주인 없는 디스플레이를 거둔다 — 지워진 상점의 것이나 겹쳐 세워진 것. 로드된 청크만 본다.
     * 리로드 뒤 + 60초마다 + 청크 로드 때마다 돈다. 리로드 없이도 저절로 사라진다.
     */
    fun sweepUnknown() {
        if (!Bukkit.isPrimaryThread()) return shop.main { sweepUnknown() }
        val known = shop.chests.all().map { it.id }.toSet()
        for (world in Bukkit.getWorlds()) {
            for (entity in world.getEntitiesByClass(Display::class.java)) {
                val id = entity.persistentDataContainer.get(tag, PersistentDataType.STRING) ?: continue
                if (id !in known) entity.remove()
            }
        }
    }

    /** 청크 하나만 본다 — 로드 때 그 자리 고아를 즉시 치운다. */
    private fun sweepChunk(chunk: Chunk) {
        val known = shop.chests.all().map { it.id }.toSet()
        for (entity in chunk.entities) {
            if (entity !is Display) continue
            val id = entity.persistentDataContainer.get(tag, PersistentDataType.STRING) ?: continue
            if (id !in known) entity.remove()
        }
    }

    private fun spawn(value: ChestShop) {
        if (!settings.enabled) return
        val base = value.key.location()?.add(0.5, 0.0, 0.5) ?: return
        val world = base.world ?: return
        val now = System.currentTimeMillis()
        val products = value.products
        val product = if (products.isEmpty()) null else products[(cursor[value.id] ?: 0).mod(products.size)]
        val range = settings.viewDistance / 64f

        var itemId: UUID? = null
        var showcaseId: UUID? = null
        if (product != null) {
            val stack = shop.chests.template(product)?.apply { amount = 1 }
            if (stack != null) {
                itemId = world.spawn(base.clone().add(0.0, 1.25, 0.0), ItemDisplay::class.java) { d ->
                    prepare(d, value, range)
                    d.setItemStack(stack)
                    d.billboard = Display.Billboard.VERTICAL
                    d.transformation = Transformation(Vector3f(), AxisAngle4f(), Vector3f(0.45f, 0.45f, 0.45f), AxisAngle4f())
                }.uniqueId
            }
            value.showcase?.let { Material.matchMaterial(it) }?.takeIf { it.isBlock }?.let { material ->
                showcaseId = world.spawn(base.clone().add(0.0, 1.0, 0.0), BlockDisplay::class.java) { d ->
                    prepare(d, value, range)
                    d.block = material.createBlockData()
                    d.transformation = Transformation(Vector3f(-0.3f, 0f, -0.3f), AxisAngle4f(), Vector3f(0.6f, 0.6f, 0.6f), AxisAngle4f())
                }.uniqueId
            }
        }
        var textId: UUID? = null
        if (value.hologram && settings.hologram) {
            val lines = lines(value, product, now)
            textId = world.spawn(base.clone().add(0.0, if (product != null) 1.75 else 1.3, 0.0), TextDisplay::class.java) { d ->
                prepare(d, value, range)
                d.text(Text.render(lines.joinToString("\n")))
                d.billboard = Display.Billboard.CENTER
                d.isShadowed = true
                d.backgroundColor = Color.fromARGB(64, 0, 0, 0)
                d.lineWidth = 200
            }.uniqueId
        }
        spawned[value.id] = Spawned(textId, itemId, showcaseId)
    }

    private fun prepare(entity: Entity, value: ChestShop, range: Float) {
        entity.isPersistent = false
        entity.persistentDataContainer.set(tag, PersistentDataType.STRING, value.id)
        (entity as? Display)?.viewRange = range
    }

    private fun lines(value: ChestShop, product: ChestProduct?, now: Long): List<String> {
        val name = if (value.admin) settings.adminName else "<yellow>" + value.name + "</yellow>"
        if (shop.chests.rentable(value, now)) {
            val currency = shop.currency(value.rent.currency, settings.currency)
            return listOf(name, "<green><b>임대 가능</b></green>", "<gray>${value.rent.days}일 · ${currency?.format(value.rent.price) ?: value.rent.price}</gray>")
        }
        if (product == null) return listOf(name, "<red>[ 준비 중 ]</red>")
        val currency = shop.chests.currencyOf(product)
        val out = arrayListOf(name, "<white>" + shop.chests.label(product) + "</white> <gray>x${shop.chests.unitOf(product)}</gray>")
        val prices = buildList {
            product.price(TradeType.BUY)?.let { add("<green>구매 " + (currency?.format(it) ?: it.toString()) + "</green>") }
            product.price(TradeType.SELL)?.let { add("<red>판매 " + (currency?.format(it) ?: it.toString()) + "</red>") }
        }
        if (prices.isNotEmpty()) out += prices.joinToString(" <dark_gray>|</dark_gray> ")
        if (!value.admin) out += "<gray>재고 " + "%,d".format(shop.chests.stock(value, product)) + "</gray>"
        if (value.rent.active(now)) out += "<dark_gray>" + value.operatorName(now) + " 운영</dark_gray>"
        return out
    }

    companion object {
        /** 고정 — 플러그인 이름이 바뀌어도 남은 엔티티를 알아본다. */
        const val TAG_NAMESPACE = "inmcshop"

        /** 고아 청소 주기(초). Display 엔티티 훑기라 1초마다 돌릴 것은 아니다. */
        const val SWEEP_SECONDS = 60L
    }
}
