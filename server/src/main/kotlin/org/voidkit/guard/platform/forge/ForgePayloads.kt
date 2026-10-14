package org.voidkit.guard.platform.forge

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import org.voidkit.guard.common.GuardWire

/**
 * guard 的 Forge 1.21.1 载荷实现。
 *
 * **这是整个服务端唯一碰 MC 网络协议的文件**（连同 [ForgeServerChannel]）。
 *
 * 与 NeoForge 版的 `GuardWirePayload`逐行相同 —— 1.21.1 的载荷 API 属原版
 * （`CustomPacketPayload` / `StreamCodec` / `ResourceLocation`，Mojang 官方映射），
 * 差异只在「谁来注册它」：NeoForge 用 registrar DSL，
 * Forge 52 用 `SimpleChannel.messageBuilder`（见 [ForgeServerEntry]）。
 *
 * 单信封：只注册一个 `voidkit_guard:wire`，消息 id 在 body 外层。
 */
class ForgeWirePayload(val wire: GuardWire) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<ForgeWirePayload> = TYPE

    companion object {
        val CHANNEL_ID: ResourceLocation =
            ResourceLocation.fromNamespaceAndPath("voidkit_guard", "wire")

        const val PROTOCOL_VERSION = 1

        val TYPE: CustomPacketPayload.Type<ForgeWirePayload> =
            CustomPacketPayload.Type(CHANNEL_ID)

        /** 手写 codec 而不用 `StreamCodec.composite`：字段少且固定，手写更直白。 */
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ForgeWirePayload> =
            object : StreamCodec<RegistryFriendlyByteBuf, ForgeWirePayload> {
                override fun encode(buf: RegistryFriendlyByteBuf, value: ForgeWirePayload) {
                    buf.writeUtf(value.wire.id)
                    buf.writeByteArray(value.wire.body)
                }

                override fun decode(buf: RegistryFriendlyByteBuf): ForgeWirePayload {
                    val id = buf.readUtf()
                    val body = buf.readByteArray()
                    return ForgeWirePayload(GuardWire(id, body))
                }
            }

        // ---- 供 messageBuilder 的 encoder/decoder 使用的静态版 ----
        // （Kotlin 传给 Java 的 Function/BiConsumer 需要 SAM 转换，
        //   写成静态方法比 lambda 更直白，也避免类型推断踩坑。）

        @JvmStatic
        fun encode(msg: ForgeWirePayload, buf: RegistryFriendlyByteBuf) {
            buf.writeUtf(msg.wire.id)
            buf.writeByteArray(msg.wire.body)
        }

        @JvmStatic
        fun decode(buf: RegistryFriendlyByteBuf): ForgeWirePayload {
            val id = buf.readUtf()
            val body = buf.readByteArray()
            return ForgeWirePayload(GuardWire(id, body))
        }
    }
}