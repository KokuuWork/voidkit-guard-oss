package org.voidkit.guard.server

import org.voidkit.guard.common.*
import org.voidkit.guard.platform.GPlayer
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * 服务端 RSA 密钥对（持久化到 `guard/server.pem`，私钥 + 公钥两段 PEM）。
 *
 * 握手时客户端要把 AES 会话密钥安全递过来，靠的就是这对钥匙。
 * 私钥**只在解包瞬间解码**（[usePrivate]），平时堆里只有 [SecretBox]
 * 混淆副本 —— 内存扫描器搜不到 PEM/密钥字节。
 */
class ServerKeyPair(private val path: Path) {

    private var public: PublicKey? = null
    private var privateBox: SecretBox? = null

    fun load() {
        if (Files.isRegularFile(path)) {
            val text = Files.readString(path, Charsets.UTF_8)
            val priv = GCrypto.pemToPrivate(text)
            val pub = readPublicFromPem(text)
            if (priv != null && pub != null) {
                privateBox = SecretBox(GCrypto.encodePrivate(priv))
                public = pub
                return
            }
        }
        val kp = GCrypto.newRsaKeyPair()
        privateBox = SecretBox(GCrypto.encodePrivate(kp.private))
        public = kp.public
        Files.createDirectories(path.parent)
        Files.writeString(path, GCrypto.privateToPem(kp.private) + GCrypto.publicToPem(kp.public), Charsets.UTF_8)
    }

    private fun readPublicFromPem(text: String): PublicKey? {
        val marker = "-----BEGIN PUBLIC KEY-----"
        val start = text.indexOf(marker)
        if (start < 0) return null
        val end = text.indexOf("-----END PUBLIC KEY-----", start)
        if (end < 0) return null
        val body = text.substring(start + marker.length, end).replace("\\s".toRegex(), "")
        return runCatching {
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(body)))
        }.getOrNull()
    }

    fun publicKey(): PublicKey = public ?: error("服务端公钥未加载")

    /** 给挑战签名（RSA/SHA256）。客户端用内置公钥环验签，防中间代理篡改挑战。 */
    fun sign(data: ByteArray): ByteArray {
        val box = privateBox ?: error("服务端私钥未加载")
        return box.use { encoded ->
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(encoded))
            val s = java.security.Signature.getInstance("SHA256withRSA")
            s.initSign(key)
            s.update(data)
            s.sign()
        }
    }

    fun <T> usePrivate(body: (PrivateKey) -> T): T {
        val box = privateBox ?: error("服务端私钥未加载")
        return box.use { encoded ->
            body(KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(encoded)))
        }
    }

    fun wipe() {
        privateBox?.wipe()
        privateBox = null
        public = null
    }
}

/**
 * 哈希锁白名单。条目 = 一个「已知干净」的 mod 文件（id + version + sha256）。
 *
 * `approved` 区分「服主点过头的」和「自动学来的」：
 * 后者默认放行（学习期），`hashlockRequireReview=true` 时拦着等审核。
 */
class WhitelistStore(private val path: Path) {

    data class Entry(
        val modId: String,
        val version: String,
        val sha256: String,
        val note: String = "",
        val approved: Boolean = false,
        val addedAt: Long = System.currentTimeMillis(),
    )

    @Volatile
    var entries: List<Entry> = emptyList()
        private set

