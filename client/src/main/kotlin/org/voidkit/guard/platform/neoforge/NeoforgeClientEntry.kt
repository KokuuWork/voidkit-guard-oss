package org.voidkit.guard.platform.neoforge

import net.minecraft.network.protocol.PacketFlow
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import org.voidkit.guard.GuardClientBootstrap
import org.voidkit.guard.client.GuardClient
import org.voidkit.guard.platform.GuardPlatforms

/**
 * NeoForge 加载器入口 —— **客户端** jar。
 *
 * 客户端只做三件事：
 * 1. 装载 mod 枚举实现（哈希锁要的清单）
 * 2. 存一个「发给服务端」的通道
 * 3. 注册网络载荷，收到服务端消息后交给 [GuardClient]
 *
 * **不碰 core、不碰服务端逻辑** —— 客户端 jar 的 class 里没有一行服务端代码，
 * 即使反编译也拿不到服务端的密钥处理与检测策略。
 *
 * channel 标识 `voidkit_guard:wire` 与服务端一致（硬编码，与 modId 解耦），
 * 两侧各注册一份副本即可通信。
 */
@Mod(GuardClientBootstrap.MODID)
class NeoforgeClientEntry(bus: IEventBus, container: ModContainer) {

    init {
        GuardPlatforms.installMods(NeoforgeMods())
        GuardPlatforms.installGameDir { net.neoforged.fml.loading.FMLPaths.GAMEDIR.get() }
        GuardClientBootstrap.init(NeoforgeClientChannel())

        bus.addListener { event: RegisterPayloadHandlersEvent ->
            event.registrar("voidkit_guard").optional().versioned("1")
                .playBidirectional(GuardWirePayload.TYPE, GuardWirePayload.STREAM_CODEC) { payload, ctx ->
                    // 客户端只关心服务端发来的消息（CLIENTBOUND）
                    if (ctx.flow() == PacketFlow.CLIENTBOUND) {
                        GuardClient.handleIncoming(payload.wire)
                    }
                }
        }

        GuardClientBootstrap.LOG.debug("VoidkitGuard 客户端 Agent 就绪")
    }
}
