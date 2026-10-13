package org.voidkit.guard.platform

import java.nio.file.Path

/**
 * guard 的平台持有者 —— **零 core 依赖**（客户端不装 core，这一层必须自洽）。
 *
 * ## 为什么不像以前那样委派 core
 *
 * 以前 `gameDir()` 直接委派 core 的 `Platforms.current.gameDir()`。但那是
 * **服务端专属**的套件基础设施 —— 客户端不装 core，托管不了。所以 guard
 * 自己的平台层自给自足：游戏目录、mod 枚举都由加载器入口注入，不碰 core。
 *
 * ## 注入式设计
 *
 * 默认 [GMods] 是空壳、目录提供者是「当前目录」—— 真正的实现由各加载器的
 * **mod 入口**装载。这样 platform 根包不依赖任何加载器类（Fabric 上不会
 * 误加载 NeoForge 实现），也不依赖 core。
 */
object GuardPlatforms {

    @Volatile
    var mods: GMods = EmptyMods
        private set

    /** 加载器注入的游戏目录提供者（入口装 `FMLPaths.GAMEDIR` 之类）。 */
    @Volatile
    private var dirProvider: (() -> Path)? = null

    /** 游戏根目录（客户端 = .minecraft，服务端 = 服务端目录）。 */
    fun gameDir(): Path = dirProvider?.invoke() ?: Path.of(".")

    /** 由加载器入口调用，安装 mod 枚举实现。 */
    fun installMods(impl: GMods) {
        mods = impl
    }

    /** 由加载器入口调用，安装游戏目录提供者。 */
    fun installGameDir(provider: () -> Path) {
        dirProvider = provider
    }

    private object EmptyMods : GMods {
        override fun loadedMods(): List<GModInfo> = emptyList()
    }
}
