package com.inmc.shop.hook

import com.inmc.shop.Shop
import kr.inmc.core.integration.CustomItemHook
import kr.inmc.core.item.ItemRef
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

/**
 * **만들기 전용** 공급처 `inmcshop:creation:<블록>` (2026-10-10) — 놓으면 상자 상점이 되는 블록 아이템.
 *
 * 그 아이템은 PDC 표시(`inmcshop:creation`)만 단 바닐라 블록이라 참조가 없다. 이 공급처가 essentials `/지급`·킷·urb 보상에
 * `/상자상점 아이템지급` 과 **같은 아이템**([com.inmc.shop.chest.ChestService.creationItem])을 준다. 블록은 설정
 * `chest.blocks` 에 있는 것만(놓아도 상점이 못 되는 블록 아이템은 만들지 않는다).
 *
 * **[identify] 는 언제나 null 이다.** 알아보기 시작하면 모든 플러그인의 보상 캡처가 이 아이템을 참조로 저장하게 된다 —
 * 지금까지 스냅샷으로 저장하던 동작이 조용히 바뀐다.
 */
class CreationItemProvider(private val shop: Shop) : CustomItemHook.Provider {

    override val namespace: String get() = NAMESPACE

    /** 만들기 전용 — 찾기·지우기 제안에서 빠지고 "꽂혔나" 에 세지 않는다(core `makeOnly`). */
    override val makeOnly: Boolean get() = true

    override fun identify(stack: ItemStack): String? = null

    override fun create(ref: ItemRef.Namespaced): ItemStack? {
        if (!ref.namespace.equals(NAMESPACE, ignoreCase = true)) return null
        val name = blockOf(ref.id) ?: return null
        if (!allowed(name, shop.config.chest.blocks)) return null
        val material = Material.matchMaterial(name)?.takeIf { it.isBlock && it.isItem } ?: return null
        return shop.chests.creationItem(material, 1)
    }

    /** 설정의 블록 전부(`SHULKER_BOX` 는 색깔별 셜커 상자까지). */
    override fun ids(): Collection<String> {
        val blocks = shop.config.chest.blocks
        return Material.entries.filter { !it.name.startsWith("LEGACY_") && it.isBlock && it.isItem && allowed(it.name, blocks) }.map { PREFIX + it.name.lowercase() }
    }

    companion object {
        const val NAMESPACE = "inmcshop"
        private const val PREFIX = "creation:"

        /** `creation:<블록>` → 대문자 재질 이름(`minecraft:` 앞붙이도 받는다). 그 밖의 꼴은 null. */
        fun blockOf(id: String): String? {
            val text = id.trim()
            if (!text.startsWith(PREFIX, ignoreCase = true)) return null
            val block = text.substring(PREFIX.length).removePrefix("minecraft:").trim()
            return block.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '_' } }?.uppercase()
        }

        /** 상자 상점이 될 수 있는 블록인가 — `ChestService.allowedBlock` 과 같은 규칙(`SHULKER_BOX` 는 색깔별도). */
        fun allowed(name: String, blocks: List<String>): Boolean =
            blocks.any { allowed -> name == allowed || (allowed == "SHULKER_BOX" && name.endsWith("SHULKER_BOX")) }
    }
}
