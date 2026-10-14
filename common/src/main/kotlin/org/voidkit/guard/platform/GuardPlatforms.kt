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

    /**
     * 由加载器入口调用，安装 [GServer] 工厂。
     *
     * 为什么需要它：`GuardBootstrap.assembleHub` 需要造一个 [GServer]，但
     * Bootstrap 是**加载器无关**的（不 import 任何加载器类）。以前它直接
     * `NeoforgeServer(server)`，那就把 NeoForge 漏进业务装配层了 ——
     * 换 Forge/Fabric 时得改这个文件，违反「唯一改写面 = platform 层」。
     *
     * 现在由入口注入工厂：`ForgeServerEntry` 装 `::ForgeServer`，
     * NeoForge 入口装 `::NeoforgeServer`，Bootstrap 对两者无感。
     * 默认留空 → 入口没装就报错（而不是静默用错平台的实现）。
     */
    @Volatile
    private var serverFactory: ((Any) -> GServer)? = null

    /**
     * 由加载器入口调用，安装 [GServer] 工厂。
     *
     * @param factory 入参是 `net.minecraft.server.MinecraftServer`；common 层
     *   不能 import 它（会把 MC 类漏进纯 Kotlin 层），故类型擦成 [Any]，
     *   入口用 `::ForgeServer` 这类函数引用传入即可（编译器在入口侧检查类型）。
     */
    fun installServerFactory(factory: (Any) -> GServer) {
        serverFactory = factory
    }

    /** 由 [org.voidkit.guard.GuardBootstrap] 调用：造本平台的 [GServer]。 */
    fun newServer(minecraftServer: Any): GServer =
        serverFactory?.invoke(minecraftServer)
            ?: error("GServer 工厂未安装（加载器入口没调 GuardPlatforms.installServerFactory）")

    /**
     * 由加载器入口调用，安装 [GPlayer] 工厂。
     *
     * 与 [installServerFactory] 同一个理由：`BanFeature` / `IntegrityFeature`
     * 从 core 的 `VPlayerJoin`/`VPlayerQuit` 拿到 `ServerPlayer` 后要包成
     * [GPlayer]，但业务层**不能** import `platform.neoforge.NeoforgePlayer`
     * —— 那等于把加载器实现漏进业务代码，换加载器就得改业务。
     *
     * 入参同样擦成 [Any]（common 层不 import MC 类），入口传 `::ForgePlayer`。
     */
    @Volatile
    private var playerFactory: ((Any) -> GPlayer)? = null

    fun installPlayerFactory(factory: (Any) -> GPlayer) {
        playerFactory = factory
    }

    /** 由业务层调用：把平台玩家对象包成 [GPlayer]。 */
    fun newPlayer(minecraftPlayer: Any): GPlayer =
        playerFactory?.invoke(minecraftPlayer)
            ?: error("GPlayer 工厂未安装（加载器入口没调 GuardPlatforms.installPlayerFactory）")

    private object EmptyMods : GMods {
        override fun loadedMods(): List<GModInfo> = emptyList()
    }
}
