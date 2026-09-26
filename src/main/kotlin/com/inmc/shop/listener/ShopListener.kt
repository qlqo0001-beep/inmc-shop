package com.inmc.shop.listener

import com.inmc.shop.Shop
import com.inmc.shop.gui.ChestManageMenu
import com.inmc.shop.gui.ChestShopMenu
import com.inmc.shop.util.Ph
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.block.Container
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType

/** 접속·퇴장. */
class PlayerListener(private val shop: Shop) : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        shop.limits.load(player.uniqueId)
        // 접속 직후 알림은 한 틱 뒤 — 다른 플러그인의 접속 메시지에 묻히지 않게.
        player.scheduler.runDelayed(shop.plugin, { _ ->
            if (!player.isOnline) return@runDelayed
            shop.auction.notifyJoin(player)
            shop.returns.count(player.uniqueId) { n -> if (n > 0 && player.isOnline) shop.messages.send(player, "returns-join", Ph.of().count(n)) }
        }, null, 40L)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        shop.limits.unload(event.player.uniqueId)
    }
}

/**
 * 상자 상점 — 우클릭(주인은 관리, 남은 상점), 그리고 보호. 상자는 간판이라 열리지 않고, 부수기·폭발·피스톤·호퍼·옆에 상자를 붙여
 * 큰 상자로 만들기를 막는다(삭제는 관리 화면에서만 — 실수로 부숴 창고를 잃지 않게).
 */
class ChestListener(private val shop: Shop) : Listener {

    private val creationKey by lazy { NamespacedKey(TAG_NAMESPACE, "creation") }

    // 허공 클릭이 처음부터 "취소됨"으로 오는 함정(ARCHITECTURE 지뢰 14)과 무관하게 블록 클릭만 받는다.
    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return
        val value = shop.chests.at(block) ?: return
        event.isCancelled = true
        val player = event.player
        if (!shop.config.chest.enabled) return shop.messages.send(player, "module-disabled")
        if (value.canManage(player.uniqueId) || (player.isSneaking && player.hasPermission("inmcshop.chestshop.edit.others"))) {
            ChestManageMenu(shop, player, value.id).show()
        } else {
            if (!shop.guard(player)) return
            ChestShopMenu(shop, player, value.id).show()
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    fun onBreak(event: BlockBreakEvent) {
        if (shop.chests.at(event.block) == null) return
        event.isCancelled = true
        shop.messages.send(event.player, "chest-break-denied")
    }

    @EventHandler(ignoreCancelled = true)
    fun onExplode(event: EntityExplodeEvent) {
        event.blockList().removeIf { shop.chests.at(it) != null }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        event.blockList().removeIf { shop.chests.at(it) != null }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (event.blocks.any { shop.chests.at(it) != null }) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (event.blocks.any { shop.chests.at(it) != null }) event.isCancelled = true
    }

    /** 호퍼가 상점 상자로 넣거나 빼지 못하게 — 넣은 것은 보이지 않는 곳에 갇힌다. */
    @EventHandler(ignoreCancelled = true)
    fun onHopper(event: InventoryMoveItemEvent) {
        val source = (event.source.holder as? Container)?.block
        val destination = (event.destination.holder as? Container)?.block
        if ((source != null && shop.chests.at(source) != null) || (destination != null && shop.chests.at(destination) != null)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    fun onPlace(event: BlockPlaceEvent) {
        val block = event.blockPlaced
        // 상점 상자 옆에 상자를 붙이면 큰 상자가 되어 반쪽이 상점이 아닌 채로 열린다.
        if (block.type.name.endsWith("CHEST")) {
            for (face in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
                val next = block.getRelative(face)
                if (next.type == block.type && shop.chests.at(next) != null) {
                    event.isCancelled = true
                    return shop.messages.send(event.player, "chest-no-merge")
                }
            }
        }
        // 상점 블록 아이템.
        val meta = event.itemInHand.itemMeta ?: return
        if (!meta.persistentDataContainer.has(creationKey, PersistentDataType.BYTE)) return
        if (!shop.config.chest.creationItems) return
        val player = event.player
        player.scheduler.run(shop.plugin, { _ ->
            if (shop.chests.create(player, block) == null && block.type == event.itemInHand.type) {
                // 못 만들었다 — 놓인 블록을 거두고 아이템을 돌려준다.
                block.type = org.bukkit.Material.AIR
                player.inventory.addItem(event.itemInHand.clone().apply { amount = 1 })
            }
        }, null)
    }

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) = shop.displays.onChunkLoad(event.chunk)

    @EventHandler
    fun onChunkUnload(event: ChunkUnloadEvent) = shop.displays.onChunkUnload(event.chunk)

    companion object {
        /** 고정 — 플러그인 이름이 바뀌어도 돌아다니는 상점 블록 아이템을 알아본다. */
        const val TAG_NAMESPACE = "inmcshop"
    }
}
