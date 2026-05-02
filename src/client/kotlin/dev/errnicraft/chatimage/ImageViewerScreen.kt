package dev.errnicraft.chatimage

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import javax.imageio.ImageIO

/**
 * Полноэкранный просмотр фото.
 *
 * Стратегия:
 * 1. Сразу показываем превью-миниатюру пока грузится полное фото.
 * 2. Качаем полное фото по HTTP (или с диска / из кэша).
 * 3. Когда готово — рендерим в высоком качестве.
 *
 * Статус "Загружаю..." показывается ТОЛЬКО в нижней панели (там где S — сохранить).
 * Отдельная строка над фото убрана.
 */
class ImageViewerScreen(
    private val previewTextureId: Identifier,
    private val imageId: String,
    private val previewW: Int,
    private val previewH: Int,
    private val originalFile: File? = null
) : Screen(Component.literal("Image Viewer")) {

    private var hiResTextureId: Identifier? = null
    private var hiResW: Int = previewW
    private var hiResH: Int = previewH

    @Volatile private var isLoadingFull = false
    @Volatile private var loadError = false
    @Volatile private var isSaving = false

    override fun init() {
        super.init()
        // Сбрасываем курсор — в viewer не нужна рука
        org.lwjgl.glfw.GLFW.glfwSetCursor(minecraft!!.window.handle(), 0L)
        loadFullImage()
    }

    private fun loadFullImage() {
        if (isLoadingFull) return

        // Если hi-res текстура уже в ImageCache (была загружена при предыдущем открытии)
        // — берём её сразу, без повторной загрузки и без "Загружаю..." в строке статуса.
        val cachedTex = ImageCache.getTexture(imageId)
        val cachedTexSize = ImageCache.getTexSize(imageId)
        if (cachedTex != null && cachedTexSize != null && ImageCache.isLoaded(imageId)) {
            val origSize = ImageCache.getOrigSize(imageId)
            if (origSize != null && (origSize.first > previewW || origSize.second > previewH)) {
                // Это hi-res текстура — используем напрямую
                hiResTextureId = cachedTex
                hiResW = origSize.first
                hiResH = origSize.second
                hiResTransferredToCache = true
                return
            }
        }

        isLoadingFull = true

        // Байты уже в памяти (своя картинка или уже качали)
        val cachedFull = ImageCache.getFullData(imageId)
        if (cachedFull != null) {
            loadHiResFromBytes(cachedFull)
            return
        }

        // Оригинальный файл отправителя
        if (originalFile != null && originalFile.exists()) {
            Thread {
                try {
                    val bytes = originalFile.readBytes()
                    ImageCache.storeFullData(imageId, bytes)
                    loadHiResFromBytes(bytes)
                } catch (e: Exception) {
                    loadError = true
                    isLoadingFull = false
                }
            }.also { it.isDaemon = true }.start()
            return
        }

        // Сначала дисковый кэш — не качаем снова
        val diskBytes = ImageDiskCache.load(imageId)
        if (diskBytes != null) {
            ImageCache.storeFullData(imageId, diskBytes)
            loadHiResFromBytes(diskBytes)
            return
        }

        // Качаем по TCP и сохраняем на диск
        ChatImageClient.fetchFullImage(imageId) { bytes ->
            loadHiResFromBytes(bytes)
        }
    }

    private fun loadHiResFromBytes(bytes: ByteArray) {
        ImageCache.loadAndUpgradeHiRes(imageId, bytes) { loc, w, h ->
            hiResTextureId = loc
            hiResW = w
            hiResH = h
            isLoadingFull = false
            hiResTransferredToCache = true
        }
        // Ошибка декодирования логируется внутри loadAndUpgradeHiRes.
        // isLoadingFull сбросится в onDone на главном потоке.
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        guiGraphics.fill(0, 0, width, height, 0xFF000000.toInt())

        val padding = 8
        val barH = 22
        val maxW = width - padding * 2
        val maxH = height - padding * 2 - barH

        val texId = hiResTextureId ?: previewTextureId
        val texW  = if (hiResTextureId != null) hiResW else previewW
        val texH  = if (hiResTextureId != null) hiResH else previewH

        val s = minOf(maxW.toFloat() / texW, maxH.toFloat() / texH)
        val dispW = (texW * s).toInt().coerceAtLeast(1)
        val dispH = (texH * s).toInt().coerceAtLeast(1)
        val x = (width - dispW) / 2
        val y = padding + (maxH - dispH) / 2

        guiGraphics.pose().pushMatrix()
        guiGraphics.pose().translate(x.toFloat(), y.toFloat())
        guiGraphics.pose().scale(s, s)
        guiGraphics.blit(
            RenderPipelines.GUI_TEXTURED,
            texId,
            0, 0,
            0.0f, 0.0f,
            texW, texH,
            texW, texH,
            -1
        )
        guiGraphics.pose().popMatrix()

        // ── Нижняя панель ────────────────────────────────────────────────────
        val barY = height - barH
        guiGraphics.fill(0, barY, width, height, 0xBB000000.toInt())

        val canSave = originalFile != null || ImageCache.getFullData(imageId) != null

        // Строим подсказку — статус загрузки ТОЛЬКО здесь, не над фото
        val hint = buildString {
            append("§7[ ESC — ")
            append(ChatImageConfig.tr("закрыть", "close"))
            if (canSave) {
                append(" | §eS §7— ")
                append(ChatImageConfig.tr("сохранить", "save"))
            }
            when {
                isSaving -> {
                    append(" | §7")
                    append(ChatImageConfig.tr("Сохраняю...", "Saving..."))
                }
                isLoadingFull && hiResTextureId == null -> {
                    append(" | §7")
                    append(ChatImageConfig.tr("Загружаю...", "Loading..."))
                }
                loadError -> {
                    append(" | §c")
                    append(ChatImageConfig.tr("Ошибка загрузки", "Load error"))
                }
            }
            append(" ]")
        }
        guiGraphics.drawCenteredString(minecraft!!.font, hint, width / 2, barY + (barH - minecraft!!.font.lineHeight) / 2, 0xFFAAAAAA.toInt())

        super.render(guiGraphics, mouseX, mouseY, partialTick)
    }

    override fun keyPressed(event: net.minecraft.client.input.KeyEvent): Boolean {
        if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_S) {
            if (!isSaving) saveImage()
            return true
        }
        return super.keyPressed(event)
    }

    override fun mouseClicked(event: net.minecraft.client.input.MouseButtonEvent, doubleClick: Boolean): Boolean = false
    override fun shouldCloseOnEsc(): Boolean = true
    override fun isPauseScreen(): Boolean = false

    private var hiResTransferredToCache = false

    override fun onClose() {
        // Не освобождаем hi-res текстуру если она передана в ImageCache (для превью в чате)
        if (!hiResTransferredToCache) {
            hiResTextureId?.let {
                try { Minecraft.getInstance().textureManager.release(it) } catch (_: Exception) {}
            }
        }
        super.onClose()
    }

    private fun saveImage() {
        val mc = Minecraft.getInstance()
        isSaving = true
        Thread {
            try {
                val dir = mc.gameDirectory.toPath().resolve("screenshots").resolve("chatimages")
                Files.createDirectories(dir)
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(Date())
                val outFile = dir.resolve("chatimage_$timestamp.png").toFile()

                when {
                    originalFile != null && originalFile.exists() -> {
                        originalFile.copyTo(outFile, overwrite = true)
                    }
                    ImageCache.getFullData(imageId) != null -> {
                        val raw = ImageCache.getFullData(imageId)!!
                        val img = ImageIO.read(ByteArrayInputStream(raw))
                        if (img != null) ImageIO.write(img, "png", outFile)
                    }
                    else -> {
                        isSaving = false
                        return@Thread
                    }
                }

                mc.execute {
                    isSaving = false
                    mc.gui.getChat().addMessage(
                        Component.literal(
                            "§a[ChatImage] " + ChatImageConfig.tr(
                                "Сохранено: §fscreenshots/chatimages/chatimage_$timestamp.png",
                                "Saved: §fscreenshots/chatimages/chatimage_$timestamp.png"
                            )
                        )
                    )
                }
            } catch (e: Exception) {
                mc.execute {
                    isSaving = false
                    mc.gui.getChat().addMessage(
                        Component.literal("§c[ChatImage] " + ChatImageConfig.tr(
                            "Ошибка сохранения: ${e.message}",
                            "Save error: ${e.message}"
                        ))
                    )
                }
            }
        }.also { it.isDaemon = true }.start()
    }
}
