package com.inmc.shop

import com.inmc.shop.config.Messages
import com.inmc.shop.config.ShopConfig
import com.inmc.shop.virtual.DefaultShops
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.InputStreamReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 배포 파일과 코드가 같은 말을 하는지. 어긋나도 오류는 안 나고 조용히 틀린다. */
class ResourceTest {

    private fun resource(name: String): YamlConfiguration {
        val stream = javaClass.classLoader.getResourceAsStream(name)
        assertNotNull(stream, name)
        return stream.use { YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8)) }
    }

    private val sources: List<File> by lazy { File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList() }

    @Test
    fun `배포 messages 와 코드 기본값의 키가 정확히 같다`() {
        val file = resource("messages.yml").getKeys(true).filter { !resource("messages.yml").isConfigurationSection(it) }.toSet()
        assertEquals(Messages.DEFAULTS.keys, file)
    }

    @Test
    fun `코드가 보내는 메시지 키는 전부 있다`() {
        val keys = HashSet<String>()
        val patterns = listOf(
            Regex("""messages\.send\([^,()]+(?:\([^)]*\))?,\s*"([a-z0-9-]+)""""),
            Regex("""fail\("([a-z0-9-]+)""""),
            Regex("""if \([^)]*\) "([a-z0-9-]+)" else "([a-z0-9-]+)""""),
            Regex(""""([a-z0-9-]+)" to (?:null|Ph)"""),
        )
        for (file in sources) {
            val text = file.readText()
            for (p in patterns) for (m in p.findAll(text)) for (g in m.groupValues.drop(1)) if (g.isNotEmpty()) keys += g
        }
        val missing = keys - Messages.DEFAULTS.keys
        assertTrue(missing.isEmpty(), "메시지 기본값에 없는 키: $missing")
    }

    @Test
    fun `배포 config 는 코드 기본값과 같다`() {
        assertEquals(ShopConfig(), ShopConfig.from(resource("config.yml")))
    }

    @Test
    fun `설정은 저장해도 그대로 읽힌다`() {
        val original = ShopConfig()
        val reread = YamlConfiguration().apply { loadFromString(original.toYaml().saveToString()) }
        assertEquals(original, ShopConfig.from(reread))
    }

    @Test
    fun `코드가 묻는 권한은 전부 선언돼 있다 — 안 하면 OP 자동 허용`() {
        // YAML 로 읽으면 점이 경로가 되어 노드 이름이 쪼개진다(지뢰 1) — 줄로 읽는다.
        val text = File("src/main/resources/paper-plugin.yml").readText(Charsets.UTF_8)
        val declared = Regex("""^  (inmcshop\.[a-z0-9_.*]+):\s*$""", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }.toSet()
        assertTrue(declared.size > 40, "선언을 못 읽었다: ${declared.size}")
        val used = HashSet<String>()
        val node = Regex(""""(inmcshop\.[a-z0-9_.*]+)"""")
        for (file in sources) for (m in node.findAll(file.readText())) {
            val n = m.groupValues[1]
            if (n.endsWith(".")) continue // 뒤에 id 가 붙는 접두사
            used += n
        }
        // 등급 값 접두사의 기본값(설정)은 권한 목록이 아니라 접두사다.
        val prefixes = setOf("inmcshop.sellmultiplier.", "inmcshop.chestshop.shops.", "inmcshop.chestshop.products.", "inmcshop.chestshop.capacity.", "inmcshop.auction.listings.")
        val missing = used.filter { it !in declared && prefixes.none { p -> it.startsWith(p) } }
        assertTrue(missing.isEmpty(), "선언 안 된 권한: $missing")
    }

    @Test
    fun `기본 상점 목록의 재질 이름이 전부 있다`() {
        val names = DefaultShops.MINERALS + DefaultShops.CROPS + DefaultShops.LOOT
        val unknown = names.filter { Material.matchMaterial(it) == null }
        assertTrue(unknown.isEmpty(), "없는 재질: $unknown")
        val unobtainableUnknown = DefaultShops.UNOBTAINABLE.filter { Material.matchMaterial(it) == null }
        assertTrue(unobtainableUnknown.isEmpty(), "못 얻는 목록의 오타: $unobtainableUnknown")
    }

    @Test
    fun `경매 분류 배포 파일이 읽힌다`() {
        val y = resource("auction/categories.yml")
        assertEquals(6, y.getKeys(false).size)
        for (id in y.getKeys(false)) {
            assertNotNull(y.getString("$id.icon")?.let { Material.matchMaterial(it) }, "$id 아이콘")
            assertTrue(y.getStringList("$id.match").isNotEmpty(), "$id 패턴")
        }
    }
}
