package org.voidkit.guard

import com.mojang.brigadier.CommandDispatcher
import com.mojang.logging.LogUtils
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.MinecraftServer
import org.slf4j.Logger
import org.voidkit.core.api.ServerLifecycleListener
import org.voidkit.core.core.PluginRegistry
import org.voidkit.core.platform.Platforms
import org.voidkit.core.util.ConfigFiles
import org.voidkit.guard.platform.GChannel
import org.voidkit.guard.platform.GuardPlatforms
import org.voidkit.guard.server.AuditFeature
import org.voidkit.guard.server.BanFeature
import org.voidkit.guard.server.CombatFeature
import org.voidkit.guard.server.DeepScanFeature
import org.voidkit.guard.server.GuardDb
import org.voidkit.guard.server.GuardHub
import org.voidkit.guard.server.GuardSessions
import org.voidkit.guard.server.GuardStore
import org.voidkit.guard.server.IntegrityFeature
import org.voidkit.guard.server.ScreenshotFeature
import org.voidkit.guard.server.ServerKeyPair
import org.voidkit.guard.server.WhitelistStore
import org.voidkit.guard.server.XrayStatisticsFeature

/**
 * guard 的加载器无关装配中心。
 *
 * ## 多加载器分工（2026-10-13 对齐 core 1.2.0 platform 层）
 *
 * ```
 * 加载器入口（每个加载器一个文件，@Mod/@ModInitializer 注解不同）
 *   └── NeoforgeModEntry.kt        ← net.neoforged.* 唯一允许出现的地方之一
 *        ├── GuardPlatforms.installMods(NeoforgeMods())   平台适配装载
 *        ├── GuardBootstrap.bootstrap()                   本文件：模块注册 + 生命周期
 *        └── 载荷注册（playBidirectional）                 网络协议，NeoForge API
 * ```
 *
 * - 本文件**不 import 任何加载器类**（`net.neoforged.*` 等一概没有）——
 *   生命周期走 core 的 `Platforms.current.registerLifecycle`，游戏目录走
 *   `Platforms.current.gameDir()`，事件挂载走 core 的 PluginRegistry。
 * - 换加载器 = 新写一个入口文件 + 一个 `XxxMods` 实现，本文件零改动。
 * - 事件监听全部走 core 的 VEvent 回调（onPlayerJoin/onPlayerQuit），业务代码零 `net.neoforged` import。
 * - 服务端游戏事件桥接（方块破坏/攻击/tick 采样）由加载器入口注册，也不在本文件。
 */
object GuardBootstrap {

    const val MODID = "voidkit_guard"
    val LOG: Logger = LogUtils.getLogger()

    // ---- 功能模块实例（入口构造期创建，hub 在 ServerAboutToStart 注入） ----
    val integrity = IntegrityFeature()
    val bans = BanFeature()
    val screenshots = ScreenshotFeature()
    val audit = AuditFeature()
    val deep = DeepScanFeature()
    val xrayStats = XrayStatisticsFeature()
    val combat = CombatFeature()

    /** 服务端共享上下文；null = 尚未装配（构造期/已关服）。 */
    @Volatile
    var hub: GuardHub? = null
        private set

    /** 加载器通道实现（入口注入；客户端 Agent 也用它发 C→S）。 */
    @Volatile
    var channel: GChannel? = null
        private set

    /**
     * 装配：注册模块 + 挂生命周期。
     *
     * 由加载器入口在 mod 构造期调用（纯内存操作 + 事件监听注册，零 IO）。
     *
     * @param channel 加载器实现的通道（NeoForge 入口传 [NeoforgeChannel]）。
     *   服务端与客户端共用同一个实例：服务端用它发 S→C，客户端用它发 C→S。
     *   本文件只认 [GChannel] 抽象，不 import 加载器类。
     */
    fun bootstrap(channel: GChannel) {
        this.channel = channel
        PluginRegistry.register(integrity, MODID)
        PluginRegistry.register(bans, MODID)
        PluginRegistry.register(screenshots, MODID)
        PluginRegistry.register(audit, MODID)
        PluginRegistry.register(deep, MODID)
        PluginRegistry.register(xrayStats, MODID)
        PluginRegistry.register(combat, MODID)

        // 关服收尾走 core 的平台无关生命周期抽象（不直接抱 NeoForge 事件总线）
        Platforms.current.registerLifecycle(GuardLifecycle)
    }

