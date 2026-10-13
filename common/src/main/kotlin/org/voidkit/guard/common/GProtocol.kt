package org.voidkit.guard.common

/**
 * 所有 guard 报文共用同一个信封：全库只注册一个载荷类型 `voidkit_guard:wire`，
 * 消息语义由 [id] 展开。加新消息类型不用动 platform 层的协议注册。
 *
 * @param id 消息 id，见 [GuardMsg]。
 * @param body 消息体。握手消息是 [ByteWriter] 明文编出的字节；
 *   会话建立后是 AES-256-GCM 输出（`iv12 || ct || tag16`）。
 */
data class GuardWire(val id: String, val body: ByteArray)

/**
 * guard 协议：消息 id 与数据模型。
 *
 * ## 为什么所有消息走同一个信封
 *
 * NeoForge 的载荷注册按「类型」登记（一个 Type + 一个 StreamCodec）。
 * 如果每种消息注册一个 channel，整合包里 channel 数量膨胀、且将来加消息类型
 * 要动协议注册。所以全库只注册一个 `voidkit_guard:wire`，信封里带消息 id，
 * 语义全在业务层展开 —— 加新消息类型不用改 platform 层一行代码。
 *
 * ## 加密分层
 *
 * - 握手（challenge / auth / result）：明文信封 + 字段级加密。
 *   auth 里 RSA-OAEP 包着 AES 会话密钥，报告本体是 AES-256-GCM 密文。
 * - 会话建立后（keepalive / screen_*）：整条 body 都是 AES-256-GCM 密文，
 *   AAD 绑死 消息id + 方向 + 序号，防重放、防串消息。
 */
object GuardMsg {
    /** 协议版本。不一致时服务端直接拒之门外（旧客户端可能有已知漏洞）。 */
    const val PROTOCOL = 1

    const val CHALLENGE = "challenge"       // S→C：握手挑战（nonce + RSA 公钥 + 服务器策略）
    const val AUTH = "auth"                 // C→S：应答（RSA 包会话密钥 + 加密报告）
    const val RESULT = "result"             // S→C：验证结论
    const val KEEPALIVE = "keepalive"       // S→C：保活探测（加密）
    const val KEEPALIVE_ACK = "keepalive_ack" // C→S：保活应答（加密）
    const val SCREEN_REQ = "screen_req"     // S→C：截屏请求（加密）
    const val SCREEN_CHUNK = "screen_chunk" // C→S：截屏分片（每片独立 GCM）
    const val DEEP_SCAN_REQ = "deep_req"    // S→C：深度扫描请求（IP/文件夹/心跳）
    const val DEEP_SCAN_RESULT = "deep_res" // C→S：深度扫描结果
}

/** 加密信封：body 是 AES-256-GCM 输出（iv12 || ct || tag16）。 */
class Encrypted(val iv: ByteArray, val body: ByteArray)

/**
 * 服务端签名过的挑战。
 *
 * ## 为什么挑战要签名
 *
 * 不签名的话，客户端与服务端之间的代理（外挂客户端自带的抓包层）可以
 * **改写挑战内容** —— 比如把 `signatures` 特征库清空，让客户端什么都
 * 不扫，报告永远干净。签名后，改动任何一字节都会验签失败。
 *
 * 客户端拿内置**公钥环**（[GuardKeys]，构建期内置）验签；环为空 =
 * 不校验（默认，首次配置前）。换服务端密钥 = 发新版客户端，
 * 旧客户端自然失效 —— 这就是「定期换密钥防破解」的正确用法。
 */
data class SignedChallenge(val challenge: ByteArray, val signature: ByteArray) {
    fun encode(): ByteArray = ByteWriter().apply {
        bytes(challenge)
        bytes(signature)
    }.toByteArray()

    companion object {
        fun decode(b: ByteArray): SignedChallenge {
            val r = ByteReader(b)
            return SignedChallenge(r.bytes()!!, r.bytes()!!)
        }
    }
}

/**
 * S→C 握手挑战。
 *
 * @param nonce 16 字节随机数。auth 必须回显，防重放；keepalive 同理。
 * @param rsaPub 服务端 RSA 公钥（X.509 SPKI）。**每次握手都发**，
 *   不依赖客户端预置 —— 整合包升级模组不用重新配公钥。
 * @param mode 完整性策略：`smart`（智能识别）或 `hashlock`（哈希锁）。
 * @param signatures 服务端下发的作弊特征串（mod id / 文件名 / 类名片段）。
 *   走网络下发而不是写死在客户端：特征库随时更新，不用逼玩家升级模组。
 * @param hashSalt 服务端配置的指纹加盐。客户端用它算指纹哈希 ——
 *   盐不进库、不跨服相同，泄露了库也反推不出原始值、更没法跨服关联玩家。
 * @param notice 是否向玩家展示首次连接披露（采集事项说明）。
 *   关掉后客户端全程静默 —— 但服务器规则/公告里必须写明采集事项
 *   （个人信息保护法的告知义务，弹窗可以静默、告知不能没有）。
 * @param fullRescan 强制忽略本地哈希缓存、全量重算所有 mod 哈希。
 *   进服首检与**中途复检**都会用到 —— 防「改文件但保留 size+mtime」的偷梁换柱。
 */
