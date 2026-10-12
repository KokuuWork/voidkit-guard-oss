package org.voidkit.guard.client

import net.minecraft.client.Minecraft
import org.voidkit.guard.common.Auth
import org.voidkit.guard.common.Challenge
import org.voidkit.guard.common.ClientReport
import org.voidkit.guard.common.DeepScanReq
import org.voidkit.guard.common.GCrypto
import org.voidkit.guard.common.GuardMsg
import org.voidkit.guard.GuardBootstrap
import org.voidkit.guard.common.GuardWire
import org.voidkit.guard.common.HeapGuard
import org.voidkit.guard.common.Keepalive
import org.voidkit.guard.common.Result
import org.voidkit.guard.common.ScreenChunk
import org.voidkit.guard.common.ScreenReq
import org.voidkit.guard.common.SecretBox
import org.voidkit.guard.client.capture.ScreenCapturer
import org.voidkit.guard.client.hw.EnvCollector
import org.voidkit.guard.client.hw.Hardware
import org.voidkit.guard.client.scan.ModScanner
import org.voidkit.guard.client.scan.SelfIntegrity
import java.util.UUID

/**
 * 客户端 Agent 本体。
 *
 * ## 行为准则：静默
 *
 * - 全程不弹提示、不发 info 日志（debug 级才输出，排查用）。
 * - 唯一的玩家可见输出是**首次连接披露**（采集事项说明，服务端可关 ——
 *   关了的话服务器规则里必须写明，这是告知义务，弹窗可静默、告知不能没有）。
 * - 被踢时玩家只看到服务端给的那句话，Agent 自己不多嘴。
 *
 * ## 线程模型
 *
 * - 载荷到达（网络线程）→ 握手响应扔**后台线程**（哈希几百个 jar 要几秒，
 *   不能堵网络线程）；截屏/GPU 读取需要 GL 或主线程 → 扔 MC 主线程。
 * - 会话密钥只以 [SecretBox] 混淆形态驻留堆内存。
 */
object ClientAgent {

    const val AGENT_VERSION = "voidkit_guard/1.0.0"

    private var keyBox: SecretBox? = null
    private var lastSignatures: List<String> = emptyList()

    // ---------------------------------------------------------------- 握手

    fun onChallenge(c: Challenge) {
        if (c.proto != org.voidkit.guard.common.GuardMsg.PROTOCOL) {
            // 版本不匹配：服务端会因收不到合规 auth 而踢，这里只记 debug
            log("协议版本不匹配：客户端 ${GuardMsg.PROTOCOL} / 服务端 ${c.proto}")
            return
        }
        lastSignatures = c.signatures
        if (c.notice) GuardClient.showNotice()

        // 重连：换新会话密钥（复检走同一通道，keyBox 保留复用）
        if (keyBox == null) keyBox = SecretBox(GCrypto.newAesKey())

        Thread({ buildAndSendAuth(c) }, "voidkit-guard-agent").start()
    }

    private fun buildAndSendAuth(c: Challenge) {
        runCatching {
            val mc = Minecraft.getInstance()
            val player = mc.player

            val mods = ModScanner.collect(forceFull = c.fullRescan)
            val fp = Hardware.collect(c.hashSalt)
            val env = EnvCollector.collect()
            val signals = ModScanner.signals(mods, c.signatures) + SelfIntegrity.envSignals()
            val self = SelfIntegrity.check()

            val report = ClientReport(
                proto = GuardMsg.PROTOCOL,
                nonce = GCrypto.sha256Hex(c.nonce),
                agent = AGENT_VERSION,
                player = player?.gameProfile?.name ?: "unknown",
                uuid = player?.getStringUUID() ?: "",
                clientBrand = "neoforge",
                mods = mods,
                fingerprint = fp,
                signals = signals,
                selfIntegrity = self,
                env = env,
            )

            val json = com.google.gson.Gson().toJson(report)
            val box = keyBox ?: return
            val pub = GCrypto.decodePublic(c.rsaPub)
            val wrapped = box.use { k -> GCrypto.wrapKey(k, pub) }
            val sealed = box.use { k -> GCrypto.seal(k, c.nonce, json.toByteArray(Charsets.UTF_8)) }

            val auth = Auth(
                proto = GuardMsg.PROTOCOL,
                nonce = c.nonce,
                wrappedKey = wrapped,
                report = sealed,
            )
            send(GuardMsg.AUTH, auth.encode())
            log("已回送校验报告（${mods.size} 个 mod，${signals.size} 个信号${if (c.fullRescan) "，全量重扫" else ""}）")
        }.onFailure { log("构建报告失败：${it.message}") }
    }

