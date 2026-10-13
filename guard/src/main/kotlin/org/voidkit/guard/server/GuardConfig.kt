package org.voidkit.guard.server

import org.voidkit.core.util.ConfigFiles
import org.voidkit.guard.common.FailAction
import org.voidkit.guard.common.IntegrityMode

/**
 * guard 配置，落盘到 `voidkit/guard/config.json`（带中文注释，JSONC 风格）。
 *
 * ## 默认值哲学（Voidkit 一贯）：装上就能用，不出问题
 *
 * - 完整性默认 **智能识别**：不拦白名单外 mod，只打已知作弊特征 —— 生存服玩家
 *   装个小地图/optifine 类模组不该被误杀。要「装了别的就进不来」再切哈希锁。
 * - 封禁默认全开（IP/机器码/网卡/QQ）—— 这是服主自己要的严格度。
 * - 截屏默认**只手动触发**，校验失败时自动取证一张；随机抽查默认关（不扰民）。
 * - 采集项全部默认开但**只上传哈希**，原文不出客户端（见 [GFingerprint]）。
 *
 * ⚠️ Gson 反序列化 Kotlin class 不走默认参数（字段全是零值），
 * 所以加载后必须立刻 [normalize]（套件铁律，踩过）。
 */
class GuardConfig {

    // ---------- 总开关 ----------

    /** 模块总开关。关掉后客户端握手请求一律不应（等于没装反作弊）。 */
    var enabled: Boolean = true

    // ---------- 客户端完整性校验 ----------

    /** `smart` = 智能识别（特征库+自检，命中才处置）；`hashlock` = 哈希锁（清单逐项比对白名单）。 */
    var integrityMode: String = "smart"

    /** 进服后多少秒内必须完成握手。超时按失败处置。 */
    var integrityGraceSeconds: Int = 20

    /** 校验失败动作：`kick` = 踢出；`kick_ban` = 踢出并封禁机器码/IP。 */
    var integrityOnFail: String = "kick"

    /** 智能识别模式下，累计失败这么多次后自动升级为封禁（0 = 从不自动封，只踢）。 */
    var integrityMaxFailsBeforeBan: Int = 3

    /** 会话保活：周期性探测客户端 Agent 是否还活着（掉了线/被强杀的进程会暴露）。 */
    var keepaliveEnabled: Boolean = true
    var keepaliveIntervalSeconds: Int = 180

    /** 保活超时（秒）。超时按一次失败计。 */
    var keepaliveTimeoutSeconds: Int = 20

    /**
     * 哈希锁：首次见到的 mod 清单是否自动收入白名单（待审状态）。
     * true = 零配置起步（第一个玩家的干净清单成为基线，服主审核后生效）；
     * false = 白名单为空时谁都进不来，必须先用 `/guard whitelist import` 导清单。
     */
    var hashlockAutoLearn: Boolean = true

    /** 待审的白名单条目是否拦截。true = 新组合要服主点头；false = 自动放行（宽松）。 */
    var hashlockRequireReview: Boolean = true

    // ---------- 硬件指纹采集 ----------

    /** 采集主板/系统机器码哈希（Windows MachineGuid / Linux machine-id / macOS IOPlatformUUID）。 */
    var collectMachine: Boolean = true

    /** 采集物理网卡 MAC 哈希（过滤虚拟网卡）。 */
    var collectMac: Boolean = true

    /** 采集本机 QQ 登录记录哈希（Documents/Tencent Files 账号目录 + 注册表）。 */
    var collectQQ: Boolean = true

    /** 采集本机微信痕迹哈希（安装路径/文档目录；微信不落明文账号，尽力而为）。 */
    var collectWeChat: Boolean = true

    /**
     * 指纹加盐。同一份指纹在不同盐下哈希不同 —— 库泄露也无法跨服关联玩家。
     * 留空 = 不加盐（不推荐，但兼容旧数据）。改盐会让所有历史指纹失效。
     */
    var hashSalt: String = ""

    /** 是否强制要求客户端回传指纹。false = 采集失败也不卡进服（只影响封禁匹配精度）。 */
    var requireFingerprint: Boolean = false

    // ---------- 封禁策略 ----------

    /** 自动封禁时是否带上连接 IP。 */
    var banIp: Boolean = true

    /** 自动封禁时是否带上机器码。 */
    var banMachine: Boolean = true

    /** 自动封禁时是否带上全部网卡 MAC。 */
    var banMac: Boolean = true