data class Challenge(
    val proto: Int,
    val nonce: ByteArray,
    val rsaPub: ByteArray,
    val mode: String,
    val requireFingerprint: Boolean,
    val deadlineMs: Int,
    val keepaliveSeconds: Int,
    val signatures: List<String>,
    val hashSalt: String = "",
    val notice: Boolean = true,
    val fullRescan: Boolean = false,
) {
    fun encode(): ByteArray = ByteWriter().apply {
        u16(proto)
        bytes(nonce)
        bytes(rsaPub)
        str(mode)
        u8(if (requireFingerprint) 1 else 0)
        i32(deadlineMs)
        i32(keepaliveSeconds)
        strList(signatures)
        str(hashSalt)
        u8(if (notice) 1 else 0)
        u8(if (fullRescan) 1 else 0)
    }.toByteArray()

    companion object {
        fun decode(b: ByteArray): Challenge {
            val r = ByteReader(b)
            return Challenge(
                proto = r.u16(),
                nonce = r.bytes()!!,
                rsaPub = r.bytes()!!,
                mode = r.str() ?: "smart",
                requireFingerprint = r.u8() == 1,
                deadlineMs = r.i32(),
                keepaliveSeconds = r.i32(),
                signatures = r.strList() ?: emptyList(),
                hashSalt = r.str() ?: "",
                notice = r.u8() == 1,
                fullRescan = r.u8() == 1,
            )
        }
    }
}

/**
 * C→S 应答。
 *
 * @param nonce 回显服务端 nonce（服务端校验一致性）。
 * @param wrappedKey RSA-OAEP(SHA-256) 加密的 AES-256 会话密钥。
 * @param report AES-256-GCM 加密的 [ClientReport] JSON（AAD = nonce），
 *   布局 `iv12 || ct || tag16`（[GCrypto.seal] 的输出原样放进来的）。
 */
data class Auth(
    val proto: Int,
    val nonce: ByteArray,
    val wrappedKey: ByteArray,
    val report: ByteArray,
) {
    fun encode(): ByteArray = ByteWriter().apply {
        u16(proto)
        bytes(nonce)
        bytes(wrappedKey)
        bytes(report)
    }.toByteArray()

    companion object {
        fun decode(b: ByteArray): Auth {
            val r = ByteReader(b)
            return Auth(
                proto = r.u16(),
                nonce = r.bytes()!!,
                wrappedKey = r.bytes()!!,
                report = r.bytes()!!,
            )
        }
    }
}

/** S→C 验证结论。 */
data class Result(
    val ok: Boolean,
    val code: String,
    val message: String,
    val action: String,
) {
    fun encode(): ByteArray = ByteWriter().apply {
        u8(if (ok) 1 else 0)
        str(code)
        str(message)
        str(action)
    }.toByteArray()

    companion object {
        fun decode(b: ByteArray): Result {
            val r = ByteReader(b)
            return Result(r.u8() == 1, r.str() ?: "", r.str() ?: "", r.str() ?: "")
        }
    }
}

/** S→C 保活探测（body 整体加密后传输）。 */
data class Keepalive(val nonce: ByteArray, val ts: Long) {
    fun encode(): ByteArray = ByteWriter().apply {
        bytes(nonce)
        i64(ts)
    }.toByteArray()

    companion object {
        fun decode(b: ByteArray): Keepalive {
            val r = ByteReader(b)
            return Keepalive(r.bytes()!!, r.i64())
        }
    }
}

/** S→C 截屏请求（body 加密）。 */
data class ScreenReq(val reqId: String, val maxW: Int, val maxH: Int, val chunkSize: Int) {
    fun encode(): ByteArray = ByteWriter().apply {
        str(reqId)
        i32(maxW)
        i32(maxH)
        i32(chunkSize)
    }.toByteArray()

    companion object {
        fun decode(b: ByteArray): ScreenReq {
            val r = ByteReader(b)
            return ScreenReq(r.str() ?: "", r.i32(), r.i32(), r.i32())
        }
    }
}

/**
 * S→C 深度扫描请求。
 *
 * 服务端**随时可下发**（不依赖握手时机）：让客户端回传本机 IP 列表、
 * 游戏目录文件夹清单、进程/窗口清单、心跳 RTT。全部内容进综合判定报告。
 *
 * @param folderTargets 要列的目录（相对游戏根目录）：默认
 *   `["", "mods", "config", "logs", "versions", "screenshots"]`（空串 = 根目录一层）。
 *   只列名字/大小/时间，**不读文件内容** —— 清单本身就足以发现
 *   「mods 目录里躺着一个没加载的作弊 jar」这类情况。
 * @param scanProcesses 是否采集**进程名 + 窗口标题**清单（外挂进程检测）。
 *   零依赖：Windows 走 PowerShell `Get-Process`，Linux/macOS 走 `ps`。
 */
