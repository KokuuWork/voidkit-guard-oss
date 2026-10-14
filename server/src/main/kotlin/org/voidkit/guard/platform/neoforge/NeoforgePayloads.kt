package org.voidkit.guard.platform.neoforge

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import org.voidkit.guard.common.GuardWire

/**
 * guard 的 Forge 1.20.1 载荷实现（**服务端侧**）。
 *
 * **这是整个服务端唯一碰 MC 网络协议的文件**（连同 [NeoforgeServerChannel]）。
 * 换加载器/换版本线时重写这两个文件即可，业务层零改动。
 *
 * 单信封：只注册一个 `voidkit_guard:wire` 频道，消息 id 在 body 外层。
 * 这样加新消息类型只是 common 层加个 id + 编解码函数，不动这里。
 *
 * Forge 1.20.1 没有 NeoForge 1.21.1 的 `CustomPacketPayload`/`StreamCodec` 那套
 * 载荷注册机制，用经典的 `SimpleChannel` + 手写 encoder/decoder：
 * 频道里按方向注册两条消息（index 0 = C→S，index 1 = S→C），两侧各自注册两份。
 *
 * ⚠️ `ResourceLocation` 在 1.20.1 只有双参/单参构造器，没有 1.21 的
 *    `fromNamespaceAndPath` 工厂 —— 直接用 `ResourceLocation("voidkit_guard", "wire")`。
 */
class GuardWirePayload(val wire: GuardWire) {

    companion object {
        /** 频道标识。与服务端/客户端硬编码一致（与 modId 解耦）。 */
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
