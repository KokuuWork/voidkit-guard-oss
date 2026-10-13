package org.voidkit.guard.platform.neoforge

import net.minecraft.server.level.ServerPlayer
import org.voidkit.guard.platform.GPlayer

/**
 * NeoForge 的 [GPlayer] 实现（服务端侧）。
 *
 * 只包装服务端的 [ServerPlayer]；客户端不产生 GPlayer（Agent 自己处理数据），
 * 所以这个类只出现在服务端 jar 里。
 */
class NeoforgePlayer(private val player: ServerPlayer) : GPlayer {
    override val uuid: String get() = player.stringUUID
    override val name: String get() = player.gameProfile.name
    override val ip: String get() = player.ipAddress

    fun unwrap(): ServerPlayer = player
}