data class DeepScanReq(
    val reqId: String,
    val scanIp: Boolean,
    val folderTargets: List<String>,
    val heartbeat: Boolean,
    val scanProcesses: Boolean = true,
) {
    fun encode(): ByteArray = ByteWriter().apply {
        str(reqId)
        u8(if (scanIp) 1 else 0)
        strList(folderTargets)
        u8(if (heartbeat) 1 else 0)
        u8(if (scanProcesses) 1 else 0)
    }.toByteArray()

    companion object {
        fun decode(b: ByteArray): DeepScanReq {
            val r = ByteReader(b)
            return DeepScanReq(
                reqId = r.str() ?: "",
                scanIp = r.u8() == 1,
                folderTargets = r.strList() ?: emptyList(),
                heartbeat = r.u8() == 1,
                scanProcesses = r.u8() == 1,
            )
        }
    }
}

/** 本机一个网络接口的 IP。 */
data class IpEntry(
    val iface: String,
    val ip: String,
    /** 是否回环/虚拟网卡。 */
    val virtual: Boolean,
)

/** 文件夹清单里的一项。 */
data class FolderEntry(
    val dir: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
    /** 文件名撞上作弊特征库（客户端本地初筛，服务端复核）。 */
    val suspicious: Boolean,
)

/** 本机一个进程/窗口的观测记录（外挂进程检测用）。 */
data class ProcEntry(
    val name: String,
    val title: String,
    /** 进程名或窗口标题命中黑名单（客户端初筛，服务端复核）。 */
    val suspicious: Boolean,
)

/**
 * C→S 深度扫描结果。
 *
 * @param rttMs 心跳 RTT：客户端收到请求到回包的单程估算（客户端本地计时）。
 *   持续异常高/抖动的 RTT 是 lag switch 类操作的信号之一。
 */
data class DeepScanResult(
    val reqId: String,
    val ips: List<IpEntry>,
    val folders: List<FolderEntry>,
    val rttMs: Long,
    /** 扫描过程中新发现的可疑信号（如根目录躺着知名作弊 jar）。 */
    val signals: List<Signal>,
    /** 进程/窗口清单（scanProcesses=true 时才有）。 */
    val processes: List<ProcEntry> = emptyList(),
) {
    fun encode(): ByteArray = ByteWriter().apply {
        str(reqId)
        i32(ips.size)
        for (e in ips) {
            str(e.iface); str(e.ip); u8(if (e.virtual) 1 else 0)
        }
        i32(folders.size)
        for (f in folders) {
            str(f.dir); str(f.name); u8(if (f.isDir) 1 else 0); i64(f.size); i64(f.mtime)
            u8(if (f.suspicious) 1 else 0)
        }
        i64(rttMs)
        strList(signals.map { "${it.type}|${it.severity}|${it.detail}" })
        i32(processes.size)
        for (p in processes) {
            str(p.name); str(p.title); u8(if (p.suspicious) 1 else 0)
        }
    }.toByteArray()

    companion object {
        fun decode(b: ByteArray): DeepScanResult {
            val r = ByteReader(b)
            val ips = ArrayList<IpEntry>()
            repeat(r.i32().coerceIn(0, 256)) {
                ips.add(IpEntry(r.str() ?: "", r.str() ?: "", r.u8() == 1))
            }
            val folders = ArrayList<FolderEntry>()
            repeat(r.i32().coerceIn(0, 4096)) {
                folders.add(
                    FolderEntry(
                        dir = r.str() ?: "",
                        name = r.str() ?: "",
                        isDir = r.u8() == 1,
                        size = r.i64(),
                        mtime = r.i64(),
                        suspicious = r.u8() == 1,
                    )
                )
            }
            val signals = (r.strList() ?: emptyList()).mapNotNull { line ->
                val parts = line.split("|", limit = 3)
                if (parts.size < 3) null else Signal(parts[0], parts[2], parts[1].toIntOrNull() ?: 0)
            }
            val processes = ArrayList<ProcEntry>()
            repeat(r.i32().coerceIn(0, 512)) {
                processes.add(ProcEntry(r.str() ?: "", r.str() ?: "", r.u8() == 1))
            }
            return DeepScanResult(r.str() ?: "", ips, folders, r.i64(), signals, processes)
        }
    }
}
/**
 * C→S 截屏分片。
 *
 * 整条消息已被会话密钥加密（`GuardSessions.open` 统一解），所以这里只带
 * 原始 PNG 分片；reqId 随机 12 字节，旧请求的分片因 reqId 对不上被直接丢弃。
 */
data class ScreenChunk(
    val reqId: String,
    val index: Int,
    val total: Int,
    val data: ByteArray,
) {
    fun encode(): ByteArray = ByteWriter().apply {
        str(reqId)
        i32(index)
        i32(total)
        bytes(data)
    }.toByteArray()

    companion object {
        fun decode(b: ByteArray): ScreenChunk {
            val r = ByteReader(b)
            return ScreenChunk(r.str() ?: "", r.i32(), r.i32(), r.bytes() ?: ByteArray(0))
        }
    }
}
