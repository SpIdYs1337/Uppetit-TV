package com.uppetit.tv

import android.graphics.Bitmap
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import android.content.Intent
import androidx.core.content.edit
import androidx.core.content.FileProvider
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.buffer
import okio.sink
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import java.util.UUID

// Элемент плейлиста
data class PlaylistItem(
    val id: String,
    val type: String, // "video" или "image"
    val url: String,
    val durationSeconds: Int,
    var localPath: String? = null
)

class MainActivity : ComponentActivity() {

    // Ссылка на видео-заставку по умолчанию (пока ТВ не привязан)
    private val defaultVideoUrl = "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4"

    private var secretClickCount = 0
    private var lastClickTime: Long = 0
    private val clickThreshold = 3000L

    private var currentDeviceId: String = ""
    private var currentDeviceSecret: String = ""

    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var lastTelemetry: JSONObject? = null

    private var exoPlayer: ExoPlayer? = null
    private lateinit var ivContent: ImageView
    private lateinit var infoLayer: View

    // Плейлист
    private val playlist = mutableListOf<PlaylistItem>()
    private var currentIndex = -1
    private val playbackHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tvDeviceId = findViewById<TextView>(R.id.tvDeviceId)
        ivContent = findViewById(R.id.ivContent)
        infoLayer = findViewById(R.id.infoLayer)
        
        val sharedPreferences = getSharedPreferences("EvaVisionPrefs", MODE_PRIVATE)

        val deviceId = sharedPreferences.getString("SHORT_DEVICE_ID", null) ?: run {
            val charset = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val newId = (1..6).asSequence().map { charset.random() }.joinToString("")
            sharedPreferences.edit { putString("SHORT_DEVICE_ID", newId) }
            newId
        }

        val deviceSecret = sharedPreferences.getString("DEVICE_SECRET", null) ?: run {
            val newSecret = UUID.randomUUID().toString()
            sharedPreferences.edit { putString("DEVICE_SECRET", newSecret) }
            newSecret
        }

        currentDeviceId = deviceId
        currentDeviceSecret = deviceSecret

        val formattedId = if (deviceId.length == 6) {
            "${deviceId.substring(0, 3)} ${deviceId.substring(3, 6)}"
        } else {
            deviceId
        }

        tvDeviceId.text = formattedId

        setupPlayer()
        playNextItem() // Пробуем запустить контент или показать ID

        val telemetryJson = collectTelemetry()
        Log.d("UPPETIT_TELEMETRY", telemetryJson.toString(4))

