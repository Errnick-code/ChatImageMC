package dev.errnicraft.chatimage

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.brigadier.arguments.IntegerArgumentType
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.commands.Commands
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.roundToInt

object ChatImageConfig {
    @Volatile var resolution: String = "720"
    @Volatile var serverHost: String = ""
    @Volatile var imagePort: Int = 5050
    @Volatile var uploadToken: String = ""
    @Volatile var serverHasModVersion: String? = null
    @Volatile var serverReachable: Boolean = false

    /** Если сервер включил autoDownload — клиент автоматически качает полный файл фоном */
    @Volatile var autoDownload: Boolean = false

    /** Игрок забанен на этом сервере (получено PhotoDeniedPacket reason=banned) */
    @Volatile var isBanned: Boolean = false
        @JvmName("getBanned") get

    /** Cooldown в секундах между отправками фото (из конфига, default 5) */
    @Volatile var cooldownSeconds: Int = 5

    /** Время (System.currentTimeMillis) окончания кулдауна, 0 = не активен */
    @Volatile var cooldownUntilMs: Long = 0L

    /** Возвращает оставшиеся миллисекунды кулдауна (0 если не активен) */
    @JvmName("cooldownRemainingMs")
    fun cooldownRemainingMs(): Long = (cooldownUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)

    /** Активирует кулдаун на cooldownSeconds секунд */
    fun startCooldown() {
        cooldownUntilMs = System.currentTimeMillis() + cooldownSeconds * 1000L
    }

    /** Масштаб превью фото в чате: 1.0 = базовый, 0.5–2.0 */
    @Volatile var previewScale: Float = 1.0f
        set(value) { field = value.coerceIn(0.5f, 2.0f) }

    /** Масштаб карточки над полем ввода (pending image): 1.0 = базовый, 0.5–2.0 */
    @Volatile var inputPreviewScale: Float = 1.0f
        set(value) { field = value.coerceIn(0.5f, 2.0f) }

    private val configFile: File get() {
        val mc = try { Minecraft.getInstance() } catch (_: Exception) { null }
        val gameDir = mc?.gameDirectory ?: File(".")
        return File(gameDir, "config/chatimage.json")
    }

    fun saveConfig() {
        try {
            val f = configFile
            f.parentFile?.mkdirs()
            val scale = previewScale.coerceIn(0.5f, 2.0f)
            val inputScale = inputPreviewScale.coerceIn(0.5f, 2.0f)
            val json = com.google.gson.JsonObject().apply {
                addProperty("configVersion", CURRENT_CONFIG_VERSION)
                addProperty("previewScale", scale)
                addProperty("inputPreviewScale", inputScale)
            }
            f.writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(json))
        } catch (e: Exception) {
            println("[ChatImage] Config save error: ${e.message}")
        }
    }

    fun loadConfig() {
        try {
            val f = configFile
            if (!f.exists()) return
            val text = f.readText()
            val json = try {
                com.google.gson.Gson().fromJson(text, com.google.gson.JsonObject::class.java)
            } catch (_: Exception) { null } ?: return

            previewScale = json.get("previewScale")?.asFloat?.coerceIn(0.5f, 2.0f) ?: 1.0f
            inputPreviewScale = json.get("inputPreviewScale")?.asFloat?.coerceIn(0.5f, 2.0f) ?: 1.0f
        } catch (e: Exception) {
            println("[ChatImage] Config load error: ${e.message}")
        }
    }

    val previewMaxW: Int get() {
        val base = when (resolution) {
            "480" -> 112
            "HD"  -> 187
            else  -> 140
        }
        return (base * previewScale).roundToInt().coerceAtLeast(16)
    }
    val previewMaxH: Int get() {
        val base = when (resolution) {
            "480" -> 42
            "HD"  -> 70
            else  -> 52
        }
        return (base * previewScale).roundToInt().coerceAtLeast(8)
    }

    /** Размер карточки pending image над полем ввода */
    val inputPreviewMaxW: Int get() {
        val base = when (resolution) {
            "480" -> 112
            "HD"  -> 187
            else  -> 140
        }
        return (base * inputPreviewScale).roundToInt().coerceAtLeast(16)
    }
    val inputPreviewMaxH: Int get() {
        val base = when (resolution) {
            "480" -> 42
            "HD"  -> 70
            else  -> 52
        }
        return (base * inputPreviewScale).roundToInt().coerceAtLeast(8)
    }

    val maxDim: Int get() = when (resolution) {
        "480" -> 640
        "HD"  -> 1920
        "2K"  -> 2560
        else  -> 1280
    }

    fun reset() {
        resolution = "720"
        serverHost = ""
        imagePort = 5050
        uploadToken = ""
        serverHasModVersion = null
        serverReachable = false
        autoDownload = false
        isBanned = false
        cooldownSeconds = 5
        cooldownUntilMs = 0L
        // previewScale и inputPreviewScale НЕ сбрасываем — это локальные настройки клиента
    }

    /** Возвращает переведённую строку через систему локализации Minecraft */
    fun tr(key: String, vararg args: Any): String {
        return try {
            Component.translatable(key, *args).string
        } catch (_: Exception) {
            key
        }
    }

    private const val CURRENT_CONFIG_VERSION = 1
}

class ChatImageClient : ClientModInitializer {

    override fun onInitializeClient() {
        ChatImageConfig.loadConfig()
        DragDropHandler.register()

        // ── Два ползунка размера превью — добавляем в OptionsList ─────────────
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is net.minecraft.client.gui.screens.options.ChatOptionsScreen) return@register

            val MIN = 0.5f; val MAX = 2.0f; val STEP = 0.05f

            // ── Ползунок 1: размер превью в чате ──
            val initChat = ((ChatImageConfig.previewScale - MIN) / (MAX - MIN)).toDouble().coerceIn(0.0, 1.0)
            val chatSlider = object : net.minecraft.client.gui.components.AbstractSliderButton(
                0, 0, 150, 20,
                net.minecraft.network.chat.Component.empty(), initChat
            ) {
                init { updateMessage() }
                override fun updateMessage() {
                    var s = MIN + value.toFloat() * (MAX - MIN)
                    s = (Math.round(s / STEP) * STEP).coerceIn(MIN, MAX)
                    setMessage(net.minecraft.network.chat.Component.translatable(
                        "chatimage.preview_scale_slider", "%.0f%%".format(s * 100)
                    ))
                }
                override fun applyValue() {
                    var s = MIN + value.toFloat() * (MAX - MIN)
                    s = (Math.round(s / STEP) * STEP).coerceIn(MIN, MAX)
                    ChatImageConfig.previewScale = s
                    ChatImageConfig.saveConfig()
                }
            }

