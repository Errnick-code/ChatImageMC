package dev.errnicraft.chatimage

import net.minecraft.client.Minecraft
import java.io.File
import java.nio.file.Files

/**
 * Дисковый кэш полных изображений.
 * Путь: .minecraft/chatimage_cache/<imageId>.png
 * При первом открытии на весь экран — скачивает и сохраняет.
 * При повторном — читает с диска, не дёргает сервер.
 */
object ImageDiskCache {

    private fun cacheDir(): File {
        val dir = Minecraft.getInstance().gameDirectory.toPath()
            .resolve("chatimage_cache").toFile()
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun fileFor(imageId: String): File =
        File(cacheDir(), "$imageId.png")

    /** Сохраняет байты на диск. Молча игнорирует ошибки. */
    fun save(imageId: String, data: ByteArray) {
        try {
            val f = fileFor(imageId)
            if (!f.exists()) f.writeBytes(data)
        } catch (_: Exception) {}
    }

    /** Загружает байты с диска. Null если нет. */
    fun load(imageId: String): ByteArray? {
        return try {
            val f = fileFor(imageId)
            if (f.exists()) f.readBytes() else null
        } catch (_: Exception) { null }
    }

    /** Очищает весь кэш (опционально, для команды). */
    fun clear() {
        try { cacheDir().listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
    }
}
