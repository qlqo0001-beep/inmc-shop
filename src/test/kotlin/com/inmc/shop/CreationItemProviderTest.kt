package com.inmc.shop

import com.inmc.shop.hook.CreationItemProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 만들기 전용 공급처 `inmcshop:creation:<블록>` 의 id 문법과 블록 거르기. */
class CreationItemProviderTest {

    @Test
    fun `creation 다음의 블록 이름을 읽는다`() {
        assertEquals("CHEST", CreationItemProvider.blockOf("creation:chest"))
        assertEquals("BARREL", CreationItemProvider.blockOf("Creation:minecraft:barrel"))
        assertEquals("RED_SHULKER_BOX", CreationItemProvider.blockOf("creation:red_shulker_box"))
        assertNull(CreationItemProvider.blockOf("chest"))
        assertNull(CreationItemProvider.blockOf("creation:"))
        assertNull(CreationItemProvider.blockOf("creation:che st"))
        assertNull(CreationItemProvider.blockOf("display:chest"))
    }

    @Test
    fun `설정의 블록만 - SHULKER_BOX 는 색깔별도`() {
        val blocks = listOf("CHEST", "SHULKER_BOX")
        assertTrue(CreationItemProvider.allowed("CHEST", blocks))
        assertTrue(CreationItemProvider.allowed("RED_SHULKER_BOX", blocks))
        assertFalse(CreationItemProvider.allowed("BARREL", blocks))
        assertFalse(CreationItemProvider.allowed("TRAPPED_CHEST", listOf("CHEST")))
    }
}
