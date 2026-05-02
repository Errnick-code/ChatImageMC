package dev.errnicraft.chatimage

import com.google.gson.Gson
import com.google.gson.JsonObject
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.arguments.EntityArgument
import net.fabricmc.fabric.api.command.v2.ArgumentTypeRegistry
import net.minecraft.commands.synchronization.SingletonArgumentInfo
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import java.nio.file.Path
import java.util.Collections
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class WordArgumentType : com.mojang.brigadier.arguments.ArgumentType<String> {
    override fun parse(reader: com.mojang.brigadier.StringReader): String {
        val start = reader.cursor
        while (reader.canRead() && reader.peek() != ' ') reader.skip()
        return reader.string.substring(start, reader.cursor)
    }
    companion object {
        val INSTANCE = WordArgumentType()
        fun word() = INSTANCE
        fun getWord(ctx: com.mojang.brigadier.context.CommandContext<*>, name: String): String =
            ctx.getArgument(name, String::class.java)
    }
}

class ChatImageMod : ModInitializer {

    companion object {
        private val GSON = Gson()

        /** UUID игроков у которых есть мод (handshake завершён) */
        private val modPlayers: MutableSet<UUID> = Collections.synchronizedSet(mutableSetOf())

        /** Токен загрузки для каждого игрока. Ключ — UUID игрока, значение — uploadToken */
        private val playerTokens: MutableMap<UUID, String> = Collections.synchronizedMap(mutableMapOf())

        /** UUID игроков которым запрещена отправка фото (персистентный, хранится на диске) */
        private val bannedPlayers: MutableSet<UUID> = Collections.synchronizedSet(mutableSetOf())

        /** Путь к файлу бан-листа (устанавливается при старте сервера) */
        @Volatile private var banListFile: Path? = null

        /** Ожидающие рассылки: imageId → данные. Заполняется при ImageUploadedPacket,
         *  очищается когда onImageReady callback получил файл по TCP. */
        data class PendingBroadcast(val sender: String, val caption: String, val senderUuid: UUID, val width: Int, val height: Int)
        private val pendingBroadcasts: MutableMap<String, PendingBroadcast> =
            Collections.synchronizedMap(mutableMapOf())

        @Volatile var currentServer: net.minecraft.server.MinecraftServer? = null

        fun hasModInstalled(uuid: UUID) = uuid in modPlayers
        fun isPhotoBanned(uuid: UUID) = uuid in bannedPlayers

        // ── Персистентный бан-лист ────────────────────────────────────────────

        fun loadBanList(serverDir: Path) {
            val file = serverDir.resolve("config/chatimage-bans.json")
            banListFile = file
            if (!file.exists()) return
            try {
                val arr = GSON.fromJson(file.readText(), com.google.gson.JsonArray::class.java) ?: return
                bannedPlayers.clear()
                arr.forEach { el ->
                    val uuidStr = el.asJsonObject?.get("uuid")?.asString ?: return@forEach
                    try { bannedPlayers.add(UUID.fromString(uuidStr)) } catch (_: Exception) {}
                }
                println("[ChatImage] Загружен бан-лист: ${bannedPlayers.size} игроков")
            } catch (e: Exception) {
                println("[ChatImage] Ошибка загрузки бан-листа: ${e.message}")
            }
        }

        fun saveBanList(server: net.minecraft.server.MinecraftServer) {
            val file = banListFile ?: return
            try {
                file.parent?.createDirectories()
                val arr = com.google.gson.JsonArray()
                bannedPlayers.forEach { uuid ->
                    val name = server.playerList.getPlayer(uuid)?.name?.string ?: uuid.toString()
                    arr.add(com.google.gson.JsonObject().apply {
                        addProperty("uuid", uuid.toString())
                        addProperty("name", name)
                    })
                }
                file.writeText(GSON.newBuilder().setPrettyPrinting().create().toJson(arr))
            } catch (e: Exception) {
                println("[ChatImage] Ошибка сохранения бан-листа: ${e.message}")
            }
        }

        fun banPlayer(uuid: UUID, server: net.minecraft.server.MinecraftServer) {
            bannedPlayers.add(uuid)
            // Отзываем токен — TCP upload вернёт forbidden
            playerTokens[uuid]?.let { token -> ImageHttpServer.removeToken(token) }
            saveBanList(server)
        }

        fun unbanPlayer(uuid: UUID, server: net.minecraft.server.MinecraftServer) {
            bannedPlayers.remove(uuid)
            // Возвращаем токен если игрок онлайн
            playerTokens[uuid]?.let { token -> ImageHttpServer.addToken(token) }
            saveBanList(server)
        }

        data class ServerConfig(val resolution: String, val imagePort: Int, val autoDownload: Boolean, val photoCooldownSeconds: Int)

        fun loadOrCreateServerConfig(serverDir: Path): ServerConfig {
            val configFile = serverDir.resolve("config/chatimage-server.json")
            val currentConfigVersion = 1

            if (!configFile.exists()) {
                try {
                    configFile.parent.createDirectories()
                    val default = JsonObject().apply {
                        addProperty("configVersion", currentConfigVersion)
                        addProperty("_comment_resolution", "Image resolution: 480 | 720 | HD | 2K")
                        addProperty("resolution", "720")
                        addProperty("_comment_imagePort",
                            "TCP server port for images. Open it in firewall! " +
                            "If port is busy — change imagePort here and restart.")
                        addProperty("imagePort", 5050)
                        addProperty("_comment_autoDownload",
                            "If true — clients automatically download full image in background when received. " +
                            "Default: false (client opens image manually).")
                        addProperty("autoDownload", false)
                        addProperty("_comment_photoCooldownSeconds",
                            "Cooldown in seconds between photo sends per player. 0 = no cooldown. Default: 5.")
                        addProperty("photoCooldownSeconds", 5)
                    }
                    configFile.writeText(GSON.newBuilder().setPrettyPrinting().create().toJson(default))
                    println("[ChatImage] Config created: ${configFile.toAbsolutePath()}")
                    println("[ChatImage] TCP port: 5050. If busy — change imagePort in config.")
                    return ServerConfig("720", 5050, false, 5)
                } catch (e: Exception) {
                    println("[ChatImage] Could not create config: ${e.message}")
                    return ServerConfig("720", 5050, false, 5)
                }
            }

            return try {
                val json = GSON.fromJson(configFile.readText(), JsonObject::class.java)

                val res = json.get("resolution")?.asString?.trim()
                    .let { if (it == "480" || it == "720" || it == "HD" || it == "2K") it else "720" }!!
                val port = json.get("imagePort")?.asInt?.takeIf { it in 1024..65535 } ?: 5050
                val autoDownload = json.get("autoDownload")?.asBoolean ?: false
                val cooldown = json.get("photoCooldownSeconds")?.asInt?.coerceAtLeast(0) ?: 5
                ServerConfig(res, port, autoDownload, cooldown)
            } catch (e: Exception) {
                println("[ChatImage] Config read error: ${e.message}. Using defaults.")
                ServerConfig("720", 5050, false, 5)
            }
        }
    }