    /** 自动封禁时是否带上 QQ 登录记录。 */
    var banQQ: Boolean = true

    /** 手动封禁玩家时是否同时封 UUID。 */
    var banUuid: Boolean = true

    /** 手动封禁默认天数。0 = 永久。 */
    var defaultBanDays: Int = 0

    // ---------- 截屏 ----------

    var screenshotEnabled: Boolean = true

    /** 同一玩家两次截屏的最小间隔（秒）。防骚扰。 */
    var screenshotCooldownSeconds: Int = 60

    /** 截屏后压缩到的最大边长（保持比例）。 */
    var screenshotMaxWidth: Int = 1920
    var screenshotMaxHeight: Int = 1080

    /** 分片大小（字节）。24KB 是经验值：MTU 友好、重传代价小。 */
    var screenshotChunkSize: Int = 24576

    /** 校验失败时，踢/封之前先截图取证一张。 */
    var screenshotAutoOnFail: Boolean = true

    /** 随机抽查：按概率对在线玩家突然截屏（默认关，零配置不扰民）。 */
    var screenshotSpotCheck: Boolean = false

    /**
     * 校验失败时**连续抓拍**（静默连拍，默认 3 张 / 2 秒一张）。
     *
     * 单张截图容易刚好拍到加载界面或者被 Alt+Tab 躲掉；连拍能覆盖
     * 「发现被抓 → 切窗口」的时间差。全程无闪光无提示，玩家侧零感知。
     */
    var screenshotBurstOnFail: Boolean = true
    var screenshotBurstCount: Int = 3
    var screenshotBurstIntervalMs: Int = 2000

    // ---------- 中途复检（随机换 nonce 重走握手） ----------

    /**
     * 中途复检总开关。
     *
     * 进服首检干净 ≠ 一直干净：玩家完全可能进服后才启动作弊。
     * 复检 = 随机间隔换全新 nonce 重走一遍完整握手（全新报告、
     * 可选全量重扫 mod 哈希），作弊时机再巧也躲不过随机性。
     */
    var rechallengeEnabled: Boolean = true

    /** 复检间隔下限（秒）。 */
    var rechallengeMinSeconds: Int = 300

    /** 复检间隔上限（秒）。实际间隔在 [min, max] 随机 —— 摸不到规律。 */
    var rechallengeMaxSeconds: Int = 900

    /** 复检宽限期（秒）。默认比进服宽限期长：全量重算几百个 mod 哈希要时间。 */
    var rechallengeGraceSeconds: Int = 45

    /** 复检时强制全量重扫（忽略本地哈希缓存）。防「改文件但保留 size+mtime」。 */
    var rechallengeFullRescan: Boolean = true

    /** 复检时随机截图概率（0-100）。作弊进行时的客户端大概率挂着 overlay。 */
    var rechallengeScreenshotChance: Int = 20

    // ---------- 反侦察（静默） ----------

    /**
     * 客户端行为静默：不弹任何提示、不打 info 日志（debug 级仍可排查）。
     * 默认开 —— 反作弊的存在只需要让服主知道，不需要向全服广播。
     */
    var stealthSilentClient: Boolean = true

    /**
     * 踢人提示不透露检测到了什么。真实原因只进服务端审计 + 在线管理员私信。
     * 默认开 —— 提示越具体（"检测到 wurst"），作弊者越快知道该改什么；
     * 只说"环境校验未通过"，对方连自己漏在哪都不知道。
     */
    var stealthGenericKick: Boolean = true

    // ---------- X 射线统计检测 ----------
    //
    // 开/关不落在这里，走 core 的 Feature.enabled（面板「模块」页 / core 配置
    // features 表都能切，见 XrayStatisticsFeature.defaultEnabled）——避免同一件事
    // 两处开关打架。下面的都是「开着时怎么判」的阈值，不是开关。

    /** 稀有矿暴露度分析的滑动窗口长度（分钟）。 */
    var xrayStatsWindowMinutes: Int = 30

    /**
     * 稀有矿占比告警阈值（累计口径）。正常玩法（探洞/合理条纹挖矿）远低于
     * 5%，透视直奔矿脉会显著抬升 —— 0.20 留了 4 倍以上余量，宁保守不误报。
     */
    var xrayStatsRareRatioThreshold: Double = 0.20

    /** 占比判定需要的最小总挖掘量。样本不足时沉默 —— 冷启动误报是统计检测头号杀手。 */
    var xrayStatsMinMinedSamples: Int = 400

