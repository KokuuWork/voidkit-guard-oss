package org.voidkit.guard

import com.mojang.logging.LogUtils
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import org.slf4j.Logger
import org.voidkit.core.core.PluginRegistry
import org.voidkit.core.util.ConfigFiles
import org.voidkit.guard.client.GuardClient
import org.voidkit.guard.platform.neoforge.GuardWirePayload
import org.voidkit.guard.platform.neoforge.NeoforgeChannel
import org.voidkit.guard.platform.neoforge.NeoforgePlayer
import org.voidkit.guard.platform.neoforge.NeoforgeServer
import org.voidkit.guard.server.AuditFeature
import org.voidkit.guard.server.BanFeature
import org.voidkit.guard.server.DeepScanFeature
import org.voidkit.guard.server.GuardDb
import org.voidkit.guard.server.GuardHub
import org.voidkit.guard.server.GuardSessions
import org.voidkit.guard.server.GuardStore
import org.voidkit.guard.server.IntegrityFeature
import org.voidkit.guard.server.ScreenshotFeature
import org.voidkit.guard.server.ServerKeyPair
import org.voidkit.guard.server.WhitelistStore
import net.minecraft.server.MinecraftServer

/**
 * VoidkitGuard —— 反作弊组件（客户端 Agent + 服务端校验）。
 *
 * ## 结构（与套件同构）
 *
 * ```
 * guard/          本 jar（side=BOTH，客户端服务端同一份）
 *   common/       协议、加密、内存机密保护（纯 Kotlin，零 MC import）
 *   platform/     抽象接口 + neoforge 实现（唯一碰 MC/加载器的一层）
 *   server/       五个 Feature：完整性/封禁/截屏/审计/深度扫描
 *   client/       Agent：握手应答、采集、截屏、深度扫描
 * ```
 *
 * 只依赖 `voidkit_core`（组合构建自本地 voidkit 仓库），零跨插件 import。
 * 将来 core 长出 platform 层（issue #1）时，替换 `platform/neoforge` 即可，
 * 业务代码零改动。
 *
 * ## 双端载荷
 *
 * 全库只注册一个通道 `voidkit_guard:wire`（optional）：
 * - 服务端收 `playToServer` → 切主线程 → [GuardSessions.onMessage]
 * - 客户端收 `playToClient` → [GuardClient.handleIncoming]
 * optional = 没装模组的客户端能正常连进来，然后被握超时踢掉并看到
 * 统一话术 —— 比协议层硬断开更可控（话术是我们自己的）。
 */
@Mod(VoidkitGuard.MODID)
class VoidkitGuard(bus: IEventBus, container: ModContainer) {

    init {
        // 1. 载荷注册（全库单通道，双向）。
        //
        // 🚨 同一个 channel id 在新载荷系统里只能注册一次 —— playToServer +
        //    playToClient 分开注册会直接 "already registered" 崩 mod 加载。
        //    所以用 playBidirectional，靠 ctx.flow() 区分方向：
        //    - CLIENTBOUND = 服务端发来的（只可能在客户端进程里执行）
        //    - SERVERBOUND = 客户端发来的（只可能在服务端进程里执行）
        //    默认 HandlerThread.MAIN：处理器已在主线程跑，服务端这里再包一层
        //    execute 是幂等防御（断线瞬间 isSameThread 判定失效时兜底）。
        bus.addListener { event: RegisterPayloadHandlersEvent ->
            event.registrar(MODID).optional().versioned("1")
                .playBidirectional(GuardWirePayload.TYPE, GuardWirePayload.STREAM_CODEC) { payload, ctx ->
                    if (ctx.flow() == net.minecraft.network.protocol.PacketFlow.CLIENTBOUND) {
                        // 服务端 → 客户端（客户端进程）
                        GuardClient.handleIncoming(payload.wire)
                    } else {
                        // 客户端 → 服务端（服务端进程）：切主线程再进会话状态机
                        val player = ctx.player() as? ServerPlayer ?: return@playBidirectional
                        val h = hub ?: return@playBidirectional
                        h.server.execute {
                            h.sessions.onMessage(NeoforgePlayer(player), payload.wire.id, payload.wire.body)
                        }
                    }
                }
        }

        // 2. 功能模块注册（内核的插件注册表，构造期零 IO）
        PluginRegistry.register(integrity, MODID)
        PluginRegistry.register(bans, MODID)
        PluginRegistry.register(screenshots, MODID)
        PluginRegistry.register(audit, MODID)
        PluginRegistry.register(deep, MODID)

        // 3. 关服收尾：落盘中的写队列、擦密钥、清会话
        NeoForge.EVENT_BUS.addListener { _: ServerStoppingEvent ->
            val h = hub
            if (h != null) {
                h.sessions.shutdown()
                runCatching { h.db.close() }
                h.keys.wipe()
                hub = null
                LOG.info("VoidkitGuard 已关闭")
            }
        }

        // 4. 客户端不需要额外初始化（playToClient 处理器已注册；
        //    专用服务端上 client 包压根不会被执行）
        if (FMLEnvironment.dist.isClient) {
            LOG.debug("VoidkitGuard 客户端 Agent 就绪")
        }
    }

    companion object {
        const val MODID = "voidkit_guard"
        val LOG: Logger = LogUtils.getLogger()

        // ---- 功能模块实例（@Mod 构造期创建，hub 在启动阶段注入） ----
        val integrity = IntegrityFeature()
        val bans = BanFeature()
        val screenshots = ScreenshotFeature()
        val audit = AuditFeature()
        val deep = DeepScanFeature()

        /** 服务端共享上下文；null = 尚未装配（构造期/已关服）。 */
        @Volatile
        var hub: GuardHub? = null
            private set

        /**
         * 装配服务端上下文（幂等）。
         *
         * 时机：[org.voidkit.core.api.Feature.onServerAboutToStart] ——
         * 内核此时已完成 ConfigFiles.init / VoidkitServer.attach，
         * 且还没通知任何模块，所以五个模块无论谁先拿到 hub 都是全的。
         */
        fun assembleHub(server: MinecraftServer): GuardHub {
            hub?.let { return it }
            val g = GuardHub()
            g.server = NeoforgeServer(server)
            g.channel = NeoforgeChannel()
            g.config = GuardStore.load()
            g.db = GuardDb(ConfigFiles.resolve(g.config.databaseFile)).also { it.open() }
            g.keys = ServerKeyPair(ConfigFiles.resolve(g.config.serverKeyFile)).also { it.load() }
            g.whitelist = WhitelistStore(ConfigFiles.resolve("guard/whitelist.json")).also { it.load() }

            g.bans = bans.also { it.hub = g }
            g.screenshots = screenshots.also { it.hub = g }
            g.deep = deep.also { it.hub = g }
            audit.hub = g
            integrity.hub = g

            g.sessions = GuardSessions(g)
            g.channel.onClientMessage = { player, msgId, body ->
                g.server.execute { g.sessions.onMessage(player, msgId, body) }
            }
            g.started = true
            hub = g

            integrity.publishSnapshot()
            LOG.info(
                "VoidkitGuard 就绪：模式={}｜封禁 {} 条｜白名单 {} 条（{} 待审）",
                g.config.integrityMode, g.bans.count(), g.whitelist.entries.size, g.whitelist.pendingCount(),
            )
            return g
        }
    }
}
