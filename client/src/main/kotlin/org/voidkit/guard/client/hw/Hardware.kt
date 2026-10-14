package org.voidkit.guard.client.hw

import org.voidkit.guard.client.common.Fingerprint
import org.voidkit.guard.client.common.GCrypto
import java.io.File
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * 硬件指纹采集（客户端）。
 *
 * ## 铁律：原文不出客户端
 *
 * 所有值在**本机**就算成加盐 SHA-256（盐由服务端随握手下发），
 * 上报的只有哈希。服务端拿库也反推不出机器码/QQ 号；换服盐不同，
 * 同一份数据不能跨服串用。
 *
 * ## 采集项与落地方式
 *
 * | 项 | Windows | Linux | macOS |
 * |---|---|---|---|
 * | 机器码 | 注册表 MachineGuid | /etc/machine-id | IOPlatformUUID |
 * | 网卡 | NetworkInterface（过滤虚拟网卡） | 同左 | 同左 |
 * | QQ 登录记录 | Documents/Tencent Files 账号目录 | — | — |
 * | 微信痕迹 | 注册表安装路径 + Documents/WeChat Files | — | — |
 *
 * 全部**尽力而为**：任何一步失败只让对应字段为空，绝不抛异常中断握手 ——
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
            macs = macs().map { hash(salt, it) }.sorted(),
            qq = qqAccounts().map { hash(salt, it) },
            wechat = wechatTraces().map { hash(salt, it) },
            platform = platform,
        )
    }

    private fun hash(salt: String, value: String): String = GCrypto.sha256Hex(salt + "|" + value)

    // ---------------------------------------------------------------- 机器码

    /**
     * 主板/系统级机器码。
     *
     * 为什么不走 `wmic`：Win11 24H2 起 wmic 已是默认移除的可选功能，
     * 玩家的机器上不能假设它存在。注册表 MachineGuid 是全版本 Windows
     * 都有的「事实标准」机器码（国内软件这么多年都这么干）。
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

    // ---------------------------------------------------------------- 网卡

    /**
     * 物理网卡 MAC（十六进制串）。
     *
     * 过滤规则：排除 down/loopback/虚拟网卡（docker、vmware、vmnet、veth、
     * tun/tap、ppp、bluetooth）。虚拟网卡跟着软件走，今天装个 Docker
     * 就多一个 MAC，会把指纹搞得不稳定 —— 封禁匹配要的是稳定标识。
     */
    fun macs(): List<String> = runCatching {
        val out = ArrayList<String>()
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        for (ni in ifaces) {
            if (ni.isLoopback || !ni.isUp) continue
            val name = ni.name.lowercase()
            val virtual = VIRTUAL_HINTS.any { name.contains(it) } || ni.isVirtual
            if (virtual) continue
            val hw = ni.hardwareAddress ?: continue
            if (hw.size != 6) continue
            // 全 0 / 全相同（部分虚拟网卡的把戏）跳过
            if (hw.all { it == hw[0] }) continue
            out.add(hw.joinToString(":") { String.format("%02X", it) })
        }
        out.distinct()
    }.getOrElse { emptyList() }

    private val VIRTUAL_HINTS = listOf(
        "lo", "docker", "veth", "vmnet", "vmware", "virtual", "vbox", "tun", "tap",
        "ppp", "bluetooth", "awdl", "llw", "bridge", "zerotier", "tailscale", "wireguard",
    )

    // ---------------------------------------------------------------- QQ

    /**
     * 本机 QQ 登录记录（账号数字串，随后哈希）。
     *
     * QQ NT 时代每个登录过的账号在 `Documents/Tencent Files/` 下留一个
     * 以 QQ 号命名的目录 —— 这是最可靠的落地方式。老版 QQ 的注册表项
     * 各家系统残缺不齐，不作为主路径。
     */
    fun qqAccounts(): List<String> {
        val home = System.getProperty("user.home") ?: return emptyList()
        val candidates = listOf(
            File(home, "Documents/Tencent Files"),
            File(home, "Documents/Tencent Files (x86)"),
            File(home, "Tencent Files"),
        )
        val out = LinkedHashSet<String>()
        for (dir in candidates) {
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (c in children) {
                if (!c.isDirectory) continue
                val name = c.name
                // QQ 号 5-12 位数字；跳过 "All 用户" 这类非账号目录
                if (name.length in 5..12 && name.all { it.isDigit() }) out.add(name)
            }
        }
        return out.toList()
    }

    // ---------------------------------------------------------------- 微信

    /**
     * 本机微信痕迹（尽力而为）。
     *
     * 微信不像 QQ 那样把账号落成明文目录，能稳定拿到的只有安装路径 /
     * 文件目录这类「装过微信」的证据。对封禁匹配是弱标识，但有比没有强
     * （换 QQ 小号的人常常忘了自己这台机器登录过微信）。
     */
    fun wechatTraces(): List<String> {
        val out = LinkedHashSet<String>()
        if (isWindows()) {
            val reg = runCommand(
                listOf("reg", "query", "HKCU\\Software\\Tencent\\WeChat", "/s"),
                4000,
            )
            if (reg != null) {
                for (line in reg.lineSequence()) {
                    val v = line.substringAfter("REG_SZ", "").trim()
                    if (v.isNotBlank() && (v.contains("WeChat") || v.contains("Tencent"))) out.add(v)
                }
            }
        }
        val home = System.getProperty("user.home") ?: return out.toList()
        for (rel in listOf("Documents/WeChat Files", "Documents/xwechat_files")) {
            val f = File(home, rel)
            if (f.isDirectory) out.add(rel)
        }
        return out.toList()
    }

    // ---------------------------------------------------------------- 工具

    private fun isWindows(): Boolean =
        System.getProperty("os.name")?.lowercase()?.contains("win") == true

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