    /**
     * 装配服务端上下文（幂等）。
     *
     * 时机：[org.voidkit.core.api.Feature.onServerAboutToStart] ——
     * 内核此时已完成 ConfigFiles.init / VoidkitServer.attach，
     * 且还没通知任何模块，所以七个模块无论谁先拿到 hub 都是全的。
     */
    fun assembleHub(server: MinecraftServer): GuardHub {
        hub?.let { return it }
        val g = GuardHub()
        // GServer 由加载器入口注入的工厂造（NeoForge/Forge/Fabric 各自一份）——
        // 本文件不 import 任何加载器类，换加载器零改动。
        g.server = GuardPlatforms.newServer(server)
        g.channel = channel ?: error("通道未注入（入口没调 bootstrap(channel)？）")
        g.config = GuardStore.load()
        g.db = GuardDb(ConfigFiles.resolve(g.config.databaseFile)).also { it.open() }
        g.keys = ServerKeyPair(ConfigFiles.resolve(g.config.serverKeyFile)).also { it.load() }
        g.whitelist = WhitelistStore(ConfigFiles.resolve("guard/whitelist.json")).also { it.load() }

        g.bans = bans.also { it.hub = g }
        g.screenshots = screenshots.also { it.hub = g }
        g.deep = deep.also { it.hub = g }
        g.combat = combat.also { it.hub = g }
        g.xrayStats = xrayStats.also { it.hub = g }
        audit.hub = g
        integrity.hub = g

        g.sessions = GuardSessions(g)
        g.channel.onClientMessage = { player, msgId, body ->
            g.server.execute { g.sessions.onMessage(player, msgId, body) }
        }
        g.started = true
        hub = g

        integrity.publishSnapshot()
        // 归属地库启动自检：拿 8.8.8.8 打一枪，库坏了这里立刻知道（而不是
        // 等服主某天 /gcheck 时发现什么都不显示）。失败只降级不影响主流程。
        runCatching {
            val sample = org.voidkit.guard.server.IpRegion.lookup("8.8.8.8")
            if (sample.isBlank()) {
                LOG.warn("VoidkitGuard 归属地库初始化失败（IP 归属地将不显示，其余功能不受影响）")
            } else {
                LOG.info("VoidkitGuard 归属地库就绪（样例 8.8.8.8 → {}）", sample)
            }
        }
        LOG.info(
            "VoidkitGuard 就绪：模式={}｜封禁 {} 条｜白名单 {} 条（{} 待审）",
            g.config.integrityMode, g.bans.count(), g.whitelist.entries.size, g.whitelist.pendingCount(),
        )
        return g
    }

    /** 关服收尾：落盘中的写队列、擦密钥、清会话。 */
    fun shutdown() {
        val g = hub ?: return
        g.sessions.shutdown()
        runCatching { g.db.close() }
        g.keys.wipe()
        hub = null
        LOG.info("VoidkitGuard 已关闭")
    }

    /**
     * 生命周期回调（core 的 ServerLifecycleListener）。
     *
     * guard 只关心「关服」—— 启动/命令/tick 全走 core 的 PluginRegistry
     * （Feature 自己的回调），不在这里重复接一遍。
     */
    private object GuardLifecycle : ServerLifecycleListener {
        override fun onServerAboutToStart(server: MinecraftServer) {}
        override fun onRegisterCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {}
        override fun onServerStarted(server: MinecraftServer) {}
        override fun onServerStopping(server: MinecraftServer) = shutdown()
        override fun onServerTick(server: MinecraftServer) {}
    }
}
