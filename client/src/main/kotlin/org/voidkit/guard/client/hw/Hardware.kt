package org.voidkit.guard.client.hw

import org.voidkit.guard.common.Fingerprint
import org.voidkit.guard.common.GCrypto
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 硬件指纹采集（客户端，只采机器码）。
 *
 * ## 铁律：原文不出客户端
 *
 * 机器码在**本机**就算成加盐 SHA-256（盐由服务端随握手下发），
 * 上报的只有哈希。服务端拿库也反推不出机器码原文；换服盐不同，
 * 同一份数据不能跨服串用。
 *
 * ## 只采集机器码
 *
 * 网卡 MAC、QQ 登录记录、微信痕迹属于更敏感的个人信息，
 * 一律不采集。机器码（系统/主板唯一标识）是「封禁换号」所需的最少且
 * 不可再少的标识：跟着硬件走，换号无效。
 *
 * 采集**尽力而为**：失败只让 machine 为空，绝不抛异常中断握手 ——
 * 采集不到指纹不该成为进不了服的理由（除非服主开了 requireFingerprint）。
 */
object Hardware {

    fun collect(salt: String): Fingerprint {
        val os = System.getProperty("os.name")?.lowercase() ?: "unknown"
        val platform = when {
            os.contains("win") -> "windows"
            os.contains("mac") -> "macos"
            os.contains("nix") || os.contains("linux") -> "linux"
            else -> os
        }
        return Fingerprint(
            machine = machineId(platform)?.let { hash(salt, it) } ?: "",
            platform = platform,
        )
    }

    private fun hash(salt: String, value: String): String = GCrypto.sha256Hex(salt + "|" + value)

    /**
     * 主板/系统级机器码。
     *
     * 为什么不走 `wmic`：Win11 24H2 起 wmic 已是默认移除的可选功能，
     * 玩家的机器上不能假设它存在。注册表 MachineGuid 是全版本 Windows
     * 都有的「事实标准」机器码。
     */
    private fun machineId(platform: String): String? = when (platform) {
        "windows" -> windowsMachineGuid() ?: fallbackId()
        "linux" -> readFirstLine(File("/etc/machine-id")) ?: readFirstLine(File("/var/lib/dbus/machine-id")) ?: fallbackId()
        "macos" -> runCommand(listOf("ioreg", "-d2", "-c", "IOPlatformExpertDevice"), 3000)
            ?.lineSequence()
            ?.firstOrNull { it.contains("IOPlatformUUID") }
            ?.substringAfter("\"IOPlatformUUID\" = \"")
            ?.substringBefore("\"")
            ?: fallbackId()
        else -> fallbackId()
    }

    private fun windowsMachineGuid(): String? {
        val out = runCommand(
            listOf("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid"),
            4000,
        ) ?: return null
        // 输出形如：    MachineGuid    REG_SZ    xxxxxxxx-xxxx-...
        return out.lineSequence()
            .firstOrNull { it.contains("MachineGuid") && it.contains("REG_SZ") }
            ?.substringAfter("REG_SZ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    /** 兜底：主机名 + 用户名 + CPU 数。换机器大概率变，但比没有强。 */
    private fun fallbackId(): String =
        listOfNotNull(
            System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME"),
            System.getProperty("user.name"),
            Runtime.getRuntime().availableProcessors().toString(),
        ).joinToString("|")

    // ---------------------------------------------------------------- 工具

    private fun readFirstLine(f: File): String? = runCatching { f.readLines().firstOrNull()?.trim() }
        .getOrNull()?.takeIf { it.isNotBlank() }

    /** 跑一条命令拿输出。超时强杀，绝不阻塞握手线程。 */
    private fun runCommand(cmd: List<String>, timeoutMs: Long): String? = runCatching {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (p.isAlive) {
            p.destroyForcibly()
            return null
        }
        out
    }.getOrNull()
}
