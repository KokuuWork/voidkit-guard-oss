package org.voidkit.guard.client.platform.neoforge

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import org.voidkit.guard.client.common.GuardWire

/**
 * guard 的 Forge 1.20.1 载荷实现（**客户端侧**）。
 *
 * ⚠️ 与服务端 jar 里的同名类**必须保持一致**（频道 id + 编解码）——
 * 两个 jar 各自注册自己的副本，靠相同的 `voidkit_guard:wire` 标识通信。
 * 改协议时两边同步改（本文件是两侧各一份，不在共享模块里，因为它是加载器代码）。
 *
 * Forge 1.20.1 没有 NeoForge 1.21.1 的 `CustomPacketPayload`/`StreamCodec`，
 * 用经典 `SimpleChannel` + 手写 encoder/decoder，按方向注册两条消息。
 */
class GuardWirePayload(val wire: GuardWire) {

    companion object {
        /** 频道标识。与服务端硬编码一致（与 modId 解耦）。 */
        val CHANNEL_ID: ResourceLocation = ResourceLocation("voidkit_guard", "wire")

        /** 协议版本号（登录握手时两侧核对，不匹配拒绝连接）。 */
        const val PROTOCOL_VERSION: String = "1"

        fun encode(msg: GuardWirePayload, buf: FriendlyByteBuf) {
            buf.writeUtf(msg.wire.id)
            buf.writeByteArray(msg.wire.body)
        }

        fun decode(buf: FriendlyByteBuf): GuardWirePayload =
            GuardWirePayload(GuardWire(buf.readUtf(), buf.readByteArray()))
    }
}
