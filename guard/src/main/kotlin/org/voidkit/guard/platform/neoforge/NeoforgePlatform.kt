package org.voidkit.guard.platform.neoforge

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import org.voidkit.guard.platform.GChannel
import org.voidkit.guard.platform.GPlayer
import org.voidkit.guard.GuardBootstrap
import org.voidkit.guard.platform.GServer

/**
 * NeoForge 平台适配（服务端）。
 *
 * 与 [NeoforgePayloads] 一起构成「唯一按加载器重写」的一层：
 * 换 Forge / Fabric 时只改这两个文件，`server/` 业务代码零改动。
 */
class NeoforgePlayer(private val player: ServerPlayer) : GPlayer {
    override val uuid: String get() = player.stringUUID
    override val name: String get() = player.gameProfile.name
    override val ip: String get() = player.ipAddress

    fun unwrap(): ServerPlayer = player
}

class NeoforgeServer(private val server: MinecraftServer) : GServer {

    override fun isMainThread(): Boolean = server.isSameThread

    override fun execute(task: Runnable) {
        if (server.isSameThread) task.run() else server.execute(task)
    }

    override fun onlinePlayers(): List<GPlayer> =
        server.playerList.players.map { NeoforgePlayer(it) }

    override fun playerByName(name: String): GPlayer? =
        server.playerList.getPlayerByName(name)?.let { NeoforgePlayer(it) }

    override fun disconnect(player: GPlayer, reason: String) {
        val sp = (player as? NeoforgePlayer)?.unwrap() ?: return
        // connection 在断线后可能为 null；此时人已经走了，无需再踢
        sp.connection?.disconnect(net.minecraft.network.chat.Component.literal(reason))
    }

    override fun notifyAdmins(text: String) {
        val players = server.playerList.players ?: return
        val msg = net.minecraft.network.chat.Component.literal("")
            .append(net.minecraft.network.chat.Component.literal("[Guard] ").withStyle(net.minecraft.ChatFormatting.DARK_RED))
            .append(net.minecraft.network.chat.Component.literal(text).withStyle(net.minecraft.ChatFormatting.GRAY))
        for (p in players) {
            if (org.voidkit.core.api.VoidkitServices.permission().has(p, "voidkit.guard")) {
                p.sendSystemMessage(msg)
            }
        }
    }

    override fun sendOk(player: GPlayer, text: String) {
        (player as? NeoforgePlayer)?.unwrap()?.let { org.voidkit.core.util.Msg.ok(it, text) }
    }

    override fun sendErr(player: GPlayer, text: String) {
        (player as? NeoforgePlayer)?.unwrap()?.let { org.voidkit.core.util.Msg.err(it, text) }
    }

    override fun sendInfo(player: GPlayer, text: String) {
        (player as? NeoforgePlayer)?.unwrap()?.let { org.voidkit.core.util.Msg.info(it, text) }
    }

    override fun logInfo(message: String) {
        GuardBootstrap.LOG.info(message)
    }

    override fun logWarn(message: String) {
        GuardBootstrap.LOG.warn(message)
    }
}

/**
 * 加密通道的 NeoForge 实现。
 *
 * 线程模型：`onClientMessage` 在**网络线程**触发。业务层（GuardSessions /
 * IntegrityFeature）收到后自己决定是否切主线程 —— 这里不替业务做决定，
 * 免得「以为在主线程」的隐性 bug。
 */
class NeoforgeChannel : GChannel {

    override var onClientMessage: ((player: GPlayer, msgId: String, body: ByteArray) -> Unit)? = null

    override fun send(player: GPlayer, msgId: String, body: ByteArray) {
        val sp = (player as? NeoforgePlayer)?.unwrap() ?: return
        val conn = sp.connection ?: return // 已断线的玩家：静默丢弃，不炸
        PacketDistributor.sendToPlayer(sp, GuardWirePayload(org.voidkit.guard.common.GuardWire(msgId, body)))
    }

    /**
     * 客户端 → 服务端。
     *
     * 只在客户端进程有意义（服务端进程里没人调；真被调了也只是白发）。
     * 未连接服务器时 sendToServer 会静默失败 —— Agent 只在收到 challenge
     * 之后才发东西，那时必然在连接中。
     */
    override fun sendToServer(msgId: String, body: ByteArray) {
        runCatching {
            PacketDistributor.sendToServer(
                GuardWirePayload(org.voidkit.guard.common.GuardWire(msgId, body))
            )
        }
    }

    /** 由载荷处理器调用（网络线程）。 */
    internal fun handleIncoming(player: ServerPlayer, wire: org.voidkit.guard.common.GuardWire) {
        onClientMessage?.invoke(NeoforgePlayer(player), wire.id, wire.body)
    }
}
