package com.inmc.shop

import com.inmc.shop.virtual.ItemStorage
import com.inmc.shop.virtual.Product
import com.inmc.shop.virtual.ProductType
import kr.inmc.core.item.ItemRef
import kr.inmc.core.item.StorageMode
import kr.inmc.core.item.StoredItem
import org.bukkit.Material
import kotlin.test.Test
import kotlin.test.assertEquals

/** 상품 아이템의 동적/스냅샷 — 고를 수 있는 것만 고르게, 아이템을 바꿔도 고른 방식이 남게. */
class ItemStorageTest {

    private val vanilla = StoredItem(ItemRef.Vanilla(Material.DIAMOND), Material.DIAMOND)
    private val handmade = StoredItem(ItemRef.None, Material.STICK, StorageMode.SNAPSHOT, byteArrayOf(1))
    private val custom = StoredItem(ItemRef.Namespaced("inmc", "sword"), Material.DIAMOND_SWORD, StorageMode.REFERENCE, byteArrayOf(2))
    private val mmo = StoredItem(ItemRef.MMOItems("SWORD", "EXCALIBUR"), Material.IRON_SWORD)

    @Test
    fun `원본 정의가 따로 있는 아이템만 방식을 고른다`() {
        assertEquals(ItemStorage.Choice.SAME, ItemStorage.choice(vanilla))
        assertEquals(ItemStorage.Choice.FIXED, ItemStorage.choice(handmade))
        assertEquals(ItemStorage.Choice.CHOOSABLE, ItemStorage.choice(custom))
        assertEquals(ItemStorage.Choice.CHOOSABLE, ItemStorage.choice(mmo))
        assertEquals("동적(참조)", ItemStorage.label(custom))
        assertEquals("스냅샷(고정)", ItemStorage.label(custom.withMode(StorageMode.SNAPSHOT)))
    }

    @Test
    fun `아이템을 바꿔 등록해도 고른 방식이 남는다`() {
        val pinned = custom.withMode(StorageMode.SNAPSHOT)
        assertEquals(StorageMode.SNAPSHOT, ItemStorage.keepMode(pinned, mmo).mode)
        // 바닐라·손으로 만든 것은 capture 가 정한 그대로 — 고를 수 없는 방식을 물려받지 않는다.
        assertEquals(StorageMode.REFERENCE, ItemStorage.keepMode(pinned, vanilla).mode)
        assertEquals(StorageMode.SNAPSHOT, ItemStorage.keepMode(custom, handmade).mode)
        assertEquals(mmo, ItemStorage.keepMode(null, mmo))
    }

    @Test
    fun `방식은 화면에 보이는 아이템에 걸린다`() {
        val item = Product("a", "s", ProductType.ITEM, item = custom)
        assertEquals(custom, item.shown)
        assertEquals(StorageMode.SNAPSHOT, item.withShown(custom.withMode(StorageMode.SNAPSHOT)).item?.mode)

        val command = Product("b", "s", ProductType.COMMAND, item = vanilla, preview = mmo)
        assertEquals(mmo, command.shown)
        val changed = command.withShown(mmo.withMode(StorageMode.SNAPSHOT))
        assertEquals(StorageMode.SNAPSHOT, changed.preview?.mode)
        assertEquals(vanilla, changed.item)

        val noPreview = Product("c", "s", ProductType.COMMAND, item = custom)
        assertEquals(custom, noPreview.shown)
        assertEquals(StorageMode.SNAPSHOT, noPreview.withShown(custom.withMode(StorageMode.SNAPSHOT)).item?.mode)
    }
}
