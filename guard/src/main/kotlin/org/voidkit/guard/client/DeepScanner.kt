package org.voidkit.guard.client

import net.neoforged.fml.loading.FMLPaths
import org.voidkit.guard.common.DeepScanReq
import org.voidkit.guard.common.DeepScanResult
import org.voidkit.guard.common.FolderEntry
import org.voidkit.guard.common.IpEntry
import org.voidkit.guard.common.ProcEntry
import org.voidkit.guard.common.Signal
import java.io.File
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * 深度扫描（服务端可控，随时下发）。
 *
 * ## 采什么
 *
 * 1. **本机 IP 列表**：全部网络接口的 IP（标出虚拟网卡）。
 *    服务端侧的连接 IP 只有一个，而本机列表能看出 VPN/多网卡/代理 的痕迹，
 *    也补充封禁标识。
 * 2. **文件夹清单**：游戏目录各处的**文件名 + 大小 + 时间**（不读内容）。
 *    最典型的用法：`mods/` 里躺着一个没被加载的作弊 jar（改了 .disabled
 *    或放在子目录），或根目录里一个来路不明的 `.jar/.exe`。
 * 3. **心跳 RTT**：从收到请求到回包的耗时，异常值进报告。
 *
 * ## 边界（刻意保守）
 *
 * - 只列**直接子级**，不递归 —— 清单已经够发现「藏起来的作弊文件」，
 *   递归既慢又把玩家目录结构整个端上来，没必要。
 * - 不读任何文件内容。文件名 + 时间戳就是足够的信号。
 */
object DeepScanner {

    fun scan(req: DeepScanReq, signatures: List<String>): DeepScanResult {
        val t0 = System.currentTimeMillis()
        val ips = if (req.scanIp) collectIps() else emptyList()
        val folders = listFolders(req.folderTargets)
        val processes = if (req.scanProcesses) collectProcesses(signatures) else emptyList()
        val signals = suspiciousFiles(folders, signatures) + suspiciousProcesses(processes)
        val rtt = System.currentTimeMillis() - t0
        return DeepScanResult(
            reqId = req.reqId,
            ips = ips,
            folders = folders,
            rttMs = rtt,
            signals = signals,
            processes = processes,
        )
    }

    // ---------------------------------------------------------------- IP

    private fun collectIps(): List<IpEntry> = runCatching {
        val out = ArrayList<IpEntry>()
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        for (ni in ifaces) {
            val addrs = ni.inetAddresses ?: continue
            for (addr in addrs) {
                if (addr.isLoopbackAddress) continue
                val host = addr.hostAddress ?: continue
                val virtual = ni.isVirtual || VIRTUAL_HINTS.any { ni.name.lowercase().contains(it) }
                out.add(IpEntry(iface = ni.name, ip = host.substringBefore('%'), virtual = virtual))
            }
        }
        out.distinctBy { it.ip }.take(64)
    }.getOrElse { emptyList() }

    private val VIRTUAL_HINTS = listOf(
        "lo", "docker", "veth", "vmnet", "vmware", "virtual", "vbox", "tun", "tap",
        "ppp", "bluetooth", "awdl", "llw", "zerotier", "tailscale", "wireguard",
    )

    private fun isWindows(): Boolean =
        System.getProperty("os.name")?.lowercase()?.contains("win") == true

    /** 跑一条命令拿输出。超时强杀，绝不阻塞调用线程。 */
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

    // ---------------------------------------------------------------- 进程/窗口

    /**
     * 外挂进程检测：进程名 + 窗口标题黑名单。
     *
     * 这是**客户端侧对技术玩家最直接的一剑**：内存修改器（CE/x64dbg）、
     * 逆向工具（IDA/dnSpy）、外挂客户端本体，全都有可识别进程名或窗口标题。
     * 零依赖：Windows 走 PowerShell Get-Process（拿得到 MainWindowTitle），
     * Linux/macOS 走 ps。只报名字和标题，不碰别的进程内存。
     *
     * ⚠️ 已知边界：对手 Kill 掉工具窗口、改进程名就能绕。所以它是**加分项**
     * 不是生死线 —— 真正的生死线在服务端行为遥测（ roadmap 第 2 项）。
     */
    private fun collectProcesses(signatures: List<String>): List<ProcEntry> {
        val out = ArrayList<ProcEntry>()
        if (isWindows()) {
            // JSON 输出，字段稳定；-NoProfile 防用户 profile 脚本干扰
            val json = runCommand(
                listOf(
                    "powershell", "-NoProfile", "-Command",
                    "Get-Process | Where-Object {\$_.MainWindowTitle -ne ''} | " +
                        "Select-Object ProcessName,MainWindowTitle | ConvertTo-Json -Compress",
                ),
                6000,
            )
            if (json != null) {
                val parsed = runCatching {
                    com.google.gson.JsonParser.parseString(json.trim())
                }.getOrNull()
                when (parsed) {
                    is com.google.gson.JsonArray -> parsed.forEach { addProc(out, it, signatures) }
                    is com.google.gson.JsonObject -> addProc(out, parsed, signatures)
                }
            }
        } else {
            // Linux/macOS：只拿进程名（ps 拿窗口标题不可靠，标题检测留给 Windows）
            val text = runCommand(listOf("ps", "-eo", "comm="), 4000) ?: return out
            for (line in text.lineSequence()) {
                val name = line.trim()
                if (name.isBlank()) continue
                out.add(ProcEntry(name = name, title = "", suspicious = matchAny(name, "", signatures)))
            }
        }
        return out.distinctBy { it.name + "|" + it.title }.take(300)
    }

