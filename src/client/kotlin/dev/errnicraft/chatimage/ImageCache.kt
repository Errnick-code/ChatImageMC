package dev.errnicraft.chatimage

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

object ImageCache {

    private val previewTexture = ConcurrentHashMap<String, Identifier>()
    private val sizes          = ConcurrentHashMap<String, Pair<Int, Int>>()  // dispW x dispH в gui-единицах (НЕИЗМЕНЯЕМЫ после регистрации)
    private val origSizes      = ConcurrentHashMap<String, Pair<Int, Int>>()  // texW x texH пикселей текстуры
    private val texSizes       = ConcurrentHashMap<String, Pair<Int, Int>>()  // реальный размер превью-текстуры в пикселях
    private val loadedFlags    = ConcurrentHashMap<String, Boolean>()
    private val fullDataMap    = ConcurrentHashMap<String, ByteArray>()
    // Состояния для "удалено" и "ошибка загрузки"
    private val deletedFlags   = ConcurrentHashMap<String, Boolean>()
    private val errorFlags     = ConcurrentHashMap<String, Boolean>()

    fun getTexture(imageId: String): Identifier? = previewTexture[imageId]
    fun getSize(imageId: String): Pair<Int, Int>? = sizes[imageId]
    fun getOrigSize(imageId: String): Pair<Int, Int>? = origSizes[imageId]
    fun getTexSize(imageId: String): Pair<Int, Int>? = texSizes[imageId]
    fun isLoaded(imageId: String): Boolean = loadedFlags[imageId] == true
    fun getFullData(imageId: String): ByteArray? = fullDataMap[imageId]
    fun isDeleted(imageId: String): Boolean = deletedFlags[imageId] == true
    fun isError(imageId: String): Boolean = errorFlags[imageId] == true

    /** Помечает фото как удалённое — рендерер покажет плейсхолдер с крестом */
    fun markDeleted(imageId: String) {
        deletedFlags[imageId] = true
        // Освобождаем текстуру если была загружена
        val old = previewTexture.remove(imageId)
        if (old != null) {
            try { Minecraft.getInstance().textureManager.release(old) } catch (_: Exception) {}
        }
        loadedFlags[imageId] = false
    }

    /** Помечает фото как ошибку загрузки — рендерер покажет плейсхолдер с ! */
    fun markError(imageId: String) {
        errorFlags[imageId] = true
        loadedFlags[imageId] = false
    }

    fun storeFullData(imageId: String, data: ByteArray) {
        fullDataMap[imageId] = data
    }

    fun upgradeToHiRes(imageId: String, hiResTextureId: Identifier, hiResW: Int, hiResH: Int) {
        val old = previewTexture[imageId]
        if (old != null && old != hiResTextureId) {
            try { Minecraft.getInstance().textureManager.release(old) } catch (_: Exception) {}
        }
        previewTexture[imageId] = hiResTextureId
        origSizes[imageId] = Pair(hiResW, hiResH)
        // sizes (dispW/H в GUI) НЕ МЕНЯЕМ — размер карточки зафиксирован
    }

    /**
     * Декодирует полные байты → регистрирует hi-res текстуру → вызывает upgradeToHiRes.
     * Используется и при авто-download, и из ImageViewerScreen.
     * Безопасно вызывать с любого потока — декодирование выполняется в фоне,
     * финальный апгрейд — на главном потоке MC.
     *
     * @param onDone  вызывается на главном потоке с (textureId, w, h) — для ImageViewerScreen
     *                чтобы обновить свой hiResTextureId. null если нужен только апгрейд в чате.
     */
    fun loadAndUpgradeHiRes(
        imageId: String,
        bytes: ByteArray,
        onDone: ((Identifier, Int, Int) -> Unit)? = null
    ) {
        Thread {
            try {
                val buffered: BufferedImage = ImageIO.read(ByteArrayInputStream(bytes))
                    ?: throw Exception("Cannot decode")
                val hiResW = buffered.width
                val hiResH = buffered.height

                val pngOut = ByteArrayOutputStream()
                val pngImg = if (buffered.type != BufferedImage.TYPE_INT_ARGB) {
                    val c = BufferedImage(hiResW, hiResH, BufferedImage.TYPE_INT_ARGB)
                    c.createGraphics().also { it.drawImage(buffered, 0, 0, null); it.dispose() }
                    c
                } else buffered
                ImageIO.write(pngImg, "png", pngOut)

                val nativeImage = NativeImage.read(ByteArrayInputStream(pngOut.toByteArray()))
                val loc = Identifier.fromNamespaceAndPath("chatimage", "hires_$imageId")

                Minecraft.getInstance().execute {
                    val mc = Minecraft.getInstance()
                    mc.textureManager.register(loc, DynamicTexture({ "chatimage_hires" }, nativeImage))
                    upgradeToHiRes(imageId, loc, hiResW, hiResH)
                    onDone?.invoke(loc, hiResW, hiResH)
                }
            } catch (e: Exception) {
                println("[ChatImage] loadAndUpgradeHiRes error $imageId: ${e.message}")
            }
        }.also { it.isDaemon = true }.start()
    }

