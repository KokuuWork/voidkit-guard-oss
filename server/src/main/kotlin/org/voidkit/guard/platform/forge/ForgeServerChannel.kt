package org.voidkit.guard.platform.forge

import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.network.PacketDistributor
import net.minecraftforge.network.SimpleChannel
import org.voidkit.guard.common.GuardWire
import org.voidkit.guard.platform.GChannel
import org.voidkit.guard.platform.GPlayer

/**
 * 加密通道的 Forge 实现（**服务端侧**）。
 *
 * 只实现服务端会用到的两个方向：`send`（S→C）与 `onClientMessage`（C→S 回调）。
 * 客户端的 `sendToServer` 由客户端 jar 里的
 * [org.voidkit.guard.client.platform.forge.ForgeClientChannel] 实现 —— 两个 jar
 * 各持自己那一半，客户端 jar 里看不到服务端的发包/回调逻辑。
 *
 * 发包走 `SimpleChannel.send(msg, PacketDistributor.PLAYER.with(player))`。
 * ⚠️ 不能用 `player.connection.send(payload)` —— 那个方法收的是 `Packet<?>`，
 *    而 CustomPacketPayload 不是 Packet（编译期就拦得住，已实测）。
 *    SimpleChannel 自己会把 msg 包成 `ClientboundCustomPayloadPacket` 再发。
 *
 * 线程模型：`onClientMessage` 在网络线程触发。业务层（GuardSessions /
 * IntegrityFeature）收到后自己决定是否切主线程 —— 这里不替业务做决定。
 */
class ForgeServerChannel(private val channel: SimpleChannel) : GChannel {

    override var onClientMessage: ((player: GPlayer, msgId: String, body: ByteArray) -> Unit)? = null

    override fun send(player: GPlayer, msgId: String, body: ByteArray) {
        val sp = (player as? ForgePlayer)?.unwrap() ?: return
        val conn = sp.connection ?: return // 已断线的玩家：静默丢弃，不炸
        if (conn == null) return
        channel.send(ForgeWirePayload(GuardWire(msgId, body)), PacketDistributor.PLAYER.with(sp))
    }

    /** 服务端进程里没人调它；保留空实现以完整实现接口。 */
    override fun sendToServer(msgId: String, body: ByteArray) = Unit

    /** 由载荷处理器调用（网络线程）。 */
    internal fun handleIncoming(player: ServerPlayer, wire: GuardWire) {
        onClientMessage?.invoke(ForgePlayer(player), wire.id, wire.body)
    }
}