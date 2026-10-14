package org.voidkit.guard.client.platform.forge

import net.minecraft.network.protocol.PacketFlow
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.loading.FMLEnvironment
import net.minecraftforge.fml.loading.FMLPaths
import net.minecraftforge.network.ChannelBuilder
import net.minecraftforge.network.NetworkDirection
import net.minecraftforge.network.PacketDistributor
import net.minecraftforge.network.SimpleChannel
import org.voidkit.guard.client.GuardClient
import org.voidkit.guard.client.GuardClientBootstrap
import org.voidkit.guard.client.platform.GuardPlatforms

/**
 * Forge 1.21.1 加载器入口 —— **客户端** jar。
 *
 * 客户端只做三件事：
 * 1. 装载 mod 枚举实现（哈希锁要的清单）
 * 2. 存一个「发给服务端」的通道
 * 3. 注册网络载荷，收到服务端消息后交给 [GuardClient]
 *
 * **不碰 core、不碰服务端逻辑** —— 客户端 jar 的 class 里没有一行服务端代码，
 * 即使反编译也拿不到服务端的密钥处理与检测策略。
 *
 * ## 载荷注册为什么和服务端一样用 SimpleChannel
 *
 * Forge 52 没有 NeoForge 的 payload registrar DSL（见 [org.voidkit.guard.platform.forge.ForgeServerEntry]
 * 的详细说明），两边都用 `ChannelBuilder` + `messageBuilder(Class, index, NetworkDirection)`，
 * index 必须**对称**：服务端 C→S 是 0、客户端 S→C 也必须是 0（同一方向同一 index）。
 *
 * ## 为什么限定客户端（dist 守卫）
 *
 * 频道名全局唯一：服务端 jar 注册同名频道。若本入口在专用服上也被构造并抢先
 * 注册，服务端入口注册时直接炸。Forge 52 的 `@Mod` 无 dist 属性，只能运行时判。
 */
@Mod(GuardClientBootstrap.MODID)
class ForgeClientEntry {

    init {
        // 专用服进程：整体退避（Kotlin init 块不允许 return，用正向条件包住）
        if (FMLEnvironment.dist == Dist.CLIENT) {
            initClient()
        }
    }

    private fun initClient() {
        GuardPlatforms.installMods(ForgeMods())
        GuardPlatforms.installGameDir { FMLPaths.GAMEDIR.get() }

        val channel: SimpleChannel = ChannelBuilder
            .named(ForgeClientWirePayload.CHANNEL_ID)
            .networkProtocolVersion(ForgeClientWirePayload.PROTOCOL_VERSION)
            .optional()
            .simpleChannel()

        GuardClientBootstrap.init(ForgeClientChannel(channel))

        // S→C（index 0，与服务端「C→S index 0」对称：同一方向用同一 index）
        channel.messageBuilder(ForgeClientWirePayload::class.java, 0, NetworkDirection.PLAY_TO_CLIENT)
            .encoder { msg, buf -> ForgeClientWirePayload.encode(msg, buf) }
            .decoder { buf -> ForgeClientWirePayload.decode(buf) }
            .consumerMainThread { payload, _ ->
                GuardClient.handleIncoming(payload.wire)
            }
            .add()

        // C→S（index 1）：客户端不「收」，但发送侧的编解码必须注册，否则发包找不到 codec。
        channel.messageBuilder(ForgeClientWirePayload::class.java, 1, NetworkDirection.PLAY_TO_SERVER)
            .encoder { msg, buf -> ForgeClientWirePayload.encode(msg, buf) }
            .decoder { buf -> ForgeClientWirePayload.decode(buf) }
            .consumerMainThread { _, _ -> }
            .add()

        channel.build()

        GuardClientBootstrap.LOG.debug("VoidkitGuard 客户端 Agent 就绪")
    }
}