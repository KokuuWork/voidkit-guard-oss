package org.voidkit.guard.platform.neoforge

import net.minecraft.server.MinecraftServer
import org.voidkit.guard.GuardBootstrap
import org.voidkit.guard.platform.GPlayer
import org.voidkit.guard.platform.GServer

/**
 * NeoForge 的 [GServer] 实现（服务端侧）。
 *
 * 服务端专属：踢人、给管理员发消息、日志。权限与聊天消息走 core 的
 * `VoidkitServices.permission()` / `Msg`（[GServer] 是 core 没有的抽象，
 * 因为 core 只管自己的模块，不管别的插件怎么跟玩家说话）。
 */
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
