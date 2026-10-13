package org.voidkit.guard.client.capture

import net.minecraft.client.Minecraft
import java.awt.Rectangle
import java.awt.Robot
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * 客户端截屏（AWT Robot 方案，只截 MC 游戏窗口）。
 *
 * ## 只截 MC 窗口，不截全屏
 *
 * 截图范围限定为 MC 游戏窗口本身 —— 不抓玩家屏幕上
 * 窗口之外的内容（桌面、任务栏、其它应用）。做法是用 GLFW 拿窗口的
 * **屏幕坐标位置 + 窗口尺寸**，Robot 只抓这一块矩形。
 *
 * ## DPI 缩放的坑（关键）
 *
 * `Window.width/height` 是 **framebuffer 尺寸**（物理像素），在 Windows
 * 125%/150% 缩放时比窗口的屏幕坐标尺寸大 —— 用它们当抓取尺寸会把
 * 窗口外的区域也截进来。所以这里用 `glfwGetWindowSize`（窗口逻辑尺寸，
 * 与 `glfwGetWindowPos` 同坐标系），保证抓取矩形**精确等于窗口**。
 *
 * ## 为什么用 Robot 而不是读 MC 帧缓冲
 *
 * - **零 mixin**：套件铁律（整合包里已有 30+ mixin mod，不插一脚）。
 *   读 framebuffer 要么 mixin 要么调内部渲染状态，Robot 纯 JDK。
 * - **无感**：不闪光、不发声、不改窗口状态，玩家侧零感知。
 */
object ScreenCapturer {

    /** 抓 MC 窗口区域，压缩到 maxW/maxH 内，返回 PNG 字节。失败返回 null。 */
    fun capture(maxW: Int, maxH: Int): ByteArray? = runCatching {
        val mc = Minecraft.getInstance()
        val window = mc.window
        val handle = window.window
        if (handle == 0L) return null

        val px = IntArray(1)
        val py = IntArray(1)
        val wx = IntArray(1)
        val wy = IntArray(1)
        org.lwjgl.glfw.GLFW.glfwGetWindowPos(handle, px, py)
        org.lwjgl.glfw.GLFW.glfwGetWindowSize(handle, wx, wy)
        val w = wx[0]
        val h = wy[0]
        if (w <= 0 || h <= 0) return null

        // 夹到屏幕边界（多显示器/部分跑出屏幕的情况）
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice ?: return null
        val bounds = screen.defaultConfiguration.bounds
        var x = px[0]
        var y = py[0]
        var cw = w
        var ch = h
        if (x < bounds.x) { cw += (x - bounds.x); x = bounds.x }
        if (y < bounds.y) { ch += (y - bounds.y); y = bounds.y }
        cw = cw.coerceAtMost(bounds.x + bounds.width - x)
        ch = ch.coerceAtMost(bounds.y + bounds.height - y)
        if (cw <= 0 || ch <= 0) return null

        val raw = Robot().createScreenCapture(Rectangle(x, y, cw, ch))
        val scaled = scale(raw, maxW, maxH)
        val out = ByteArrayOutputStream()
        ImageIO.write(scaled, "png", out)
        out.toByteArray()
    }.getOrNull()

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
