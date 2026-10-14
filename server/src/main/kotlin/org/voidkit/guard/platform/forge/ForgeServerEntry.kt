package org.voidkit.guard.platform.forge

import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.network.SimpleChannel
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.loading.FMLEnvironment
import net.minecraftforge.fml.loading.FMLPaths
import org.voidkit.guard.GuardBootstrap
import org.voidkit.guard.platform.GuardPlatforms

/**
 * Forge 1.21.1 加载器入口 —— **服务端** jar。
 *
 * ## 启动序列
 *
 * 1. `GuardBootstrap.bootstrap(channel)` —— 注册 Feature + 生命周期（依赖 core）
 * 2. `ForgeServerEvents.register()` —— 服务端游戏事件桥
 * 3. 载荷注册 —— 单频道 `voidkit_guard:wire`，**同一个载荷类只注册一次**
 *
 * ## ⚠️ Forge 1.21.1 与 NeoForge 1.21.1 的真实差异（javap 逐个核实）
 *
 * **① 没有 NeoForge 那套 payload registrar DSL**
 * 这是移植最大的坑：NeoForge 有
 * `event.registrar("id").optional().versioned("1").playBidirectional(...)`，
 * **Forge 52 根本没有 `RegisterPayloadHandlerEvent` 这个类**（在 universal jar 里
 * 搜不到任何 `RegisterPayload*`）。Forge 走的是老牌
 * `ChannelBuilder` + `SimpleChannel.messageBuilder(Class, index, NetworkDirection)`
 * —— 与我们的 mc/1.20.1 分支同一套写法（那份已真机验证过）。
 *
 * **② 频道要显式 build()**
 * `ChannelBuilder.named(id).optional().simpleChannel()` 返回 SimpleChannel，
 * 注册完 messageBuilder 后**必须 `build()`** 才真正注册进 NetworkRegistry。
 *
 * **③ 没有构造器注入的 IEventBus**
 * 本入口不需要总线（载荷注册走 ChannelBuilder），故用无参构造器。
 *
 * ## 为什么整体退避非专用服（dist 守卫）
 *
 * 频道名 `voidkit_guard:wire` 全局唯一：客户端 jar 会创建**同名** SimpleChannel。
 * 若本入口在 CLIENT 端（单人游戏装了双 jar）也构造，第二个同名频道注册直接炸。
 * Forge 52 的 `@Mod` **没有** `dist` 属性（那是 NeoForge 的注解级守卫），
 * 故只能运行时判 `FMLEnvironment.dist` —— 与 mc/1.20.1 分支同一手法。
 */
@Mod(GuardBootstrap.MODID)
class ForgeServerEntry {

    init {
        // 单人游戏 / 客户端进程：整体退避，不建频道、不装配、不注册任何事件。
        // （Kotlin 不允许 init块里 return，只能正向条件包住整个初始化体。）
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            initServer()
        }
    }

    private fun initServer() {
        val channel: SimpleChannel = net.minecraftforge.network.ChannelBuilder
            .named(ForgeWirePayload.CHANNEL_ID)
            .networkProtocolVersion(ForgeWirePayload.PROTOCOL_VERSION)
            .optional()
            .simpleChannel()

        // 0. 游戏目录提供者（服务端游戏目录 = 服务端根目录）
        GuardPlatforms.installGameDir { FMLPaths.GAMEDIR.get() }

        // 0.5 GServer/GPlayer 工厂 —— 让加载器无关的 GuardBootstrap 与业务层
        // 造出本平台的实现（业务层不 import 任何加载器类）
        GuardPlatforms.installServerFactory { ForgeServer(it as net.minecraft.server.MinecraftServer) }
        GuardPlatforms.installPlayerFactory { ForgePlayer(it as ServerPlayer) }

        // 1. 服务端装配（模块 + 生命周期；通道实现由本入口注入）
        GuardBootstrap.bootstrap(ForgeServerChannel(channel))

        // 2. 服务端游戏事件桥接（加载器 API，不放进 Bootstrap）
        ForgeServerEvents.register()

        // 3. 网络载荷：**单次** 2 参注册（方向留空 = 收/发都认）。
        //
        //    ⚠️ 为什么不能像 NeoForge/1.20.1 那样「同一个类注册两次」（index 0 收 + index 1 发）：
        //    SimpleChannel 内部是 `byType: Class -> Message` 的映射，一个 Class **只能注册一次**，
        //    重复注册直接抛 `IllegalStateException: Class ... already registered`
        //    （真机 1.21.1 Forge 实测，见 SimpleChannel$MessageBuilder.add）。
        //    2 参 `messageBuilder` 留下的 `direction == null` 即「任意方向」，
        //    才是 Forge 的双向写法；编解码注册一次即够（发送侧复用同一 codec）。
        channel.messageBuilder(ForgeWirePayload::class.java, 0)
            .encoder { msg, buf -> ForgeWirePayload.encode(msg, buf) }
            .decoder { buf -> ForgeWirePayload.decode(buf) }
            .consumerMainThread { payload, ctx ->
                // consumerMainThread 已在主线程；execute 是幂等防御，统一线程纪律
                // SimpleChannel 的 ctx.sender 静态类型为 ServerPlayer?（平台可能给 null）
                val player = ctx.sender ?: return@consumerMainThread
                val h = GuardBootstrap.hub ?: return@consumerMainThread
                h.server.execute {
                    h.sessions.onMessage(ForgePlayer(player), payload.wire.id, payload.wire.body)
                }
            }
            .add()

        // 4. build() —— 真正注册进 NetworkRegistry（Forge 52 必须显式调用）
        channel.build()
    }
}