package org.voidkit.guard.client.platform.neoforge

import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.loading.FMLEnvironment
import net.minecraftforge.network.NetworkDirection
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.simple.SimpleChannel
import org.voidkit.guard.client.GuardClientBootstrap
import org.voidkit.guard.client.GuardClient
import org.voidkit.guard.client.platform.GuardPlatforms

/**
 * Forge 1.20.1 加载器入口 —— **客户端** jar。
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
 *
 * ⚠️ Forge 1.20.1 的 @Mod 构造器注入只认 `(FMLJavaModLoadingContext)` 或无参
 *    （javap 核对 FMLModContainer.constructMod）。NeoForge 1.21.1 那套
 *    `(IEventBus, ModContainer)` 双参在这里会编译通过、加载即崩。
 *
 * ## 为什么整个入口都包在 dist == CLIENT 分支里（dist 守卫）
 *
 * 频道名 `voidkit_guard:wire` 全局唯一：服务端 jar 会创建**同名** SimpleChannel。
 * 若本入口在专用服上也被构造（Forge 1.20.1 的 @Mod 没有 dist 属性，所有 mod
 * 在所有端都会构造），第二个同名频道注册直接炸。与 master 的
 * `@Mod(dist = [Dist.CLIENT])` 注解守卫语义对齐：专用服上本 mod 静默空转，
 * 什么都不建、什么都不注册。单人游戏是 CLIENT dist，本入口正常工作，
 * 而服务端入口对 CLIENT dist 整体退避 —— 双 jar 共存零冲突。
 */
@Mod(GuardClientBootstrap.MODID)
class NeoforgeClientEntry {

    init {
        // 专用服上整体空转：不建频道、不装平台、不注册任何消息。
        //（频道必须在 mod 构造期建 —— 此时还在 NetworkRegistry.lock() 之前；
        //  所以连频道一起搬进分支，而不是只挡注册。）
        if (FMLEnvironment.dist == Dist.CLIENT) {
            val channel: SimpleChannel = NetworkRegistry.newSimpleChannel(
                GuardWirePayload.CHANNEL_ID,
                { GuardWirePayload.PROTOCOL_VERSION },
                { it == GuardWirePayload.PROTOCOL_VERSION },
                { it == GuardWirePayload.PROTOCOL_VERSION },
            )

            GuardPlatforms.installMods(NeoforgeMods())
            GuardPlatforms.installGameDir { net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get() }
            GuardClientBootstrap.init(NeoforgeClientChannel(channel))

            // index 0 = C→S：客户端是发送方，注册是为了发送侧有 codec；
            // 消费器留空（客户端不会收到自己发的方向）。
            channel.messageBuilder(GuardWirePayload::class.java, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder { msg, buf -> GuardWirePayload.encode(msg, buf) }
                .decoder { buf -> GuardWirePayload.decode(buf) }
                .consumerMainThread { _, _ -> }
                .add()

            // index 1 = S→C：客户端只关心服务端发来的消息。
            channel.messageBuilder(GuardWirePayload::class.java, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder { msg, buf -> GuardWirePayload.encode(msg, buf) }
                .decoder { buf -> GuardWirePayload.decode(buf) }
                .consumerMainThread { payload, _ -> GuardClient.handleIncoming(payload.wire) }
                .add()

            GuardClientBootstrap.LOG.debug("VoidkitGuard 客户端 Agent 就绪")
        }
    }
}
