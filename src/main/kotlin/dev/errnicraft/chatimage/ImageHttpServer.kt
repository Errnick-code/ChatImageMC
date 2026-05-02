package dev.errnicraft.chatimage

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * TCP сервер (замена HTTP). Работает на любом игровом хосте без HTTP-блокировок.
 *
 * Протокол (big-endian, строки UTF-8):
 *   Клиент → Сервер: [1 byte command] + аргументы
 *
 *   PING (0x01)      → ответ: [0x01][2 bytes len]["chatimage-ok"]
 *   UPLOAD (0x02)    → [2b token_len][token][1b id_len][id][4b data_len][data]
 *                      ответ: 0x00=ok, 0x01=forbidden, 0x02=too_large, 0x03=error
 *   GET_FULL (0x03)  → [1b id_len][id]
 *                      ответ: 0x00=ok [4b len][data] | 0x01=not_found
 *   GET_THUMB (0x04) → [1b id_len][id]
 *                      ответ: 0x00=ok [4b len][data] | 0x01=not_found
 */
object ImageHttpServer {

    private const val MAX_UPLOAD_BYTES = 8 * 1024 * 1024

    private const val CMD_PING      = 0x01.toByte()
    private const val CMD_UPLOAD    = 0x02.toByte()
    private const val CMD_GET_FULL  = 0x03.toByte()
    private const val CMD_GET_THUMB = 0x04.toByte()

    private const val RES_OK        = 0x00.toByte()
    private const val RES_FORBIDDEN = 0x01.toByte()
    private const val RES_TOO_LARGE = 0x02.toByte()
    private const val RES_ERROR     = 0x03.toByte()
    private const val RES_NOT_FOUND = 0x01.toByte()

    // Сервер хранит только оригинальный PNG.
    // Thumbnail генерирует каждый клиент самостоятельно.
    data class CachedImage(val fullPng: ByteArray)

    private val cache       = ConcurrentHashMap<String, CachedImage>()
    private val validTokens = ConcurrentHashMap.newKeySet<String>()
    private val executor    = Executors.newFixedThreadPool(8)

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    var onImageReady: ((imageId: String) -> Unit)? = null

    fun addToken(token: String)    { validTokens.add(token) }
    fun removeToken(token: String) { validTokens.remove(token) }

    @Synchronized
    fun startIfNeeded(port: Int) {
        if (running) return
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port), 64)
            serverSocket = ss
            running = true

            Thread({
                println("[ChatImage] TCP сервер запущен на 0.0.0.0:$port")
                while (running) {
                    try {
                        val client = ss.accept()
                        executor.submit { handleClient(client) }
                    } catch (_: Exception) { /* socket закрыт */ }
                }
            }, "ChatImage-TCP-Accept").also { it.isDaemon = true }.start()

        } catch (e: java.net.BindException) {
            println("[ChatImage] ОШИБКА: порт $port занят! Измените imagePort в config/chatimage-server.json.")
        } catch (e: Exception) {
            println("[ChatImage] ОШИБКА запуска TCP сервера: ${e.message}")
        }
    }

    fun stop() {
        running = false
        serverSocket?.close()
        serverSocket = null
        cache.clear()
        validTokens.clear()
        println("[ChatImage] TCP сервер остановлен")
    }

    fun isRunning() = running
    fun hasCached(imageId: String): Boolean = cache.containsKey(imageId)

    private fun handleClient(socket: Socket) {
        socket.soTimeout = 30_000
        try {
            socket.use {
                val din  = DataInputStream(socket.getInputStream())
                val dout = DataOutputStream(socket.getOutputStream())
                when (din.readByte()) {
                    CMD_PING      -> handlePing(dout)
                    CMD_UPLOAD    -> handleUpload(din, dout)
                    CMD_GET_FULL  -> handleGetFull(din, dout)
                    CMD_GET_THUMB -> handleGetThumb(din, dout)
                }
                dout.flush()
            }
        } catch (_: Exception) {}
    }

    private fun handlePing(dout: DataOutputStream) {
        val msg = "chatimage-ok".toByteArray(Charsets.UTF_8)
        dout.writeByte(CMD_PING.toInt())
        dout.writeShort(msg.size)
        dout.write(msg)
    }

    private fun handleUpload(din: DataInputStream, dout: DataOutputStream) {
        val tokenLen = din.readShort().toInt() and 0xFFFF
        val token    = String(din.readNBytes(tokenLen), Charsets.UTF_8)

        if (!validTokens.contains(token)) { dout.writeByte(RES_FORBIDDEN.toInt()); return }

        val idLen   = din.readByte().toInt() and 0xFF
        val imageId = String(din.readNBytes(idLen), Charsets.UTF_8)
        if (imageId.isBlank() || imageId.length > 32 || !imageId.all { it.isLetterOrDigit() || it == '-' }) {
            dout.writeByte(RES_ERROR.toInt()); return
        }
        if (cache.containsKey(imageId)) { dout.writeByte(RES_OK.toInt()); return }

        val dataLen = din.readInt()
        if (dataLen > MAX_UPLOAD_BYTES) { dout.writeByte(RES_TOO_LARGE.toInt()); return }

        val bytes = din.readNBytes(dataLen)

        // Быстрая проверка что это валидный PNG/JPEG — без полного декодирования.
        // Thumbnail генерирует каждый клиент сам, сервер не тратит CPU.
        if (bytes.size < 4) { dout.writeByte(RES_ERROR.toInt()); return }
        val isPng  = bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
        val isJpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        if (!isPng && !isJpeg) { dout.writeByte(RES_ERROR.toInt()); return }

        cache[imageId] = CachedImage(bytes)
        dout.writeByte(RES_OK.toInt())
        // Сразу сигнализируем — никакой генерации thumbnail не нужно.
        onImageReady?.invoke(imageId)
    }

    private fun handleGetFull(din: DataInputStream, dout: DataOutputStream) {
        val idLen = din.readByte().toInt() and 0xFF
        val id    = String(din.readNBytes(idLen), Charsets.UTF_8)
        val c     = cache[id]
        if (c == null) { dout.writeByte(RES_NOT_FOUND.toInt()) }
        else           { dout.writeByte(RES_OK.toInt()); dout.writeInt(c.fullPng.size); dout.write(c.fullPng) }
    }

    private fun handleGetThumb(din: DataInputStream, dout: DataOutputStream) {
        // Сервер больше не хранит thumbnail — клиент генерирует его сам из fullPng.
        // Для обратной совместимости читаем id и отвечаем not_found.
        val idLen = din.readByte().toInt() and 0xFF
        din.readNBytes(idLen) // consume
        dout.writeByte(RES_NOT_FOUND.toInt())
    }

    fun evictOld(maxEntries: Int = 100) {
        if (cache.size > maxEntries) cache.keys.take(cache.size - maxEntries).forEach { cache.remove(it) }
    }

    /** Удаляет фото с сервера по команде оператора */
    fun deleteImage(imageId: String) {
        cache.remove(imageId)
    }
}
