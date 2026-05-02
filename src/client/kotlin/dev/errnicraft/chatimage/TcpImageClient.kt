package dev.errnicraft.chatimage

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

/**
 * TCP-клиент для общения с ImageHttpServer.
 * Все методы — синхронные, вызывать в отдельном потоке!
 */
object TcpImageClient {

    private const val CMD_PING      = 0x01.toByte()
    private const val CMD_UPLOAD    = 0x02.toByte()
    private const val CMD_GET_FULL  = 0x03.toByte()
    private const val CMD_GET_THUMB = 0x04.toByte()

    private const val RES_OK        = 0x00.toByte()
    private const val RES_NOT_FOUND = 0x01.toByte()
    private const val RES_FORBIDDEN = 0x01.toByte()

    // Максимум попыток и базовая задержка между ними
    private const val UPLOAD_MAX_ATTEMPTS = 4
    private const val UPLOAD_BASE_DELAY_MS = 1_000L

    private const val DOWNLOAD_MAX_ATTEMPTS = 5
    private const val DOWNLOAD_BASE_DELAY_MS = 500L

    private fun connect(timeout: Int = 5000): Socket {
        val s = Socket()
        s.connect(
            java.net.InetSocketAddress(ChatImageConfig.serverHost, ChatImageConfig.imagePort),
            timeout
        )
        s.soTimeout = timeout
        return s
    }

    /** Проверяет что TCP-сервер доступен. Возвращает true при успехе. */
    fun ping(): Boolean {
        return try {
            connect(5000).use { socket ->
                val dout = DataOutputStream(socket.getOutputStream())
                val din  = DataInputStream(socket.getInputStream())
                dout.writeByte(CMD_PING.toInt())
                dout.flush()
                val cmd = din.readByte()
                if (cmd != CMD_PING) return false
                val len = din.readShort().toInt() and 0xFFFF
                val body = String(din.readNBytes(len), Charsets.UTF_8)
                body == "chatimage-ok"
            }
        } catch (_: Exception) { false }
    }

    /**
     * Загружает изображение на сервер с автоматическими повторными попытками.
     * При временных сетевых ошибках делает до UPLOAD_MAX_ATTEMPTS попыток
     * с экспоненциальной задержкой (1s, 2s, 4s...).
     * @return "ok", "forbidden", "too_large", "error", или "exception: ..."
     */
    fun upload(imageId: String, token: String, data: ByteArray): String {
        var lastError = "unknown"
        for (attempt in 1..UPLOAD_MAX_ATTEMPTS) {
            if (attempt > 1) {
                val delayMs = UPLOAD_BASE_DELAY_MS * (1L shl (attempt - 2)) // 1s, 2s, 4s
                println("[ChatImage] Upload attempt $attempt/$UPLOAD_MAX_ATTEMPTS for $imageId (retry in ${delayMs}ms)")
                Thread.sleep(delayMs)
            }
            val result = uploadOnce(imageId, token, data)
            when (result) {
                "ok"        -> return "ok"
                // Эти ошибки — постоянные, ретраить бессмысленно
                "forbidden" -> return "forbidden"
                "too_large" -> return "too_large"
                else -> {
                    lastError = result
                    println("[ChatImage] Upload error (attempt $attempt): $result")
                }
            }
        }
        println("[ChatImage] Upload failed after $UPLOAD_MAX_ATTEMPTS attempts for $imageId: $lastError")
        return lastError
    }

    private fun uploadOnce(imageId: String, token: String, data: ByteArray): String {
        return try {
            connect(10_000).use { socket ->
                socket.soTimeout = 30_000
                val dout = DataOutputStream(socket.getOutputStream())
                val din  = DataInputStream(socket.getInputStream())

                val tokenBytes = token.toByteArray(Charsets.UTF_8)
                val idBytes    = imageId.toByteArray(Charsets.UTF_8)

                dout.writeByte(CMD_UPLOAD.toInt())
                dout.writeShort(tokenBytes.size)
                dout.write(tokenBytes)
                dout.writeByte(idBytes.size)
                dout.write(idBytes)
                dout.writeInt(data.size)
                dout.write(data)
                dout.flush()

                when (din.readByte()) {
                    0x00.toByte() -> "ok"
                    0x01.toByte() -> "forbidden"
                    0x02.toByte() -> "too_large"
                    else          -> "error"
                }
            }
        } catch (e: Exception) { "exception: ${e.message}" }
    }

    /**
     * Скачивает полное PNG с сервера с автоматическими повторными попытками.
     * Фото может ещё не успеть загрузиться — делаем до DOWNLOAD_MAX_ATTEMPTS попыток
     * с нарастающей задержкой (500ms, 1s, 2s, 4s...).
     * @return байты PNG или null если так и не удалось
     */
    fun getFull(imageId: String): ByteArray? {
        for (attempt in 1..DOWNLOAD_MAX_ATTEMPTS) {
            if (attempt > 1) {
                val delayMs = DOWNLOAD_BASE_DELAY_MS * (1L shl (attempt - 2)) // 500ms, 1s, 2s, 4s
                println("[ChatImage] Download attempt $attempt/$DOWNLOAD_MAX_ATTEMPTS for $imageId (retry in ${delayMs}ms)")
                Thread.sleep(delayMs)
            }
            val result = getFullOnce(imageId)
            if (result != null) return result
            println("[ChatImage] Download miss (attempt $attempt): $imageId not ready yet")
        }
        println("[ChatImage] Download failed after $DOWNLOAD_MAX_ATTEMPTS attempts for $imageId")
        return null
    }

    private fun getFullOnce(imageId: String): ByteArray? {
        return try {
            connect(5000).use { socket ->
                socket.soTimeout = 15_000
                val dout = DataOutputStream(socket.getOutputStream())
                val din  = DataInputStream(socket.getInputStream())

                val idBytes = imageId.toByteArray(Charsets.UTF_8)
                dout.writeByte(CMD_GET_FULL.toInt())
                dout.writeByte(idBytes.size)
                dout.write(idBytes)
                dout.flush()

                if (din.readByte() != RES_OK) return null
                val len = din.readInt()
                din.readNBytes(len)
            }
        } catch (_: Exception) { null }
    }

    /**
     * Скачивает JPEG-миниатюру с сервера.
     * @return байты JPEG или null если не найдено/ошибка
     */
    fun getThumb(imageId: String): ByteArray? {
        return try {
            connect(5000).use { socket ->
                socket.soTimeout = 10_000
                val dout = DataOutputStream(socket.getOutputStream())
                val din  = DataInputStream(socket.getInputStream())

                val idBytes = imageId.toByteArray(Charsets.UTF_8)
                dout.writeByte(CMD_GET_THUMB.toInt())
                dout.writeByte(idBytes.size)
                dout.write(idBytes)
                dout.flush()

                if (din.readByte() != RES_OK) return null
                val len = din.readInt()
                din.readNBytes(len)
            }
        } catch (_: Exception) { null }
    }
}