    /**
     * Регистрирует placeholder для image в чате.
     * dispW/dispH — финальный размер карточки в gui-единицах, вычисляется из thumbnail.
     * Эти размеры НИКОГДА не изменятся — чат зарезервирует строки один раз.
     */
    fun registerPlaceholder(imageId: String, dispW: Int, dispH: Int) {
        sizes[imageId]       = Pair(dispW, dispH)
        origSizes[imageId]   = Pair(dispW, dispH)
        texSizes[imageId]    = Pair(dispW, dispH)
        loadedFlags[imageId] = false
        // previewTexture[imageId] — null, ChatComponentMixin рендерит иконку
    }

    /**
     * Вычисляет dispW/dispH из размеров thumbnail-данных, не загружая в GPU.
     * Используется для предварительного резервирования строк в чате.
     */
    fun computeDispSize(thumbnailData: ByteArray): Pair<Int, Int>? {
        return try {
            val buffered: BufferedImage = ImageIO.read(ByteArrayInputStream(thumbnailData)) ?: return null
            val mc = Minecraft.getInstance()
            val chatWidthPx = Mth.floor(mc.options.chatWidth().get() * 280.0 + 40.0)
            val maxDispW = ((chatWidthPx - 8) * 0.9).toInt()
                .coerceAtMost(ChatImageConfig.previewMaxW)
                .coerceAtLeast(40)
            val chatScale = mc.options.chatScale().get().toFloat().coerceAtLeast(1f)
            val texW = buffered.width
            val texH = buffered.height
            val dispWraw = (texW / chatScale).toInt().coerceAtLeast(1)
            val dispHraw = (texH / chatScale).toInt().coerceAtLeast(1)
            val maxDispH = (mc.window.guiScaledHeight / 3)
                .coerceAtMost(ChatImageConfig.previewMaxH)
                .coerceAtLeast(40)
            val sW = if (dispWraw > maxDispW) maxDispW.toFloat() / dispWraw else 1f
            val sH = if ((dispHraw * sW).toInt() > maxDispH) maxDispH.toFloat() / dispHraw else sW
            val s = minOf(sW, sH)
            val dispW = (dispWraw * s).toInt().coerceAtLeast(1)
            val dispH = (dispHraw * s).toInt().coerceAtLeast(1)
            Pair(dispW, dispH)
        } catch (_: Exception) { null }
    }

