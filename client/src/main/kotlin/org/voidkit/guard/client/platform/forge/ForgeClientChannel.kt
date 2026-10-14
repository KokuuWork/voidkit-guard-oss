package org.voidkit.guard.client.platform.forge

import net.minecraftforge.network.PacketDistributor
import net.minecraftforge.network.SimpleChannel
import org.voidkit.guard.client.common.GuardWire
import org.voidkit.guard.client.platform.GChannel
import org.voidkit.guard.client.platform.GPlayer

/**
 * 加密通道的 Forge 实现（**客户端侧**）。
 *
 * 客户端只做一件事：把报文发给服务端（`sendToServer`）。它**不含**
 * 服务端的发包给玩家、也不含服务端收包回调 —— 那两半在服务端 jar 里。
 * 客户端 jar 交付到玩家手上后，反编译看到的只是「怎么把加密报文递给服务端」，
 * 看不到服务端的会话管理 / 密钥处理 / 检测策略。
 *
 * 发包走 `SimpleChannel.send(msg, PacketDistributor.SERVER.noArg())`
 * —— Forge 的 PacketDistributor 会判断当前是客户端还是服务端：
 * 客户端进程里 SERVER 目标 = 发给服务端；服务端进程里 = 广播给所有玩家。
 * 这与 NeoForge 的 `PacketDistributor.sendToServer` 语义一致。
 */
class ForgeClientChannel(private val channel: SimpleChannel) : GChannel {

    /** 客户端不做「服务端 → 玩家」方向；保留空实现以完整实现接口。 */
    override var onClientMessage: ((player: GPlayer, msgId: String, body: ByteArray) -> Unit)? = null

    override fun send(player: GPlayer, msgId: String, body: ByteArray) = Unit

    override fun sendToServer(msgId: String, body: ByteArray) {
        runCatching {
            channel.send(ForgeClientWirePayload(GuardWire(msgId, body)), PacketDistributor.SERVER.noArg())
        }
    }
}