    /** 暴露度告警阈值：窗口内挖到稀有矿的平均贴空气面数。正常玩家 ≥1（看得见才挖）。 */
    var xrayStatsExposureThreshold: Double = 1.0

    /** 暴露度判定需要的窗口内最小稀有矿样本数。 */
    var xrayStatsMinRareSamples: Int = 12

    /** 同一玩家同一告警原因的最小重复间隔（秒）。防日志刷屏。 */
    var xrayStatsWarnCooldownSeconds: Int = 600

    /** 命中时是否追加写 JSONL 证据文件（默认关 —— 告警日志已够用，落盘按需开）。 */
    var xrayStatsJsonlEnabled: Boolean = false

    /** JSONL 输出文件（相对 voidkit/ 根目录）。 */
    var xrayStatsJsonlFile: String = "guard/xray-stats.jsonl"

    // ---------- 战斗遥测（reach / 瞄准急转 / CPS；只告警不处置） ----------
    //
    // 开/关同样走 core 的 Feature.enabled（CombatFeature.defaultEnabled=false，
    // 即**默认关**）——战斗检测是误报重灾区，宁可默认不动，服主想开了再去
    // 面板/配置打开。下面都是阈值，不是开关。

    /** 战斗遥测滑动窗口（分钟）。窗口太短样本不够，太长反应迟钝。 */
    var combatWindowMinutes: Int = 10

    /**
     * reach 告警阈值（格）：攻击者眼睛到目标碰撞箱的距离。生存模式原版
     * 互动范围 3.0 + 延迟补偿余量；持续超出即远程攻击特征。
     */
    var combatReachThreshold: Double = 3.75

    /** reach 判定：窗口内超距攻击的占比阈值（单次超距可能是延迟，占比才说明问题）。 */
    var combatReachRatioThreshold: Double = 0.30

    /** 瞄准急转阈值（度）：攻击所在 tick 的合成视角角速度。人类打斗时视角钉在目标上。 */
    var combatSnapThresholdDeg: Double = 30.0

    /** 急转判定：带大急转的攻击占比阈值（人类偶尔也甩镜头，高比例才是自动瞄准）。 */
    var combatSnapRatioThreshold: Double = 0.40

    /** CPS 告警下限：只抓「既快又稳」—— 慢而稳的点击器无害。 */
    var combatCpsMin: Double = 8.0

    /** CPS 变异系数阈值：σ/μ 低于它说明点击过于恒定（人类高 CPS 时手抖）。 */
    var combatCpsCvThreshold: Double = 0.12

    /** 三个信号共用的最小样本数。样本不足时沉默 —— 冷启动误报是统计检测头号杀手。 */
    var combatMinSamples: Int = 10

    var combatWarnCooldownSeconds: Int = 600

    var combatJsonlEnabled: Boolean = false

    var combatJsonlFile: String = "guard/combat-stats.jsonl"

    // ---------- 审计 ----------

    /** 审计与截屏记录保留天数。0 = 永久。 */
    var retentionDays: Int = 90

    /** 首次连接时向玩家披露采集事项（弹一次聊天说明）。 */
    var noticeEnabled: Boolean = true

    // ---------- 存储 ----------

    /** 数据库文件（相对 `voidkit/` 根目录）。 */
    var databaseFile: String = "guard/guard.db"

    /** 服务端 RSA 私钥文件（PKCS8 PEM）。首次启动自动生成。 */
    var serverKeyFile: String = "guard/server.pem"

    /** 截屏存放目录（相对 `voidkit/` 根目录）。 */
    var screenshotDir: String = "guard/screenshots"

    // ---------- 派生 ----------

    fun integrityModeEnum(): IntegrityMode = IntegrityMode.from(integrityMode)

    fun failActionEnum(): FailAction = FailAction.from(integrityOnFail)

    fun effectiveSalt(): String = hashSalt

