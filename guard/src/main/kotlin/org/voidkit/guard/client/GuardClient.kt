package org.voidkit.guard.client

import net.minecraft.client.Minecraft
import net.neoforged.fml.loading.FMLPaths
import org.voidkit.guard.common.GuardMsg
import org.voidkit.guard.common.GuardWire
import java.io.File

/**
 * 客户端入口：接收入站报文 + 首次连接披露。
 *
 * 本对象在专用服务端上永远不会被触发（playToClient 处理器只在客户端跑），
 * 所以放这里的东西全部是客户端专属，服务端 classpath 里带着也不执行。
 */
object GuardClient {

    /** 由 @Mod 的载荷处理器调用（可能来自网络线程）。 */
    fun handleIncoming(wire: GuardWire) {
        when (wire.id) {
            GuardMsg.CHALLENGE -> {
                // 挑战是签名信封：先验签再解内容。验不过 = 对面不是真服务端
                // （或被中间代理改过）—— 不回应，让它收不到 auth 自己去超时。
                val signed = org.voidkit.guard.common.SignedChallenge.decode(wire.body)
                if (!GuardKeys.verify(signed.challenge, signed.signature)) {
                    org.voidkit.guard.VoidkitGuard.LOG.debug("[guard-client] 挑战签名验证失败，已忽略")
                    return
                }
                ClientAgent.onChallenge(
                    org.voidkit.guard.common.Challenge.decode(signed.challenge)
                )
            }
            GuardMsg.RESULT -> ClientAgent.onResult(
                org.voidkit.guard.common.Result.decode(wire.body)
            )
            GuardMsg.KEEPALIVE -> ClientAgent.onKeepalive(wire.body)
            GuardMsg.SCREEN_REQ -> ClientAgent.onScreenReq(wire.body)
            GuardMsg.DEEP_SCAN_REQ -> ClientAgent.onDeepScanReq(wire.body)
        }
    }

    /**
     * 首次连接披露（每个服务器只弹一次）。
     *
     * 这是合规底线的一环：**告知义务**。弹窗可以关（服务端配置
     * `notice.enabled=false`），但服务器规则/公告里必须写明采集事项 ——
     * 个人信息处理不能零告知。文案刻意只说明「采什么、为什么」，
     * 不透露检测细节（那是服务端的秘密）。
     */
    fun showNotice() {
        val server = currentServerKey() ?: return
        val marker = markerFile(server)
        if (marker.isFile) return
        runCatching { marker.parentFile?.mkdirs(); marker.writeText("1") }

        val text = net.minecraft.network.chat.Component.literal("")
            .append(net.minecraft.network.chat.Component.literal("[安全校验] ").withStyle(net.minecraft.ChatFormatting.DARK_AQUA))
            .append(
                net.minecraft.network.chat.Component.literal(
                    "本服务器启用了反作弊组件：将采集硬件指纹（机器码/网卡，仅加密哈希）" +
                        "与模组清单用于封禁作弊者；管理员可对你的客户端截屏取证。继续游戏视为知悉。"
                ).withStyle(net.minecraft.ChatFormatting.GRAY)
            )
        Minecraft.getInstance().execute {
            Minecraft.getInstance().player?.sendSystemMessage(text)
        }
    }

    private fun currentServerKey(): String? = runCatching {
        val mc = Minecraft.getInstance()
        val info = mc.currentServer ?: return null
        info.ip
    }.getOrNull()

    private fun markerFile(server: String): File {
        val hash = Integer.toHexString(server.hashCode())
        return FMLPaths.GAMEDIR.get().resolve("voidkit_guard/noticed-$hash.txt").toFile()
    }
}
