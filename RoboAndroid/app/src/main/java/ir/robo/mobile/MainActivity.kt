package ir.robo.mobile

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.io.File
import java.security.MessageDigest

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private val usbPermission = "ir.robo.mobile.USB_PERMISSION"
    private lateinit var usbManager: UsbManager
    private var serialPort: UsbSerialPort? = null
    private val mainHandler = Handler(Looper.getMainLooper())
private var serialReadyAtMs = 0L
    private var tts: TextToSpeech? = null
    private var speech: SpeechRecognizer? = null
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var keyInput: EditText
    private lateinit var messageInput: EditText
    private lateinit var connectButton: Button
    private val prefs by lazy { getSharedPreferences("robo", MODE_PRIVATE) }
    private var live: RoboLiveClient? = null
    private val timers: ScheduledExecutorService = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private var movementStopTask: java.util.concurrent.ScheduledFuture<*>? = null
    private val projectRoot by lazy { File(filesDir, "RoboProject").apply { mkdirs(); seedProject(this) } }

    private fun seedProject(root: File) {
        val entries = listOf("robo_app.py", "config.py", "requirements.txt", "start_robo.bat", "README_FA.txt", "Arduino/Robo_Mega_Updated/Robo_Mega_Updated.ino")
        entries.forEach { rel ->
            val dest=File(root, rel)
            if(!dest.exists()) try { dest.parentFile?.mkdirs(); assets.open("robo_seed/$rel").use { input -> dest.outputStream().use { input.copyTo(it) } } } catch (_: Exception) {}
        }
        File(root, "README_ANDROID.txt").takeIf { !it.exists() }?.writeText("پروژهٔ دسکتاپ رُوبُو برای مرور و ویرایش روی گوشی کپی شده است. اجرای robo_app.py و فایل BAT ویندوز روی Android انجام نمی‌شود.\n")
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == usbPermission) {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) openDevice(device)
                else setStatus("اجازه USB داده نشد")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        registerReceiver(usbReceiver, IntentFilter(usbPermission), RECEIVER_NOT_EXPORTED)
        tts = TextToSpeech(this, this)
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 7)
        }
        setStatus("آماده؛ ابتدا Arduino را با OTG وصل کن")
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(22, 26, 22, 20); setBackgroundColor(0xFF101426.toInt()) }
        fun text(s: String, size: Float, color: Int = 0xFFFFFFFF.toInt()) = TextView(this).apply { text=s; textSize=size; setTextColor(color); gravity=Gravity.CENTER; setPadding(4,10,4,10) }
        root.addView(text("رُوبُو | نسخه اندروید", 25f))
        status = text("وضعیت: در حال آماده‌سازی", 14f, 0xFF8EA6FF.toInt()); root.addView(status)
        keyInput = EditText(this).apply { hint="Gemini API Key را اینجا وارد کن"; setSingleLine(true); setText(prefs.getString("api_key", "")); setHintTextColor(0xFFAAAAAA.toInt()); setTextColor(-1); setPadding(12,8,12,8) }
        root.addView(keyInput, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        val saveKey = Button(this).apply { text="ذخیره کلید Gemini"; setOnClickListener { prefs.edit().putString("api_key", keyInput.text.toString().trim()).apply(); sayLog("کلید روی گوشی ذخیره شد.") } }; root.addView(saveKey)
        connectButton = Button(this).apply { text="اتصال به Arduino از طریق USB OTG"; setOnClickListener { connectArduino() } }; root.addView(connectButton)
        val row = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER }
        listOf("جلو" to "FORWARD", "عقب" to "BACKWARD", "چپ" to "LEFT", "راست" to "RIGHT", "توقف" to "STOP").forEach { (label, cmd) ->
            row.addView(Button(this).apply { text=label; textSize=12f; setOnClickListener { sendMove(cmd) } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(row)
        messageInput = EditText(this).apply { hint="پیام یا فرمان را بنویس…"; setTextColor(-1); setHintTextColor(0xFFAAAAAA.toInt()) }; root.addView(messageInput)
        val actions = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply { text="ارسال"; setOnClickListener { val s=messageInput.text.toString().trim(); if(s.isNotEmpty()){ messageInput.text.clear(); handleUserText(s) } } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        actions.addView(Button(this).apply { text="🎙 صحبت"; setOnClickListener { listen() } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        root.addView(actions)
        root.addView(Button(this).apply { text="شروع مکالمهٔ پیوسته Gemini Live"; setOnClickListener { startLive() } })
        root.addView(Button(this).apply { text="قطع مکالمهٔ Live"; setOnClickListener { live?.close(); live=null; setStatus("مکالمه Live قطع شد") } })
        root.addView(Button(this).apply { text="مدیریت فایل‌های پروژه / پشتیبان‌ها"; setOnClickListener { showProjectManager() } })
        root.addView(Button(this).apply { text="توقف فوری موتورها"; setOnClickListener { sendMove("STOP") } })
        log = text("گزارش برنامه", 13f); log.gravity=Gravity.START; log.textAlignment=TextView.TEXT_ALIGNMENT_VIEW_START
        val scroll = ScrollView(this); scroll.addView(log); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun connectArduino() {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) { setStatus("Arduino پیدا نشد؛ کابل/OTG و روشن بودن Mega را بررسی کن"); return }
        val device = drivers[0].device
        if (!usbManager.hasPermission(device)) {
            val pi = PendingIntent.getBroadcast(this, 0, Intent(usbPermission).setPackage(packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            usbManager.requestPermission(device, pi)
        } else openDevice(device)
    }

    private fun openDevice(device: UsbDevice) {
    private fun openDevice(device: UsbDevice) {
    try {
        serialPort?.let {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        serialPort = null
        serialReadyAtMs = 0L

        val driver =
            UsbSerialProber.getDefaultProber()
                .probeDevice(device)
                ?: error("درایور USB شناخته نشد")

        val connection =
            usbManager.openDevice(device)
                ?: error("USB باز نشد")

        val port = driver.ports[0]

        port.open(connection)
        port.setParameters(
            9600,
            8,
            UsbSerialPort.STOPBITS_1,
            UsbSerialPort.PARITY_NONE
        )

        try {
            port.setDTR(true)
        } catch (_: Exception) {
        }

        serialPort = port
        serialReadyAtMs =
            SystemClock.elapsedRealtime() + 2000L

        setStatus("USB وصل شد؛ آردوینو در حال آماده‌شدن است")

        mainHandler.postDelayed({
            if (serialPort === port) {
                serialReadyAtMs = 0L
                setStatus("Arduino آماده است (9600 baud)")
                sayLog("آردوینو آماده دریافت فرمان است.")
            }
        }, 2000L)

    } catch (e: Exception) {
        serialReadyAtMs = 0L
        setStatus("خطای USB: ${e.message}")
    }
    }

    private fun sendMove(command: String): Boolean {
        if(command == "STOP") movementStopTask?.cancel(false)
        return try {
            val port = serialPort ?: run { setStatus("اول Arduino را وصل کن"); return false }
            val bytes = "$command\n".toByteArray(Charsets.UTF_8)
            port.write(bytes, 700)
            sayLog("فرمان حرکتی: $command")
            true
        } catch (e: Exception) { setStatus("ارسال فرمان ناموفق: ${e.message}"); false }
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
