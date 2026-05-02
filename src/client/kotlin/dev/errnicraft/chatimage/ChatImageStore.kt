package dev.errnicraft.chatimage

import java.io.File
import java.util.concurrent.ConcurrentHashMap

object ChatImageStore {
    data class ImageMessage(
        val imageId: String,
        val sender: String,
        val caption: String,
        val addedTime: Int,
        var dismissed: Boolean = false
    ) {
        var boundsX0 = 0; var boundsY0 = 0
        var boundsX1 = 0; var boundsY1 = 0
        private var boundsSet = false

        fun setScreenBounds(x0: Int, y0: Int, x1: Int, y1: Int) {
            boundsX0 = x0; boundsY0 = y0
            boundsX1 = x1; boundsY1 = y1
            boundsSet = true
        }
        fun hasScreenBounds() = boundsSet
    }

    val messages = ArrayDeque<ImageMessage>(50)
    private const val MAX_MESSAGES = 50

    // Оригинальный файл для своих картинок — для сохранения без потерь
    private val originalFileMap = ConcurrentHashMap<String, File>()

    fun addMessage(imageId: String, sender: String, caption: String, addedTime: Int) {
        messages.addLast(ImageMessage(imageId, sender, caption, addedTime))
        if (messages.size > MAX_MESSAGES) {
            val removed = messages.removeFirst()
            originalFileMap.remove(removed.imageId)
        }
    }

    /** Сохраняет ссылку на оригинальный файл (только для своих картинок) */
    fun storeOriginalFile(imageId: String, file: File) {
        originalFileMap[imageId] = file
    }

    @JvmStatic
    fun getOriginalFile(imageId: String): File? = originalFileMap[imageId]

    fun dismiss(imageId: String) {
        messages.find { it.imageId == imageId }?.dismissed = true
    }

    @JvmStatic
    fun dismissMessage(imageId: String) = dismiss(imageId)

    @JvmStatic
    fun getMessageList(): List<ImageMessage> = messages.toList()

    fun clear() {
        messages.clear()
        originalFileMap.clear()
    }
}
