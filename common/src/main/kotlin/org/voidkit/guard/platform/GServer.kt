package org.voidkit.guard.platform

/**
 * 平台抽象：服务端能力。
 *
 * 业务代码（server/ 下的 Feature）只认这个接口 —— 不 import `net.minecraft.*`、
 * 不 import 加载器事件类。等 core 长出 `core.platform`（见 KokuuWork/voidkit
 * issue #1）时，把这里的实现换成 core.platform 的即可，业务代码零改动。
 * 这也是 guard 将来移植 Forge / Fabric 的唯一改写面。
 */
interface GServer {
    fun isMainThread(): Boolean

    /** 把任务扔回服务端主线程。已在主线程则直接跑（调用方负责判重）。 */
    fun execute(task: Runnable)

    fun onlinePlayers(): List<GPlayer>

    fun playerByName(name: String): GPlayer?

    /** 踢人并断连。reason 会原样展示给玩家，写中文、写清原因。 */
    fun disconnect(player: GPlayer, reason: String)

    /**
     * 只通知在线的有 `voidkit.guard` 权限的管理员（私聊样式，不广播）。
     *
     * 静默模式的核心通道：检测细节给服主看，不给玩家看。
     */
    fun notifyAdmins(text: String)

    /** 给单个玩家发消息（走内核的 Msg 格式化，带统一前缀）。 */
    fun sendOk(player: GPlayer, text: String)
    fun sendErr(player: GPlayer, text: String)
    fun sendInfo(player: GPlayer, text: String)

    /** 服务端日志。业务层不直接抱 LOG，避免散落的日志格式。 */
    fun logInfo(message: String)
    fun logWarn(message: String)
}

/** 平台抽象：一个在线玩家。 */
interface GPlayer {
    val uuid: String
    val name: String

    /** 连接 IP（登录期拿得到，断线后为空串）。 */
    val ip: String
}

/**
 * 平台抽象：加密通道。
 *
 * 单信封设计（见 [org.voidkit.guard.common.GuardWire]）：业务层只认
 * `(player, msgId, body)` 三元组，编解码、线程模型全在 platform 层。
 */
interface GChannel {
    /**
     * 收到客户端消息。可能在网络线程触发 —— 涉及世界/玩家的操作，
     * 业务层必须自己过 [GServer.execute]。
     */
    var onClientMessage: ((player: GPlayer, msgId: String, body: ByteArray) -> Unit)?

    /** 发一条信封消息给玩家（明文握手期与加密会话期都用它）。 */
    fun send(player: GPlayer, msgId: String, body: ByteArray)

    /**
     * 客户端 → 服务端发一条信封消息。
     *
     * 只在客户端进程调用（服务端没有「发给服务端」的概念）。
     * NeoForge 实现是 `PacketDistributor.sendToServer` —— 加载器 API，
     * 所以必须待在 platform 层，客户端 Agent 不直接摸。
     */
    fun sendToServer(msgId: String, body: ByteArray)
}