    override fun onInitialize() {
        // Регистрируем кастомный ArgumentType через Fabric
        ArgumentTypeRegistry.registerArgumentType(
            Identifier.fromNamespaceAndPath("chatimage", "word"),
            WordArgumentType::class.java,
            SingletonArgumentInfo.contextFree { WordArgumentType() }
        )

        ImageChatPacket.register()
        ServerHelloPacket.register()
        ClientHelloPacket.register()
        ServerConfigPacket.register()
        HandshakeErrorPacket.register()
        ImageUploadedPacket.register()
        PhotoDeniedPacket.register()
        ImageDeletedPacket.register()
        ImageErrorPacket.register()

        // ─── onImageReady: TCP загрузка завершена ────────────────────────────────
        // Рассылка уже была сделана мгновенно при ImageUploadedPacket.
        // Здесь просто очищаем очередь (отменяем таймаут) и evictOld.
        ImageHttpServer.onImageReady = handler@{ imageId ->
            pendingBroadcasts.remove(imageId) ?: return@handler
            ImageHttpServer.evictOld(100)
        }

        // ─── Создаём конфиг и стартуем TCP сервер при запуске ────────────────
        // Сервер доступен на net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register { server ->
            currentServer = server
            val config = loadOrCreateServerConfig(server.serverDirectory)
            loadBanList(server.serverDirectory)
            ImageHttpServer.startIfNeeded(config.imagePort)
            println("[ChatImage] TCP сервер запущен при старте на порту ${config.imagePort}")
        }

        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            currentServer = null
            pendingBroadcasts.clear()
            ImageHttpServer.stop()
        }

        // ─── Команды ─────────────────────────────────────────────────────────
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("chatimage")
                    .requires(Commands.hasPermission(Commands.LEVEL_OWNERS))// только операторы
                    .then(
                        Commands.literal("ban")
                            .then(
                                Commands.argument("player", EntityArgument.player())
                                    .executes { ctx ->
                                        val target = EntityArgument.getPlayer(ctx, "player")
                                        banPlayer(target.uuid, ctx.source.server)
                                        // Уведомляем игрока если он онлайн
                                        ServerPlayNetworking.send(target, PhotoDeniedPacket("banned"))
                                        ctx.source.sendSuccess({
                                            Component.literal("§8[ChatImage] §c${target.name.string} §7— отправка фото заблокирована.")
                                        }, true)
                                        1
                                    }
                            )
                    )
                    .then(
                        Commands.literal("unban")
                            .then(
                                Commands.argument("player", EntityArgument.player())
                                    .executes { ctx ->
                                        val target = EntityArgument.getPlayer(ctx, "player")
                                        unbanPlayer(target.uuid, ctx.source.server)
                                        ctx.source.sendSuccess({
                                            Component.literal("§8[ChatImage] §a${target.name.string} §7— отправка фото разблокирована.")
                                        }, true)
                                        1
                                    }
                            )
                    )
                    .then(
                        Commands.literal("delete")
                            .then(
                                Commands.argument("imageId", WordArgumentType.word())
                                    .executes { ctx ->
                                        val imageId = WordArgumentType.getWord(ctx, "imageId")
                                        val server = ctx.source.server
                                        // Удаляем с TCP-сервера
                                        ImageHttpServer.deleteImage(imageId)
                                        // Рассылаем всем игрокам с модом
                                        val packet = ImageDeletedPacket(imageId)
                                        server.execute {
                                            server.playerList.players.forEach { player ->
                                                if (hasModInstalled(player.uuid)) {
                                                    ServerPlayNetworking.send(player, packet)
                                                }
                                            }
                                        }
                                        ctx.source.sendSuccess({
                                            Component.literal("§8[ChatImage] §7Фото §f$imageId §7удалено.")
                                        }, true)
                                        1
                                    }
                            )
                    )
            )
        }

        // ─── Шаг 2 handshake: клиент ответил на ServerHello ──────────────────
        ServerPlayNetworking.registerGlobalReceiver(ClientHelloPacket.TYPE) { payload, context ->
            val player = context.player()
            val server = context.server()
            val clientVersion = payload.clientProtocolVersion

            if (clientVersion != MOD_PROTOCOL_VERSION) {
                ServerPlayNetworking.send(
                    player,
                    HandshakeErrorPacket(
                        "§cВерсия мода несовместима: сервер v$MOD_PROTOCOL_VERSION, клиент v$clientVersion. Обновите мод."
                    )
                )
                println("[ChatImage] ${player.name.string} has incompatible mod version: client v$clientVersion, server v$MOD_PROTOCOL_VERSION")
                return@registerGlobalReceiver
            }

            modPlayers.add(player.uuid)
            val uploadToken = UUID.randomUUID().toString()
            playerTokens[player.uuid] = uploadToken
            // Если игрок забанен — не добавляем токен в TCP сервер (upload будет forbidden)
            if (!isPhotoBanned(player.uuid)) {
                ImageHttpServer.addToken(uploadToken)
            }

            val config = loadOrCreateServerConfig(server.serverDirectory)
            ServerPlayNetworking.send(
                player,
                ServerConfigPacket(config.resolution, config.imagePort, uploadToken, config.autoDownload, config.photoCooldownSeconds)
            )
            // Если игрок забанен — сразу уведомляем клиента
            if (isPhotoBanned(player.uuid)) {
                ServerPlayNetworking.send(player, PhotoDeniedPacket("banned"))
            }
            println("[ChatImage] Handshake complete with ${player.name.string} (protocol v$clientVersion)")
        }

        // ─── Клиент загрузил фото ─────────────────────────────────────────────
        ServerPlayNetworking.registerGlobalReceiver(ImageUploadedPacket.TYPE) { payload, context ->
            if (!hasModInstalled(context.player().uuid)) {
                println("[ChatImage] ${context.player().name.string} sent ImageUploadedPacket without handshake — ignoring")
                return@registerGlobalReceiver
            }

            // Проверяем бан на отправку
            if (isPhotoBanned(context.player().uuid)) {
                println("[ChatImage] ${context.player().name.string} tried to send photo but is banned")
                ServerPlayNetworking.send(
                    context.player(),
                    PhotoDeniedPacket("banned")
                )
                return@registerGlobalReceiver
            }

            val server = context.server()
            val imageId = payload.imageId
            // Берём имя напрямую с сервера — игнорируем sender из пакета клиента.
            // displayName несёт цвета и кастомный ник от других модов (LuckPerms и т.д.)
            val senderPlayer = context.player()
            val sender = senderPlayer.name.string
            val senderComp = senderPlayer.displayName ?: net.minecraft.network.chat.Component.literal(sender)
            val caption = payload.caption
            val width = payload.width
            val height = payload.height
            val senderUuid = senderPlayer.uuid

            // Мгновенно рассылаем ImageChatPacket ВСЕМ кроме отправителя —
            // они сразу видят плейсхолдер и начнут качать фото по TCP когда оно появится.
            // Отправитель уже показал сообщение локально и пропустит этот пакет.
            val packet = ImageChatPacket(imageId, sender, caption, width, height, senderComp)
            server.execute {
                server.playerList.players.forEach { player: ServerPlayer ->
                    if (hasModInstalled(player.uuid) && player.uuid != senderUuid) {
                        ServerPlayNetworking.send(player, packet)
                    }
                }
            }

            // Ставим в очередь — onImageReady не нужен для рассылки,
            // но таймаут нужен чтобы уведомить отправителя об ошибке TCP.
            pendingBroadcasts[imageId] = PendingBroadcast(sender, caption, senderUuid, width, height)

            // onImageReady просто очищает pendingBroadcasts и evictOld — рассылка уже сделана выше.
            // Таймаут 120 сек на случай если TCP соединение вообще не установилось.
            Thread {
                Thread.sleep(120_000L)
                val stillPending = pendingBroadcasts.remove(imageId) ?: return@Thread
                println("[ChatImage] Timeout (120s) waiting for upload $imageId from ${stillPending.sender}")
                server.execute {
                    server.playerList.getPlayer(stillPending.senderUuid)?.let { senderPlayer ->
                        ServerPlayNetworking.send(senderPlayer, ImageErrorPacket(imageId, "timeout"))
                    }
                }
            }.also { it.isDaemon = true }.start()
        }

        // ─── JOIN ─────────────────────────────────────────────────────────────
        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            val player = handler.getPlayer()

            Thread {
                // Небольшая задержка — клиент должен полностью загрузить мир
                // прежде чем сможет принимать кастомные пакеты.
                // Без задержки ServerHelloPacket приходит слишком рано и дропается,
                // из-за чего handshake не проходит и игрок с модом получает сообщение
                // "установите мод".
                Thread.sleep(2000L)
                server.execute {
                    ServerPlayNetworking.send(player, ServerHelloPacket(MOD_PROTOCOL_VERSION))
                }

                Thread.sleep(13_000L)
                if (!hasModInstalled(player.uuid)) {
                    server.execute {
                        player.sendSystemMessage(
                            Component.literal(
                                "§8[§bChatImage§8] §7На этом сервере установлен мод для отображения фото в чате! §fУстановите §bChatImage §fчтобы видеть и отправлять фото."
                            )
                        )
                    }
                }
            }.also { it.isDaemon = true }.start()
        }

        // ─── DISCONNECT ───────────────────────────────────────────────────────
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            val uuid = handler.getPlayer().uuid
            modPlayers.remove(uuid)
            playerTokens.remove(uuid)?.let { token ->
                ImageHttpServer.removeToken(token)
            }
        }
    }
}
