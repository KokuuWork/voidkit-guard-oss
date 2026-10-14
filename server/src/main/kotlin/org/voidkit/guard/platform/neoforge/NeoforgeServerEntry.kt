package org.voidkit.guard.platform.neoforge

import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.level.ServerPlayer
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import org.voidkit.guard.GuardBootstrap
import org.voidkit.guard.platform.GuardPlatforms

/**
 * NeoForge 加载器入口 —— **服务端** jar。
 *
 * ## 启动序列
 *
 * 1. `GuardBootstrap.bootstrap(channel)` —— 注册 Feature + 生命周期（依赖 core）
 * 2. `NeoforgeServerEvents.register()` —— 服务端游戏事件桥（方块破坏/攻击/tick）
 * 3. 载荷注册 —— 单通道 `voidkit_guard:wire`，**playBidirectional**
 *    （同一个 channel id 只能注册一次），靠 `ctx.flow()` 区分方向；
 *    只处理 SERVERBOUND（客户端发来的报文）。
 *
 * optional = 没装客户端 Agent 的玩家能连进来，然后被握手超时踢掉。
 *
 * ## 为什么限定 DEDICATED_SERVER（dist 守卫）
 *
 * 通道 id `voidkit_guard:wire` 全局唯一：客户端 jar 的入口会注册**同一 id**的
 * 客户端侧副本。若本入口在 CLIENT 端（单人游戏装了双 jar）也构造并注册，
 * 后注册的一方直接炸（「Cannot register payload ... already registered」）。
 * 限定专用服后：单人游戏里服务端入口不构造，客户端入口正常注册 —— 双 jar
 * 共存零冲突，反作弊在单人场景下也本来就没有意义（客户端是自己人的）。
 * （代价：LAN 联机的整合服主世界也不跑守卫 —— 可接受，局域网默认信任。）
 */
@Mod(value = GuardBootstrap.MODID, dist = [Dist.DEDICATED_SERVER])
class NeoforgeServerEntry(bus: IEventBus, container: ModContainer) {

    init {
        // 0. 游戏目录提供者（服务端游戏目录 = 服务端根目录）
        GuardPlatforms.installGameDir { net.neoforged.fml.loading.FMLPaths.GAMEDIR.get() }

        // 1. 服务端装配（模块 + 生命周期；通道实现由本入口注入）
        GuardBootstrap.bootstrap(NeoforgeServerChannel())

        // 2. 服务端游戏事件桥接（加载器 API，不放进 Bootstrap）
        NeoforgeServerEvents.register()

        // 3. 网络载荷
        bus.addListener { event: RegisterPayloadHandlersEvent ->
            event.registrar("voidkit_guard").optional().versioned("1")
                .playBidirectional(GuardWirePayload.TYPE, GuardWirePayload.STREAM_CODEC) { payload, ctx ->
                    if (ctx.flow() == PacketFlow.SERVERBOUND) {
                        // 客户端 → 服务端。默认 HandlerThread.MAIN 已在主线程，execute 是幂等防御。
                        val player = ctx.player() as? ServerPlayer ?: return@playBidirectional
                        val h = GuardBootstrap.hub ?: return@playBidirectional
                        h.server.execute {
                            h.sessions.onMessage(NeoforgePlayer(player), payload.wire.id, payload.wire.body)
                        }
                    }
                }
        }
    }
}
