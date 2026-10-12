package org.voidkit.guard.client.capture

import net.minecraft.client.Minecraft
import java.awt.Rectangle
import java.awt.Robot
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * 客户端截屏（AWT Robot 方案）。
 *
 * ## 为什么用 Robot 而不是读 MC 帧缓冲
 *
 * - **零 mixin**：套件铁律（整合包里已有 30+ mixin mod，不插一脚）。
 *   读 framebuffer 要么 mixin 要么调内部渲染状态，Robot 纯 JDK。
 * - **顺带取证**：Robot 抓的是**屏幕区域**而不是游戏画面 ——
 *   覆盖在 MC 窗口上的 overlay（外挂菜单、网页教程）一样进图，
 *   这恰恰是截屏取证想要的效果。
 * - **无感**：不闪光、不发声、不改窗口状态，玩家侧零感知。
 *
 * ## 窗口几何
 *
 * 位置用 GLFW `glfwGetWindowPos`（窗口模式的位置；全屏时左上角就是原点），
 * 尺寸用 `Window.getWidth/Height`（帧缓冲尺寸，全屏时=显示器尺寸）。
 * 抓取区域夹到屏幕边界内，防止多显示器/负坐标坐标把 Robot 惹毛。
 */
object ScreenCapturer {

    /** 抓一张屏，压缩到 maxW/maxH 内，返回 PNG 字节。失败返回 null。 */
    fun capture(maxW: Int, maxH: Int): ByteArray? = runCatching {
        val mc = Minecraft.getInstance()
        val window = mc.window
        val handle = window.window
        if (handle == 0L) return null

        val px = IntArray(1)
        val py = IntArray(1)
        org.lwjgl.glfw.GLFW.glfwGetWindowPos(handle, px, py)
        val w = window.width
        val h = window.height
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
