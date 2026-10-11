package ir.robo.mobile

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.AudioManager
import android.util.Base64
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Gemini Live raw WebSocket client. Audio: 16 kHz PCM input, 24 kHz PCM output. */
class RoboLiveClient(
    private val apiKey: String,
    private val onStatus: (String) -> Unit,
    private val onText: (String) -> Unit,
    private val onToolCall: (id: String, name: String, args: JSONObject) -> Unit
) {
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var socket: WebSocket? = null
    private val connected = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var recordThread: Thread? = null
    private var player: AudioTrack? = null

    fun isConnected() = connected.get()

    fun connect() {
        onStatus("در حال اتصال به Gemini Live…")
        val key = URLEncoder.encode(apiKey, "UTF-8")
        val req = Request.Builder()
    .url("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")
    .addHeader("x-goog-api-key", apiKey)
    .build()
        socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val setupBody = JSONObject()
                    .put("model", "models/gemini-3.8-live")
                    .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO")))
                    .put("inputAudioTranscription", JSONObject())
                    .put("outputAudioTranscription", JSONObject())
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "تو رُوبُو هستی؛ دستیار صوتی فارسی سازنده‌ات امیرعلی باقری است. دوستانه و فارسی پاسخ بده. برای حرکت واقعی فقط از ابزار move_robot استفاده کن، حرکت حداکثر ۵ ثانیه باشد و توقف را با stop_robot اجرا کن. برای فایل‌ها فقط از ابزارهای پروژه استفاده کن؛ هر تغییر یا حذف فایل باید با تأیید کاربر انجام شود."))))
                    .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", toolDeclarations())))
                val setup = JSONObject().put("setup", setupBody)
                webSocket.send(setup.toString())
                onStatus("اتصال Live برقرار شد؛ منتظر آماده‌شدن جلسه…")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root=JSONObject(text)
                    if(root.has("setupComplete")) { connected.set(true); startAudio(); onStatus("Gemini Live فعال است؛ صحبت کن") }
                    if(root.has("error")) onStatus("خطای Gemini Live: ${root.optJSONObject("error")?.optString("message") ?: "خطای ناشناخته"}")
                    val call=root.optJSONObject("toolCall")
                    val calls=call?.optJSONArray("functionCalls")
                    if(calls!=null) for(i in 0 until calls.length()) {
                        val fc=calls.optJSONObject(i) ?: continue
                        onToolCall(fc.optString("id"), fc.optString("name"), fc.optJSONObject("args") ?: JSONObject())
                    }
                    val content=root.optJSONObject("serverContent")
                    content?.optJSONObject("inputTranscription")?.optString("text")?.takeIf{it.isNotBlank()}?.let { onText("شما: $it") }
                    content?.optJSONObject("outputTranscription")?.optString("text")?.takeIf{it.isNotBlank()}?.let { onText("رُوبُو: $it") }
                    val parts=content?.optJSONObject("modelTurn")?.optJSONArray("parts")
                    if(parts!=null) for(i in 0 until parts.length()) {
                        val part=parts.optJSONObject(i) ?: continue
                        val inline=part.optJSONObject("inlineData")
                        val b64=inline?.optString("data")
                        if(!b64.isNullOrBlank()) play(Base64.decode(b64, Base64.DEFAULT))
                    }
                } catch(e:Exception) { onStatus("خواندن پاسخ Live: ${e.message}") }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected.set(false); stopAudio(); onStatus("اتصال Gemini Live ناموفق: ${t.message}")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected.set(false); stopAudio(); onStatus("Gemini Live قطع شد")
            }
        })
    }

    fun sendText(text: String) {
        if(!connected.get()) { onStatus("Gemini Live هنوز آماده نیست"); return }
        socket?.send(JSONObject().put("realtimeInput", JSONObject().put("text", text)).toString())
    }

    fun sendToolResponse(id: String, name: String, result: JSONObject) {
        val response = JSONObject().put("id", id).put("name", name).put("response", result)
        socket?.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", JSONArray().put(response))).toString())
    }

    private fun startAudio() {
        if(!running.compareAndSet(false,true)) return
        try {
            val minIn=AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
            recorder=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,minIn*2)
            val minOut=AudioTrack.getMinBufferSize(24000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
            player=AudioTrack(AudioManager.STREAM_MUSIC,24000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT,minOut*2,AudioTrack.MODE_STREAM)
            player?.play(); recorder?.startRecording()
            recordThread=Thread {
                val buffer=ByteArray(2048)
                while(running.get()) {
                    try {
                        val n=recorder?.read(buffer,0,buffer.size) ?: -1
                        if(n>0 && connected.get()) {
                            val bytes=buffer.copyOf(n)
                            val audio=JSONObject().put("data",Base64.encodeToString(bytes,Base64.NO_WRAP)).put("mimeType","audio/pcm;rate=16000")
                            socket?.send(JSONObject().put("realtimeInput",JSONObject().put("audio",audio)).toString())
                        }
                    } catch(_:Exception) { break }
                }
            }.apply { name="RoboLiveMic"; start() }
        } catch(e:Exception) { running.set(false); onStatus("راه‌اندازی میکروفون/صدا ناموفق: ${e.message}") }
    }

    @Synchronized private fun play(bytes: ByteArray) {
        try { if(running.get() && bytes.isNotEmpty()) player?.write(bytes,0,bytes.size) } catch(_:Exception) {}
    }
    private fun stopAudio() {
        running.set(false)
        try { recorder?.stop() } catch(_:Exception) {}
        try { recorder?.release() } catch(_:Exception) {}
        recorder=null
        try { player?.pause(); player?.flush(); player?.stop(); player?.release() } catch(_:Exception) {}
        player=null
    }
    fun close() { connected.set(false); stopAudio(); socket?.close(1000,"user closed"); socket=null }

    private fun toolDeclarations(): JSONArray {
        fun tool(name:String, description:String, props:JSONObject=JSONObject(), required:JSONArray=JSONArray()) = JSONObject()
            .put("name",name).put("description",description).put("parameters",JSONObject().put("type","OBJECT").put("properties",props).put("required",required))
        fun prop(type:String, description:String, enum:JSONArray?=null):JSONObject = JSONObject().put("type",type).put("description",description).also { if(enum!=null) it.put("enum",enum) }
        return JSONArray()
            .put(tool("move_robot","حرکت واقعی رُوبُو؛ فقط با درخواست روشن کاربر، حداکثر ۵ ثانیه.",JSONObject().put("direction",prop("STRING","جهت حرکت",JSONArray().put("forward").put("backward").put("left").put("right"))).put("duration",prop("NUMBER","مدت ثانیه از 0.1 تا 5")),JSONArray().put("direction")))
            .put(tool("stop_robot","توقف فوری موتورها"))
            .put(tool("list_project_files","فهرست فایل‌های پروژه"))
            .put(tool("read_project_file","خواندن فایل متنی پروژه",JSONObject().put("path",prop("STRING","مسیر نسبی فایل")),JSONArray().put("path")))
            .put(tool("search_project_code","جست‌وجوی متن در فایل‌های پروژه",JSONObject().put("query",prop("STRING","عبارت جست‌وجو")),JSONArray().put("query")))
            .put(tool("check_python_syntax","بررسی ابتدایی ساختار فایل Python؛ بررسی کامل AST روی Android در دسترس نیست",JSONObject().put("path",prop("STRING","مسیر نسبی اختیاری فایل Python"))))
            .put(tool("propose_file_change","پیشنهاد تغییر فایل؛ برنامه پیش از اعمال از کاربر تأیید می‌گیرد",JSONObject().put("path",prop("STRING","مسیر نسبی فایل")).put("summary",prop("STRING","خلاصه تغییر")).put("content",prop("STRING","محتوای کامل فایل پیشنهادی")),JSONArray().put("path").put("summary").put("content")))
            .put(tool("propose_file_delete","پیشنهاد حذف فایل؛ نیازمند تأیید کاربر",JSONObject().put("path",prop("STRING","مسیر نسبی فایل")).put("summary",prop("STRING","دلیل حذف")),JSONArray().put("path").put("summary")))
    }
}
