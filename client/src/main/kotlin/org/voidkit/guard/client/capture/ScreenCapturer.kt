package org.voidkit.guard.client.capture

import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * 客户端截屏（AWT Robot 方案，全屏 + 多显示器）。
 *
 * ## 私有版行为：截整张（虚拟）桌面，不是 MC 窗口
 *
 * 私有版要的是「取证」而不是「只拍游戏」—— 作弊者常把外挂窗口/网页教程/
 * 其它可疑软件开在 MC 旁边或副屏上，只截游戏窗口会漏掉这些。所以：
 * - **单显示器**：截整块屏幕。
 * - **多显示器**：逐屏截取、按屏幕坐标拼成一张整图（含主屏左侧/上方的
 *   负坐标副屏）。
 *
 * 开源版会把本文件换成「只截 MC 窗口」的窗口版（另一套代码路径），
 * 两者共用同一套传输/分片/落盘，只有抓取源不同。
 *
 * ## 为什么用 Robot 而不是读 MC 帧缓冲
 *
 * - **零 mixin**：套件铁律（整合包里已有 30+ mixin mod，不插一脚）。
 *   读 framebuffer 要么 mixin 要么调内部渲染状态，Robot 纯 JDK。
 * - **顺带取证**：Robot 抓的是**屏幕像素**而不是游戏画面 ——
 *   覆盖在 MC 窗口上的 overlay（外挂菜单）一样进图，正是取证想要的效果。
 * - **无感**：不闪光、不发声、不改窗口状态，玩家侧零感知。
 */
object ScreenCapturer {

    /** 抓整张桌面（多显示器拼接），压缩到 maxW/maxH 内，返回 PNG 字节。失败返回 null。 */
    fun capture(maxW: Int, maxH: Int): ByteArray? = runCatching {
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val devices = ge.screenDevices
        val robot = Robot()

        val full: BufferedImage = if (devices.size <= 1) {
            robot.createScreenCapture(ge.defaultScreenDevice.defaultConfiguration.bounds)
        } else {
            stitch(devices, robot)
        }

        val scaled = scale(full, maxW, maxH)
        val out = ByteArrayOutputStream()
        ImageIO.write(scaled, "png", out)
        out.toByteArray()
    }.getOrNull()

    /**
     * 多显示器拼接：算所有屏幕的并集画布，逐屏截取按相对坐标贴上去。
     *
     * 逐屏截而不是对并集矩形一次 createScreenCapture —— 某些 JDK 对
     * 「负坐标副屏 + 跨屏大矩形」的虚拟屏幕截取会黑屏/异常，逐屏最稳。
     * 单屏失败只跳过（留黑），保证主屏至少能截到。
     */
    private fun stitch(devices: Array<GraphicsDevice>, robot: Robot): BufferedImage {
        val bounds = devices
            .map { it.defaultConfiguration.bounds }
            .filter { it.width > 0 && it.height > 0 }
        if (bounds.isEmpty()) {
            return robot.createScreenCapture(GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds)
        }
        val minX = bounds.minOf { it.x }
        val minY = bounds.minOf { it.y }
        val w = bounds.maxOf { it.x + it.width } - minX
        val h = bounds.maxOf { it.y + it.height } - minY
        val canvas = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = canvas.createGraphics()
        for (b in bounds) {
            val img = runCatching { robot.createScreenCapture(Rectangle(b.x, b.y, b.width, b.height)) }
                .getOrNull() ?: continue
            g.drawImage(img, b.x - minX, b.y - minY, null)
            img.flush()
        }
        g.dispose()
        return canvas
    }

    /** 等比缩放（只缩不放）。 */
    private fun scale(img: BufferedImage, maxW: Int, maxH: Int): BufferedImage {
        val w = img.width
        val h = img.height
        if (w <= maxW && h <= maxH) return img
        val scale = minOf(maxW.toDouble() / w, maxH.toDouble() / h)
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        val out = BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.drawImage(img.getScaledInstance(nw, nh, java.awt.Image.SCALE_SMOOTH), 0, 0, null)
        g.dispose()
        return out
    }
}
