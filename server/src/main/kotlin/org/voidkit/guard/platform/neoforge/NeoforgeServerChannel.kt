package org.voidkit.guard.platform.neoforge

import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.network.PacketDistributor
import net.minecraftforge.network.simple.SimpleChannel
import org.voidkit.guard.common.GuardWire
import org.voidkit.guard.platform.GChannel
import org.voidkit.guard.platform.GPlayer

/**
 * 加密通道的 Forge 1.20.1 实现（**服务端侧**）。
 *
 * 只实现服务端会用到的两个方向：`send`（S→C）与 `onClientMessage`（C→S 回调）。
 * 客户端的 `sendToServer` 由客户端 jar 里的 [NeoforgeClientChannel]
 * 实现 —— 两个 jar 各持自己那一半，客户端 jar 里看不到服务端的发包/回调逻辑。
 *
 * 线程模型：`onClientMessage` 在**主线程**触发（`consumerMainThread` 已保证）。
 * 业务层（GuardSessions / IntegrityFeature）收到后自己决定是否再切线程 ——
 * 这里不替业务做决定，免得「以为在某线程」的隐性 bug。
 */
class NeoforgeServerChannel(private val channel: SimpleChannel) : GChannel {

    override var onClientMessage: ((player: GPlayer, msgId: String, body: ByteArray) -> Unit)? = null

    override fun send(player: GPlayer, msgId: String, body: ByteArray) {
        val sp = (player as? NeoforgePlayer)?.unwrap() ?: return
        if (sp.connection == null) return // 已断线的玩家：静默丢弃，不炸
        // 通道断了（极少见：玩家在登录阶段掉线）也不能炸业务线程
        runCatching {
            channel.send(PacketDistributor.PLAYER.with { sp }, GuardWirePayload(GuardWire(msgId, body)))
        }
    }

    /** 服务端进程里没人调它；保留空实现以完整实现接口。 */
    override fun sendToServer(msgId: String, body: ByteArray) = Unit
}
