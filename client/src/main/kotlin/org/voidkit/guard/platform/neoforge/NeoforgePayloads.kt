package org.voidkit.guard.platform.neoforge

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import org.voidkit.guard.common.GuardWire

/**
 * guard 的 NeoForge 载荷实现（**客户端侧**）。
 *
 * ⚠️ 与服务端 jar 里的同名类**必须保持一致**（ResourceLocation + codec）——
 * 两个 jar 各自注册自己的副本，靠相同的 `voidkit_guard:wire` 标识通信。
 * 改协议时两边同步改（本文件是两侧各一份，不在共享模块里，因为它是加载器代码）。
 */
class GuardWirePayload(val wire: GuardWire) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<GuardWirePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<GuardWirePayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("voidkit_guard", "wire"))

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, GuardWirePayload> =
            object : StreamCodec<RegistryFriendlyByteBuf, GuardWirePayload> {
                override fun encode(buf: RegistryFriendlyByteBuf, value: GuardWirePayload) {
                    buf.writeUtf(value.wire.id)
                    buf.writeByteArray(value.wire.body)
                }

                override fun decode(buf: RegistryFriendlyByteBuf): GuardWirePayload {
                    val id = buf.readUtf()
                    val body = buf.readByteArray()
                    return GuardWirePayload(GuardWire(id, body))
                }
            }
    }
}