    /**
     * Загружает миниатюру в GPU текстуру для показа в чате.
     * dispW/dispH берутся из sizes[] (уже зафиксированы) — текстура масштабируется под них.
     * Если sizes[] ещё нет (редкий случай) — вычисляет и фиксирует.
     */
    fun loadThumbnail(imageId: String, thumbnailData: ByteArray, onLoaded: ((dispW: Int, dispH: Int) -> Unit)? = null) {
        try {
            val buffered: BufferedImage = ImageIO.read(ByteArrayInputStream(thumbnailData))
                ?: throw Exception("Cannot decode thumbnail")

            val mc = Minecraft.getInstance()
            val chatScale = mc.options.chatScale().get().toFloat().coerceIn(0.01f, 1f)
            val guiScale = mc.window.guiScale.toFloat().coerceAtLeast(1f)
            val chatWidthPx = Mth.floor(mc.options.chatWidth().get() * 280.0 + 40.0)
            val maxDispW = ((chatWidthPx - 8) * 0.9).toInt().coerceAtLeast(40)

            val texW = buffered.width
            val texH = buffered.height

            // dispW/H — берём из зафиксированного sizes[], если есть
            val (dispW, dispH) = sizes[imageId] ?: run {
                val dispWraw = (texW / chatScale).toInt().coerceAtLeast(1)
                val dispHraw = (texH / chatScale).toInt().coerceAtLeast(1)
                val maxDispH = (mc.window.guiScaledHeight / 3).coerceAtLeast(40)
                val sW = if (dispWraw > maxDispW) maxDispW.toFloat() / dispWraw else 1f
                val sH = if ((dispHraw * sW).toInt() > maxDispH) maxDispH.toFloat() / dispHraw else sW
                val s = minOf(sW, sH)
                val dw = (dispWraw * s).toInt().coerceAtLeast(1)
                val dh = (dispHraw * s).toInt().coerceAtLeast(1)
                Pair(dw, dh)
            }

            // Целевой размер текстуры в физических пикселях = dispW * guiScale * chatScale
            val targetTexW = (dispW * guiScale * chatScale).toInt().coerceAtLeast(1)
            val targetTexH = (dispH * guiScale * chatScale).toInt().coerceAtLeast(1)

            // Масштабируем buffered до targetTexW x targetTexH с сохранением aspect ratio (letterbox)
            val scaleX = targetTexW.toFloat() / texW
            val scaleY = targetTexH.toFloat() / texH
            val s = minOf(scaleX, scaleY)
            val fitW = (texW * s).toInt().coerceAtLeast(1)
            val fitH = (texH * s).toInt().coerceAtLeast(1)

            val pngBytes: ByteArray
            val finalTexW: Int
            val finalTexH: Int

            if (fitW != texW || fitH != texH) {
                val scaled = BufferedImage(fitW, fitH, BufferedImage.TYPE_INT_RGB)
                val g = scaled.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.drawImage(buffered, 0, 0, fitW, fitH, null)
                g.dispose()
                val bos = ByteArrayOutputStream()
                ImageIO.write(scaled, "png", bos)
                pngBytes = bos.toByteArray()
                finalTexW = fitW
                finalTexH = fitH
            } else {
                val img = if (buffered.type == BufferedImage.TYPE_INT_RGB) buffered else {
                    val c = BufferedImage(texW, texH, BufferedImage.TYPE_INT_RGB)
                    c.createGraphics().also { it.drawImage(buffered, 0, 0, null); it.dispose() }
                    c
                }
                val bos = ByteArrayOutputStream()
                ImageIO.write(img, "png", bos)
                pngBytes = bos.toByteArray()
                finalTexW = texW
                finalTexH = texH
            }

            mc.execute {
                try {
                    val nativeImage = NativeImage.read(ByteArrayInputStream(pngBytes))
                    val texture = DynamicTexture({ "chatimg_$imageId" }, nativeImage)
                    val loc = Identifier.fromNamespaceAndPath("chatimage", "thumb_$imageId")
                    mc.textureManager.register(loc, texture)

                    previewTexture[imageId] = loc
                    // sizes[imageId] НЕ ТРОГАЕМ — размер зафиксирован
                    origSizes[imageId]   = Pair(finalTexW, finalTexH)
                    texSizes[imageId]    = Pair(finalTexW, finalTexH)
                    loadedFlags[imageId] = true

                    val (dw, dh) = sizes[imageId] ?: Pair(dispW, dispH)
                    onLoaded?.invoke(dw, dh)
                } catch (e: Exception) {
                    println("[ChatImage] Texture register error $imageId: ${e.message}")
                }
            }
        } catch (e: Exception) {
            println("[ChatImage] Thumbnail decode error $imageId: ${e.message}")
        }
    }

    /** Возвращает список imageId для которых есть данные (fullData) для перезагрузки */
    fun getAllLoadedIds(): List<String> = fullDataMap.keys.toList()

