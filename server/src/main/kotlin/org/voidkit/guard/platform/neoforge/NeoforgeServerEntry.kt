package org.voidkit.guard.platform.neoforge

import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.loading.FMLEnvironment
import net.minecraftforge.network.NetworkDirection
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.simple.SimpleChannel
import org.voidkit.guard.GuardBootstrap
import org.voidkit.guard.platform.GuardPlatforms

/**
 * Forge 1.20.1 加载器入口 —— **服务端** jar。
 *
 * ## 启动序列
 *
 * 1. `GuardBootstrap.bootstrap(channel)` —— 注册 Feature + 生命周期（依赖 core）
 * 2. `NeoforgeServerEvents.register()` —— 服务端游戏事件桥（方块破坏/攻击/tick）
 * 3. 载荷注册 —— 单频道 `voidkit_guard:wire`，按方向注册两条消息
 *    （index 0 = C→S 收，index 1 = S→C 发；两侧对称注册，id 各自唯一）；
 *    只处理 PLAY_TO_SERVER（客户端发来的报文）。
 *
 * optional 语义：Forge 1.20.1 的 SimpleChannel 没有 NeoForge 1.21.1 的
 * `optional()` 标记，但**不需要** —— 实测（javap 核对过 Forge 补丁字节码）：
 * 客户端收到未注册频道的自定义载荷时 `NetworkHooks.onCustomPayload` 返回
 * false 后落入原版分支，原版对未知频道**静默忽略**。所以没装客户端 Agent 的
 * 玩家能正常连进来，然后被握手超时踢掉，全程不会看到任何与 guard 有关的提示。
 *
 * ⚠️ Forge 1.20.1 的 @Mod 构造器注入只认 `(FMLJavaModLoadingContext)` 或无参
 *    （javap 核对 FMLModContainer.constructMod：先试单参 LoadingContext，
 *    失败再退无参）。NeoForge 1.21.1 那套 `(IEventBus, ModContainer)` 双参
 *    在这里会**编译通过、加载即崩** —— 本模组不需要事件总线，直接用无参。
 *
 * ## 为什么整体退避非专用服（dist 守卫）
 *
 * 频道名 `voidkit_guard:wire` 全局唯一：客户端 jar 会创建**同名**
 * SimpleChannel。若本入口在 CLIENT 端（单人游戏装了双 jar）也构造，
 * 第二个同名频道注册直接炸。与 master 的
 * `@Mod(dist = [Dist.DEDICATED_SERVER])` 注解守卫语义对齐：单人游戏里
 * 本入口整体空转（Forge 1.20.1 的 @Mod 没有 dist 属性，只能运行时判断），
 * 反作弊在单人场景没有意义，LAN 联机同理（默认信任）。
 */
@Mod(GuardBootstrap.MODID)
class NeoforgeServerEntry {

    init {
        // 单人游戏 / 客户端进程：整体退避，不建频道、不装配、不注册任何事件。
        //（Kotlin 不允许 init 块里 return，只能正向条件包住整个初始化体。）
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            initServer()
        }
    }

    private fun initServer() {
        // 频道在 mod 构造期创建：此时还在 NetworkRegistry.lock() 之前，注册合法。
        val channel: SimpleChannel = NetworkRegistry.newSimpleChannel(
            GuardWirePayload.CHANNEL_ID,
            { GuardWirePayload.PROTOCOL_VERSION },
            { it == GuardWirePayload.PROTOCOL_VERSION },
            { it == GuardWirePayload.PROTOCOL_VERSION },
        )

        // 0. 游戏目录提供者（服务端游戏目录 = 服务端根目录）
        GuardPlatforms.installGameDir { net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get() }

        // 1. 服务端装配（模块 + 生命周期；通道实现由本入口注入）
        GuardBootstrap.bootstrap(NeoforgeServerChannel(channel))

        // 2. 服务端游戏事件桥接（加载器 API，不放进 Bootstrap）
        NeoforgeServerEvents.register()

        // 3. 网络载荷：C→S（index 0，服务端只收这个方向）
        channel.messageBuilder(GuardWirePayload::class.java, 0, NetworkDirection.PLAY_TO_SERVER)
            .encoder { msg, buf -> GuardWirePayload.encode(msg, buf) }
            .decoder { buf -> GuardWirePayload.decode(buf) }
            .consumerMainThread { payload, ctx ->
                // consumerMainThread 已在主线程；execute 是幂等防御，统一线程纪律
                val player = ctx.get().sender as? ServerPlayer ?: return@consumerMainThread
                val h = GuardBootstrap.hub ?: return@consumerMainThread
                h.server.execute {
                    h.sessions.onMessage(NeoforgePlayer(player), payload.wire.id, payload.wire.body)
                }
            }
            .add()

        // 4. S→C（index 1）：服务端不「收」，但发送侧的编解码必须注册，
        //    否则 PacketDistributor 发包时找不到 codec。
        channel.messageBuilder(GuardWirePayload::class.java, 1, NetworkDirection.PLAY_TO_CLIENT)
            .encoder { msg, buf -> GuardWirePayload.encode(msg, buf) }
            .decoder { buf -> GuardWirePayload.decode(buf) }
            .consumerMainThread { _, _ -> }
            .add()
    }
}
