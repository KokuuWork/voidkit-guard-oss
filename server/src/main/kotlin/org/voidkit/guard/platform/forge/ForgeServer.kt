package org.voidkit.guard.platform.forge

import net.minecraft.server.MinecraftServer
import net.minecraft.network.chat.Component
import net.minecraft.ChatFormatting
import org.voidkit.guard.GuardBootstrap
import org.voidkit.guard.platform.GPlayer
import org.voidkit.guard.platform.GServer

/**
 * Forge 1.21.1 的 [GServer] 实现（服务端侧）。
 *
 * 与 NeoForge 版 [org.voidkit.guard.platform.neoforge.NeoforgeServer] **零逻辑差异**
 * —— 用的全是 1.21.1 原版 API（`MinecraftServer` / `playerList` / `Component` /
 * `ChatFormatting`），两加载器同名。本文件存在的意义就是**证明**「唯一改写面」
 * 铁律：换加载器只改 platform 层，业务装配零改动。
 *
 * 权限与聊天消息走 core 的 `VoidkitServices.permission()` / `Msg`
 * （[GServer] 是 core 没有的抽象 —— core 只管自己的模块，不管别的插件怎么跟玩家说话）。
 */
class ForgeServer(private val server: MinecraftServer) : GServer {

    override fun isMainThread(): Boolean = server.isSameThread

    override fun execute(task: Runnable) {
        if (server.isSameThread) task.run() else server.execute(task)
    }

    override fun onlinePlayers(): List<GPlayer> =
        server.playerList.players.map { ForgePlayer(it) }

    override fun playerByName(name: String): GPlayer? =
        server.playerList.getPlayerByName(name)?.let { ForgePlayer(it) }

    override fun disconnect(player: GPlayer, reason: String) {
        val sp = (player as? ForgePlayer)?.unwrap() ?: return
        // connection 在断线后可能为 null；此时人已经走了，无需再踢
        sp.connection?.disconnect(Component.literal(reason))
    }

    override fun notifyAdmins(text: String) {
        val players = server.playerList.players ?: return
        val msg = Component.literal("")
            .append(Component.literal("[Guard] ").withStyle(ChatFormatting.DARK_RED))
            .append(Component.literal(text).withStyle(ChatFormatting.GRAY))
        for (p in players) {
            if (org.voidkit.core.api.VoidkitServices.permission().has(p, "voidkit.guard")) {
                p.sendSystemMessage(msg)
            }
        }
    }

    override fun sendOk(player: GPlayer, text: String) {
        (player as? ForgePlayer)?.unwrap()?.let { org.voidkit.core.util.Msg.ok(it, text) }
    }

    override fun sendErr(player: GPlayer, text: String) {
        (player as? ForgePlayer)?.unwrap()?.let { org.voidkit.core.util.Msg.err(it, text) }
    }

    override fun sendInfo(player: GPlayer, text: String) {
        (player as? ForgePlayer)?.unwrap()?.let { org.voidkit.core.util.Msg.info(it, text) }
    }

    override fun logInfo(message: String) {
        GuardBootstrap.LOG.info(message)
    }

    override fun logWarn(message: String) {
        GuardBootstrap.LOG.warn(message)
    }
}