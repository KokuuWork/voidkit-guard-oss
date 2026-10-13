package org.voidkit.guard.platform

/**
 * 一个已加载的 mod（跨加载器统一视图）。
 *
 * @param filePath mod 文件的绝对路径；null = 拿不到（开发环境/内存加载）。
 *   哈希锁只对能定位到文件的 mod 有效，null 的会在报告里留空哈希。
 */
data class GModInfo(
    val id: String,
    val version: String,
    val filePath: String?,
)

/**
 * mod 清单枚举接口（**加载器相关**，与 core.platform 同一思路）。
 *
 * 这是客户端 Agent 最核心的原料：哈希锁比的就是这份清单。三个加载器
 * 枚举方式完全不同——
 *
 * | 加载器 | 实现 |
 * |---|---|
 * | NeoForge | `ModList.get().mods` |
 * | Fabric | `FabricLoader.getInstance().getAllMods()` |
 * | Forge | `ModList.get().getMods()`（近亲，包名不同） |
 *
 * 所以收口成接口，[GuardPlatforms.mods] 持有当前实现。换加载器 =
 * 加一个实现类 + 入口里装一下，业务代码零改动。
 */
interface GMods {
    fun loadedMods(): List<GModInfo>
}