    /**
     * Пересчитывает dispW/dispH с текущим previewScale и перезагружает текстуру превью.
     * Вызывается когда пользователь двигает ползунок размера.
     * sizes[] (dispW/dispH) ОБНОВЛЯЮТСЯ — это меняет размер карточки в чате.
     */
    fun reloadThumbnail(imageId: String, thumbnailData: ByteArray) {
        try {
            val mc = Minecraft.getInstance()
            val buffered: BufferedImage = ImageIO.read(ByteArrayInputStream(thumbnailData)) ?: return
            val chatScale = mc.options.chatScale().get().toFloat().coerceAtLeast(1f)
            val chatWidthPx = Mth.floor(mc.options.chatWidth().get() * 280.0 + 40.0)
            val maxDispW = ((chatWidthPx - 8) * 0.9).toInt().coerceAtLeast(40)
            val maxDispH = (mc.window.guiScaledHeight / 3).coerceAtLeast(40)

            val texW = buffered.width
            val texH = buffered.height
            val dispWraw = (texW / chatScale).toInt().coerceAtLeast(1)
            val dispHraw = (texH / chatScale).toInt().coerceAtLeast(1)
            val sW = if (dispWraw > maxDispW) maxDispW.toFloat() / dispWraw else 1f
            val sH = if ((dispHraw * sW).toInt() > maxDispH) maxDispH.toFloat() / dispHraw else sW
            val s = minOf(sW, sH)
            val dispW = (dispWraw * s).toInt().coerceAtLeast(1)
            val dispH = (dispHraw * s).toInt().coerceAtLeast(1)

            // Обновляем dispW/dispH — карточка поменяет размер при следующем рендере
            sizes[imageId] = Pair(dispW, dispH)

            // Перезагружаем текстуру под новый размер (loadThumbnail уже умеет это)
            loadThumbnail(imageId, thumbnailData)
        } catch (e: Exception) {
            println("[ChatImage] reloadThumbnail error for $imageId: ${e.message}")
        }
    }

    /**
     * Заменяет текстуру превью на полную версию (после авто-загрузки).
     * Размер карточки НЕ меняется — только текстура становится лучше.
     * Вызывается с главного потока MC.
     */
    fun upgradeToFullTexture(mc: Minecraft, imageId: String, fullBytes: ByteArray) {
        if (deletedFlags[imageId] == true || errorFlags[imageId] == true) return
        try {
            val buffered: BufferedImage = ImageIO.read(ByteArrayInputStream(fullBytes))
                ?: return
            val chatScale = mc.options.chatScale().get().toFloat().coerceIn(0.01f, 1f)
            val guiScale = mc.window.guiScale.toFloat().coerceAtLeast(1f)
            val (dispW, dispH) = sizes[imageId] ?: return  // карточка уже зафиксирована

            val targetTexW = (dispW * guiScale * chatScale).toInt().coerceAtLeast(1)
            val targetTexH = (dispH * guiScale * chatScale).toInt().coerceAtLeast(1)

            val scaleX = targetTexW.toFloat() / buffered.width
            val scaleY = targetTexH.toFloat() / buffered.height
            val s = minOf(scaleX, scaleY)
            val fitW = (buffered.width * s).toInt().coerceAtLeast(1)
            val fitH = (buffered.height * s).toInt().coerceAtLeast(1)

            val scaled = if (fitW != buffered.width || fitH != buffered.height) {
                val out = BufferedImage(fitW, fitH, BufferedImage.TYPE_INT_RGB)
                val g = out.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.drawImage(buffered, 0, 0, fitW, fitH, null)
                g.dispose()
                out
            } else buffered

            val bos = ByteArrayOutputStream()
            ImageIO.write(scaled, "png", bos)
            val pngBytes = bos.toByteArray()

            // Освобождаем старую текстуру
            previewTexture[imageId]?.let { old ->
                try { mc.textureManager.release(old) } catch (_: Exception) {}
            }

            val nativeImage = NativeImage.read(ByteArrayInputStream(pngBytes))
            val loc = Identifier.fromNamespaceAndPath("chatimage", "full_${imageId}")
            mc.textureManager.register(loc, DynamicTexture({ "chatimage_full_$imageId" }, nativeImage))
            previewTexture[imageId] = loc
            texSizes[imageId] = Pair(fitW, fitH)
            loadedFlags[imageId] = true
        } catch (e: Exception) {
            println("[ChatImage] upgradeToFullTexture error for $imageId: ${e.message}")
        }
    }

    fun clear() {
        val mc = Minecraft.getInstance()
        previewTexture.forEach { (_, loc) ->
            try { mc.textureManager.release(loc) } catch (_: Exception) {}
        }
        previewTexture.clear()
        sizes.clear()
        origSizes.clear()
        texSizes.clear()
        loadedFlags.clear()
        fullDataMap.clear()
        deletedFlags.clear()
        errorFlags.clear()
    }
}