    fun load() {
        if (!Files.isRegularFile(path)) {
            entries = emptyList()
            return
        }
        entries = runCatching {
            val text = Files.readString(path, Charsets.UTF_8)
            val arr = com.google.gson.JsonParser.parseString(text).takeIf { it.isJsonArray }?.asJsonArray
            arr?.map { el ->
                val o = el.asJsonObject
                Entry(
                    modId = o.get("modId")?.asString ?: "",
                    version = o.get("version")?.asString ?: "",
                    sha256 = o.get("sha256")?.asString ?: "",
                    note = o.get("note")?.asString ?: "",
                    approved = o.get("approved")?.asBoolean ?: false,
                    addedAt = o.get("addedAt")?.asLong ?: System.currentTimeMillis(),
                )
            } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    fun save() {
        Files.createDirectories(path.parent)
        val arr = com.google.gson.JsonArray()
        for (e in entries) {
            arr.add(
                com.google.gson.JsonObject().apply {
                    addProperty("modId", e.modId)
                    addProperty("version", e.version)
                    addProperty("sha256", e.sha256)
                    addProperty("note", e.note)
                    addProperty("approved", e.approved)
                    addProperty("addedAt", e.addedAt)
                }
            )
        }
        Files.writeString(path, arr.toString(), Charsets.UTF_8)
    }

    /** 把报告里没见过的 mod 学进来（待审）。返回新学到的条目数。 */
    fun learn(report: ClientReport): Int {
        val known = entries.map { it.sha256 }.toHashSet()
        var added = 0
        for (m in report.mods) {
            val h = m.sha256 ?: continue
            if (h in known) continue
            entries = entries + Entry(m.id, m.version, h, note = "auto-learned")
            added++
        }
        if (added > 0) save()
        return added
    }

    fun approve(modId: String): Int {
        var n = 0
        entries = entries.map {
            if (it.modId.equals(modId, ignoreCase = true) && !it.approved) {
                n++
                it.copy(approved = true)
            } else it
        }
        if (n > 0) save()
        return n
    }

    fun approveAll(): Int {
        var n = 0
        entries = entries.map { if (!it.approved) { n++; it.copy(approved = true) } else it }
        if (n > 0) save()
        return n
    }

    fun remove(modId: String): Int {
        val before = entries.size
        entries = entries.filterNot { it.modId.equals(modId, ignoreCase = true) }
        val n = before - entries.size
        if (n > 0) save()
        return n
    }

    fun add(modId: String, version: String, sha256: String, note: String = "", approved: Boolean = true): Boolean {
        if (entries.any { it.sha256 == sha256 }) return false
        entries = entries + Entry(modId, version, sha256, note, approved)
        save()
        return true
    }

    fun pendingCount(): Int = entries.count { !it.approved }

    /** 导入客户端导出的清单 JSON（[{modId, version, sha256, file}]），全部按待审导入。 */
    fun importJson(json: String): Int {
        val arr = com.google.gson.JsonParser.parseString(json).takeIf { it.isJsonArray }?.asJsonArray ?: return 0
        var n = 0
        for (el in arr) {
            val o = el.asJsonObject
            val id = o.get("modId")?.asString ?: continue
            val h = o.get("sha256")?.asString ?: continue
            if (add(id, o.get("version")?.asString ?: "", h, note = "imported", approved = false)) n++
        }
        return n
    }

    /** 导出清单 JSON（供别的服主/备份用）。 */
    fun exportJson(): String {
        val arr = com.google.gson.JsonArray()
        for (e in entries) {
            arr.add(
                com.google.gson.JsonObject().apply {
                    addProperty("modId", e.modId)
                    addProperty("version", e.version)
                    addProperty("sha256", e.sha256)
                    addProperty("note", e.note)
                    addProperty("approved", e.approved)
                    addProperty("addedAt", e.addedAt)
                }
            )
        }
        return arr.toString()
    }

    /** 比对一份客户端清单。 */
    fun match(report: ClientReport): Verdict {
        val byHash = entries.associateBy { it.sha256 }
        val manifest = report.mods.mapNotNull { it.sha256 }.toHashSet()
        val unknown = report.mods.filter { (it.sha256 ?: "") !in byHash }
        val unapproved = report.mods.filter { byHash[it.sha256]?.approved == false }
        val missing = entries.filter { it.approved && it.sha256 !in manifest }
        return Verdict(unknown, unapproved, missing)
    }

    class Verdict(
        /** 报告里有、白名单里完全没有的 mod。 */
        val unknown: List<ModEntry>,
        /** 在白名单里但还没批准的 mod。 */
        val unapproved: List<ModEntry>,
        /** 批准过、但这次报告里没出现的 mod（被下了/被换了）。 */
        val missing: List<Entry>,
    ) {
        fun clean(): Boolean = unknown.isEmpty() && unapproved.isEmpty() && missing.isEmpty()
    }
}

/**
 * 会话状态机 + 握手/校验核心。
 *
 * ## 一个玩家的 guard 生命周期
 *
 * ```
 * 进服 ──▶ CHALLENGE_SENT ──(auth 到达, 校验通过)──▶ VERIFIED ──▶ 断线/关服 ──▶ CLOSED
 *              │                                          │
 *              ├─(超时未应答)─▶ FAILED(kick/ban)          ├─(保活超时)─▶ FAILED
 *              └─(校验失败)────▶ FAILED(kick/ban)
 * ```
 *
 * 全程服务端主线程执行（channel 消息进来先过 `server.execute`）——
 * 不搞并发状态机，少一类竞态 bug。
 *
 * ## 防重放设计
 *
 * - auth 回显 nonce：旧连接的 auth 在新连接里解不开（nonce 每次随机）。
 * - keepalive：服务端每轮换 nonce，ack 必须回显当前 nonce。
 * - screen_chunk：AAD 绑 reqId + index，旧请求的分片重组不了新图。
 */
class GuardSessions(private val hub: GuardHub) {

    enum class State { CHALLENGE_SENT, VERIFIED, FAILED, CLOSED }

    class Session(val player: GPlayer) {
        var state: State = State.CHALLENGE_SENT
        val nonce: ByteArray = GCrypto.randomBytes(16)
        var keyBox: SecretBox? = null
        var report: ClientReport? = null
        var failCount: Int = 0
        var deadline: Long = 0L
        var lastKeepaliveSent: Long = 0L
        var lastKeepaliveNonce: ByteArray? = null
        var lastKeepaliveAck: Long = 0L
        var verifiedAt: Long = 0L
        /** 下次中途复检的时间戳（0 = 未排期）。复检 = 换新 nonce 重新走一遍完整握手。 */
        var nextRechallengeAt: Long = 0L

        fun wipe() {
            keyBox?.wipe()
            keyBox = null
            HeapGuard.wipe(nonce)
            HeapGuard.wipe(lastKeepaliveNonce)
            lastKeepaliveNonce = null
        }
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    fun session(uuid: String): Session? = sessions[uuid]

    fun all(): List<Session> = sessions.values.toList()

    // ---------- 生命周期 ----------

    fun onJoin(player: GPlayer) {
        if (!hub.config.enabled) return
        // 同名快速重进的旧会话先清掉
        sessions.remove(player.uuid)?.wipe()
        val s = Session(player)
        sessions[player.uuid] = s
        scheduleRechallenge(s)
        sendChallenge(s)
        hub.audit("guard_join", actor = player.name, target = player.uuid, ip = player.ip)
    }

    fun onQuit(player: GPlayer) {
        val s = sessions.remove(player.uuid) ?: return
        s.state = State.CLOSED
        if (s.report != null) {
            hub.audit("guard_quit", actor = player.name, target = player.uuid, ip = player.ip)
        }
        s.wipe()
    }

    // ---------- 握手 ----------

    private fun sendChallenge(s: Session, fullRescan: Boolean = false) {
        val cfg = hub.config
        val challenge = Challenge(
            proto = GuardMsg.PROTOCOL,
            nonce = s.nonce,
            rsaPub = GCrypto.encodePublic(hub.keys.publicKey()),
            mode = cfg.integrityModeEnum().id,
            requireFingerprint = cfg.requireFingerprint,
            deadlineMs = cfg.integrityGraceSeconds * 1000,
            keepaliveSeconds = if (cfg.keepaliveEnabled) cfg.keepaliveIntervalSeconds else 0,
            signatures = CheatSignatures.all(),
            hashSalt = cfg.effectiveSalt(),
            notice = cfg.noticeEnabled,
            fullRescan = fullRescan || cfg.rechallengeFullRescan,
        )
        // 签名信封：客户端验不过就不回应（中间代理改特征库的念头断在这里）
        val body = SignedChallenge(challenge.encode(), hub.keys.sign(challenge.encode())).encode()
        hub.channel.send(s.player, GuardMsg.CHALLENGE, body)
        s.deadline = System.currentTimeMillis() + cfg.integrityGraceSeconds * 1000L
        val now = System.currentTimeMillis()
        s.lastKeepaliveAck = now
        s.lastKeepaliveSent = now
    }

    /** 收到客户端报文（已在主线程）。 */
    fun onMessage(player: GPlayer, msgId: String, body: ByteArray) {
        val s = sessions[player.uuid] ?: return
        when (msgId) {
            GuardMsg.AUTH -> handleAuth(s, body)
            // 会话期消息：整条 body 先用会话密钥解开（认证失败 = 篡改），再解码业务字段
            GuardMsg.KEEPALIVE_ACK, GuardMsg.SCREEN_CHUNK, GuardMsg.DEEP_SCAN_RESULT -> {
                val plain = open(s, msgId, body) ?: run {
                    hub.logWarn("guard: ${player.name} 的 $msgId 解密失败（疑被篡改）")
                    return
                }
                when (msgId) {
                    GuardMsg.KEEPALIVE_ACK -> handleKeepaliveAck(s, plain)
                    GuardMsg.SCREEN_CHUNK -> hub.screenshots.handleChunk(s, ScreenChunk.decode(plain))
                    GuardMsg.DEEP_SCAN_RESULT -> hub.deep.handleResult(s, DeepScanResult.decode(plain))
                }
            }
            else -> hub.logWarn("guard: 未知消息 $msgId 来自 ${player.name}")
        }
    }

    private fun handleAuth(s: Session, body: ByteArray) {
        if (s.state != State.CHALLENGE_SENT) {
            hub.logWarn("guard: ${s.player.name} 重复提交 auth，忽略")
            return
        }
        val auth = runCatching { Auth.decode(body) }.getOrNull()
        if (auth == null) {
            fail(s, "应答报文无法解析", "bad_auth")
            return
        }
        if (auth.proto != GuardMsg.PROTOCOL) {
            fail(s, "协议版本不匹配（客户端 v${auth.proto} / 服务端 v${GuardMsg.PROTOCOL}），请更新反作弊模组", "proto_mismatch")
            return
        }
        if (!auth.nonce.contentEquals(s.nonce)) {
            fail(s, "握手校验失败（nonce 不匹配）", "nonce_mismatch")
            return
        }

        // 1) RSA 解包会话密钥
        val aesKey = hub.keys.usePrivate { priv -> GCrypto.unwrapKey(auth.wrappedKey, priv) }
        if (aesKey == null) {
            fail(s, "会话密钥解包失败", "key_unwrap")
            return
        }
        val box = SecretBox(aesKey)
        HeapGuard.wipe(aesKey)

        // 2) AES-GCM 解密报告（AAD = nonce：换一条连接的 nonce 解不开旧报告）
        val plain = box.use { k -> GCrypto.open(k, s.nonce, auth.report) }
        if (plain == null) {
            box.wipe()
            fail(s, "报告解密失败（可能被篡改）", "report_tampered")
            return
        }
        val report = runCatching {
            com.google.gson.Gson().fromJson(String(plain, Charsets.UTF_8), ClientReport::class.java)
        }.getOrNull()
        HeapGuard.wipe(plain)
        if (report == null || report.nonce != GCrypto.sha256Hex(s.nonce)) {
            box.wipe()
            fail(s, "报告内容无效", "report_invalid")
            return
        }

        s.keyBox = box
        s.report = report
        verify(s, report)
    }

    /** 校验报告 → 通过/失败。策略分歧全在这一个函数里。 */
    private fun verify(s: Session, report: ClientReport) {
        val cfg = hub.config

        // 0) 身份一致性：报告自报的身份要和连接方对得上（防拿别人报告顶包）
        if (!report.player.equals(s.player.name, ignoreCase = true) &&
            !report.uuid.equals(s.player.uuid, ignoreCase = true)
        ) {
            fail(s, "客户端报告与连接身份不一致", "identity_mismatch")
            return
        }

        // 1) 自完整性：客户端 Agent 自己被改过 → 直接失败
        if (!report.selfIntegrity.ok) {
            fail(s, "客户端反作弊组件完整性校验失败：${report.selfIntegrity.detail}", "self_tampered")
            return
        }

        // 2) 确证级信号（已知作弊 mod）
        val confirmed = report.signals.filter { it.severity >= 2 }
        if (confirmed.isNotEmpty()) {
            val names = confirmed.joinToString("、") { it.detail }
            fail(s, "检测到作弊软件：$names", "cheat_detected")
            return
        }

        // 3) 可疑级信号：只记录不拦（零误杀哲学；审计里留痕供服主判断）
        val suspicious = report.signals.filter { it.severity == 1 }
        if (suspicious.isNotEmpty()) {
            hub.audit(
                "guard_suspect", actor = s.player.name, target = s.player.uuid, ip = s.player.ip,
                detail = mapOf("signals" to suspicious.map { "${it.type}:${it.detail}" }),
            )
        }

        // 4) 指纹强制要求
        if (cfg.requireFingerprint && report.fingerprint == null) {
            fail(s, "无法采集硬件指纹，已被服务器拒绝", "no_fingerprint")
            return
        }

        // 5) 哈希锁比对
        if (cfg.integrityModeEnum() == IntegrityMode.HASH_LOCK) {
            val verdict = hub.whitelist.match(report)
            if (!verdict.clean()) {
                if (verdict.unknown.isEmpty() && verdict.missing.isEmpty() && verdict.unapproved.isNotEmpty()) {
                    // 全是「学来了但没批准」的新组合
                    if (cfg.hashlockRequireReview) {
                        fail(s, "客户端模组组合等待审核，请联系服主批准白名单", "whitelist_pending")
                        return
                    }
                } else {
                    val extra = (verdict.unknown.map { it.id } + verdict.missing.map { it.modId })
                        .distinct().joinToString("、")
                    fail(s, "客户端模组与服务器白名单不一致：$extra", "whitelist_mismatch")
                    return
                }
            }
            if (cfg.hashlockAutoLearn) hub.whitelist.learn(report)
        }

        // 6) 通过
        s.state = State.VERIFIED
        s.verifiedAt = System.currentTimeMillis()
        scheduleRechallenge(s)
        hub.channel.send(s.player, GuardMsg.RESULT, Result(true, "ok", "", "none").encode())
        hub.onVerified(s)
    }

    /** 排下一次中途复检（随机间隔，对手摸不到规律）。 */
    private fun scheduleRechallenge(s: Session) {
        val cfg = hub.config
        if (!cfg.rechallengeEnabled) {
            s.nextRechallengeAt = 0L
            return
        }
        val min = cfg.rechallengeMinSeconds.coerceAtLeast(30)
        val max = cfg.rechallengeMaxSeconds.coerceAtLeast(min)
        s.nextRechallengeAt = System.currentTimeMillis() + (min + kotlin.random.Random.nextInt(max - min + 1)) * 1000L
    }

    /**
     * 失败处置：静默连拍取证 → 按策略踢/封 → 审计 + 只把真实原因给管理员。
     *
     * 玩家侧永远只看到一句「环境校验未通过」—— 不透露检测到了什么、
     * 甚至连"我们检测了"都不说破。真实原因进审计和在线管理员私信。
     */
    fun fail(s: Session, reason: String, code: String) {
        if (s.state == State.FAILED || s.state == State.CLOSED) return
        s.state = State.FAILED
        s.failCount++

        val cfg = hub.config
        val ban = cfg.failActionEnum() == FailAction.KICK_AND_BAN ||
            (cfg.integrityMaxFailsBeforeBan in 1..s.failCount)

        hub.audit(
            "guard_fail", actor = s.player.name, target = s.player.uuid, ip = s.player.ip,
            detail = mapOf(
                "code" to code, "reason" to reason, "fails" to s.failCount,
                "ban" to ban, "mode" to cfg.integrityMode,
            ),
        )

        // 先取证再踢：静默连拍，全程无闪光无提示
        if (cfg.screenshotEnabled && cfg.screenshotAutoOnFail) {
            hub.screenshots.requestBurst(s.player, "校验失败取证：$reason", admin = "auto", session = s)
        }

        if (ban) {
            hub.bans.banFromSession(s, reason, admin = "auto")
        }

        // 玩家看到的话术：静默模式只说"校验未通过"，不说漏了什么
        val shown = if (cfg.stealthGenericKick) {
            if (ban) "【安全校验】客户端环境校验未通过，此设备已被限制登录（请联系服务器管理员）"
            else "【安全校验】客户端环境校验未通过（请联系服务器管理员）"
        } else {
            if (ban) "【反作弊】$reason（已封禁此机器，申诉请联系服主）" else "【反作弊】$reason"
        }
        hub.server.disconnect(s.player, shown)

        // 真实原因只进管理员渠道 —— 静默模式的核心
        hub.server.notifyAdmins("guard ${s.player.name} 校验失败[$code]：$reason${if (ban) "（已封禁）" else ""}")

        hub.channel.send(s.player, GuardMsg.RESULT, Result(false, code, shown, if (ban) "ban" else "kick").encode())
        sessions.remove(s.player.uuid)
        s.wipe()
    }

    // ---------- 保活 ----------

    private fun handleKeepaliveAck(s: Session, plain: ByteArray) {
        if (s.state != State.VERIFIED) return
        val expected = s.lastKeepaliveNonce ?: return
        if (plain.contentEquals(expected)) {
            s.lastKeepaliveAck = System.currentTimeMillis()
            HeapGuard.wipe(s.lastKeepaliveNonce)
            s.lastKeepaliveNonce = null
        } else {
            hub.logWarn("guard: ${s.player.name} 保活应答 nonce 不匹配")
        }
        HeapGuard.wipe(plain)
    }

    /** 每秒驱动：超时检查 + 保活探测。 */
    fun onSecond() {
        if (!hub.config.enabled) return
        val now = System.currentTimeMillis()
        val cfg = hub.config

        // 快照遍历：fail() 内部会 remove 会话，边遍历边删会 CME 崩服。
        // 会话数是「在线玩家数」，toList() 的代价可以忽略。
        for (s in sessions.values.toList()) {
            if (s.state == State.CHALLENGE_SENT) {
                if (now > s.deadline) fail(s, "未安装或被阻止运行反作弊客户端", "no_agent")
                continue
            }
            if (s.state != State.VERIFIED) continue

            if (cfg.keepaliveEnabled) {
                val due = s.lastKeepaliveSent == 0L ||
                    now - s.lastKeepaliveSent >= cfg.keepaliveIntervalSeconds * 1000L
                if (due) {
                    // 上一轮还没应答且已超时 → 失败
                    if (s.lastKeepaliveNonce != null &&
                        now - s.lastKeepaliveSent > (cfg.keepaliveIntervalSeconds + cfg.keepaliveTimeoutSeconds) * 1000L
                    ) {
                        fail(s, "反作弊客户端无响应", "keepalive_timeout")
                        continue
                    }
                    sendKeepalive(s)
                }
            }

            // 中途复检：到点就换新 nonce 重走一遍完整握手（全新报告、可选全量重扫）。
            // 这是「进服时干净、开局后作弊」的克星 —— 作弊时机再巧也躲不过随机复检。
            if (cfg.rechallengeEnabled && s.nextRechallengeAt in 1..now) {
                s.state = State.CHALLENGE_SENT
                sendChallenge(s, fullRescan = cfg.rechallengeFullRescan)
                // 复检宽限期更长：全量重算几百个 mod 哈希要时间（sendChallenge 会按
                // 进服宽限期设 deadline，这里覆盖掉）
                s.deadline = now + cfg.rechallengeGraceSeconds * 1000L
                // 复检时按概率顺手截图：作弊中的客户端大概率正挂着 overlay
                if (cfg.rechallengeScreenshotChance > 0 &&
                    kotlin.random.Random.nextInt(100) < cfg.rechallengeScreenshotChance
                ) {
                    hub.screenshots.request(s.player, "复检抽查", admin = "auto", session = s)
                }
                hub.audit(
                    "guard_rechallenge", actor = "system", target = s.player.name, ip = s.player.ip,
                    detail = mapOf("fullRescan" to cfg.rechallengeFullRescan),
                )
            }
        }
    }

    private fun sendKeepalive(s: Session) {
        val box = s.keyBox ?: return
        val nonce = GCrypto.randomBytes(16)
        val ts = System.currentTimeMillis()
        val body = box.use { k -> GCrypto.seal(k, aad(GuardMsg.KEEPALIVE), Keepalive(nonce, ts).encode()) }
        HeapGuard.wipe(s.lastKeepaliveNonce)
        s.lastKeepaliveNonce = nonce
        s.lastKeepaliveSent = ts
        hub.channel.send(s.player, GuardMsg.KEEPALIVE, body)
    }

    // ---------- 会话加密封装 ----------

    private fun aad(msgId: String, extra: String = ""): ByteArray =
        (if (extra.isEmpty()) msgId else "$msgId:$extra").toByteArray(Charsets.UTF_8)

    /** 服务端 → 客户端：加密。 */
    fun seal(s: Session, msgId: String, plain: ByteArray, extra: String = ""): ByteArray? {
        val box = s.keyBox ?: return null
        return box.use { k -> GCrypto.seal(k, aad(msgId, extra), plain) }
    }

    /** 客户端 → 服务端：解密；认证失败返回 null（按篡改处理）。 */
    fun open(s: Session, msgId: String, body: ByteArray, extra: String = ""): ByteArray? {
        val box = s.keyBox ?: return null
        return box.use { k -> GCrypto.open(k, aad(msgId, extra), body) }
    }

    // ---------- 关服 ----------

    fun shutdown() {
        for (s in sessions.values) {
            s.state = State.CLOSED
            s.wipe()
        }
        sessions.clear()
    }
}

/**
 * 内置作弊特征库（智能识别用，握手时下发给客户端）。
 *
 * 走网络下发而不是写死在客户端：特征库更新不用逼玩家升级模组。
 * 匹配是**子串包含**（mod id / jar 文件名），宁多报不错杀：
 * 命中的进 `signals` 由服务端分级，severity>=2 才拒。
 */
object CheatSignatures {

    /** 确证级：知名作弊客户端主体（见到就拒）。 */
    private val CONFIRMED = listOf(
        "wurst", "impact", "future", "aristois", "liquidbounce", "meteor-client",
        "kamikaze", "seppuku", "sigma", "konas", "autumn", "novoline", "entropy",
        "xray-plus", "advanced-xray", "xrayultimate", "freecam",
        "baritone", // 争议项：自动建筑常伴生作弊，但也可正当使用 —— 默认确证，服主可改配置摘掉
    )

    /** 可疑级：常见透视/辅助（只记录不拦）。 */
    private val SUSPICIOUS = listOf(
        "xray", "x-ray", "cavefinder", "litematica-printer", "auto-clicker", "killaura",
    )

    fun all(): List<String> = CONFIRMED + SUSPICIOUS
}
