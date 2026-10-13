package org.voidkit.guard.platform.neoforge

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import org.voidkit.guard.common.GuardWire

/**
 * guard 的 NeoForge 载荷实现。
 *
 * **这是整个服务端唯一碰 MC 网络协议的文件**（连同 [NeoforgeChannel]）。
 * 换加载器（Forge/Fabric）时重写这两个文件即可，业务层零改动。
 *
 * 单信封：只注册一个 `voidkit_guard:wire`，消息 id 在 body 外层。
 * 这样加新消息类型只是 common 层加个 id + 编解码函数，不动这里。
 */
class GuardWirePayload(val wire: GuardWire) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<GuardWirePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<GuardWirePayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("voidkit_guard", "wire"))

        /**
         * 手写 codec 而不用 `StreamCodec.composite`：字段少且固定，手写更直白，
         * 也不用管 composite 对各字段数量的编译期展开。长度前缀由 codec 保证，
         * 业务层的 [org.voidkit.guard.common.ByteReader] 再校验一遍（双重防畸形包）。
         */
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