    /**
     * 收口所有取值。配置文件是手写的，什么离谱值都可能有 ——
     * 这里全部夹到合理区间，业务层拿到就能直接用。
     */
    fun normalize() {
        integrityMode = IntegrityMode.from(integrityMode).id
        integrityOnFail = FailAction.from(integrityOnFail).id
        integrityGraceSeconds = integrityGraceSeconds.coerceIn(3, 120)
        integrityMaxFailsBeforeBan = integrityMaxFailsBeforeBan.coerceIn(0, 100)
        keepaliveIntervalSeconds = keepaliveIntervalSeconds.coerceIn(30, 3600)
        keepaliveTimeoutSeconds = keepaliveTimeoutSeconds.coerceIn(5, 120)
        rechallengeMinSeconds = rechallengeMinSeconds.coerceIn(30, 86400)
        rechallengeMaxSeconds = rechallengeMaxSeconds.coerceIn(rechallengeMinSeconds, 86400)
        rechallengeGraceSeconds = rechallengeGraceSeconds.coerceIn(10, 300)
        rechallengeScreenshotChance = rechallengeScreenshotChance.coerceIn(0, 100)
        screenshotCooldownSeconds = screenshotCooldownSeconds.coerceIn(0, 86400)
        screenshotMaxWidth = screenshotMaxWidth.coerceIn(320, 7680)
        screenshotMaxHeight = screenshotMaxHeight.coerceIn(240, 4320)
        screenshotChunkSize = screenshotChunkSize.coerceIn(4096, 262144)
        screenshotBurstCount = screenshotBurstCount.coerceIn(1, 10)
        screenshotBurstIntervalMs = screenshotBurstIntervalMs.coerceIn(500, 30000)
        xrayStatsWindowMinutes = xrayStatsWindowMinutes.coerceIn(1, 1440)
        combatWindowMinutes = combatWindowMinutes.coerceIn(1, 1440)
        combatReachThreshold = combatReachThreshold.coerceIn(2.0, 8.0)
        combatReachRatioThreshold = combatReachRatioThreshold.coerceIn(0.05, 1.0)
        combatSnapThresholdDeg = combatSnapThresholdDeg.coerceIn(5.0, 180.0)
        combatSnapRatioThreshold = combatSnapRatioThreshold.coerceIn(0.05, 1.0)
        combatCpsMin = combatCpsMin.coerceIn(2.0, 30.0)
        combatCpsCvThreshold = combatCpsCvThreshold.coerceIn(0.01, 1.0)
        combatMinSamples = combatMinSamples.coerceIn(3, 10000)
        combatWarnCooldownSeconds = combatWarnCooldownSeconds.coerceIn(0, 86400)
        if (combatJsonlFile.isBlank()) combatJsonlFile = "guard/combat-stats.jsonl"
        xrayStatsRareRatioThreshold = xrayStatsRareRatioThreshold.coerceIn(0.0, 1.0)
        xrayStatsMinMinedSamples = xrayStatsMinMinedSamples.coerceIn(10, 1_000_000)
        xrayStatsExposureThreshold = xrayStatsExposureThreshold.coerceIn(0.0, 6.0)
        xrayStatsMinRareSamples = xrayStatsMinRareSamples.coerceIn(1, 100_000)
        xrayStatsWarnCooldownSeconds = xrayStatsWarnCooldownSeconds.coerceIn(0, 86400)
        if (xrayStatsJsonlFile.isBlank()) xrayStatsJsonlFile = "guard/xray-stats.jsonl"
        retentionDays = retentionDays.coerceIn(0, 3650)
        defaultBanDays = defaultBanDays.coerceIn(0, 36500)
        if (databaseFile.isBlank()) databaseFile = "guard/guard.db"
        if (serverKeyFile.isBlank()) serverKeyFile = "guard/server.pem"
        if (screenshotDir.isBlank()) screenshotDir = "guard/screenshots"
    }

    companion object {
        const val FILE = "guard/config.json"
    }
}

/**
 * 配置注释表（写盘时按 key 插中文说明）。
 * key 用字段名；嵌套注释用 `字段.子字段`（本类无嵌套，保持简单）。
 */
