package org.voidkit.guard.platform.neoforge

import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import org.voidkit.guard.common.GuardWire
import org.voidkit.guard.platform.GChannel
import org.voidkit.guard.platform.GPlayer

/**
 * 加密通道的 NeoForge 实现（**服务端侧**）。
 *
 * 只实现服务端会用到的两个方向：`send`（S→C）与 `onClientMessage`（C→S 回调）。
 * 客户端的 `sendToServer` 由客户端 jar 里的 [org.voidkit.guard.platform.neoforge.NeoforgeClientChannel]
 * 实现 —— 两个 jar 各持自己那一半，客户端 jar 里看不到服务端的发包/回调逻辑。
 *
 * 线程模型：`onClientMessage` 在**网络线程**触发。业务层（GuardSessions /
 * IntegrityFeature）收到后自己决定是否切主线程 —— 这里不替业务做决定，
 * 免得「以为在主线程」的隐性 bug。
 */
class NeoforgeServerChannel : GChannel {

    override var onClientMessage: ((player: GPlayer, msgId: String, body: ByteArray) -> Unit)? = null

    override fun send(player: GPlayer, msgId: String, body: ByteArray) {
        val sp = (player as? NeoforgePlayer)?.unwrap() ?: return
        val conn = sp.connection ?: return // 已断线的玩家：静默丢弃，不炸
        PacketDistributor.sendToPlayer(sp, GuardWirePayload(GuardWire(msgId, body)))
    }

    /** 服务端进程里没人调它；保留空实现以完整实现接口。 */
    override fun sendToServer(msgId: String, body: ByteArray) = Unit

    /** 由载荷处理器调用（网络线程）。 */
    internal fun handleIncoming(player: ServerPlayer, wire: GuardWire) {
        onClientMessage?.invoke(NeoforgePlayer(player), wire.id, wire.body)
    }
}
