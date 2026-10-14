package org.voidkit.guard.client.hw

import com.sun.management.OperatingSystemMXBean
import org.voidkit.guard.client.common.EnvInfo
import org.voidkit.guard.client.common.GCrypto
import java.lang.management.ManagementFactory
import java.util.Locale
import java.util.TimeZone

/**
 * 运行环境画像采集（系统版本 + 硬件配置 + 区域信息）。
 *
 * ## 为什么值得采
 *
 * 1. **身份佐证**：同一台机器的 CPU/GPU/内存/时区组合跨会话稳定，
 *    换号不换机的人在这露出马脚（`envHash` 进身份档案）。
 * 2. **异常信号**：软件渲染器（llvmpipe/软渲染）意味着虚拟机/远程桌面/
 *    无显卡环境 —— 正常玩 MC 的玩家几乎不会这样，是强信号。
 * 3. **报告可读**：服主看到的是「Win11 + 13700K + RTX 4070」而不是一串哈希。
 *
 * ## GPU 怎么拿
 *
 * `GL_RENDERER` 需要 GL 上下文，只能在 MC 主线程取 —— 用
 * `Minecraft.submit { }` 把读取任务扔过去等结果（2 秒超时兜底）。
 * 拿不到就留空，不阻塞握手。
 */
object EnvCollector {

    fun collect(): EnvInfo {
        val os = System.getProperty("os.name") ?: ""
        val osVer = System.getProperty("os.version") ?: ""
        val arch = System.getProperty("os.arch") ?: ""
        val runtime = Runtime.getRuntime()
        val args = ManagementFactory.getRuntimeMXBean().inputArguments ?: emptyList<String>()

        return EnvInfo(
            osName = os,
            osVersion = osVer,
            osArch = arch,
            cpu = cpuModel(),
            cpuCores = runtime.availableProcessors(),
            gpu = gpuRenderer(),
            glVendor = gpuVendor(),
            ramMb = totalRamMb(),
            timezone = TimeZone.getDefault().id,
            locale = "${Locale.getDefault().toLanguageTag()}|${System.getProperty("user.country") ?: ""}",
            javaVersion = System.getProperty("java.version") ?: "",
            vmArgsHash = GCrypto.sha256Hex(args.joinToString(" ")),
        )
    }

    private fun cpuModel(): String = when {
        isWindows() -> System.getenv("PROCESSOR_IDENTIFIER") ?: ""
        isLinux() -> runCatching {
            java.io.File("/proc/cpuinfo").readLines()
                .firstOrNull { it.startsWith("model name") }
                ?.substringAfter(":")?.trim()
        }.getOrNull() ?: ""
        isMac() -> runCatching {
            val p = ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string").start()
            p.inputStream.bufferedReader().use { it.readText() }.trim()
        }.getOrNull() ?: ""
        else -> ""
    }

    /**
     * GPU 型号。必须在主线程调（GL 上下文）—— 失败返回空串。
     *
     * 软渲染（llvmpipe / Software Rasterizer）本身就是一个信号：
     * 真玩家几乎都有独显或集显，软渲染多半是虚拟机/远程桌面/无头环境。
     */
    private fun gpuRenderer(): String = onMainThread(1500) { glString(7936) } // GL_RENDERER = 0x1F01
    private fun gpuVendor(): String = onMainThread(1500) { glString(7937) }   // GL_VENDOR  = 0x1F02

    private fun glString(name: Int): String = runCatching {
        val gl = org.lwjgl.opengl.GL11.glGetString(name)
        gl?.take(96) ?: ""
    }.getOrElse { "" }

    private fun totalRamMb(): Long {
        runCatching {
            val beans = ManagementFactory.getPlatformMXBeans(OperatingSystemMXBean::class.java)
            val total = beans.firstOrNull()?.totalPhysicalMemorySize ?: 0L
            if (total > 0) return total / (1024 * 1024)
        }
        return Runtime.getRuntime().maxMemory() / (1024 * 1024)
    }

    private fun isWindows(): Boolean = System.getProperty("os.name")?.lowercase()?.contains("win") == true
    private fun isLinux(): Boolean = System.getProperty("os.name")?.lowercase()?.let { it.contains("nix") || it.contains("linux") } == true
    private fun isMac(): Boolean = System.getProperty("os.name")?.lowercase()?.contains("mac") == true

    /** 把 GL 读取扔到 MC 主线程并等待（客户端专用；失败返回空串）。 */
    private fun onMainThread(timeoutMs: Long, body: () -> String): String = runCatching {
        val mc = net.minecraft.client.Minecraft.getInstance()
        val future = mc.submit(body)
        future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }.getOrElse { "" }
}