object GuardComments {
    val CONFIG: Map<String, String> = mapOf(
        "enabled" to "总开关。关闭后不再校验任何客户端（等于没装反作弊）",
        "integrityMode" to "完整性模式：smart=智能识别（默认，不误杀）| hashlock=哈希锁（清单逐项比对）",
        "integrityGraceSeconds" to "进服后必须完成握手的秒数，超时按失败处置",
        "integrityOnFail" to "失败动作：kick=踢出 | kick_ban=踢出并封禁机器码/IP",
        "integrityMaxFailsBeforeBan" to "智能识别下累计失败多少次后升级封禁（0=只踢不封）",
        "keepaliveEnabled" to "会话保活：探测客户端 Agent 是否还活着",
        "keepaliveIntervalSeconds" to "保活探测间隔（秒）",
        "keepaliveTimeoutSeconds" to "保活超时（秒），超时按一次失败计",
        "rechallengeEnabled" to "中途复检：随机间隔换新 nonce 重走完整握手（抓「进服后作弊」）",
        "rechallengeMinSeconds" to "复检间隔下限（秒）",
        "rechallengeMaxSeconds" to "复检间隔上限（秒），实际在区间内随机",
        "rechallengeGraceSeconds" to "复检宽限期（秒），比进服宽限期长（全量重扫要时间）",
        "rechallengeFullRescan" to "复检时强制全量重扫 mod 哈希（防改文件保留时间戳）",
        "rechallengeScreenshotChance" to "复检时随机截图概率（%）",
        "hashlockAutoLearn" to "哈希锁：首个干净清单自动成为白名单基线（服主审核后生效）",
        "hashlockRequireReview" to "待审白名单条目是否拦截（false=自动放行）",
        "collectMachine" to "采集主板/系统机器码哈希（只上传哈希，原文不出客户端）",
        "collectMac" to "采集物理网卡 MAC 哈希（过滤虚拟网卡）",
        "collectQQ" to "采集本机 QQ 登录记录哈希",
        "collectWeChat" to "采集本机微信痕迹哈希（微信不落明文账号，尽力而为）",
        "hashSalt" to "指纹加盐：不同盐下哈希不同，库泄露也无法跨服关联。改盐会使历史指纹全部失效",
        "requireFingerprint" to "强制要求回传指纹。false=采集失败也不卡进服",
        "banIp" to "自动封禁时带上连接 IP",
        "banMachine" to "自动封禁时带上机器码",
        "banMac" to "自动封禁时带上全部网卡 MAC",
        "banQQ" to "自动封禁时带上 QQ 登录记录",
        "banUuid" to "手动封禁玩家时同时封 UUID",
        "defaultBanDays" to "手动封禁默认天数，0=永久",
        "screenshotEnabled" to "截屏功能总开关",
        "screenshotCooldownSeconds" to "同一玩家两次截屏最小间隔（秒）",
        "screenshotMaxWidth" to "截屏压缩最大宽",
        "screenshotMaxHeight" to "截屏压缩最大高",
        "screenshotChunkSize" to "截屏分片大小（字节）",
        "screenshotAutoOnFail" to "校验失败时先截图取证再踢/封",
        "screenshotSpotCheck" to "随机抽查在线玩家（默认关）",
        "screenshotBurstOnFail" to "校验失败时连续抓拍（静默连拍取证）",
        "screenshotBurstCount" to "连拍张数（1-10）",
        "screenshotBurstIntervalMs" to "连拍间隔（毫秒）",
        "stealthSilentClient" to "客户端行为静默：不弹提示、不打 info 日志",
        "stealthGenericKick" to "踢人提示不透露检测细节（真实原因只进审计+管理员私信）",
        "xrayStatsWindowMinutes" to "稀有矿暴露度分析的滑动窗口长度（分钟）",
        "xrayStatsRareRatioThreshold" to "稀有矿占比告警阈值（0-1，默认 0.20；正常玩法远低于 5%）",
        "xrayStatsMinMinedSamples" to "占比判定需要的最小总挖掘量（样本不足不判定，防冷启动误报）",
        "xrayStatsExposureThreshold" to "暴露度告警阈值：挖到稀有矿的平均贴空气面数（正常玩家 ≥1）",
        "xrayStatsMinRareSamples" to "暴露度判定需要的窗口内最小稀有矿样本数",
        "xrayStatsWarnCooldownSeconds" to "同一玩家同一告警原因的最小重复间隔（秒）",
        "xrayStatsJsonlEnabled" to "命中时追加写 JSONL 证据文件（默认关）",
        "xrayStatsJsonlFile" to "JSONL 输出文件（相对 voidkit/ 根目录）",
        "retentionDays" to "审计与截屏记录保留天数，0=永久",
        "noticeEnabled" to "首次连接向玩家披露采集事项",
        "databaseFile" to "数据库文件（相对 voidkit/ 根目录）",
        "serverKeyFile" to "服务端 RSA 私钥（PKCS8 PEM），首次启动自动生成",
        "screenshotDir" to "截屏存放目录",
    )
}

/** 配置单例。加载时机：ServerAboutToStartEvent（构造期零 IO 铁律）。 */
object GuardStore {
    @Volatile
    var config: GuardConfig = GuardConfig()
        private set

    fun load(): GuardConfig {
        config = ConfigFiles.load(GuardConfig.FILE, GuardComments.CONFIG) { GuardConfig() }
        config.normalize()
        return config
    }

    fun save() = ConfigFiles.save(GuardConfig.FILE, config, GuardComments.CONFIG)
}