            // ── Ползунок 2: размер карточки над полем ввода ──
            val initInput = ((ChatImageConfig.inputPreviewScale - MIN) / (MAX - MIN)).toDouble().coerceIn(0.0, 1.0)
            val inputSlider = object : net.minecraft.client.gui.components.AbstractSliderButton(
                0, 0, 150, 20,
                net.minecraft.network.chat.Component.empty(), initInput
            ) {
                init { updateMessage() }
                override fun updateMessage() {
                    var s = MIN + value.toFloat() * (MAX - MIN)
                    s = (Math.round(s / STEP) * STEP).coerceIn(MIN, MAX)
                    setMessage(net.minecraft.network.chat.Component.translatable(
                        "chatimage.input_scale_slider", "%.0f%%".format(s * 100)
                    ))
                }
                override fun applyValue() {
                    var s = MIN + value.toFloat() * (MAX - MIN)
                    s = (Math.round(s / STEP) * STEP).coerceIn(MIN, MAX)
                    ChatImageConfig.inputPreviewScale = s
                    ChatImageConfig.saveConfig()
                }
            }

            // addSmall(first, second) — два виджета по 150px в одну строку сетки
            screen.children()
                .filterIsInstance<net.minecraft.client.gui.components.OptionsList>()
                .firstOrNull()
                ?.addSmall(chatSlider, inputSlider)
        }

        ClientPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val mc = Minecraft.getInstance()
            ChatImageConfig.serverHost = if (mc.isLocalServer) {
                "127.0.0.1"
            } else {
                val addr = handler.connection.remoteAddress
                when (addr) {
                    is java.net.InetSocketAddress -> {
                        val ip = addr.address?.hostAddress ?: addr.hostString
                        ip.ifBlank { "127.0.0.1" }
                    }
                    else -> {
                        val raw = addr?.toString() ?: ""
                        raw.substringAfterLast("/").substringBefore(":").trim().ifBlank { "127.0.0.1" }
                    }
                }
            }
            println("[ChatImage] Server host resolved: ${ChatImageConfig.serverHost}")
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            ImageCache.clear()
            ChatImageStore.clear()
            ChatImageConfig.reset()
        }

        ClientPlayNetworking.registerGlobalReceiver(ServerHelloPacket.TYPE) { payload, context ->
            context.client().execute {
                val serverVersion = payload.serverProtocolVersion
                ChatImageConfig.serverHasModVersion = serverVersion
                ClientPlayNetworking.send(ClientHelloPacket(MOD_PROTOCOL_VERSION))
                if (serverVersion != MOD_PROTOCOL_VERSION) {
                    val chat = Minecraft.getInstance().gui.getChat()
                    // Сравниваем semver: если сервер новее — предупреждаем об обновлении
                    val serverNewer = compareModVersions(serverVersion, MOD_PROTOCOL_VERSION) > 0
                    if (serverNewer) {
                        chat.addMessage(Component.literal(
                            "§8[ChatImage] §e⚠ На сервере установлена более новая версия мода (v$serverVersion), " +
                            "у вас v${MOD_VERSION}. Рекомендуется обновить мод."
                        ))
                    } else {
                        chat.addMessage(Component.literal("§8[ChatImage] §e" + ChatImageConfig.tr(
                            "chatimage.version_warn", serverVersion, MOD_VERSION
                        )))
                    }
                }
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(ServerConfigPacket.TYPE) { payload, context ->
            context.client().execute {
                val res = payload.resolution
                if (res == "480" || res == "720" || res == "HD" || res == "2K") ChatImageConfig.resolution = res
                ChatImageConfig.imagePort = payload.imagePort
                ChatImageConfig.uploadToken = payload.uploadToken
                ChatImageConfig.autoDownload = payload.autoDownload
                ChatImageConfig.cooldownSeconds = payload.photoCooldownSeconds.coerceAtLeast(0)
                Thread { pingTcpServer(context.client()) }.also { it.isDaemon = true }.start()
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(HandshakeErrorPacket.TYPE) { payload, context ->
            context.client().execute {
                Minecraft.getInstance().gui.getChat().addMessage(
                    Component.literal("§8[§bChatImage§8] §c❌ ${payload.reason}")
                )
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(ImageDeletedPacket.TYPE) { payload, context ->
            context.client().execute {
                ImageCache.markDeleted(payload.imageId)
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(ImageErrorPacket.TYPE) { payload, context ->
            context.client().execute {
                val imageId = payload.imageId
                val reason = payload.reason
                ImageCache.markError(imageId)
                val msg = when (reason) {
                    "timeout" -> "§cФото не удалось загрузить: сервер не получил файл вовремя."
                    "decode_error" -> "§cФото не удалось обработать: ошибка декодирования."
                    else -> "§cФото не удалось загрузить."
                }
                Minecraft.getInstance().gui.getChat().addMessage(
                    Component.literal("§8[ChatImage] $msg")
                )
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(PhotoDeniedPacket.TYPE) { payload, context ->
            context.client().execute {
                if (payload.reason == "banned") {
                    ChatImageConfig.isBanned = true
                }
                val msg = when (payload.reason) {
                    "banned" -> "§c[ChatImage] " + ChatImageConfig.tr("chatimage.banned")
                    else -> "§c[ChatImage] " + payload.reason
                }
                Minecraft.getInstance().gui.getChat().addMessage(Component.literal(msg))
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(ImageChatPacket.TYPE) { payload, context ->
            context.client().execute {
                // Если отправитель — мы сами, сообщение уже показано локально мгновенно.
                // Пропускаем чтобы не дублировать.
                val alreadyShown = ChatImageStore.messages.any { it.imageId == payload.imageId }
                if (!alreadyShown) {
                    addImageToChat(context.client(), payload.imageId, payload.sender, payload.caption, payload.width, payload.height, payload.senderComponent)
                }
            }
        }

        // ── Клиентские команды для дебага ────────────────────────────────────
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->

            // Пресеты aspect ratio: имя команды → (rw, rh)
            val presets = linkedMapOf(
                "1_1"   to Pair(1, 1),
                "4_3"   to Pair(4, 3),
                "3_2"   to Pair(3, 2),
                "16_9"  to Pair(16, 9),
                "16_10" to Pair(16, 10),
                "21_9"  to Pair(21, 9),
                "9_16"  to Pair(9, 16),
                "3_4"   to Pair(3, 4),
                "2_3"   to Pair(2, 3)
            )
            // Читаемые метки для чата (с двоеточием)
            val presetLabels = mapOf(
                "1_1" to "1:1", "4_3" to "4:3", "3_2" to "3:2",
                "16_9" to "16:9", "16_10" to "16:10", "21_9" to "21:9",
                "9_16" to "9:16", "3_4" to "3:4", "2_3" to "2:3"
            )

            // Строим узел placeholder — без аргументов запускает 16:9 по умолчанию
            var placeholderNode = literal("placeholder")
                .executes { _ ->
                    val mc = Minecraft.getInstance()
                    mc.execute { debugShowPlaceholder(mc, 16.0 / 9.0, "16:9") }
                    1
                }

            // Подкоманда для каждого пресета
            for ((cmdName, ratio) in presets) {
                val (rw, rh) = ratio
                val label = presetLabels[cmdName] ?: cmdName
                placeholderNode = placeholderNode.then(
                    literal(cmdName).executes { _ ->
                        val mc = Minecraft.getInstance()
                        mc.execute { debugShowPlaceholder(mc, rw.toDouble() / rh.toDouble(), label) }
                        1
                    }
                )
            }

            // Подкоманда custom <width> <height>
            placeholderNode = placeholderNode.then(
                literal("custom").then(
                    argument("width", IntegerArgumentType.integer(1, 7680)).then(
                        argument("height", IntegerArgumentType.integer(1, 4320))
                            .executes { ctx ->
                                val w = IntegerArgumentType.getInteger(ctx, "width")
                                val h = IntegerArgumentType.getInteger(ctx, "height")
                                val mc = Minecraft.getInstance()
                                mc.execute { debugShowPlaceholder(mc, w.toDouble() / h.toDouble(), "${w}x${h}") }
                                1
                            }
                    )
                )
            )

            val testNode = literal("test")
                .executes { _ ->
                    val mc = Minecraft.getInstance()
                    mc.execute { debugTestConnection(mc) }
                    1
                }

            // placeholder_deleted — показывает плейсхолдер удалённого фото (красный ✗)
            var placeholderDeletedNode = literal("placeholder_deleted")
                .executes { _ ->
                    val mc = Minecraft.getInstance()
                    mc.execute { debugShowPlaceholderState(mc, "deleted") }
                    1
                }
            for ((cmdName, ratio) in presets) {
                val (rw, rh) = ratio
                val label = presetLabels[cmdName] ?: cmdName
                placeholderDeletedNode = placeholderDeletedNode.then(
                    literal(cmdName).executes { _ ->
                        val mc = Minecraft.getInstance()
                        mc.execute { debugShowPlaceholderState(mc, "deleted", rw.toDouble() / rh.toDouble(), label) }
                        1
                    }
                )
            }

            // placeholder_error — показывает плейсхолдер ошибки загрузки (жёлтый !)
            var placeholderErrorNode = literal("placeholder_error")
                .executes { _ ->
                    val mc = Minecraft.getInstance()
                    mc.execute { debugShowPlaceholderState(mc, "error") }
                    1
                }
            for ((cmdName, ratio) in presets) {
                val (rw, rh) = ratio
                val label = presetLabels[cmdName] ?: cmdName
                placeholderErrorNode = placeholderErrorNode.then(
                    literal(cmdName).executes { _ ->
                        val mc = Minecraft.getInstance()
                        mc.execute { debugShowPlaceholderState(mc, "error", rw.toDouble() / rh.toDouble(), label) }
                        1
                    }
                )
            }

            dispatcher.register(
                literal("chatimagedebug")
                    .then(placeholderNode)
                    .then(placeholderDeletedNode)
                    .then(placeholderErrorNode)
                    .then(testNode)
            )
        }
    }

    companion object {
        private const val MAX_FULL_BYTES = 8 * 1024 * 1024

        @JvmStatic
        /** Сравнивает версии протокола как semver. Возвращает >0 если a > b */
        fun compareModVersions(a: String, b: String): Int {
            fun parse(v: String) = v.split(".").map { it.toIntOrNull() ?: 0 }
            val pa = parse(a); val pb = parse(b)
            val len = maxOf(pa.size, pb.size)
            for (i in 0 until len) {
                val diff = (pa.getOrElse(i) { 0 }) - (pb.getOrElse(i) { 0 })
                if (diff != 0) return diff
            }
            return 0
        }

        fun debugTestConnection(mc: Minecraft) {
            val chat = mc.gui.getChat()
            val host = ChatImageConfig.serverHost
            val port = ChatImageConfig.imagePort
            val hasMod = ChatImageConfig.serverHasModVersion
            val token = ChatImageConfig.uploadToken

            chat.addMessage(Component.literal("§8[ChatImage] §7--- Connection test ---"))

            // 1. Server mod handshake
            if (hasMod == null) {
                chat.addMessage(Component.literal("§8[ChatImage] §cServer mod: §cnot detected (no handshake)"))
            } else {
                chat.addMessage(Component.literal("§8[ChatImage] §aServer mod: §fv$hasMod"))
            }

            // 2. Upload token
            if (token.isEmpty()) {
                chat.addMessage(Component.literal("§8[ChatImage] §cUpload token: §cnot received"))
            } else {
                chat.addMessage(Component.literal("§8[ChatImage] §aUpload token: §freceived (${token.length} chars)"))
            }

            // 3. TCP ping (in background)
            chat.addMessage(Component.literal("§8[ChatImage] §7TCP $host:$port — pinging..."))
            Thread {
                val start = System.currentTimeMillis()
                val ok = TcpImageClient.ping()
                val ms = System.currentTimeMillis() - start
                ChatImageConfig.serverReachable = ok
                mc.execute {
                    if (ok) {
                        chat.addMessage(Component.literal("§8[ChatImage] §aTCP: §aOK §7(${ms}ms)"))
                        chat.addMessage(Component.literal("§8[ChatImage] §a✔ All systems ready"))
                    } else {
                        chat.addMessage(Component.literal("§8[ChatImage] §cTCP: §ccannot connect to $host:$port"))
                        chat.addMessage(Component.literal("§8[ChatImage] §c✘ Check server port/firewall"))
                    }
                }
            }.also { it.isDaemon = true }.start()
        }

        private fun pingTcpServer(mc: Minecraft) {
            val ok = TcpImageClient.ping()
            ChatImageConfig.serverReachable = ok
            mc.execute {
                if (ok) {
                    mc.gui.getChat().addMessage(
                        Component.literal("§8[ChatImage] §a✔ " + ChatImageConfig.tr(
                            "chatimage.connected", "§7${ChatImageConfig.resolution}§a"
                        ))
                    )
                } else {
                    mc.gui.getChat().addMessage(
                        Component.literal("§8[ChatImage] §c✘ " + ChatImageConfig.tr(
                            "chatimage.no_tcp_connect", ChatImageConfig.imagePort.toString()
                        ))
                    )
                }
            }
        }

        @JvmStatic
        fun addImageToChat(mc: Minecraft, imageId: String, sender: String, caption: String?, width: Int, height: Int, senderComponent: net.minecraft.network.chat.Component? = null) {
            // Если пришёл senderComponent — используем напрямую (несёт цвета от сервера).
            // Иначе — ищем в tab-листе (fallback для локального показа).
            val senderComp: net.minecraft.network.chat.Component = senderComponent
                ?.takeIf { it.string.isNotEmpty() }
                ?: mc.connection?.onlinePlayers
                    ?.firstOrNull { it.profile.name == sender }
                    ?.tabListDisplayName
                ?: net.minecraft.network.chat.Component.literal(sender)

            val msgText = net.minecraft.network.chat.MutableComponent.create(
                net.minecraft.network.chat.contents.PlainTextContents.EMPTY
            ).also { base ->
                base.append(net.minecraft.network.chat.Component.literal("<"))
                base.append(senderComp)
                base.append(net.minecraft.network.chat.Component.literal(">"))
                if (!caption.isNullOrEmpty()) {
                    base.append(net.minecraft.network.chat.Component.literal(" $caption"))
                }
                base.append(net.minecraft.network.chat.Component.literal(" §7[📷]"))
            }

            // ── Шаг 1: сразу вычисляем dispW/dispH из переданных width/height ──
            // Не нужно ждать декодирования thumbnail — размер известен мгновенно.
            val maxW = ChatImageConfig.previewMaxW
            val maxH = ChatImageConfig.previewMaxH
            val aspect = if (height > 0) width.toDouble() / height.toDouble() else 16.0 / 9.0
            val (dispW, dispH) = if (aspect >= maxW.toDouble() / maxH.toDouble()) {
                val w = maxW
                val h = (w / aspect).toInt().coerceAtLeast(1)
                Pair(w, h)
            } else {
                val h = maxH
                val w = (h * aspect).toInt().coerceAtLeast(1)
                Pair(w, h)
            }

            // ── Шаг 2: мгновенно показываем плейсхолдер и добавляем в чат ──
            ImageCache.registerPlaceholder(imageId, dispW, dispH)
            mc.gui.getChat().addMessage(msgText)
            val addedTime = mc.gui.getGuiTicks()
            ChatImageStore.addMessage(imageId, sender, caption ?: "", addedTime)

            val chatLineSpacing = mc.options.chatLineSpacing().get()
            val entryHeight = (9.0 * (chatLineSpacing + 1.0)).toInt()
            val extraLines = ceil(dispH.toDouble() / entryHeight).toInt()
            repeat(extraLines) { mc.gui.getChat().addMessage(Component.literal("")) }

            // ── Шаг 3: фоном качаем полный PNG по TCP и генерируем thumbnail ──
            // Каждый клиент делает это сам — сервер не нагружается.
            Thread {
                fetchFullImage(imageId) { bytes ->
                    val thumbnail = generateThumbnail(bytes, dispW, dispH) ?: return@fetchFullImage
                    ImageCache.loadThumbnail(imageId, thumbnail)

                    if (ChatImageConfig.autoDownload) {
                        // fullBytes уже скачаны — просто апгрейдим hi-res
                        ImageCache.loadAndUpgradeHiRes(imageId, bytes)
                    }
                }
            }.also { it.isDaemon = true }.start()
        }

        /**
         * Генерирует PNG-превью из полного PNG, масштабируя под dispW×dispH GUI-единиц.
         * Вызывается в фоновом потоке.
         */
        private fun generateThumbnail(fullBytes: ByteArray, dispW: Int, dispH: Int): ByteArray? {
            return try {
                val original = ImageIO.read(ByteArrayInputStream(fullBytes)) ?: return null
                val mc = Minecraft.getInstance()
                // Целевой размер в физических пикселях = dispW/H * guiScale монитора.
                // chatScale влияет только на матрицу рендера, guiScale определяет физические пиксели.
                val guiScale = try { mc.window.guiScale.toFloat().coerceAtLeast(1f) } catch (_: Exception) { 1f }
                val chatScale = try { mc.options.chatScale().get().toFloat().coerceIn(0.01f, 1f) } catch (_: Exception) { 1f }
                val targetTexW = (dispW * guiScale * chatScale).toInt().coerceAtLeast(1)
                val targetTexH = (dispH * guiScale * chatScale).toInt().coerceAtLeast(1)
                val scaleX = targetTexW.toDouble() / original.width
                val scaleY = targetTexH.toDouble() / original.height
                val scale  = minOf(scaleX, scaleY).coerceAtMost(1.0)
                val scaled = scaleImage(original, scale, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                toPng(scaled)
            } catch (e: Exception) {
                println("[ChatImage] generateThumbnail error: ${e.message}")
                null
            }
        }

        @JvmStatic
        fun fetchFullImage(imageId: String, onReady: (ByteArray) -> Unit) {
            val diskCached = ImageDiskCache.load(imageId)
            if (diskCached != null) { ImageCache.storeFullData(imageId, diskCached); onReady(diskCached); return }
            val cached = ImageCache.getFullData(imageId)
            if (cached != null) { onReady(cached); return }
            Thread {
                val bytes = TcpImageClient.getFull(imageId)
                if (bytes != null) {
                    ImageCache.storeFullData(imageId, bytes)
                    ImageDiskCache.save(imageId, bytes)
                    onReady(bytes)
                } else {
                    println("[ChatImage] Download failed for $imageId after all retries — marking as error")
                    Minecraft.getInstance().execute { ImageCache.markError(imageId) }
                }
            }.also { it.isDaemon = true }.start()
        }

        @JvmStatic
        fun stageImage(file: File) {
            val mc = Minecraft.getInstance()
            if (!canSendPhoto(mc)) return

            val maxPW = ChatImageConfig.inputPreviewMaxW
            val maxPH = ChatImageConfig.inputPreviewMaxH

            // ── Шаг 1: читаем размеры из заголовка файла — мгновенно, без декодирования ──
            // Если не удалось (редкий формат) — используем дефолтное 16:9
            val headerSize = readImageSizeFromHeader(file)
            val aspect = if (headerSize != null && headerSize.first > 0 && headerSize.second > 0)
                headerSize.first.toDouble() / headerSize.second.toDouble()
            else 16.0 / 9.0

            val (dispW, dispH) = if (aspect >= maxPW.toDouble() / maxPH.toDouble()) {
                val w = maxPW; Pair(w, (w / aspect).toInt().coerceAtLeast(1))
            } else {
                val h = maxPH; Pair((h * aspect).toInt().coerceAtLeast(1), h)
            }

            // ── Шаг 2: МГНОВЕННО показываем карточку-плейсхолдер ──
            PendingImageState.clear()
            PendingImageState.setPending(
                PendingImageState.PendingImage(
                    file = file, textureId = null,
                    width = dispW, height = dispH,
                    textureWidth = dispW, textureHeight = dispH,
                    previewBytes = ByteArray(0), rawBytes = ByteArray(0)
                )
            )

            // ── Шаг 3: в фоне полностью декодируем, масштабируем, грузим текстуру ──
            Thread {
                try {
                    val original: BufferedImage = ImageIO.read(file) ?: run {
                        mc.execute {
                            PendingImageState.clear()
                            mc.gui.getChat().addMessage(Component.literal("§c[ChatImage] " + ChatImageConfig.tr("chatimage.cannot_read")))
                        }
                        return@Thread
                    }

                    val maxDim = ChatImageConfig.maxDim
                    // Масштабируем ТОЛЬКО если фото больше лимита сервера — иначе передаём как есть
                    val fullScale = if (original.width > maxDim || original.height > maxDim)
                        minOf(maxDim.toDouble() / original.width, maxDim.toDouble() / original.height)
                    else 1.0

                    val fullScaled = scaleImage(original, fullScale, BufferedImage.TYPE_INT_RGB)
                    val fullBytes  = toPng(fullScaled)

                    if (fullBytes.size > MAX_FULL_BYTES) {
                        mc.execute {
                            PendingImageState.clear()
                            mc.gui.getChat().addMessage(Component.literal("§c[ChatImage] " + ChatImageConfig.tr("chatimage.file_too_large_compress")))
                        }
                        return@Thread
                    }

                    // Целевой размер превью в физических пикселях = dispW/H * guiScale * chatScale
                    val guiScale = try { mc.window.guiScale.toFloat().coerceAtLeast(1f) } catch (_: Exception) { 1f }
                    val chatScaleVal = mc.options.chatScale().get().toFloat().coerceIn(0.01f, 1f)
                    val targetTexW = (dispW * guiScale * chatScaleVal).toInt().coerceAtLeast(1)
                    val targetTexH = (dispH * guiScale * chatScaleVal).toInt().coerceAtLeast(1)
                    val previewScale = minOf(
                        targetTexW.toDouble() / fullScaled.width,
                        targetTexH.toDouble() / fullScaled.height
                    ).coerceAtMost(1.0)

                    val previewScaled = scaleImage(fullScaled, previewScale, BufferedImage.TYPE_INT_ARGB)
                    val previewBytes  = toPng(previewScaled)

                    // ── Шаг 4: загружаем текстуру — карточка уже на экране, просто обновляем ──
                    mc.execute {
                        val cur = PendingImageState.getPending()
                        if (cur == null || cur.file != file) return@execute
                        try {
                            val nativeImage = NativeImage.read(ByteArrayInputStream(previewBytes))
                            val previewId = Identifier.fromNamespaceAndPath("chatimage", "preview_${System.currentTimeMillis()}")
                            mc.textureManager.register(previewId, DynamicTexture({ "chatimage_preview" }, nativeImage))
                            PendingImageState.updateTexture(
                                textureId = previewId,
                                textureWidth = previewScaled.width, textureHeight = previewScaled.height,
                                previewBytes = previewBytes, rawBytes = fullBytes
                            )
                        } catch (e: Exception) {
                            PendingImageState.clear()
                            mc.gui.getChat().addMessage(Component.literal("§c[ChatImage] " + ChatImageConfig.tr("chatimage.preview_error", e.message ?: "?")))
                        }
                    }
                } catch (e: Exception) {
                    mc.execute {
                        PendingImageState.clear()
                        mc.gui.getChat().addMessage(Component.literal("§c[ChatImage] " + ChatImageConfig.tr("chatimage.error", e.message ?: "?")))
                    }
                }
            }.also { it.isDaemon = true }.start()
        }

        @JvmStatic
        fun canSendPhoto(mc: Minecraft): Boolean {
            if (ChatImageConfig.serverHasModVersion == null) {
                mc.gui.getChat().addMessage(Component.literal("§c[ChatImage] " + ChatImageConfig.tr("chatimage.no_server_mod")))
                return false
            }
            if (ChatImageConfig.isBanned) {
                mc.gui.getChat().addMessage(Component.literal("§c[ChatImage] " + ChatImageConfig.tr("chatimage.banned")))
                return false
            }
            val cooldownMs = ChatImageConfig.cooldownRemainingMs()
            if (cooldownMs > 0L) {
                val totalSec = (cooldownMs + 999L) / 1000L
                val cooldownMsg = if (totalSec >= 60L) {
                    val m = totalSec / 60L; val s = totalSec % 60L
                    ChatImageConfig.tr("chatimage.cooldown_minutes", m, s)
                } else {
                    ChatImageConfig.tr("chatimage.cooldown_seconds", totalSec)
                }
                mc.gui.getChat().addMessage(Component.literal("§e[ChatImage] $cooldownMsg"))
                return false
            }
            if (ChatImageConfig.uploadToken.isEmpty()) {
                mc.gui.getChat().addMessage(Component.literal("§e[ChatImage] " + ChatImageConfig.tr("chatimage.handshake_wait")))
                return false
            }
            if (!ChatImageConfig.serverReachable) {
                mc.gui.getChat().addMessage(Component.literal("§c[ChatImage] " + ChatImageConfig.tr("chatimage.no_tcp")))
                return false
            }
            return true
        }

        @JvmStatic
        fun sendPendingImageWithCaption(caption: String?) {
            val mc = Minecraft.getInstance()
            val player = mc.player ?: return
            val pending = PendingImageState.getPending() ?: return
            if (!canSendPhoto(mc)) return
            if (!pending.isLoaded()) {
                mc.gui.getChat().addMessage(Component.literal("§e[ChatImage] " + ChatImageConfig.tr("chatimage.image_loading_wait")))
                return
            }

            val imageId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12)
            val sender = player.gameProfile.name
            val senderComp = player.displayName ?: net.minecraft.network.chat.Component.literal(sender)
            val fullBytes = pending.rawBytes
            val token = ChatImageConfig.uploadToken
            val captionSafe = caption ?: ""

            // Размеры уже известны из stageImage — читать файл заново не нужно.
            // Читаем из заголовка файла мгновенно (несколько байт).
            val headerSize = readImageSizeFromHeader(pending.file)
            val origW = headerSize?.first ?: 1280
            val origH = headerSize?.second ?: 720

            // ── Шаг 1: МГНОВЕННО показываем в чате и очищаем превью ──
            PendingImageState.setPending(null)
            ChatImageStore.storeOriginalFile(imageId, pending.file)
            ImageCache.storeFullData(imageId, fullBytes)
            addImageToChat(mc, imageId, sender, captionSafe, origW, origH, senderComp)
            ChatImageConfig.startCooldown()
            ClientPlayNetworking.send(ImageUploadedPacket(imageId, sender, captionSafe, origW, origH))

            // ── Шаг 2: в фоне сохраняем на диск и грузим на TCP-сервер ──
            Thread {
                ImageDiskCache.save(imageId, fullBytes)
                val result = TcpImageClient.upload(imageId, token, fullBytes)
                when (result) {
                    "ok" -> { /* файл на сервере — остальные клиенты скачают по TCP */ }
                    "forbidden" -> mc.execute {
                        mc.gui.getChat().addMessage(Component.literal("§c[ChatImage] " + ChatImageConfig.tr("chatimage.banned")))
                        ImageCache.markError(imageId)
                    }
                    "too_large" -> mc.execute {
                        mc.gui.getChat().addMessage(Component.literal("§c[ChatImage] " + ChatImageConfig.tr("chatimage.too_large_upload")))
                        ImageCache.markError(imageId)
                    }
                    else -> mc.execute {
                        // Все ретраи исчерпаны — фото не дошло до сервера.
                        // Остальные игроки видят плейсхолдер, отправитель видит ошибку.
                        mc.gui.getChat().addMessage(Component.literal("§c[ChatImage] " + ChatImageConfig.tr("chatimage.upload_error", result)))
                        ImageCache.markError(imageId)
                    }
                }
            }.also { it.isDaemon = true }.start()
        }

        @JvmStatic
        fun sendPendingImage() = sendPendingImageWithCaption(null)

        /**
         * Дебаг-команда: симулирует карточку фото в чате которая "грузится но не может загрузиться".
         * Клиент регистрирует размер и placeholder-состояние как при реальном пакете,
         * но текстура никогда не появится — иконка остаётся навсегда.
         *
         * @param aspect  соотношение сторон (width/height), например 16.0/9.0
         * @param label   отображаемое название пресета, например "16:9" или "1920x1080"
         */
        /**
         * Дебаг-команда: симулирует получение реального ImageChatPacket с нужным aspect ratio.
         * Проходит через addImageToChat → computeDispSize → registerPlaceholder — точно как сервер.
         * Текстура никогда не загрузится, иконка останется навсегда.
         *
         * @param aspect  соотношение сторон (width/height), например 16.0/9.0
         * @param label   отображаемое название пресета, например "16:9" или "1920x1080"
         */
        @JvmStatic
        fun debugShowPlaceholder(mc: Minecraft, aspect: Double = 16.0 / 9.0, label: String = "16:9") {
            val sender = mc.player?.displayName?.string?.takeIf { it.isNotEmpty() } ?: mc.player?.gameProfile?.name ?: "debug"
            val caption = "[debug $label]"

            // Создаём синтетический thumbnail с нужным aspect ratio.
            // Размер выбираем таким же как реальный thumbnail который присылает сервер —
            // используем previewMaxW/previewMaxH из конфига (те же ограничения).
            // addImageToChat → computeDispSize сам правильно посчитает dispW/dispH.
            val safeAspect = aspect.coerceIn(0.05, 20.0)
            val thumbW: Int
            val thumbH: Int
            val maxW = ChatImageConfig.previewMaxW
            val maxH = ChatImageConfig.previewMaxH
            if (safeAspect >= maxW.toDouble() / maxH.toDouble()) {
                thumbW = maxW
                thumbH = (maxW / safeAspect).toInt().coerceAtLeast(1)
            } else {
                thumbH = maxH
                thumbW = (maxH * safeAspect).toInt().coerceAtLeast(1)
            }

            // Генерируем PNG с нужными размерами (однотонный серый, минимальный вес)
            val thumbnailBytes: ByteArray = try {
                val img = BufferedImage(thumbW, thumbH, BufferedImage.TYPE_INT_RGB)
                val g = img.createGraphics()
                g.color = java.awt.Color(30, 30, 30)
                g.fillRect(0, 0, thumbW, thumbH)
                g.dispose()
                val bos = ByteArrayOutputStream()
                ImageIO.write(img, "png", bos)
                bos.toByteArray()
            } catch (e: Exception) {
                println("[ChatImage] debug: cannot create synthetic thumbnail: ${e.message}")
                return
            }

            // Идём через точно тот же путь что и реальный пакет с сервера
            // addImageToChat: computeDispSize → registerPlaceholder → addMessage → loadThumbnail фоном
            // НО loadThumbnail мы не хотим — нужно чтобы иконка осталась.
            // Решение: регистрируем через addImageToChat, но imageId такой что
            // loadThumbnail зарегистрирует текстуру. Чтобы она НЕ появилась —
            // очищаем текстуру после загрузки через небольшую задержку.
            // Проще: вызываем addImageToChat напрямую — он запустит loadThumbnail в фоне,
            // но мы перехватим результат удалив текстуру после регистрации.
            // Самый чистый вариант: дублируем первые шаги addImageToChat без шага loadThumbnail.

            val imageId = "debug_ph_${System.currentTimeMillis()}"

            Thread {
                val dispPair = ImageCache.computeDispSize(thumbnailBytes)
                    ?: Pair(ChatImageConfig.previewMaxW, ChatImageConfig.previewMaxH)
                val (dispW, dispH) = dispPair

                mc.execute {
                    // Точная копия addImageToChat без вызова loadThumbnail
                    ImageCache.registerPlaceholder(imageId, dispW, dispH)

                    val msgText = Component.literal("§f<§b$sender§f> §f$caption §7[📷]")
                    mc.gui.getChat().addMessage(msgText)
                    val addedTime = mc.gui.getGuiTicks()
                    ChatImageStore.addMessage(imageId, sender, caption, addedTime)

                    val chatLineSpacing = mc.options.chatLineSpacing().get()
                    val entryHeight = (9.0 * (chatLineSpacing + 1.0)).toInt()
                    // +2 учитывает зазор IMG_VERT_PAD сверху и снизу
                    val extraLines = ceil(dispH.toDouble() / entryHeight).toInt()
                    repeat(extraLines) { mc.gui.getChat().addMessage(Component.literal("")) }

                    println("[ChatImage] debug placeholder: imageId=$imageId label=$label thumbW=$thumbW thumbH=$thumbH → dispW=$dispW dispH=$dispH extraLines=$extraLines")
                }
            }.also { it.isDaemon = true }.start()
        }

        /**
         * Показывает плейсхолдер в заданном состоянии: "deleted" (красный ✗) или "error" (жёлтый !)
         */
        fun debugShowPlaceholderState(
            mc: Minecraft,
            state: String,
            aspect: Double = 16.0 / 9.0,
            label: String = "16:9"
        ) {
            val sender = mc.player?.displayName?.string?.takeIf { it.isNotEmpty() } ?: mc.player?.gameProfile?.name ?: "debug"
            val stateLabel = when (state) {
                "deleted" -> "удалено"
                "error"   -> "ошибка"
                else      -> state
            }
            val caption = "[debug $stateLabel $label]"

            val safeAspect = aspect.coerceIn(0.05, 20.0)
            val maxW = ChatImageConfig.previewMaxW
            val maxH = ChatImageConfig.previewMaxH
            val thumbW: Int
            val thumbH: Int
            if (safeAspect >= maxW.toDouble() / maxH.toDouble()) {
                thumbW = maxW
                thumbH = (maxW / safeAspect).toInt().coerceAtLeast(1)
            } else {
                thumbH = maxH
                thumbW = (maxH * safeAspect).toInt().coerceAtLeast(1)
            }

            val thumbnailBytes: ByteArray = try {
                val img = BufferedImage(thumbW, thumbH, BufferedImage.TYPE_INT_RGB)
                val g = img.createGraphics()
                g.color = java.awt.Color(30, 30, 30)
                g.fillRect(0, 0, thumbW, thumbH)
                g.dispose()
                val bos = ByteArrayOutputStream()
                ImageIO.write(img, "png", bos)
                bos.toByteArray()
            } catch (e: Exception) {
                println("[ChatImage] debug state: cannot create thumbnail: ${e.message}")
                return
            }

            val imageId = "debug_${state}_${System.currentTimeMillis()}"

            Thread {
                val dispPair = ImageCache.computeDispSize(thumbnailBytes)
                    ?: Pair(maxW, maxH)
                val (dispW, dispH) = dispPair

                mc.execute {
                    ImageCache.registerPlaceholder(imageId, dispW, dispH)
                    // Применяем нужное состояние
                    when (state) {
                        "deleted" -> ImageCache.markDeleted(imageId)
                        "error"   -> ImageCache.markError(imageId)
                    }

                    val msgText = Component.literal("§f<§b$sender§f> §f$caption §7[📷]")
                    mc.gui.getChat().addMessage(msgText)
                    val addedTime = mc.gui.getGuiTicks()
                    ChatImageStore.addMessage(imageId, sender, caption, addedTime)

                    val chatLineSpacing = mc.options.chatLineSpacing().get()
                    val entryHeight = (9.0 * (chatLineSpacing + 1.0)).toInt()
                    // +2 учитывает зазор IMG_VERT_PAD сверху и снизу
                    val extraLines = ceil(dispH.toDouble() / entryHeight).toInt()
                    repeat(extraLines) { mc.gui.getChat().addMessage(Component.literal("")) }

                    println("[ChatImage] debug $state placeholder: imageId=$imageId → ${dispW}x${dispH}")
                }
            }.also { it.isDaemon = true }.start()
        }

        @JvmStatic
        fun pasteImageFromClipboard() {
            val mc = Minecraft.getInstance()
            if (!canSendPhoto(mc)) return
            Thread {
                try {
                    val bytes = readImageFromClipboardNative()
                    if (bytes == null || bytes.isEmpty()) {
                        mc.execute { mc.gui.getChat().addMessage(Component.literal("§7[ChatImage] " + ChatImageConfig.tr("chatimage.clipboard_empty"))) }
                        return@Thread
                    }
                    val tmpFile = java.io.File.createTempFile("chatimage_paste_", ".png")
                        .also { it.deleteOnExit(); it.writeBytes(bytes) }
                    mc.execute { stageImage(tmpFile) }
                } catch (e: Exception) {
                    println("[ChatImage] Clipboard paste error: ${e.message}")
                    mc.execute { mc.gui.getChat().addMessage(Component.literal("§c[ChatImage] " + ChatImageConfig.tr("chatimage.clipboard_error", e.message ?: "?"))) }
                }
            }.also { it.isDaemon = true }.start()
        }

        /**
         * Читает изображение из буфера обмена через нативные инструменты ОС.
         * Возвращает PNG-байты или null если в буфере нет изображения.
         */
        private fun readImageFromClipboardNative(): ByteArray? {
            val os = System.getProperty("os.name", "").lowercase()
            return when {
                os.contains("win") -> readClipboardWindows()
                os.contains("mac") -> readClipboardMac()
                else               -> readClipboardLinux()
            }
        }

        private fun readClipboardWindows(): ByteArray? {
            // PowerShell: читаем изображение из буфера и сохраняем как PNG в temp-файл
            val tmp = java.io.File.createTempFile("chatimage_clip_", ".png").also { it.deleteOnExit() }
            val script = """
                Add-Type -AssemblyName System.Windows.Forms;
                ${'$'}img = [System.Windows.Forms.Clipboard]::GetImage();
                if (${'$'}img -eq ${'$'}null) { exit 1 }
                ${'$'}img.Save('${tmp.absolutePath.replace("\\", "\\\\")}', [System.Drawing.Imaging.ImageFormat]::Png);
                exit 0
            """.trimIndent()
            val proc = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(true).start()
            val exit = proc.waitFor()
            if (exit != 0 || !tmp.exists() || tmp.length() == 0L) return null
            return tmp.readBytes()
        }

        private fun readClipboardMac(): ByteArray? {
            // osascript: сохраняем изображение из буфера в temp PNG
            val tmp = java.io.File.createTempFile("chatimage_clip_", ".png").also { it.deleteOnExit() }
            val script = """
                set filePath to "${tmp.absolutePath}"
                try
                    set theImage to the clipboard as «class PNGf»
                    set fileRef to open for access POSIX file filePath with write permission
                    write theImage to fileRef
                    close access fileRef
                on error
                    error "no image"
                end try
            """.trimIndent()
            val proc = ProcessBuilder("osascript", "-e", script)
                .redirectErrorStream(true).start()
            val exit = proc.waitFor()
            if (exit != 0 || !tmp.exists() || tmp.length() == 0L) return null
            return tmp.readBytes()
        }

        private fun readClipboardLinux(): ByteArray? {
            // xclip: читаем image/png из буфера обмена
            val tools = listOf(
                listOf("xclip", "-selection", "clipboard", "-t", "image/png", "-o"),
                listOf("xsel", "--clipboard", "--output")
            )
            for (cmd in tools) {
                try {
                    val proc = ProcessBuilder(cmd).redirectErrorStream(false).start()
                    val bytes = proc.inputStream.readBytes()
                    proc.waitFor()
                    if (bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) {
                        return bytes // PNG magic bytes: 0x89 P N G
                    }
                } catch (_: Exception) {}
            }
            return null
        }


        /**
         * Читает размеры изображения из заголовка файла без полного декодирования.
         * PNG: байты 16-23 содержат ширину и высоту (big-endian int).
         * JPEG: ищем маркер SOF0/SOF2 (0xFFC0/0xFFC2).
         * Возвращает Pair(width, height) или null если не удалось.
         */
        private fun readImageSizeFromHeader(file: File): Pair<Int, Int>? {
            return try {
                file.inputStream().use { stream ->
                    val header = stream.readNBytes(26)
                    if (header.size < 8) return null
                    // PNG: сигнатура 8 байт, затем IHDR чанк: 4 length + 4 type + 4 width + 4 height
                    if (header[0] == 0x89.toByte() && header[1] == 'P'.code.toByte()
                        && header[2] == 'N'.code.toByte() && header[3] == 'G'.code.toByte()) {
                        if (header.size < 24) return null
                        val w = ((header[16].toInt() and 0xFF) shl 24) or
                                ((header[17].toInt() and 0xFF) shl 16) or
                                ((header[18].toInt() and 0xFF) shl 8) or
                                 (header[19].toInt() and 0xFF)
                        val h = ((header[20].toInt() and 0xFF) shl 24) or
                                ((header[21].toInt() and 0xFF) shl 16) or
                                ((header[22].toInt() and 0xFF) shl 8) or
                                 (header[23].toInt() and 0xFF)
                        return if (w > 0 && h > 0) Pair(w, h) else null
                    }
                    // JPEG: сигнатура FF D8
                    if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()) {
                        // Сканируем маркеры — нужен SOF (0xFFC0..0xFFC3, 0xFFC5..0xFFC7, 0xFFC9..0xFFCB)
                        val buf = file.readBytes()
                        var i = 2
                        while (i + 3 < buf.size) {
                            if (buf[i] != 0xFF.toByte()) break
                            val marker = buf[i + 1].toInt() and 0xFF
                            val segLen = ((buf[i + 2].toInt() and 0xFF) shl 8) or (buf[i + 3].toInt() and 0xFF)
                            if (marker in listOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB)) {
                                if (i + 8 < buf.size) {
                                    val h = ((buf[i + 5].toInt() and 0xFF) shl 8) or (buf[i + 6].toInt() and 0xFF)
                                    val w = ((buf[i + 7].toInt() and 0xFF) shl 8) or (buf[i + 8].toInt() and 0xFF)
                                    return if (w > 0 && h > 0) Pair(w, h) else null
                                }
                            }
                            i += 2 + segLen
                        }
                    }
                    // WebP: RIFF????WEBP + VP8 /VP8L/VP8X
                    if (header.size >= 12
                        && header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte()
                        && header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte()
                        && header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte()
                        && header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte()) {
                        val buf = file.readBytes()
                        if (buf.size >= 30 && buf[12] == 'V'.code.toByte()
                            && buf[13] == 'P'.code.toByte() && buf[14] == '8'.code.toByte()) {
                            when (buf[15].toInt().toChar()) {
                                ' ' -> { // VP8 lossy: ширина/высота с 14-битными полями в байтах 26-29
                                    if (buf.size >= 30) {
                                        val w = ((buf[26].toInt() and 0xFF) or ((buf[27].toInt() and 0x3F) shl 8))
                                        val h = ((buf[28].toInt() and 0xFF) or ((buf[29].toInt() and 0x3F) shl 8))
                                        return if (w > 0 && h > 0) Pair(w, h) else null
                                    }
                                }
                                'L' -> { // VP8L lossless: первые 28 бит после сигнатуры
                                    if (buf.size >= 25) {
                                        val bits = (buf[21].toLong() and 0xFF) or
                                                   ((buf[22].toLong() and 0xFF) shl 8) or
                                                   ((buf[23].toLong() and 0xFF) shl 16) or
                                                   ((buf[24].toLong() and 0xFF) shl 24)
                                        val w = ((bits and 0x3FFF) + 1).toInt()
                                        val h = (((bits shr 14) and 0x3FFF) + 1).toInt()
                                        return if (w > 0 && h > 0) Pair(w, h) else null
                                    }
                                }
                                'X' -> { // VP8X extended: 3 байта LE ширина-1, 3 байта LE высота-1
                                    if (buf.size >= 30) {
                                        val w = ((buf[24].toInt() and 0xFF) or ((buf[25].toInt() and 0xFF) shl 8) or ((buf[26].toInt() and 0xFF) shl 16)) + 1
                                        val h = ((buf[27].toInt() and 0xFF) or ((buf[28].toInt() and 0xFF) shl 8) or ((buf[29].toInt() and 0xFF) shl 16)) + 1
                                        return if (w > 0 && h > 0) Pair(w, h) else null
                                    }
                                }
                            }
                        }
                    }
                    // BMP: сигнатура "BM", ширина в байтах 18-21, высота в 22-25 (little-endian, высота может быть отрицательной)
                    if (header.size >= 26
                        && header[0] == 'B'.code.toByte() && header[1] == 'M'.code.toByte()) {
                        val w = (header[18].toInt() and 0xFF) or
                                ((header[19].toInt() and 0xFF) shl 8) or
                                ((header[20].toInt() and 0xFF) shl 16) or
                                ((header[21].toInt() and 0xFF) shl 24)
                        val h = Math.abs(
                            (header[22].toInt() and 0xFF) or
                            ((header[23].toInt() and 0xFF) shl 8) or
                            ((header[24].toInt() and 0xFF) shl 16) or
                            ((header[25].toInt() and 0xFF) shl 24)
                        )
                        return if (w > 0 && h > 0) Pair(w, h) else null
                    }
                    // TIFF и прочие форматы — размер вытащить из заголовка сложно,
                    // возвращаем null и используем дефолтный aspect 16:9 для плейсхолдера
                    null
                }
            } catch (_: Exception) { null }
        }
        private fun scaleImage(src: BufferedImage, scale: Double, type: Int): BufferedImage {
            if (scale >= 1.0) return if (src.type == type) src else {
                val c = BufferedImage(src.width, src.height, type)
                c.createGraphics().also { it.drawImage(src, 0, 0, null); it.dispose() }
                c
            }
            val nw = (src.width * scale).toInt().coerceAtLeast(1)
            val nh = (src.height * scale).toInt().coerceAtLeast(1)
            val out = BufferedImage(nw, nh, type)
            val g = out.createGraphics().also {
                it.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                it.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY)
                it.drawImage(src, 0, 0, nw, nh, null)
            }
            g.dispose()
            return out
        }

        private fun toPng(image: BufferedImage): ByteArray {
            val bos = ByteArrayOutputStream()
            val img = if (image.type != BufferedImage.TYPE_INT_ARGB) {
                val c = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                c.createGraphics().also { it.drawImage(image, 0, 0, null); it.dispose() }
                c
            } else image
            ImageIO.write(img, "png", bos)
            return bos.toByteArray()
        }
    }
}