    fun onResult(r: Result) {
        // 静默：只记 debug。失败时服务端紧接着就会踢，玩家看到的是踢人话术
        log("服务端结论：ok=${r.ok} code=${r.code}")
    }

    // ---------------------------------------------------------------- 保活

    fun onKeepalive(body: ByteArray) {
        val box = keyBox ?: return
        val plain = box.use { k ->
            GCrypto.open(k, aad(GuardMsg.KEEPALIVE), body)
        } ?: return
        val k = runCatching { Keepalive.decode(plain) }.getOrNull()
        HeapGuard.wipe(plain)
        if (k == null) return
        val reply = box.use { key ->
            GCrypto.seal(key, aad(GuardMsg.KEEPALIVE_ACK), Keepalive(k.nonce, k.ts).encode())
        }
        send(GuardMsg.KEEPALIVE_ACK, reply)
    }

    // ---------------------------------------------------------------- 截屏

    fun onScreenReq(body: ByteArray) {
        val box = keyBox ?: return
        val plain = box.use { k -> GCrypto.open(k, aad(GuardMsg.SCREEN_REQ), body) } ?: return
        val req = runCatching { ScreenReq.decode(plain) }.getOrNull()
        HeapGuard.wipe(plain)
        if (req == null) return

        // 抓屏要走 MC 主线程（窗口/屏幕状态），切片和发送之后在主线程做也安全
        Minecraft.getInstance().execute {
            runCatching {
                val png = ScreenCapturer.capture(req.maxW, req.maxH)
                if (png == null) {
                    log("截屏失败：拿不到画面")
                    return@runCatching
                }
                val chunkSize = req.chunkSize.coerceIn(4096, 262144)
                val total = (png.size + chunkSize - 1) / chunkSize
                for (i in 0 until total) {
                    val from = i * chunkSize
                    val to = minOf(from + chunkSize, png.size)
                    val chunk = ScreenChunk(req.reqId, i, total, png.copyOfRange(from, to))
                    val sealed = box.use { key ->
                        GCrypto.seal(key, aad(GuardMsg.SCREEN_CHUNK), chunk.encode())
                    }
                    send(GuardMsg.SCREEN_CHUNK, sealed)
                }
                HeapGuard.wipe(png)
                log("截屏已回送：$total 片")
            }.onFailure { log("截屏回送失败：${it.message}") }
        }
    }

    // ---------------------------------------------------------------- 深度扫描

    fun onDeepScanReq(body: ByteArray) {
        val box = keyBox ?: return
        val plain = box.use { k -> GCrypto.open(k, aad(GuardMsg.DEEP_SCAN_REQ), body) } ?: return
        val req = runCatching { DeepScanReq.decode(plain) }.getOrNull()
        HeapGuard.wipe(plain)
        if (req == null) return

        Thread({
            runCatching {
                val result = DeepScanner.scan(req, lastSignatures)
                val sealed = box.use { key ->
                    GCrypto.seal(key, aad(GuardMsg.DEEP_SCAN_RESULT), result.encode())
                }
                send(GuardMsg.DEEP_SCAN_RESULT, sealed)
                log("深度扫描已回送：${result.folders.size} 项，${result.signals.size} 个信号")
            }.onFailure { log("深度扫描失败：${it.message}") }
        }, "voidkit-guard-deepscan").start()
    }

    // ---------------------------------------------------------------- 工具

    private fun aad(msgId: String): ByteArray = msgId.toByteArray(Charsets.UTF_8)

    private fun send(id: String, body: ByteArray) {
        runCatching {
            GuardBootstrap.channel?.sendToServer(id, body)
        }.onFailure { log("发送 $id 失败：${it.message}") }
    }

    private fun log(msg: String) {
        // 静默原则：只有 debug 级。出问题时服主让玩家开 debug 日志即可排查
        GuardBootstrap.LOG.debug("[guard-client] {}", msg)
    }

    @Suppress("unused")
    private fun unusedUuidRef(): UUID? = null
}
