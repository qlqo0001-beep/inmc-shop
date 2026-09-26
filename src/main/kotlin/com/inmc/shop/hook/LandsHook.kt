package com.inmc.shop.hook

import kr.inmc.core.integration.PluginClasses
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.logging.Logger

/**
 * Lands — "자기 땅에서만 상자 상점". 컴파일 의존 없이 리플렉션으로 부른다(Lands 가 없는 서버에서도 켜진다).
 * `LandsIntegration.of(plugin).getArea(location)` 이 null 이면 야생, 아니면 `isTrusted(uuid)`.
 */
object LandsHook {

    @Volatile
    private var integration: Any? = null

    @Volatile
    private var broken = false

    private lateinit var owner: Plugin
    private lateinit var logger: Logger

    fun setup(plugin: Plugin, logger: Logger) {
        owner = plugin
        this.logger = logger
        integration = null
        broken = false
    }

    /** true = 자기 땅(신뢰됨), false = 아님, null = Lands 없음. */
    fun canCreate(player: Player, location: Location): Boolean? {
        if (broken || !::owner.isInitialized || !PluginClasses.isEnabled("Lands")) return null
        return try {
            val api = integration ?: PluginClasses.require("Lands", "me.angeschossen.lands.api.LandsIntegration")
                .getMethod("of", Plugin::class.java).invoke(null, owner).also { integration = it }
            val area = api!!.javaClass.methods.first { it.name == "getArea" && it.parameterTypes.contentEquals(arrayOf(Location::class.java)) }
                .invoke(api, location) ?: return false
            val trusted = area.javaClass.methods.first { it.name == "isTrusted" && it.parameterTypes.contentEquals(arrayOf(UUID::class.java)) }
                .invoke(area, player.uniqueId)
            trusted as? Boolean ?: false
        } catch (t: Throwable) {
            broken = true
            logger.warning("Lands 연동 실패 - '자기 땅에서만' 검사를 끕니다: ${t.javaClass.simpleName} ${t.message}")
            null
        }
    }
}
