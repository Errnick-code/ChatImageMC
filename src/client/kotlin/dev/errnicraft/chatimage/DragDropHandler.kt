package dev.errnicraft.chatimage

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWDropCallback
import java.io.File

object DragDropHandler {

    private var registered = false
    // Флаг: курсор вошёл в окно извне (= возможный drag)
    @Volatile private var enteredFromOutside = false
    @Volatile private var wasOutside = true

    fun register() {
        ClientTickEvents.START_CLIENT_TICK.register { _ ->
            if (registered) return@register
            val mc = Minecraft.getInstance()
            val windowHandle: Long = mc.getWindow().handle()

            GLFW.glfwSetDropCallback(windowHandle) { _, count, names ->
                DragDropOverlay.setActive(false)
                enteredFromOutside = false
                wasOutside = true

                if (mc.screen !is ChatScreen) return@glfwSetDropCallback
                for (i in 0 until count) {
                    val path = GLFWDropCallback.getName(names, i)
                    val file = File(path)
                    val name = file.name.lowercase()
                    if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                        || name.endsWith(".webp") || name.endsWith(".bmp")
                        || name.endsWith(".tiff") || name.endsWith(".tif")) {
                        if (file.length() <= 10L * 1024 * 1024) {
                            mc.execute { ChatImageClient.stageImage(file) }
                        } else {
                            mc.execute {
                                mc.gui.getChat().addMessage(
                                    net.minecraft.network.chat.Component.literal(
                                        "§c[ChatImage] " + ChatImageConfig.tr("chatimage.file_too_large")
                                    )
                                )
                            }
                        }
                        break
                    }
                }
            }

            registered = true
        }

        // Оверлей показываем только когда курсор ВОШЁЛ в окно снаружи с зажатой ЛКМ.
        // Это отличает настоящий drag от обычных кликов внутри окна.
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (client.screen !is ChatScreen) {
                DragDropOverlay.setActive(false)
                enteredFromOutside = false
                wasOutside = true
                return@register
            }
            val window = client.getWindow()
            val handle = window.handle()

            val xArr = DoubleArray(1)
            val yArr = DoubleArray(1)
            GLFW.glfwGetCursorPos(handle, xArr, yArr)
            val cx = xArr[0]
            val cy = yArr[0]
            val w = window.width.toDouble()
            val h = window.height.toDouble()
            val overWindow = cx >= 0 && cy >= 0 && cx <= w && cy <= h

            val lmbDown = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS

            if (!overWindow) {
                wasOutside = true
                enteredFromOutside = false
                DragDropOverlay.setActive(false)
                return@register
            }

            if (wasOutside && overWindow && lmbDown) {
                enteredFromOutside = true
            }
            if (wasOutside && overWindow && !lmbDown) {
                enteredFromOutside = false
            }
            wasOutside = false

            val isDragging = enteredFromOutside && lmbDown
            DragDropOverlay.setActive(isDragging)

            if (!lmbDown) enteredFromOutside = false
        }
    }
}
