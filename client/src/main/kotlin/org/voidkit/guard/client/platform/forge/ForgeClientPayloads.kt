package org.voidkit.guard.client.platform.forge

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import org.voidkit.guard.client.common.GuardWire

/**
 * guard 的 Forge 1.21.1 载荷实现（**客户端侧**）。
 *
 * ⚠️ 与服务端 jar 里的 `ForgeWirePayload` **必须字节级一致**（同样
 * writeUtf + writeByteArray、同样 CHANNEL_ID、同样 PROTOCOL_VERSION）
 * —— 两个 jar 各自注册自己的副本，靠 `voidkit_guard:wire` 通信。
 * 本文件用**重定位后**的 common 包（`client.common`），服务端是 `guard.common`
 * —— 序列化格式相同，包名不同（JPMS split package 修复）。
 */
class ForgeClientWirePayload(val wire: GuardWire) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<ForgeClientWirePayload> = TYPE

    companion object {
        val CHANNEL_ID: ResourceLocation =
            ResourceLocation.fromNamespaceAndPath("voidkit_guard", "wire")

        const val PROTOCOL_VERSION = 1

        val TYPE: CustomPacketPayload.Type<ForgeClientWirePayload> =
            CustomPacketPayload.Type(CHANNEL_ID)

        @JvmStatic
        fun encode(msg: ForgeClientWirePayload, buf: FriendlyByteBuf) {
            buf.writeUtf(msg.wire.id)
            buf.writeByteArray(msg.wire.body)
        }

        @JvmStatic
        fun decode(buf: FriendlyByteBuf): ForgeClientWirePayload {
            val id = buf.readUtf()
            val body = buf.readByteArray()
            return ForgeClientWirePayload(GuardWire(id, body))
        }
    }
}