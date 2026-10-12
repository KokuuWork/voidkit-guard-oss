package org.voidkit.guard.platform.neoforge

import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.network.protocol.PacketFlow
import org.voidkit.guard.GuardBootstrap
import org.voidkit.guard.platform.GuardPlatforms
import org.voidkit.guard.client.GuardClient

/**
 * NeoForge 加载器入口 —— guard 的 `net.neoforged.*` 聚集地。
 *
 * ## 这个文件为什么在 platform/neoforge 下
 *
 * `@Mod` 注解、`IEventBus`、`RegisterPayloadHandlersEvent` 全是加载器 API。
 * 按 core 1.2.0 立下的规矩（插件只依赖 core.api，加载器差异进 platform 层），
 * 入口类和加载器实现放一起，换加载器时新增 `platform/fabric/FabricModEntry.kt`
 * 即可，[GuardBootstrap] 与全部业务代码零改动。
 *
 * ## 启动序列
 *
 * 1. `GuardPlatforms.installMods(NeoforgeMods())` —— 装载 mod 枚举实现
 * 2. `GuardBootstrap.bootstrap()` —— 注册 5 个 Feature + 生命周期（加载器无关）
 * 3. 载荷注册 —— 全库单通道 `voidkit_guard:wire`，**playBidirectional**
 *    （同一个 channel id 只能注册一次，分方向注册会 "already registered" 崩加载），
 *    靠 `ctx.flow()` 区分方向。optional = 没装模组的客户端能连进来，
 *    然后被握超时踢掉并看到统一话术。
 * 4. 客户端无需额外初始化（playToClient 处理器已注册；专用服务端上
 *    client 包压根不会被执行）
 */
@Mod(GuardBootstrap.MODID)
class NeoforgeModEntry(bus: IEventBus, container: ModContainer) {

    init {
        // 1. 平台适配装载
        GuardPlatforms.installMods(NeoforgeMods())

        // 2. 模块 + 生命周期（加载器无关装配；通道实现由本入口注入）
        GuardBootstrap.bootstrap(NeoforgeChannel())

        // 3. 网络载荷（NeoForge 专属 API）
        bus.addListener { event: RegisterPayloadHandlersEvent ->
            event.registrar(GuardBootstrap.MODID).optional().versioned("1")
                .playBidirectional(GuardWirePayload.TYPE, GuardWirePayload.STREAM_CODEC) { payload, ctx ->
                    if (ctx.flow() == PacketFlow.CLIENTBOUND) {
                        // 服务端 → 客户端（只在客户端进程执行）
                        GuardClient.handleIncoming(payload.wire)
                    } else {
                        // 客户端 → 服务端（只在服务端进程执行）。
                        // 默认 HandlerThread.MAIN 已在主线程，execute 是幂等防御。
                        val player = ctx.player() as? ServerPlayer ?: return@playBidirectional
                        val h = GuardBootstrap.hub ?: return@playBidirectional
                        h.server.execute {
                            h.sessions.onMessage(
                                NeoforgePlayer(player), payload.wire.id, payload.wire.body
                            )
                        }
                    }
                }
        }

        // 4. 客户端侧无额外动作
        if (FMLEnvironment.dist.isClient) {
            GuardBootstrap.LOG.debug("VoidkitGuard 客户端 Agent 就绪")
        }
    }
}
