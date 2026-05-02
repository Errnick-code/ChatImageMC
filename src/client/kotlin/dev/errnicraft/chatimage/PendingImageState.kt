package dev.errnicraft.chatimage

import net.minecraft.resources.Identifier
import java.io.File

object PendingImageState {

    data class PendingImage(
        val file: File,
        /** null пока текстура ещё грузится (показываем заглушку) */
        val textureId: Identifier?,
        val width: Int,
        val height: Int,
        val textureWidth: Int,
        val textureHeight: Int,
        val previewBytes: ByteArray, // PNG для превью в чате
        val rawBytes: ByteArray      // PNG для fullscreen
    ) {
        fun isLoaded() = textureId != null && previewBytes.isNotEmpty() && rawBytes.isNotEmpty()
    }

    @Volatile
    private var _pending: PendingImage? = null

    @JvmStatic
    fun getPending(): PendingImage? = _pending

    @JvmStatic
    fun setPending(img: PendingImage?) {
        // Если меняем pending — освобождаем старую текстуру
        val old = _pending
        if (old != null && old.textureId != null && (img == null || img.textureId != old.textureId)) {
            try {
                net.minecraft.client.Minecraft.getInstance().textureManager.release(old.textureId)
            } catch (_: Exception) {}
        }
        _pending = img
    }

    @JvmStatic
    fun clear() {
        val img = _pending
        _pending = null
        if (img?.textureId != null) {
            try {
                net.minecraft.client.Minecraft.getInstance().textureManager.release(img.textureId)
            } catch (_: Exception) {}
        }
    }

    /** Обновляет только текстуру и данные байтов у уже существующего pending */
    @JvmStatic
    fun updateTexture(
        textureId: Identifier,
        textureWidth: Int,
        textureHeight: Int,
        previewBytes: ByteArray,
        rawBytes: ByteArray
    ) {
        val cur = _pending ?: return
        _pending = cur.copy(
            textureId    = textureId,
            textureWidth = textureWidth,
            textureHeight = textureHeight,
            previewBytes = previewBytes,
            rawBytes     = rawBytes
        )
    }
}