    private fun addProc(out: MutableList<ProcEntry>, el: com.google.gson.JsonElement, signatures: List<String>) {
        val o = el.asJsonObject
        val name = o.get("ProcessName")?.asString ?: return
        val title = o.get("MainWindowTitle")?.asString ?: ""
        out.add(ProcEntry(name = name, title = title, suspicious = matchAny(name, title, signatures)))
    }

    private fun matchAny(name: String, title: String, signatures: List<String>): Boolean {
        val hay = (name + " " + title).lowercase()
        if (KNOWN_PROCESSES.any { hay.contains(it) }) return true
        return signatures.any { it.isNotBlank() && hay.contains(it.lowercase()) }
    }

    /** 常见内存修改/逆向/辅助工具（进程名或窗口标题子串，全小写）。 */
    private val KNOWN_PROCESSES = listOf(
        "cheatengine", "cheat engine", "x64dbg", "x32dbg", "ollydbg", "ida64", "ida32",
        "dnspy", "ilspy", "processhacker", "process hacker", "systemexplorer",
        "windbg", "reclass", "hxd", "010editor", "autoclicker", "auto clicker",
        "wurst", "impact", "future", "liquidbounce", "meteor", "baritone", "aristois",
        "openbullet", "sniffer", "fiddler", "httpdebugger", "ksdumper", "pchunter",
        "everything", // 可选争议项：全盘搜索工具本身无罪，常配合找存档/配置文件
    )

    private fun suspiciousProcesses(processes: List<ProcEntry>): List<Signal> =
        processes.filter { it.suspicious }.map {
            Signal("suspicious_process", "发现可疑进程/窗口：${it.name}${if (it.title.isNotBlank()) "（${it.title.take(40)}）" else ""}", 2)
        }

    // ---------------------------------------------------------------- 文件夹

    private fun listFolders(targets: List<String>): List<FolderEntry> {
        val root = FMLPaths.GAMEDIR.get().toFile()
        val out = ArrayList<FolderEntry>()
        for (target in targets) {
            val dir = if (target.isBlank()) root else File(root, target)
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            var n = 0
            for (c in children) {
                if (n >= capFor(target)) break
                // 根目录只挑「值得看的」：jar/exe/脚本 + 常见工具目录，别把 saves/ 整个端上来
                if (target.isBlank() && c.isDirectory) continue
                if (target.isBlank() && c.isFile && !interestingRootFile(c.name)) continue
                out.add(
                    FolderEntry(
                        dir = target.ifBlank { "." },
                        name = c.name,
                        isDir = c.isDirectory,
                        size = if (c.isFile) c.length() else 0,
                        mtime = c.lastModified(),
                        suspicious = false, // 由服务端/下方统一判定
                    )
                )
                n++
            }
        }
        return out
    }

    private fun capFor(target: String): Int = when (target) {
        "mods" -> 400
        "" -> 120
        else -> 150
    }

    private fun interestingRootFile(name: String): Boolean {
        val low = name.lowercase()
        return low.endsWith(".jar") || low.endsWith(".exe") || low.endsWith(".bat") ||
            low.endsWith(".cmd") || low.endsWith(".ps1") || low.endsWith(".vbs") ||
            low.endsWith(".lnk") || low.endsWith(".dll")
    }

    // ---------------------------------------------------------------- 可疑文件

    /**
     * 文件名撞特征库 → severity 2 信号。
     *
     * 重点场景：`mods/` 里一个**没加载**的作弊 jar。它不在 mod 清单里
     * （智能识别看不见），但静静躺在文件夹里 —— 深度扫描专治这个。
     */
    private fun suspiciousFiles(folders: List<FolderEntry>, signatures: List<String>): List<Signal> {
        if (signatures.isEmpty()) return emptyList()
        val out = ArrayList<Signal>()
        for (f in folders) {
            val low = f.name.lowercase()
            for (sig in signatures) {
                if (sig.isBlank()) continue
                if (low.contains(sig.lowercase())) {
                    out.add(Signal("suspicious_file", "发现可疑文件 ${f.dir}/${f.name}", 2))
                    break
                }
            }
        }
        return out
    }
}
