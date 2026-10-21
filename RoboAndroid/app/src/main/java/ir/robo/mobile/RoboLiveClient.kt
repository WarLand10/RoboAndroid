package ir.robo.mobile

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Gemini Live WebSocket client. Mic input: PCM 16 kHz, output: PCM 24 kHz. */
class RoboLiveClient(
    private val apiKey: String,
    private val onStatus: (String) -> Unit,
    private val onText: (String) -> Unit,
    private val onToolCall: (id: String, name: String, args: JSONObject) -> Unit
) {
    companion object {
        private const val MODEL = "models/gemini-3.8-live"
        private const val INPUT_RATE = 16000
        private const val OUTPUT_RATE = 24000
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .build()
    private val connected = AtomicBoolean(false)
    private val audioRunning = AtomicBoolean(false)
    private val playbackQueue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var player: AudioTrack? = null
    @Volatile private var recordThread: Thread? = null
    @Volatile private var playbackThread: Thread? = null

    fun isConnected(): Boolean = connected.get()

    fun connect() {
        onStatus("در حال اتصال به Gemini Live…")
        val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
        val request = Request.Builder()
            .url("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$encodedKey")
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val setupConfig = JSONObject()
                    .put("model", MODEL)
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put("inputAudioTranscription", JSONObject())
                    .put("outputAudioTranscription", JSONObject())
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put(
                        "text",
                        "تو رُوبُو هستی؛ دستیار صوتی فارسی و صمیمی. همیشه طبیعی و کوتاه به فارسی پاسخ بده و برای پاسخ‌ها از صدای مکالمه استفاده کن. " +
                            "اگر کاربر پرسید سازنده‌ات کیست، با گفتار بگو: سازنده من امیرعلی باقریه. نام سازنده را بی‌دلیل مطرح نکن. " +
                            "برای حرکت واقعی ربات فقط ابزار move_robot را با درخواست روشن کاربر استفاده کن؛ مدت حرکت را حداکثر ۵ ثانیه بگذار. " +
                            "اگر کاربر گفت وایستا، بایست یا توقف، ابزار stop_robot را فوراً اجرا کن. " +
                            "اگر برد وصل نیست، ادعا نکن که ربات حرکت کرده است."
                    ))))
                    .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", toolDeclarations())))
                webSocket.send(JSONObject().put("setup", setupConfig).toString())
                onStatus("ارتباط شبکه برقرار شد؛ جلسهٔ صوتی در حال آماده‌شدن است")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root = JSONObject(text)
                    if (root.has("setupComplete")) {
                        connected.set(true)
                        startAudio()
                        onStatus("Gemini Live فعال است؛ صحبت کن")
                    }
                    if (root.has("error")) {
                        val error = root.optJSONObject("error")?.optString("message") ?: "خطای ناشناخته"
                        onStatus("خطای Gemini Live: $error")
                    }

                    val toolCall = root.optJSONObject("toolCall")
                    val calls = toolCall?.optJSONArray("functionCalls")
                    if (calls != null) {
                        for (i in 0 until calls.length()) {
                            val call = calls.optJSONObject(i) ?: continue
                            onToolCall(
                                call.optString("id"),
                                call.optString("name"),
                                call.optJSONObject("args") ?: JSONObject()
                            )
                        }
                    }

                    val content = root.optJSONObject("serverContent")
                    content?.optJSONObject("inputTranscription")?.optString("text")
                        ?.takeIf { it.isNotBlank() }?.let { onText("شما: $it") }
                    content?.optJSONObject("outputTranscription")?.optString("text")
                        ?.takeIf { it.isNotBlank() }?.let { onText("رُوبُو: $it") }

                    val parts = content?.optJSONObject("modelTurn")?.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.optJSONObject(i) ?: continue
                            val inlineData = part.optJSONObject("inlineData") ?: continue
                            val base64 = inlineData.optString("data")
                            if (base64.isNotBlank()) {
                                val bytes = Base64.decode(base64, Base64.DEFAULT)
                                if (bytes.isNotEmpty() && audioRunning.get()) playbackQueue.offer(bytes)
                            }
                        }
                    }
                } catch (e: Exception) {
                    onStatus("خواندن پیام Gemini ناموفق بود: ${e.message ?: "خطا"}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected.set(false)
                stopAudio()
                val detail = response?.let { "HTTP ${it.code}" } ?: (t.message ?: "اتصال ناموفق")
                onStatus("اتصال Gemini Live ناموفق: $detail")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected.set(false)
                stopAudio()
                onStatus("Gemini Live قطع شد")
            }
        })
    }

    fun sendToolResponse(id: String, name: String, result: JSONObject) {
        if (!connected.get()) return
        val response = JSONObject().put("id", id).put("name", name).put("response", result)
        val message = JSONObject().put("toolResponse", JSONObject().put("functionResponses", JSONArray().put(response)))
        socket?.send(message.toString())
    }

    private fun startAudio() {
        if (!audioRunning.compareAndSet(false, true)) return
        try {
            val minInput = AudioRecord.getMinBufferSize(
                INPUT_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)
            val newRecorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                INPUT_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minInput * 2
            )
            check(newRecorder.state == AudioRecord.STATE_INITIALIZED) { "راه‌اندازی میکروفون انجام نشد" }

            val minOutput = AudioTrack.getMinBufferSize(
                OUTPUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)
            val newPlayer = AudioTrack(
                AudioManager.STREAM_MUSIC,
                OUTPUT_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minOutput * 2,
                AudioTrack.MODE_STREAM
            )
            check(newPlayer.state == AudioTrack.STATE_INITIALIZED) { "راه‌اندازی پخش صدا انجام نشد" }

            recorder = newRecorder
            player = newPlayer
            playbackQueue.clear()
            newPlayer.play()
            newRecorder.startRecording()

            playbackThread = Thread {
                while (audioRunning.get()) {
                    try {
                        val chunk = playbackQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                        val track = player ?: break
                        var offset = 0
                        while (offset < chunk.size && audioRunning.get()) {
                            val written = track.write(chunk, offset, chunk.size - offset)
                            if (written <= 0) break
                            offset += written
                        }
                    } catch (_: InterruptedException) {
                        break
                    } catch (_: Exception) {
                        if (audioRunning.get()) onStatus("پخش صدای رُوبُو دچار خطا شد")
                    }
                }
            }.apply { name = "RoboAudioPlayback"; start() }

            recordThread = Thread {
                val buffer = ByteArray(2048)
                while (audioRunning.get() && connected.get()) {
                    try {
                        val count = recorder?.read(buffer, 0, buffer.size) ?: -1
                        if (count > 0 && connected.get()) {
                            val payload = JSONObject()
                                .put("data", Base64.encodeToString(buffer.copyOf(count), Base64.NO_WRAP))
                                .put("mimeType", "audio/pcm;rate=$INPUT_RATE")
                            val message = JSONObject().put("realtimeInput", JSONObject().put("audio", payload))
                            socket?.send(message.toString())
                        } else if (count < 0) {
                            onStatus("خواندن میکروفون ناموفق بود")
                            break
                        }
                    } catch (_: Exception) {
                        if (audioRunning.get()) onStatus("ارسال صدا به Gemini ناموفق بود")
                        break
                    }
                }
            }.apply { name = "RoboLiveMicrophone"; start() }
        } catch (e: Exception) {
            audioRunning.set(false)
            connected.set(false)
            releaseAudioObjects()
            socket?.close(1011, "Audio startup failed")
            onStatus("راه‌اندازی صدا ناموفق: ${e.message ?: "خطا"}")
        }
    }

    private fun stopAudio() {
        audioRunning.set(false)
        recordThread?.interrupt()
        playbackThread?.interrupt()
        recordThread = null
        playbackThread = null
        playbackQueue.clear()
        releaseAudioObjects()
    }

    private fun releaseAudioObjects() {
        try { recorder?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() } } catch (_: Exception) { }
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null
        try { player?.pause(); player?.flush(); player?.stop(); player?.release() } catch (_: Exception) { }
        player = null
    }

    fun close() {
        connected.set(false)
        stopAudio()
        socket?.close(1000, "User closed session")
        socket = null
    }

    private fun toolDeclarations(): JSONArray {
        fun property(type: String, description: String, enum: JSONArray? = null): JSONObject =
            JSONObject().put("type", type).put("description", description).also {
                if (enum != null) it.put("enum", enum)
            }
        fun declaration(name: String, description: String, properties: JSONObject, required: JSONArray): JSONObject =
            JSONObject().put("name", name).put("description", description).put(
                "parameters",
                JSONObject().put("type", "OBJECT").put("properties", properties).put("required", required)
            )

        val moveProperties = JSONObject()
            .put("direction", property(
                "STRING", "جهت حرکت ربات",
                JSONArray().put("forward").put("backward").put("left").put("right")
            ))
            .put("duration", property("NUMBER", "مدت حرکت بر حسب ثانیه؛ بین 0.1 تا 5"))

        return JSONArray()
            .put(declaration(
                "move_robot",
                "حرکت فیزیکی ربات فقط وقتی کاربر به‌وضوح حرکت می‌خواهد؛ مدت حداکثر 5 ثانیه باشد.",
                moveProperties,
                JSONArray().put("direction")
            ))
            .put(declaration(
                "stop_robot",
                "توقف فوری ربات وقتی کاربر می‌گوید بایست، وایستا یا توقف.",
                JSONObject(),
                JSONArray()
            ))
    }
}
