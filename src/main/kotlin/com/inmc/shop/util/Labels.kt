package com.inmc.shop.util

import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.inventory.ItemStack

/**
 * 아이템 이름을 메시지에 넣을 MiniMessage 로. 이름이 붙은 것은 그 이름(색 그대로), 아니면 `<lang:…>` — 서버는 한글 이름을
 * 모르지만 클라이언트가 자기 언어로 그린다.
 */
object Labels {

    fun of(stack: ItemStack?): String {
        if (stack == null || stack.type.isAir) return "?"
        val meta = stack.itemMeta
        val custom = meta?.takeIf { it.hasDisplayName() }?.displayName()
        if (custom != null) return MiniMessage.miniMessage().serialize(custom)
        val item = meta?.takeIf { it.hasItemName() }?.itemName()
        if (item != null) return MiniMessage.miniMessage().serialize(item)
        return "<lang:" + stack.translationKey() + ">"
    }
}
