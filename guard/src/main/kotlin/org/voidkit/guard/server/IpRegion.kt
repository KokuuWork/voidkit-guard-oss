package org.voidkit.guard.server

import org.lionsoul.ip2region.xdb.Searcher
import org.voidkit.core.util.ConfigFiles
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * IP 归属地（离线）。
 *
 * ## 为什么是 ip2region 而不是在线 API
 *
 * - **不联网**：IP 永远不出服务器，不喂给任何第三方（传输加密的努力
 *   不能在归属地查询这里漏掉）。
 * - **零配置**：11MB 的 xdb 打包在 jar 里，首次查询时解到 `guard/` 数据
 *   目录；服主想换新库，直接替换那个文件重启即可（磁盘上有就不覆盖）。
 * - **够用**：小服管理只需要「哪个省哪个市、什么运营商」这个粒度 ——
 *   能看出「换宽带了没有」「是不是机房 IP」，这就够了。
 *
 * 线程安全：`newWithBuffer` 把整个 xdb 读进内存（11MB），无文件 IO，
 * 并发查询安全。懒加载 + 双检锁：查询是低频管理动作，不值得为它占启动时间。
 */
object IpRegion {

    @Volatile
    private var searcher: Searcher? = null

    /**
     * 归属地短格式：「广东省 深圳 电信」；国外只到国家（「美国」）；
     * 内网/回环 =「内网」；查不到 = 空串（调用方自行兜底显示）。
     */
    fun lookup(ip: String): String {
        if (ip.isBlank() || ip.startsWith("127.") || ip.startsWith("192.168.") ||
            ip.startsWith("10.") || ip.startsWith("172.16.") || ip == "0.0.0.0"
        ) return "内网"
        val s = searcher ?: init() ?: return ""
        val raw = try {
            s.search(ip)
        } catch (e: Exception) {
            return ""
        }
        return format(raw)
    }

    /** 原始格式「国家|区域|省份|城市|ISP」，未知段是 "0"。拼成人话，跳过空段。 */
    private fun format(raw: String): String {
        val p = raw.split('|')
        if (p.size < 5) return raw
        val (country, _, province, city, isp) = p
        val segs = ArrayList<String>(3)
        if (country != "0" && country != "内网IP") {
            segs.add(country)
            // 国外记录省市常为 0，只显示国家就够了
            if (country != "中国") return segs.joinToString(" ")
        }
        for (seg in listOf(province, city, isp)) {
            if (seg != "0" && seg.isNotBlank() && seg !in segs) segs.add(seg)
        }
        return segs.joinToString(" ")
    }

    /**
     * 加载归属地库。优先用数据目录已有的（服主可自行升级数据），
     * 没有才从 jar 资源解一份。加载失败不抛（归属地是锦上添花，
     * 挂了只降级为不显示，不能影响封禁主流程）。
     *
     * @return 就绪的 Searcher；null = 初始化失败（后续查询全部返回空串）。
     */
    @Synchronized
    private fun init(): Searcher? {
        searcher?.let { return it }
        val path: java.nio.file.Path = ConfigFiles.resolve("guard/ip2region.xdb")
        try {
            if (!Files.isRegularFile(path)) {
                Files.createDirectories(path.parent)
                IpRegion::class.java.getResourceAsStream("/guard/ip2region.xdb")?.use { input ->
                    Files.copy(input, path, StandardCopyOption.REPLACE_EXISTING)
                } ?: return null
            }
            // 整库进内存：11MB 换「无文件 IO + 并发安全」，服务器内存下不亏
            val s = Searcher.newWithBuffer(Files.readAllBytes(path))
            searcher = s
            return s
        } catch (e: Exception) {
            return null
        }
    }
}
