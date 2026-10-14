package org.voidkit.guard.client.platform.forge

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import org.voidkit.guard.client.common.GuardWire

/**
 * guard 的 Forge 1.21.1 载荷实现（**客户端侧**）。
 *
 * ⚠️ 与服务端 jar 里的 `ForgeWirePayload` **必须保持一致**（ResourceLocation +
 * codec + PROTOCOL_VERSION）—— 两个 jar 各自注册自己的副本，靠相同的
 * `voidkit_guard:wire` 标识通信。
 * 本文件用的是**重定位后**的 common 包（`client.common`），服务端那份是
 * `guard.common` —— 序列化格式相同，包名不同（JPMS split package 修复）。
 */
class ForgeClientWirePayload(val wire: GuardWire) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<ForgeClientWirePayload> = TYPE

    companion object {
        val CHANNEL_ID: ResourceLocation =
            ResourceLocation.fromNamespaceAndPath("voidkit_guard", "wire")

        const val PROTOCOL_VERSION = 1

        val TYPE: CustomPacketPayload.Type<ForgeClientWirePayload> =
            CustomPacketPayload.Type(CHANNEL_ID)

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ForgeClientWirePayload> =
            object : StreamCodec<RegistryFriendlyByteBuf, ForgeClientWirePayload> {
                override fun encode(buf: RegistryFriendlyByteBuf, value: ForgeClientWirePayload) {
                    buf.writeUtf(value.wire.id)
                    buf.writeByteArray(value.wire.body)
                }

                override fun decode(buf: RegistryFriendlyByteBuf): ForgeClientWirePayload {
                    val id = buf.readUtf()
                    val body = buf.readByteArray()
                    return ForgeClientWirePayload(GuardWire(id, body))
                }
            }

        @JvmStatic
        fun encode(msg: ForgeClientWirePayload, buf: RegistryFriendlyByteBuf) {
            buf.writeUtf(msg.wire.id)
            buf.writeByteArray(msg.wire.body)
        }

        @JvmStatic
        fun decode(buf: RegistryFriendlyByteBuf): ForgeClientWirePayload {
            val id = buf.readUtf()
            val body = buf.readByteArray()
            return ForgeClientWirePayload(GuardWire(id, body))
        }
    }
}