package org.voidkit.guard.platform.forge

import net.minecraft.server.level.ServerPlayer
import org.voidkit.guard.platform.GPlayer

/**
 * Forge 1.21.1 的 [GPlayer] 实现（服务端侧）。
 *
 * 与 NeoForge 版 [org.voidkit.guard.platform.neoforge.NeoforgePlayer] 逐行相同 ——
 * `ServerPlayer` / `stringUUID` / `gameProfile` / `ipAddress` 都是 1.21.1 原版
 * API（Mojang 官方映射），两加载器同名同义。
 */
class ForgePlayer(private val player: ServerPlayer) : GPlayer {
    override val uuid: String get() = player.stringUUID
    override val name: String get() = player.gameProfile.name
    override val ip: String get() = player.ipAddress

    fun unwrap(): ServerPlayer = player
}