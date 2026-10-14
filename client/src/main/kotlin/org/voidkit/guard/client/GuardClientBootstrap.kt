package org.voidkit.guard.client

import com.mojang.logging.LogUtils
import org.slf4j.Logger
import org.voidkit.guard.client.platform.GChannel

/**
 * guard **客户端**的装配中心（与服务端的 [GuardBootstrap] 彻底分开）。
 *
 * ## 为什么不复用 GuardBootstrap
 *
 * GuardBootstrap 是服务端装配：注册 Feature、挂生命周期、开数据库 —— 全部
 * 依赖 `voidkit_core`。而**客户端不装 core**，所以客户端 jar 里不能有任何
 * 碰 core 的类（否则客户端 mod 构造期就 NoClassDefFoundError 崩掉）。
 *
 * 客户端只需要两样东西：自己的日志、一个能把报文递给服务端的通道。
 * 就这些 —— 客户端 Agent 的所有能力（采集/截屏/扫描）都在 client 包里，
 * 与服务端代码零交集。
 */
object GuardClientBootstrap {

    /** 客户端 modId。与服务端 `voidkit_guard` 区分，避免单人游戏双 jar 冲突。 */
    const val MODID = "voidkit_guard_client"

    /** 客户端日志（Mojang 的 LogUtils，不依赖 core）。 */
    val LOG: Logger = LogUtils.getLogger()

    /** 客户端通道（发包给服务端用）。 */
    @Volatile
    var channel: GChannel? = null
        private set

    fun init(channel: GChannel) {
        this.channel = channel
    }
}