        connectWebSocket(telemetryJson)
    }

    private fun setupPlayer() {
        val playerView = findViewById<PlayerView>(R.id.playerView)
        exoPlayer = ExoPlayer.Builder(this).build().also { player ->
            playerView.player = player
            player.repeatMode = Player.REPEAT_MODE_OFF // Управляем очередью сами
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        val currentItem = playlist.getOrNull(currentIndex)
                        currentItem?.let { 
                            sendEvent("ITEM_PLAY_ENDED", JSONObject().apply { put("item_id", it.id) }) 
                        }
                        playNextItem()
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Log.e("UPPETIT_PLAYER", "Ошибка плеера: ${error.message}")
                    sendEvent("PLAYER_ERROR", JSONObject().apply {
                        put("message", error.message)
                        put("error_code", error.errorCode)
                    })
                    playNextItem() // Пробуем пропустить проблемный файл
                }
            })
        }
    }

    private fun playNextItem() {
        playbackHandler.removeCallbacksAndMessages(null)
        
        if (playlist.isEmpty()) {
            showStandby()
            return
        }

        currentIndex = (currentIndex + 1) % playlist.size
        val item = playlist[currentIndex]

        val file = item.localPath?.let { File(it) }
        if (file == null || !file.exists()) {
            Log.w("UPPETIT_PLAYER", "Файл не готов: ${item.url}")
            sendEvent("PLAYBACK_ERROR", JSONObject().apply { 
                put("item_id", item.id)
                put("reason", "File not found locally")
            })
            if (playlist.size == 1) showStandby() else playNextItem()
            return
        }

        hideStandby()

        // Логируем начало показа
        sendEvent("ITEM_PLAY_STARTED", JSONObject().apply {
            put("item_id", item.id)
            put("type", item.type)
        })

        if (item.type == "video") {
            ivContent.visibility = View.GONE
            exoPlayer?.apply {
                setMediaItem(MediaItem.fromUri(file.absolutePath))
                prepare()
                play()
            }
        } else {
            exoPlayer?.pause()
            ivContent.visibility = View.VISIBLE
            Glide.with(this).load(file).into(ivContent)
            
            playbackHandler.postDelayed({
                sendEvent("ITEM_PLAY_ENDED", JSONObject().apply { put("item_id", item.id) })
                playNextItem()
            }, item.durationSeconds * 1000L)
        }
    }

    private fun showStandby() {
        runOnUiThread {
            infoLayer.visibility = View.VISIBLE
            // Запускаем фоновое видео, если плейлист пуст
            ivContent.visibility = View.GONE
            exoPlayer?.apply {
                repeatMode = Player.REPEAT_MODE_ALL
                setMediaItem(MediaItem.fromUri(defaultVideoUrl))
                prepare()
                play()
            }
        }
    }

    private fun hideStandby() {
        runOnUiThread { infoLayer.visibility = View.GONE }
    }

    private fun handleCommand(text: String) {
        try {
            val data = JSONObject(text)
            when (data.optString("command")) {
                "UPDATE_PLAYLIST" -> {
                    val items = data.getJSONArray("playlist")
                    updatePlaylist(items)
                }
                "SCREENSHOT" -> takeScreenshot()
                "REBOOT" -> restartApp()
                "OTA_UPDATE" -> {
                    val url = data.getString("url")
                    startOtaUpdate(url)
                }
                "UNPAIR_DEVICE" -> unpairDevice()
            }
        } catch (e: Exception) {
            Log.e("UPPETIT_CMD", "Ошибка команды: ${e.message}")
        }
    }

    private fun updatePlaylist(jsonArray: JSONArray) {
        val newItems = mutableListOf<PlaylistItem>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            newItems.add(PlaylistItem(
                id = obj.getString("id"),
                type = obj.getString("type"),
                url = obj.getString("url"),
                durationSeconds = obj.optInt("duration_seconds", 10)
            ))
        }
        downloadAndSyncContent(newItems)
    }

    private fun downloadAndSyncContent(newItems: List<PlaylistItem>) {
        val dir = File(getExternalFilesDir(null), "content")
        if (!dir.exists()) dir.mkdirs()

        var processedCount = 0
        newItems.forEach { item ->
            val fileName = "${item.id}_${File(item.url).name}"
            val localFile = File(dir, fileName)
            
            if (localFile.exists()) {
                item.localPath = localFile.absolutePath
                processedCount++
                if (processedCount == newItems.size) finalizePlaylist(newItems)
            } else {
                client.newCall(Request.Builder().url(item.url).build()).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        processedCount++
                        if (processedCount == newItems.size) finalizePlaylist(newItems)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.body?.let { body ->
                            try {
                                localFile.sink().buffer().use { it.writeAll(body.source()) }
                                item.localPath = localFile.absolutePath
                            } catch (e: Exception) {}
                        }
                        processedCount++
                        if (processedCount == newItems.size) finalizePlaylist(newItems)
                    }
                })
            }
        }
    }

    private fun finalizePlaylist(items: List<PlaylistItem>) {
        runOnUiThread {
            playlist.clear()
            playlist.addAll(items)
            currentIndex = -1
            playNextItem()
            Log.d("UPPETIT_PLAYER", "Плейлист готов!")
            sendEvent("PLAYLIST_SYNC_SUCCESS", JSONObject().apply {
                put("items_count", items.size)
            })
            
            // Удаляем старые файлы, которых нет в новом плейлисте
            cleanupOldContent(items)
        }
    }

    private fun cleanupOldContent(currentItems: List<PlaylistItem>) {
        val dir = File(getExternalFilesDir(null), "content")
        if (!dir.exists()) return

        val activeFiles = currentItems.mapNotNull { it.localPath?.let { path -> File(path).name } }.toSet()
        
        dir.listFiles()?.forEach { file ->
            if (file.name !in activeFiles) {
                if (file.delete()) {
                    Log.d("UPPETIT_CLEANUP", "Удален старый файл: ${file.name}")
                }
            }
        }
    }

    private fun sendEvent(type: String, data: JSONObject = JSONObject()) {
        try {
            val event = JSONObject().apply {
                put("type", "EVENT_LOG")
                put("event_type", type)
                put("device_id", currentDeviceId)
                put("device_secret", currentDeviceSecret) // Отправляем секрет для авторизации
                put("timestamp", System.currentTimeMillis())
                put("data", data)
            }
            webSocket?.send(event.toString())
        } catch (e: Exception) {
            Log.e("UPPETIT_LOG", "Ошибка отправки лога: ${e.message}")
        }
    }

    private fun startOtaUpdate(url: String) {
        sendEvent("UPDATE_STARTED", JSONObject().apply { put("url", url) })
        val updateFile = File(getExternalFilesDir(null), "update.apk")
        
        client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                sendEvent("UPDATE_FAILED", JSONObject().apply { put("reason", e.message) })
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.let { body ->
                    updateFile.sink().buffer().use { it.writeAll(body.source()) }
                    installApk(updateFile)
                }
            }
        })
    }

    private fun installApk(file: File) {
        runOnUiThread {
            try {
                val intent = Intent(Intent.ACTION_VIEW)
                val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
                intent.setDataAndType(uri, "application/vnd.android.package-archive")
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                sendEvent("UPDATE_READY_TO_INSTALL")
            } catch (e: Exception) {
                sendEvent("UPDATE_FAILED", JSONObject().apply { put("reason", "Installation start failed") })
            }
        }
    }

    private fun unpairDevice() {
        runOnUiThread {
            Log.d("UPPETIT_UNPAIR", "⚠️ Получена команда на отвязку устройства")

            // 1. Очистка ID и Секрета
            val sharedPreferences = getSharedPreferences("EvaVisionPrefs", MODE_PRIVATE)
            sharedPreferences.edit(commit = true) {
                remove("SHORT_DEVICE_ID")
                remove("DEVICE_SECRET")
            }

            // 2. Очистка кэша медиа
            clearMediaCache()

            // 3. Перезапуск
            restartApp()
        }
    }

    private fun clearMediaCache() {
        try {
            val dir = File(getExternalFilesDir(null), "content")
            if (dir.exists()) {
                dir.deleteRecursively()
                Log.d("UPPETIT_CLEANUP", "Кэш медиа полностью очищен")
            }
        } catch (e: Exception) {
            Log.e("UPPETIT_CLEANUP", "Ошибка при очистке кэша: ${e.message}")
        }
    }

    private fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    private fun takeScreenshot() {
        runOnUiThread {
            try {
                val view = window.decorView.rootView
                val bitmap = createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                bitmap.applyCanvas {
                    view.draw(this)
                }
                
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
                val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                
                val resp = JSONObject().apply {
                    put("type", "SCREENSHOT_RESULT")
                    put("device_id", currentDeviceId)
                    put("image", base64)
                }
                webSocket?.send(resp.toString())
            } catch (e: Exception) {}
        }
    }

    private fun connectWebSocket(telemetry: JSONObject) {
        lastTelemetry = telemetry
        val request = Request.Builder().url("ws://192.168.1.42:3001").build()

        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d("UPPETIT_NETWORK", "✅ Соединение с сервером УСТАНОВЛЕНО!")
                    isConnected = true
                    reconnectHandler.removeCallbacksAndMessages(null)
                    webSocket.send(telemetry.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d("UPPETIT_NETWORK", "⬇️ Сервер прислал сообщение: $text")
                    handleCommand(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d("UPPETIT_NETWORK", "⚠️ Соединение закрывается: $reason")
                    isConnected = false
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e("UPPETIT_NETWORK", "❌ Ошибка соединения: ${t.message}")
                    isConnected = false
                    scheduleReconnect()
                }
            })
    }

    private fun scheduleReconnect() {
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectHandler.postDelayed({
            Log.d("UPPETIT_NETWORK", "🔄 Попытка переподключения...")
            lastTelemetry?.let { connectWebSocket(it) }
        }, 10000) // Пробуем каждые 10 секунд
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            val currentTime = System.currentTimeMillis()

            if ((currentTime - lastClickTime) > clickThreshold) {
                secretClickCount = 0
            }

            secretClickCount++
            lastClickTime = currentTime

            if (secretClickCount == 5) {
                secretClickCount = 0
                openSystemSettings()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun openSystemSettings() {
        try {
            val intent = Intent(Settings.ACTION_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocket?.close(1000, "Activity destroyed")
        exoPlayer?.release()
    }

    private fun collectTelemetry(): JSONObject {
        val telemetry = JSONObject()
        try {
            telemetry.put("device_id", currentDeviceId)
            telemetry.put("device_secret", currentDeviceSecret) // Важно: авторизация
            telemetry.put("device_name", "ТВ UPPETIT (Торговая точка)")
            telemetry.put("status", "online")

            val broadcast = JSONObject()
            broadcast.put("type", "video")
            telemetry.put("current_broadcast", broadcast)

            val system = JSONObject()
            system.put("android_version", Build.VERSION.RELEASE)
            system.put("device_time", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault()).format(Date()))

            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            system.put("app_version", pInfo.versionName)
            telemetry.put("system", system)

            val storage = JSONObject()
            val stat = StatFs(Environment.getDataDirectory().path)
            val bytesAvailable = stat.blockSizeLong * stat.availableBlocksLong
            val bytesTotal = stat.blockSizeLong * stat.blockCountLong
            val gbAvailable = bytesAvailable / (1024.0 * 1024.0 * 1024.0)
            val gbTotal = bytesTotal / (1024.0 * 1024.0 * 1024.0)

            storage.put("available_gb", String.format(Locale.US, "%.2f", gbAvailable).toDouble())
            storage.put("total_gb", String.format(Locale.US, "%.2f", gbTotal).toDouble())
            telemetry.put("storage", storage)

            val network = JSONObject()
            var ipAddress = "0.0.0.0"
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                for (enumIpAddr in intf.inetAddresses) {
                    if (!enumIpAddr.isLoopbackAddress && (enumIpAddr.address.size == 4)) {
                        ipAddress = enumIpAddr.hostAddress ?: "0.0.0.0"
                    }
                }
            }
            network.put("ip_address", ipAddress)
            telemetry.put("network", network)

        } catch (e: Exception) {
            e.printStackTrace()
        }
        return telemetry
    }
}