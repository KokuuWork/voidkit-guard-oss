package org.voidkit.guard.platform.forge

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import org.voidkit.guard.common.GuardWire

/**
 * guard 的 Forge 1.21.1 载荷实现。
 *
 * **这是整个服务端唯一碰 MC 网络协议的文件**（连同 [ForgeServerChannel]）。
 *
 * 单信封：只注册一个 `voidkit_guard:wire`，消息 id 在 body 外层。
 *
 * ## ⚠️ 为什么没有 StreamCodec，而用 encoder/decoder 静态方法
 *
 * NeoForge 那边用 `playBidirectional(TYPE, STREAM_CODEC)`，所以要手写
 * `StreamCodec`。Forge 的 `SimpleChannel` 走的是更老的
 * `messageBuilder(...).encoder{}.decoder{}` 风格 —— 直接对
 * [FriendlyByteBuf] 读写，不经过 StreamCodec，少一层抽象。
 *
 * 序列化格式与 NeoForge 线**逐字节一致**（writeUtf + writeByteArray），
 * 所以同一个服务端在 NeoForge / Forge 下都能与客户端 Agent 通信。
 */
class ForgeWirePayload(val wire: GuardWire) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<ForgeWirePayload> = TYPE

    companion object {
        val CHANNEL_ID: ResourceLocation =
            ResourceLocation.fromNamespaceAndPath("voidkit_guard", "wire")

        /** 频道协议版本；两端必须一致，不一致则拒绝连接。 */
        const val PROTOCOL_VERSION = 1

        val TYPE: CustomPacketPayload.Type<ForgeWirePayload> =
            CustomPacketPayload.Type(CHANNEL_ID)

        // ---- messageBuilder 用的 encoder/decoder ----
        // 写成 companion 的 @JvmStatic 方法：传给 Java 的 BiConsumer/Function
        // 需要确定的签名，静态方法比 lambda 少一层类型推断风险。

        @JvmStatic
        fun encode(msg: ForgeWirePayload, buf: FriendlyByteBuf) {
            buf.writeUtf(msg.wire.id)
            buf.writeByteArray(msg.wire.body)
        }

        @JvmStatic
        fun decode(buf: FriendlyByteBuf): ForgeWirePayload {
            val id = buf.readUtf()
            val body = buf.readByteArray()
            return ForgeWirePayload(GuardWire(id, body))
        }
    }
}