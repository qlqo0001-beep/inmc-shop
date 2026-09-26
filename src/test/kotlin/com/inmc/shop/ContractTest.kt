package com.inmc.shop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 밖에 대해 지키기로 한 약속 — 컴파일 결과와 배포 파일을 직접 읽는다. */
class ContractTest {

    private fun classFiles(): List<File> {
        val root = File("build/classes/kotlin/main")
        assertTrue(root.isDirectory, "컴파일 결과를 찾을 수 없습니다: ${root.absolutePath}")
        return root.walkTopDown().filter { it.isFile && it.extension == "class" }.toList()
    }

    private fun referencing(pkg: String): List<String> {
        val needle = pkg.toByteArray(Charsets.US_ASCII)
        return classFiles().filter { it.readBytes().containsSequence(needle) }.map { it.name }
    }

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    @Test
    fun `Lands 는 리플렉션으로만 — 없는 서버에서도 켜진다`() {
        assertTrue(referencing("me/angeschossen").isEmpty())
    }

    @Test
    fun `PlaceholderAPI 는 훅 파일에서만`() {
        val files = referencing("me/clip/placeholderapi")
        assertTrue(files.all { it.startsWith("PapiHook") || it.startsWith("ShopExpansion") }, "훅 밖에서 PAPI 를 건드린다: $files")
    }

    @Test
    fun `스캔이 실제로 동작한다`() {
        assertTrue(referencing("kr/inmc/core").isNotEmpty())
    }

    private fun descriptor(): String = File("src/main/resources/paper-plugin.yml").readText(Charsets.UTF_8)

    @Test
    fun `디스크립터 — main 이 있고, 필수 의존은 inmc-core 하나, 나머지는 OMIT`() {
        val text = descriptor()
        val main = Regex("""^main:\s*(\S+)""", RegexOption.MULTILINE).find(text)!!.groupValues[1]
        assertTrue(File("build/classes/kotlin/main/" + main.replace('.', '/') + ".class").isFile, main)
        val required = Regex("""^\s+(\S+):\s*\{[^}]*required:\s*true""", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }.toList()
        assertEquals(listOf("inmc-core"), required)
        val deps = Regex("""^\s+(\S+):\s*\{\s*load:\s*(\w+)""", RegexOption.MULTILINE).findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
        assertTrue(deps.filterKeys { it != "inmc-core" }.values.all { it == "OMIT" }, "BEFORE 를 걸면 Paper 가 순환을 보고 간선을 조용히 끊는다: $deps")
        assertTrue("\ncommands:" !in text, "paper-plugin.yml 에는 commands 절이 없다 — Brigadier 로")
        assertTrue("'\${apiVersion}'" in text, "api-version 은 손으로 적지 않는다")
    }
}
