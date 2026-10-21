package ir.robo.mobile

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    companion object {
        private const val USB_PERMISSION = "ir.robo.mobile.USB_PERMISSION"
        private const val AUDIO_PERMISSION_REQUEST = 700
        private const val BAUD_RATE = 9600
        private const val PREFS_NAME = "robo_private_settings"
        private const val PREF_API_KEY = "gemini_api_key"

        private const val BG = 0xFF06130F.toInt()
        private const val PANEL = 0xFF0D211A.toInt()
        private const val PANEL_BORDER = 0xFF1D4939.toInt()
        private const val GOLD = 0xFFD7B56D.toInt()
        private const val TEAL = 0xFF45E6C1.toInt()
        private const val MUTED = 0xFFA6BEB4.toInt()
        private const val WHITE = 0xFFF4FBF7.toInt()
        private const val RED = 0xFFB95050.toInt()
    }

    private lateinit var usbManager: UsbManager
    private lateinit var statusText: TextView
    private lateinit var voiceButton: Button
    private lateinit var boardButton: Button
    private lateinit var robotAvatar: RobotAvatarView
    private lateinit var waveform: WaveformView
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var movementStopTask: ScheduledFuture<*>? = null
    private var serialPort: UsbSerialPort? = null
    private var serialReadyAtMs = 0L
    private var live: RoboLiveClient? = null
    private var receiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != USB_PERMISSION) return
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                openDevice(device)
            } else {
                setStatus("اجازهٔ اتصال USB داده نشد")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.decorView.systemUiVisibility = 0
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        val filter = IntentFilter(USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, filter)
        }
        receiverRegistered = true
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION_REQUEST)
        }
        setStatus("آماده‌ام؛ کلید Gemini را در تنظیمات ذخیره کن")
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setFillViewport(true)
            setBackgroundColor(BG)
            clipToPadding = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(14), dp(18), dp(24))
            setBackgroundColor(BG)
        }
        scroll.addView(root, ScrollView.LayoutParams(-1, -2))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val brand = TextView(this).apply {
            text = "رُوبُو"
            textSize = 29f
            setTextColor(GOLD)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(brand, LinearLayout.LayoutParams(0, dp(52), 1f))
        val settingsButton = makeButton("⚙", PANEL, GOLD, 48).apply {
            textSize = 22f
            contentDescription = "تنظیمات کلید Gemini"
            setOnClickListener { showApiKeySettings() }
        }
        header.addView(settingsButton, LinearLayout.LayoutParams(dp(52), dp(48)))
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        val subtitle = label("مکالمهٔ زندهٔ صوتی", 13f, MUTED, Gravity.CENTER).apply {
            letterSpacing = 0.04f
            setPadding(0, dp(2), 0, dp(8))
        }
        root.addView(subtitle, LinearLayout.LayoutParams(-1, -2))

        statusText = label("وضعیت: آماده‌سازی…", 13f, TEAL, Gravity.CENTER).apply {
            background = rounded(PANEL, PANEL_BORDER, 18)
            setPadding(dp(14), dp(9), dp(14), dp(9))
        }
        root.addView(statusText, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4)
            bottomMargin = dp(10)
        })

        robotAvatar = RobotAvatarView(this).apply {
            contentDescription = "شکلک متحرک رُوبُو"
        }
        root.addView(robotAvatar, LinearLayout.LayoutParams(-1, dp(218)))

        val liveHint = label("برای شروع، دکمهٔ مکالمه را بزن و طبیعی صحبت کن", 14f, WHITE, Gravity.CENTER).apply {
            setPadding(dp(8), dp(3), dp(8), dp(8))
        }
        root.addView(liveHint, LinearLayout.LayoutParams(-1, -2))

        waveform = WaveformView(this)
        root.addView(waveform, LinearLayout.LayoutParams(-1, dp(43)).apply {
            bottomMargin = dp(10)
        })

        voiceButton = makeButton("شروع مکالمهٔ صوتی", 0xFF13664D.toInt(), WHITE, 60).apply {
            textSize = 17f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setOnClickListener { toggleLive() }
        }
        root.addView(voiceButton, LinearLayout.LayoutParams(-1, dp(60)).apply {
            bottomMargin = dp(18)
        })

        val controlsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(PANEL, PANEL_BORDER, 22)
            setPadding(dp(14), dp(14), dp(14), dp(16))
        }
        controlsCard.addView(label("کنترل دستی ربات", 17f, GOLD, Gravity.CENTER), LinearLayout.LayoutParams(-1, -2))
        controlsCard.addView(label("برای حرکت، دکمهٔ جهت را نگه دار؛ با رهاکردن متوقف می‌شود", 12f, MUTED, Gravity.CENTER).apply {
            setPadding(0, dp(3), 0, dp(12))
        }, LinearLayout.LayoutParams(-1, -2))

        boardButton = makeButton("اتصال برد از طریق USB OTG", 0xFF17392D.toInt(), TEAL, 48).apply {
            textSize = 14f
            setOnClickListener { connectArduino() }
        }
        controlsCard.addView(boardButton, LinearLayout.LayoutParams(-1, dp(48)).apply {
            bottomMargin = dp(12)
        })

        val upRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        upRow.addView(directionButton("▲  جلو", "FORWARD"), LinearLayout.LayoutParams(dp(124), dp(55)))
        controlsCard.addView(upRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })

        val middleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        middleRow.addView(directionButton("◀  چپ", "LEFT"), LinearLayout.LayoutParams(0, dp(55), 1f).apply { marginEnd = dp(5) })
        middleRow.addView(makeButton("توقف", 0xFF572D2D.toInt(), WHITE, 55).apply {
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    movementStopTask?.cancel(false)
                    sendMove("STOP")
                    true
                } else true
            }
        }, LinearLayout.LayoutParams(0, dp(55), 1f).apply { marginEnd = dp(5); marginStart = dp(5) })
        middleRow.addView(directionButton("راست  ▶", "RIGHT"), LinearLayout.LayoutParams(0, dp(55), 1f).apply { marginStart = dp(5) })
        controlsCard.addView(middleRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })

        val downRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        downRow.addView(directionButton("▼  عقب", "BACKWARD"), LinearLayout.LayoutParams(dp(124), dp(55)))
        controlsCard.addView(downRow, LinearLayout.LayoutParams(-1, -2))

        root.addView(controlsCard, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(2)
            bottomMargin = dp(12)
        })

        val securityNote = label("کلید API فقط روی همین گوشی ذخیره می‌شود؛ آن را در مخزن عمومی منتشر نکن.", 11f, MUTED, Gravity.CENTER).apply {
            setPadding(dp(8), dp(6), dp(8), dp(4))
        }
        root.addView(securityNote, LinearLayout.LayoutParams(-1, -2))
        setContentView(scroll)
    }

    private fun directionButton(title: String, command: String): Button = makeButton(title, 0xFF15372C.toInt(), WHITE, 55).apply {
        textSize = 14f
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    movementStopTask?.cancel(false)
                    sendMove(command)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    movementStopTask?.cancel(false)
                    sendMove("STOP")
                    true
                }
                else -> true
            }
        }
    }

    private fun toggleLive() {
        if (live != null) {
            stopLive("مکالمه قطع شد")
            return
        }
        val key = prefs.getString(PREF_API_KEY, "")?.trim().orEmpty()
        if (key.isBlank()) {
            showApiKeySettings()
            setStatus("ابتدا کلید Gemini را در تنظیمات وارد کن")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION_REQUEST)
            setStatus("مجوز میکروفون را بده و دوباره مکالمه را شروع کن")
            return
        }
        live?.close()
        live = RoboLiveClient(
            apiKey = key,
            onStatus = { message ->
                setStatus(message)
                if (message.contains("خطای Gemini Live") || message.contains("Gemini Live قطع شد") || message.contains("اتصال Gemini Live ناموفق") || message.contains("راه‌اندازی صدا ناموفق")) {
                    mainHandler.post {
                        live = null
                        updateLiveUi(false)
                        waveform.setActive(false)
                    }
                }
            },
            onText = { message ->
                // رابط عمداً متن مکالمه را نمایش نمی‌دهد؛ فقط حالت شنیدن/پاسخ‌دادن را تغییر می‌دهد.
                val isUser = message.startsWith("شما:")
                mainHandler.post {
                    robotAvatar.setMood(if (isUser) RobotAvatarView.MOOD_LISTENING else RobotAvatarView.MOOD_SPEAKING)
                    waveform.setActive(true)
                }
            },
            onToolCall = { id, name, args -> mainHandler.post { handleLiveTool(id, name, args) } }
        )
        robotAvatar.setMood(RobotAvatarView.MOOD_LISTENING)
        waveform.setActive(true)
        updateLiveUi(true)
        live?.connect()
    }

    private fun stopLive(message: String) {
        live?.close()
        live = null
        updateLiveUi(false)
        waveform.setActive(false)
        robotAvatar.setMood(RobotAvatarView.MOOD_IDLE)
        setStatus(message)
    }

    private fun updateLiveUi(startingOrActive: Boolean) {
        runOnUiThread {
            voiceButton.text = if (startingOrActive) "قطع مکالمهٔ صوتی" else "شروع مکالمهٔ صوتی"
            voiceButton.background = rounded(if (startingOrActive) RED else 0xFF13664D.toInt(), null, 18)
            waveform.setActive(startingOrActive)
        }
    }

    private fun showApiKeySettings() {
        val keyInput = EditText(this).apply {
            hint = "کلید Gemini API"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString(PREF_API_KEY, ""))
            setSelection(text.length)
            textSize = 14f
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(2))
            addView(keyInput, LinearLayout.LayoutParams(-1, dp(54)))
            addView(label("کلید را در فایل‌های GitHub یا پیام عمومی قرار نده.", 12f, MUTED, Gravity.START).apply {
                setPadding(0, dp(8), 0, dp(4))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("تنظیمات Gemini")
            .setView(container)
            .setNegativeButton("لغو", null)
            .setNeutralButton("پاک‌کردن کلید") { _, _ ->
                prefs.edit().remove(PREF_API_KEY).apply()
                setStatus("کلید Gemini از گوشی پاک شد")
            }
            .setPositiveButton("ذخیره روی گوشی") { _, _ ->
                val value = keyInput.text.toString().trim()
                if (value.isBlank()) {
                    setStatus("کلید خالی ذخیره نشد")
                } else {
                    prefs.edit().putString(PREF_API_KEY, value).apply()
                    setStatus("کلید Gemini روی گوشی ذخیره شد")
                }
            }
            .show()
    }

    private fun connectArduino() {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            setStatus("برد پیدا نشد؛ کابل OTG و روشن‌بودن برد را بررسی کن")
            return
        }
        val device = drivers.first().device
        if (!usbManager.hasPermission(device)) {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pendingIntent = PendingIntent.getBroadcast(
                this, 0, Intent(USB_PERMISSION).setPackage(packageName), flags
            )
            usbManager.requestPermission(device, pendingIntent)
        } else {
            openDevice(device)
        }
    }

    private fun openDevice(device: UsbDevice) {
        try {
            try { serialPort?.close() } catch (_: Exception) { }
            serialPort = null
            serialReadyAtMs = 0L
            val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
                ?: error("نوع درایور USB شناخته نشد")
            val connection = usbManager.openDevice(device) ?: error("بازکردن USB ناموفق بود")
            val port = driver.ports.firstOrNull() ?: error("پورت سریال پیدا نشد")
            port.open(connection)
            port.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            try { port.setDTR(true) } catch (_: Exception) { }
            serialPort = port
            serialReadyAtMs = SystemClock.elapsedRealtime() + 1800L
            boardButton.text = "برد وصل شد · آماده‌سازی…"
            setStatus("USB وصل شد؛ برد در حال آماده‌شدن است")
            mainHandler.postDelayed({
                if (serialPort === port) {
                    serialReadyAtMs = 0L
                    boardButton.text = "برد متصل است · اتصال دوباره"
                    setStatus("برد آماده است · سریال $BAUD_RATE")
                }
            }, 1800L)
        } catch (e: Exception) {
            serialReadyAtMs = 0L
            boardButton.text = "اتصال برد از طریق USB OTG"
            setStatus("خطای USB: ${e.message ?: "نامشخص"}")
        }
    }

    private fun sendMove(command: String): Boolean {
        val cmd = command.trim().uppercase(Locale.ROOT)
        if (cmd == "STOP") movementStopTask?.cancel(false)
        val port = serialPort ?: run {
            setStatus("برای کنترل موتورها، ابتدا برد را با USB OTG وصل کن")
            return false
        }
        if (SystemClock.elapsedRealtime() < serialReadyAtMs) {
            setStatus("برد هنوز آماده نیست؛ لحظه‌ای صبر کن")
            return false
        }
        if (cmd !in setOf("FORWARD", "BACKWARD", "LEFT", "RIGHT", "STOP")) {
            setStatus("فرمان حرکتی نامعتبر است")
            return false
        }
        return try {
            port.write("$cmd\r\n".toByteArray(Charsets.US_ASCII), 1000)
            true
        } catch (e: Exception) {
            setStatus("ارسال فرمان ناموفق بود: ${e.message ?: "خطا"}")
            false
        }
    }

    private fun handleLiveTool(id: String, name: String, args: JSONObject) {
        val result = try {
            when (name) {
                "move_robot" -> {
                    val direction = args.optString("direction", "").lowercase(Locale.ROOT)
                    val command = when (direction) {
                        "forward" -> "FORWARD"
                        "backward" -> "BACKWARD"
                        "left" -> "LEFT"
                        "right" -> "RIGHT"
                        else -> error("جهت حرکت معتبر نیست")
                    }
                    val duration = args.optDouble("duration", 1.0).coerceIn(0.1, 5.0)
                    movementStopTask?.cancel(false)
                    if (!sendMove(command)) error("فرمان حرکت ارسال نشد؛ بررسی کن برد وصل و آماده باشد")
                    movementStopTask = timer.schedule({ sendMove("STOP") }, (duration * 1000).toLong(), TimeUnit.MILLISECONDS)
                    JSONObject().put("result", "فرمان $command اجرا شد و توقف خودکار پس از ${duration} ثانیه تنظیم شد.")
                }
                "stop_robot" -> {
                    movementStopTask?.cancel(false)
                    if (!sendMove("STOP")) error("فرمان توقف ارسال نشد؛ برد را بررسی کن")
                    JSONObject().put("reatus("ارسال فرمان ناموفق: ${e.message}")
        false
    }
}

    private fun listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { setStatus("تشخیص گفتار روی این گوشی در دسترس نیست؛ از کادر نوشتن استفاده کن"); return }
        try {
            speech?.destroy(); speech = SpeechRecognizer.createSpeechRecognizer(this)
            speech?.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { setStatus("گوش می‌دهم…") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { setStatus("در حال تشخیص گفتار…") }
                override fun onError(error: Int) { setStatus("خطای تشخیص گفتار ($error)؛ دوباره امتحان کن") }
                override fun onResults(results: Bundle?) { val s=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull(); if(!s.isNullOrBlank()) handleUserText(s) }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR"); putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,"fa-IR"); putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false); putExtra(RecognizerIntent.EXTRA_PROMPT,"با رُوبُو صحبت کن") }
            speech?.startListening(intent)
        } catch (e: Exception) { setStatus("میکروفون: ${e.message}") }
    }

    private fun handleUserText(text: String) {
        sayLog("شما: $text")
        live?.let { if (it.isConnected()) { it.sendText(text); return } }
        val t=text.lowercase(Locale.ROOT)
        when {
            listOf("وایستا", "توقف", "بایست", "ایست").any { t.contains(it) } -> { sendMove("STOP"); speak("ایستادم") }
            listOf("برو جلو", "حرکت به جلو", "جلو برو").any { t.contains(it) } -> { sendMove("FORWARD"); speak("حرکت به جلو") }
            listOf("عقب برو", "برو عقب").any { t.contains(it) } -> { sendMove("BACKWARD"); speak("حرکت به عقب") }
            listOf("بپیچ چپ", "به چپ", "چپ برو").any { t.contains(it) } -> { sendMove("LEFT"); speak("به چپ") }
            listOf("بپیچ راست", "به راست", "راست برو").any { t.contains(it) } -> { sendMove("RIGHT"); speak("به راست") }
            t.contains("اسمت چیه") || t.contains("اسم تو چیه") -> speak("من رُوبُو هستم")
            t.contains("سازندت کیه") -> speak("امیرعلی باقری")
            t.contains("سلام") -> askGemini(text, "سلام چه سوالاتی داری؟")
            else -> askGemini(text, null)
        }
    }

    private fun askGemini(userText: String, fallback: String?) {
        val key = prefs.getString("api_key", "")?.trim().orEmpty()
        if (key.isBlank()) { speak(fallback ?: "برای پاسخ هوشمند، اول کلید Gemini را ذخیره کن."); setStatus("کلید Gemini تنظیم نشده"); return }
        setStatus("در حال دریافت پاسخ Gemini…")
        worker.execute {
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent")
                val conn=url.openConnection() as HttpURLConnection
                conn.requestMethod="POST"; conn.connectTimeout=15000; conn.readTimeout=30000; conn.doOutput=true; conn.setRequestProperty("Content-Type","application/json; charset=utf-8"); conn.setRequestProperty("x-goog-api-key", key)
                val prompt = "تو دستیار صوتی فارسی رُوبُو هستی. کوتاه، دوستانه و فارسی جواب بده. سازنده تو امیرعلی باقری است. برای فرمان حرکتی فقط به کاربر توضیح بده؛ حرکت موتورها فقط از فرمان‌های محلی برنامه انجام می‌شود.\nکاربر: $userText"
                val body=JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))))).put("generationConfig", JSONObject().put("temperature",0.6))
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code=conn.responseCode
                val stream=if(code in 200..299) conn.inputStream else conn.errorStream
                val response=stream.bufferedReader().use { it.readText() }
                if(code !in 200..299) throw Exception("HTTP $code: ${response.take(300)}")
                val json=JSONObject(response); val answer=json.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim()
                runOnUiThread { setStatus("پاسخ دریافت شد"); speak(answer) }
            } catch (e: Exception) { runOnUiThread { setStatus("خطای Gemini: ${e.message}"); speak("ارتباط با هوش مصنوعی برقرار نشد") } }
        }
    }

    private fun startLive() {
        val key = prefs.getString("api_key", "")?.trim().orEmpty()
        if (key.isBlank()) { setStatus("ابتدا کلید API Gemini را ذخیره کن"); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 7); setStatus("اجازه میکروفون بده و دوباره بزن"); return
        }
        live?.close()
        live = RoboLiveClient(key,
            onStatus = { setStatus(it) },
            onText = { sayLog(it) },
            onToolCall = { id, name, args -> runOnUiThread { handleLiveTool(id, name, args) } }
        )
        live?.connect()
    }

    private fun handleLiveTool(id: String, name: String, args: JSONObject) {
        val result = try {
            when (name) {
                "move_robot" -> {
                    val dir = args.optString("direction", "forward").lowercase(Locale.ROOT)
                    val command = when(dir) { "forward" -> "FORWARD"; "backward" -> "BACKWARD"; "left" -> "LEFT"; "right" -> "RIGHT"; else -> "STOP" }
                    val duration = args.optDouble("duration", 1.0).coerceIn(0.1, 5.0)
                    movementStopTask?.cancel(false)
                    if (command == "STOP") { if(!sendMove("STOP")) error("Arduino متصل نیست؛ توقف به برد ارسال نشد") }
                    else { if(!sendMove(command)) error("Arduino متصل نیست؛ فرمان حرکتی ارسال نشد"); movementStopTask = timers.schedule({ sendMove("STOP") }, (duration*1000).toLong(), TimeUnit.MILLISECONDS) }
                    JSONObject().put("result", "فرمان $command برای ${duration} ثانیه ارسال شد؛ تایمر توقف فعال است.")
                }
                "stop_robot" -> { movementStopTask?.cancel(false); if(!sendMove("STOP")) error("Arduino متصل نیست؛ فرمان توقف ارسال نشد"); JSONObject().put("result", "فرمان توقف ارسال شد") }
                "list_project_files" -> JSONObject().put("result", listProjectFiles())
                "read_project_file" -> JSONObject().put("result", readProjectFile(args.optString("path")))
                "search_project_code" -> JSONObject().put("result", searchProject(args.optString("query")))
                "check_python_syntax" -> JSONObject().put("result", syntaxCheck(args.optString("path", "")))
                "propose_file_change" -> {
                    val path=args.optString("path"); val summary=args.optString("summary"); val content=args.optString("content")
                    showProposal(id,name,path,summary,content,false); return
                }
                "propose_file_delete" -> {
                    val path=args.optString("path"); val summary=args.optString("summary")
                    showProposal(id,name,path,summary,"",true); return
                }
                else -> JSONObject().put("result", "ابزار ناشناخته")
            }
        } catch (e: Exception) { JSONObject().put("error", e.message ?: "خطا") }
        live?.sendToolResponse(id, name, result)
    }

    private fun safeProjectFile(relative: String): File {
        val base=projectRoot.canonicalFile
        val file=File(base, relative).canonicalFile
        require(file.path.startsWith(base.path + File.separator)) { "مسیر خارج از پوشه پروژه مجاز نیست" }
        require(relative.isNotBlank() && !relative.startsWith("_RoboBackups")) { "مسیر نامعتبر است" }
        return file
    }
    private fun listProjectFiles(): String {
        val out=mutableListOf<String>()
        projectRoot.walkTopDown().maxDepth(8).forEach { f -> if (f.isFile && !f.path.contains("/_RoboBackups/")) out.add(f.relativeTo(projectRoot).invariantSeparatorsPath) }
        return if(out.isEmpty()) "پوشه پروژه خالی است: ${projectRoot.absolutePath}" else out.take(300).joinToString("\n")
    }
    private fun readProjectFile(path: String): String {
        val f=safeProjectFile(path); require(f.isFile) { "فایل پیدا نشد" }; require(f.length() <= 500_000) { "فایل بزرگ‌تر از 500KB است" }
        val text=f.readText(); return text.replace(Regex("(?im)(API_KEY|GEMINI_API_KEY|password|token)\\s*[:=]\\s*[^\\r\\n]+"), "\$1=[مخفی شد]").take(30000)
    }
    private fun searchProject(query: String): String {
        require(query.isNotBlank()) { "عبارت جست‌وجو خالی است" }; val rows=mutableListOf<String>()
        projectRoot.walkTopDown().maxDepth(8).filter { it.isFile && it.length() <= 500_000 && it.extension.lowercase() in listOf("kt","py","ino","txt","md","json","xml","gradle","kts","java","html","js","css") }.forEach { f ->
            try { f.useLines { lines -> lines.forEachIndexed { i,line -> if(line.contains(query,true) && rows.size<100) rows.add("${f.relativeTo(projectRoot).invariantSeparatorsPath}:${i+1}: ${line.take(250)}") } } } catch (_:Exception) {}
        }
        return if(rows.isEmpty()) "موردی پیدا نشد" else rows.joinToString("\n")
    }
    private fun syntaxCheck(path: String): String {
        val files=if(path.isBlank()) projectRoot.walkTopDown().filter { it.isFile && it.extension=="py" }.toList() else listOf(safeProjectFile(path))
        if(files.isEmpty()) return "فایل Python در پوشه پروژه نیست. این بررسی سادهٔ ساختاری است، نه جایگزین کامل Python AST کامپیوتر."
        val issues=mutableListOf<String>()
        files.forEach { f -> try { val t=f.readText(); val opens=t.count { it=='(' }-t.count { it==')' }; val braces=t.count { it=='{' }-t.count { it=='}' }; val brackets=t.count { it=='[' }-t.count { it==']' }; if(opens!=0 || braces!=0 || brackets!=0) issues.add("${f.name}: پرانتز/آکولاد/براکت‌های نامتوازن") } catch(e:Exception) { issues.add("${f.name}: ${e.message}") } }
        return if(issues.isEmpty()) "بررسی ابتدایی انجام شد؛ خطای واضح توازن نشانه‌ها دیده نشد. این بررسی، کامپایلر Python یا AST کامل نیست." else issues.joinToString("\n")
    }
    private fun showProposal(id:String, tool:String, path:String, summary:String, content:String, delete:Boolean) {
        val dialog=android.app.AlertDialog.Builder(this).setTitle(if(delete) "تأیید حذف فایل" else "تأیید تغییر فایل")
            .setMessage("مسیر: $path\\n\\nتوضیح: $summary\\n\\n" + if(delete) "این فایل پس از پشتیبان‌گیری حذف می‌شود." else "پیش‌نمایش: ${content.take(1200)}${if(content.length>1200) "…" else ""}\\n\\nقبل از اعمال، نسخه پشتیبان ساخته می‌شود.")
            .setNegativeButton("رد کردن") { _,_-> live?.sendToolResponse(id,tool,JSONObject().put("result","کاربر تغییر را رد کرد؛ فایل دست‌نخورده ماند.")) }
            .setPositiveButton(if(delete) "پشتیبان بگیر و حذف کن" else "پشتیبان بگیر و اعمال کن") { _,_->
                try {
                    val f=safeProjectFile(path); val backupDir=File(projectRoot,"_RoboBackups/${System.currentTimeMillis()}"); backupDir.mkdirs()
                    if(f.exists()) { val dest=File(backupDir,path); dest.parentFile?.mkdirs(); f.copyTo(dest,true) }
                    if(delete) { require(f.isFile) { "فایل وجود ندارد" }; f.delete() } else { require(content.toByteArray().size<=500_000) { "محتوا بیش از 500KB است" }; f.parentFile?.mkdirs(); f.writeText(content) }
                    live?.sendToolResponse(id,tool,JSONObject().put("result", "تغییر اعمال شد. نسخه پشتیبان: ${backupDir.relativeTo(projectRoot).invariantSeparatorsPath}")); sayLog("تغییر فایل تأیید و اعمال شد: $path")
                } catch(e:Exception) { live?.sendToolResponse(id,tool,JSONObject().put("error",e.message ?: "خطا")) }
            }.create()
        dialog.setOnCancelListener { live?.sendToolResponse(id,tool,JSONObject().put("result","کاربر پنجره را بست؛ تغییری انجام نشد.")) }; dialog.show()
    }
    private fun showProjectManager() {
        val choices=arrayOf("فهرست فایل‌ها", "جست‌وجو در فایل‌ها", "خواندن فایل", "واردکردن فایل از گوشی", "فهرست پشتیبان‌ها", "بازیابی فایل از پشتیبان")
        android.app.AlertDialog.Builder(this).setTitle("مدیریت پروژه رُوبُو").setItems(choices) { _,which -> when(which) {
            0 -> android.app.AlertDialog.Builder(this).setTitle("فایل‌های پروژه").setMessage(listProjectFiles()).setPositiveButton("باشه",null).show()
            1 -> askText("جست‌وجو", "عبارت موردنظر") { q -> android.app.AlertDialog.Builder(this).setTitle("نتایج").setMessage(searchProject(q)).setPositiveButton("باشه",null).show() }
            2 -> askText("خواندن فایل", "مسیر نسبی فایل") { q -> try { android.app.AlertDialog.Builder(this).setTitle(q).setMessage(readProjectFile(q).take(12000)).setPositiveButton("باشه",null).show() } catch(e:Exception) { setStatus(e.message ?: "خطا") } }
            3 -> { val i=Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i,41) }
            4 -> { val b=File(projectRoot,"_RoboBackups"); val txt=if(b.exists()) b.listFiles()?.sortedByDescending{it.name}?.joinToString("\n"){it.name} ?: "خالی" else "هنوز پشتیبانی وجود ندارد"; android.app.AlertDialog.Builder(this).setTitle("پشتیبان‌ها").setMessage(txt).setPositiveButton("باشه",null).show() }
            5 -> restoreBackupFile()
        } }.show()
    }
    private fun restoreBackupFile() {
        val backupRoot=File(projectRoot,"_RoboBackups")
        val files=if(backupRoot.exists()) backupRoot.walkTopDown().filter { it.isFile }.toList() else emptyList()
        if(files.isEmpty()) { setStatus("فایلی برای بازیابی در پشتیبان‌ها نیست"); return }
        val labels=files.map { it.relativeTo(backupRoot).invariantSeparatorsPath }.toTypedArray()
        android.app.AlertDialog.Builder(this).setTitle("فایل پشتیبان برای بازیابی").setItems(labels) { _,index ->
            val backup=files[index]; val rel=backup.relativeTo(backupRoot).invariantSeparatorsPath.substringAfter('/', "")
            if(rel.isBlank()) { setStatus("مسیر پشتیبان نامعتبر است"); return@setItems }
            android.app.AlertDialog.Builder(this).setTitle("تأیید بازیابی").setMessage("فایل $rel جایگزین نسخه فعلی می‌شود. قبل از جایگزینی هم پشتیبان جدید ساخته می‌شود.")
                .setNegativeButton("لغو",null).setPositiveButton("بازیابی") { _,_-> try {
                    val target=safeProjectFile(rel); val snapshot=File(backupRoot,"before-restore-${System.currentTimeMillis()}"); snapshot.mkdirs()
                    if(target.exists()) { val old=File(snapshot,rel); old.parentFile?.mkdirs(); target.copyTo(old,true) }
                    target.parentFile?.mkdirs(); backup.copyTo(target,true); sayLog("فایل از پشتیبان بازیابی شد: $rel")
                } catch(e:Exception) { setStatus("بازیابی ناموفق: ${e.message}") } }.show()
        }.setNegativeButton("بستن",null).show()
    }

    private fun askText(title:String, hint:String, done:(String)->Unit) {
        val edit=EditText(this).apply { this.hint=hint }
        android.app.AlertDialog.Builder(this).setTitle(title).setView(edit).setNegativeButton("لغو",null).setPositiveButton("اجرا") { _,_-> done(edit.text.toString().trim()) }.show()
    }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) { super.onActivityResult(requestCode,resultCode,data); if(requestCode==41 && resultCode==RESULT_OK) { val uri=data?.data ?: return; try { val name=uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')?.ifBlank { "imported.txt" } ?: "imported.txt"; val target=safeProjectFile(name); contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }; sayLog("فایل وارد پروژه شد: $name") } catch(e:Exception) { setStatus("واردکردن ناموفق: ${e.message}") } } }

    private fun speak(text: String) { sayLog("رُوبُو: $text"); tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "robo-${System.currentTimeMillis()}") }
    private fun sayLog(text: String) { runOnUiThread { log.append("\n$text") } }
    private fun setStatus(text: String) { runOnUiThread { status.text="وضعیت: $text" } }
    override fun onInit(statusCode: Int) { if(statusCode==TextToSpeech.SUCCESS) { val result=tts?.setLanguage(Locale("fa","IR")); if(result==TextToSpeech.LANG_MISSING_DATA || result==TextToSpeech.LANG_NOT_SUPPORTED) setStatus("صدای فارسی TTS روی گوشی نصب/فعال نیست") } }
    override fun onDestroy() { try { sendMove("STOP"); live?.close(); timers.shutdownNow(); serialPort?.close(); speech?.destroy(); tts?.shutdown(); unregisterReceiver(usbReceiver) } catch (_: Exception) {}; worker.shutdownNow(); super.onDestroy() }
}
