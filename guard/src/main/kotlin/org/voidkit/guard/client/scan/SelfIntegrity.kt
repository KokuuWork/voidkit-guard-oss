package org.voidkit.guard.client.scan


import org.voidkit.guard.common.GCrypto
import org.voidkit.guard.common.SelfIntegrity
import org.voidkit.guard.common.Signal
import org.voidkit.guard.platform.GuardPlatforms
import java.io.File
import java.lang.management.ManagementFactory

/**
 * 客户端 Agent 自身完整性自检 + 运行环境异常检测。
 *
 * ## 自检是什么、不是什么
 *
 * 哈希本模组 jar、和首次运行时记下的基线比。**它防不住有心人** ——
 * 对方改了 jar 也能顺手改基线文件。真正的防线在服务端：哈希锁模式下
 * 我们的 jar 哈希本身就在白名单里，被替换的 Agent 直接匹配失败。
 * 自检的价值是拦住「随手用工具改一改」的那一层，并把痕迹报给服务端。
 *
 * ## 环境异常（severity 2，服务端直接拒）
 *
 * - `-javaagent`：字节码注入的标配入口（各种作弊/内存修改器都靠它）。
 * - `-agentlib:jdwp` / 调试器参数： attach 调试器改内存。
 * 这两项成本极低、漏报极少，命中即拒是合理的。
 */
object SelfIntegrity {

    private const val BASELINE_FILE = "voidkit_guard/self.json"

    /** 首次连接时调用一次：记录基线 + 返回自检结果。 */
    fun check(): SelfIntegrity {
        val jar = ownJar()
        if (jar == null) {
            // 开发环境（classes 目录）或拿不到 codeSource：不判死，照实报
            return SelfIntegrity(ok = true, expected = "", actual = "dev-or-unknown", detail = "无法定位模组文件（开发环境？）")
        }
        val actual = runCatching { GCrypto.sha256Hex(jar.readBytes()) }.getOrNull()
            ?: return SelfIntegrity(true, "", "unreadable", "无法读取模组文件")

        val baseline = readBaseline()
        if (baseline == null) {
            writeBaseline(actual)
            return SelfIntegrity(true, actual, actual, "基线已建立")
        }
        return if (baseline == actual) {
            SelfIntegrity(true, baseline, actual, "")
        } else {
            SelfIntegrity(false, baseline, actual, "模组文件哈希与基线不符（被修改或版本变更）")
        }
    }

    /** 环境异常信号。 */
    fun envSignals(): List<Signal> {
        val out = ArrayList<Signal>()
        val args = ManagementFactory.getRuntimeMXBean().inputArguments ?: emptyList()
        for (a in args) {
            val low = a.lowercase()
            if (low.startsWith("-javaagent")) {
                out.add(Signal("javaagent", "检测到 javaagent：$a", 2))
            }
            if (low.startsWith("-agentlib:jdwp") || low.contains("jdwp")) {
                out.add(Signal("debugger", "检测到调试器参数：$a", 2))
            }
        }
        return out
    }

    // ---------------------------------------------------------------- 内部

    private fun ownJar(): File? = runCatching {
        val loc = SelfIntegrity::class.java.protectionDomain?.codeSource?.location ?: return null
        val f = File(loc.toURI())
        if (f.isFile && f.name.endsWith(".jar")) f else null
    }.getOrNull()

    private fun baselineFile(): File =
        GuardPlatforms.gameDir().resolve(BASELINE_FILE).toFile()

    private fun readBaseline(): String? = runCatching {
        val f = baselineFile()
        if (!f.isFile) return null
        com.google.gson.JsonParser.parseString(f.readText()).asJsonObject.get("sha256")?.asString
    }.getOrNull()

    private fun writeBaseline(hash: String) {
        runCatching {
            val f = baselineFile()
            f.parentFile?.mkdirs()
            f.writeText(com.google.gson.JsonObject().apply { addProperty("sha256", hash) }.toString())
        }
    }
}
