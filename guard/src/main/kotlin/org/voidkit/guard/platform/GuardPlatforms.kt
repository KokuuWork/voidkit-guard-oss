package org.voidkit.guard.platform

import org.voidkit.core.platform.Platforms
import java.nio.file.Path

/**
 * guard 的平台持有者 —— 与 core 的 [Platforms] 分工：
 *
 * - **能白嫖 core 的就白嫖**：游戏目录直接委派 [Platforms.current]（core 1.2.0
 *   已收口 `FMLPaths.GAMEDIR`，guard 不重复造轮子）。
 * - **core 没有的才自己抽象**：mod 清单枚举（[GMods]）是反作弊特有需求，
 *   core 用不到，放 guard 自己的 platform 层。
 *
 * 默认 [GMods] 实现是空壳（返回空清单）—— 真正的实现由各加载器的
 * **mod 入口**装载（NeoForge 入口装 [org.voidkit.guard.platform.neoforge.NeoforgeMods]）。
 * 这样 platform 根包不依赖任何加载器类，Fabric 上不会误加载 NeoForge 实现。
 */
object GuardPlatforms {

    @Volatile
    var mods: GMods = EmptyMods
        private set

    /** 游戏根目录（客户端 = .minecraft，服务端 = 服务端目录）。走 core platform。 */
    fun gameDir(): Path = Platforms.current.gameDir()

    /** 由加载器入口调用，安装 mod 枚举实现。 */
    fun installMods(impl: GMods) {
        mods = impl
    }

    private object EmptyMods : GMods {
        override fun loadedMods(): List<GModInfo> = emptyList()
    }
}
