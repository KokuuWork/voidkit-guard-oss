package org.voidkit.guard.common

/**
 * 硬件指纹。
 *
 * ## 设计原则：原文不出客户端
 *
 * 所有字段都是**加盐 SHA-256 哈希**（盐来自服务端配置 `fingerprint.hashSalt`）。
 * 服务端只存哈希、只按哈希匹配封禁 —— 拿到库也反推不出机器码/QQ 号，
 * 换台服务器盐不同哈希也不同（同一份数据不能跨服串用）。
 *
 * @param machine 主板/系统级机器码哈希（Windows: MachineGuid；Linux: machine-id；
 *   macOS: IOPlatformUUID）。换系统/换主板才会变。
 * @param macs 网卡 MAC 哈希列表（过滤虚拟网卡后的物理网卡，按哈希排序保证稳定）。
 * @param qq 本机 QQ 登录记录哈希（Documents/Tencent Files 下的账号目录 + 注册表）。
 * @param wechat 本机微信痕迹哈希（安装路径/文档目录，尽力而为 —— 微信不落明文账号）。
 */
data class Fingerprint(
    val machine: String = "",
    val macs: List<String> = emptyList(),
    val qq: List<String> = emptyList(),
    val wechat: List<String> = emptyList(),
    val platform: String = "unknown",
) {
    /** 参与封禁匹配的标识集合（空字段自动剔除）。 */
    fun banKeys(): Map<String, String> = buildMap {
        if (machine.isNotBlank()) put("machine", machine)
        macs.forEachIndexed { i, m -> put("mac$i", m) }
        qq.forEachIndexed { i, q -> put("qq$i", q) }
        wechat.forEachIndexed { i, w -> put("wechat$i", w) }
    }
}

/** 客户端上报的一条 mod。 */
data class ModEntry(
    val id: String,
    val version: String,
    val file: String,
    val size: Long,
    val sha256: String?,
)

/** 客户端上报的可疑信号（智能识别的产出）。 */
data class Signal(
    /** cheat_mod / tampered_mod / javaagent / debugger / self_tampered / unknown_mod ... */
    val type: String,
    val detail: String,
    /** 0=提示 1=可疑 2=确证（服务端按阈值决定动作）。 */
    val severity: Int,
)

/** 客户端自身完整性自检结果。 */
data class SelfIntegrity(
    val ok: Boolean,
    val expected: String,
    val actual: String,
    val detail: String,
)

/**
 * 客户端运行环境画像（系统版本 + 硬件配置 + 区域信息）。
 *
 * ## 用途
 *
 * 1. **身份佐证**：同一台机器的 CPU/GPU/内存/时区组合是稳定的辅助标识，
 *    换号不换机的人在这露出马脚（进封禁匹配的 `envHash`）。
 * 2. **异常信号**：软件渲染器（llvmpipe/软渲染）+ 虚拟机特征 +
 *    时区与连接 IP 归属地矛盾，都是远程桌面/虚拟机/代理的典型痕迹。
 * 3. **报告可读性**：服主看报告时能直接看到「Win11 + 13700K + 4070」，
 *    比一串哈希好用。
 *
 * ## 关于「精确地理位置」（写进 READme，不夸大）
 *
 * JVM/游戏进程拿不到 GPS。能拿到的定位替身是：时区 + 系统区域 + 语言，
 * 以及服务端侧的连接 IP。要「精确地理位置」必须外挂 IP 地理库
 * （离线库 ~30MB 或第三方 API）—— 与「零配置、离线可用」冲突，
 * 所以 core 侧留了扩展位，不在模组里硬塞。时区/区域对抓
 * 「本人在中国、VPN 到美国进服」已经足够灵敏。
 */
data class EnvInfo(
    /** 操作系统名 + 版本，如 `Windows 11 / 10.0.26100`。 */
    val osName: String = "",
    val osVersion: String = "",
    val osArch: String = "",
    /** CPU 型号 + 核心数。 */
    val cpu: String = "",
    val cpuCores: Int = 0,
    /** GPU 型号（GL_RENDERER）。空 = 没取到（服务端/无上下文）。 */
    val gpu: String = "",
    val glVendor: String = "",
    /** 物理内存（MB）。 */
    val ramMb: Long = 0,
    /** 时区 ID（如 Asia/Shanghai）—— 定位替身 + 稳定辅助标识。 */
    val timezone: String = "",
    /** 系统区域 + 语言（如 zh_CN）。 */
    val locale: String = "",
    val javaVersion: String = "",
    /** JVM 启动参数哈希：跨会话匹配「同一套启动参数」，不暴露参数原文。 */
    val vmArgsHash: String = "",
) {
    /** 参与身份匹配的稳定组合哈希。 */
    fun envHash(): String = GCrypto.sha256Hex(
        listOf(osName, osVersion, cpu, cpuCores.toString(), gpu, ramMb.toString(), timezone).joinToString("|")
    )
}

/**
 * 客户端完整报告（AES-GCM 加密后传输，服务端验签解密）。
 *
 * @param nonce 回显服务端 nonce —— 服务端校验一致，重放旧报告直接死。
 * @param env 运行环境画像（系统版本/硬件配置/时区区域）。
 */
data class ClientReport(
    val proto: Int,
    val nonce: String,
    val agent: String,
    val player: String,
    val uuid: String,
    val clientBrand: String,
    val mods: List<ModEntry>,
    val fingerprint: Fingerprint?,
    val signals: List<Signal>,
    val selfIntegrity: SelfIntegrity,
    val env: EnvInfo = EnvInfo(),
)

/** 服务端完整性策略。 */
enum class IntegrityMode(val id: String, val display: String) {
    /** 智能识别：特征库 + 自检 + 信号分级，命中才处置（默认，零配置可用）。 */
    SMART("smart", "智能识别"),

    /** 哈希锁：mod 清单必须和白名单逐项一致，多一个少一个都拒。 */
    HASH_LOCK("hashlock", "哈希锁");

    companion object {
        fun from(id: String?): IntegrityMode =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: SMART
    }
}

/** 校验失败时的服务端动作。 */
enum class FailAction(val id: String, val display: String) {
    KICK("kick", "踢出"),
    KICK_AND_BAN("kick_ban", "踢出并封禁机器"),
    ;

    companion object {
        fun from(id: String?): FailAction =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: KICK
    }
}
