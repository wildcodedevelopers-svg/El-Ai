package com.elham.elai // TODO: change to your app's package name

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Hosts El-AI in a WebView and gives the page real phone abilities:
 * voice output (TextToSpeech), voice input (SpeechRecognizer), opening any installed app,
 * and sending links/intents to the right app instead of loading them inside the WebView.
 */
class MainActivity : AppCompatActivity() {

    private val appHost = "wildcodedevelopers-svg.github.io"
    private val appUrl = "https://$appHost/" // TODO: add your repo path if the app lives in a sub-folder

    private lateinit var web: WebView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val contactsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        web.addJavascriptInterface(Bridge(), "ElNative")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                // Stay in the app for El-AI itself; send everything else to the phone.
                if (req.url.host == appHost) return false
                return openExternal(req.url.toString())
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(r: PermissionRequest) { r.grant(r.resources) }
        }

        tts = TextToSpeech(this) { ttsReady = it == TextToSpeech.SUCCESS }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        web.loadUrl(appUrl)
    }

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }

    private fun openExternal(url: String): Boolean {
        try {
            val i = if (url.startsWith("intent:")) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE) // safety: no explicit components
                    component = null
                    selector = null
                }
            } else Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (_: Exception) { /* no app can handle it */ }
        return true
    }

    private fun pickVoice(t: TextToSpeech, source: String): Voice? {
        val lang = Locale.getDefault().language
        val list = t.voices?.filter {
            it.locale.language == lang && it.features?.contains("notInstalled") != true
        } ?: return null
        val pick = when (source) {
            "offline" -> list.filter { !it.isNetworkConnectionRequired }
            "online" -> list.filter { it.isNetworkConnectionRequired }
            else -> list
        }
        return pick.firstOrNull() ?: list.firstOrNull()
    }

    inner class Bridge {
        @JavascriptInterface fun hasTts(): Boolean = ttsReady
        @JavascriptInterface fun hasStt(): Boolean = SpeechRecognizer.isRecognitionAvailable(this@MainActivity)

        @JavascriptInterface
        fun speak(text: String, pitch: Float, rate: Float, volume: Float, source: String) {
            runOnUiThread {
                val t = tts ?: return@runOnUiThread
                t.stop()
                pickVoice(t, source)?.let { t.voice = it }
                t.setPitch(pitch)
                t.setSpeechRate(rate)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { js("ElCallbacks.onSpeakDone&&ElCallbacks.onSpeakDone()") }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { js("ElCallbacks.onSpeakDone&&ElCallbacks.onSpeakDone()") }
                    override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) {
                        js("ElCallbacks.onSpeakPulse&&ElCallbacks.onSpeakPulse()")
                    }
                })
                val params = Bundle().apply {
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f))
                }
                t.speak(text.take(3900), TextToSpeech.QUEUE_FLUSH, params, "el")
            }
        }

        @JavascriptInterface fun stopSpeaking() { runOnUiThread { tts?.stop() } }

        @JavascriptInterface
        fun startListening(lang: String, offline: Boolean) {
            runOnUiThread {
                recognizer?.destroy()
                val r = SpeechRecognizer.createSpeechRecognizer(this@MainActivity)
                recognizer = r
                val end = "ElCallbacks.onListenEnd&&ElCallbacks.onListenEnd()"
                r.setRecognitionListener(object : RecognitionListener {
                    private fun text(b: Bundle?) =
                        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    override fun onPartialResults(b: Bundle?) {
                        text(b)?.let { js("ElCallbacks.onResult&&ElCallbacks.onResult(${JSONObject.quote(it)},false)") }
                    }
                    override fun onResults(b: Bundle?) {
                        text(b)?.let { js("ElCallbacks.onResult&&ElCallbacks.onResult(${JSONObject.quote(it)},true)") }
                        js(end)
                    }
                    override fun onError(error: Int) {
                        js("ElCallbacks.onError&&ElCallbacks.onError($error)")
                        js(end)
                    }
                    override fun onRmsChanged(rms: Float) { js("ElCallbacks.onLevel&&ElCallbacks.onLevel($rms)") }
                    override fun onReadyForSpeech(p: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(type: Int, p: Bundle?) {}
                })
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
                }
                r.startListening(i)
            }
        }

        @JavascriptInterface fun stopListening() { runOnUiThread { recognizer?.cancel() } }

        @JavascriptInterface fun openUrl(url: String) { runOnUiThread { openExternal(url) } }

        /** Opens any installed launcher app whose name matches, e.g. "whatsapp", "camera". */
        @JavascriptInterface
        fun openAppByName(name: String): Boolean {
            val q = name.lowercase().trim()
            if (q.length < 2) return false
            val pm = packageManager
            val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            val hit = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
                ?: if (q.length >= 3) apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) } else null
            val launch = hit?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) } ?: return false
            runOnUiThread { startActivity(launch) }
            return true
        }

        /**
         * Looks up contacts by name. Returns JSON: {"matches":[{name,number,e164,exact}]} or {"error":"permission"}.
         * The first call asks Android for contacts permission; the user then repeats the command.
         */
        @JavascriptInterface
        fun findContacts(query: String): String {
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread { contactsPermission.launch(Manifest.permission.READ_CONTACTS) }
                return """{"error":"permission"}"""
            }
            val q = query.trim().lowercase()
            if (q.isEmpty()) return """{"matches":[]}"""
            val tm = this@MainActivity.getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
            val country = (tm?.networkCountryIso?.takeIf { it.isNotBlank() } ?: Locale.getDefault().country).uppercase()
            val P = ContactsContract.CommonDataKinds.Phone
            val found = LinkedHashMap<String, Pair<String, Boolean>>() // name -> (number, isMobile)
            contentResolver.query(
                P.CONTENT_URI,
                arrayOf(P.DISPLAY_NAME, P.NUMBER, P.TYPE),
                "${P.DISPLAY_NAME} LIKE ?",
                arrayOf("%$q%"),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val num = c.getString(1) ?: continue
                    val mobile = c.getInt(2) == P.TYPE_MOBILE
                    val prev = found[name]
                    if (prev == null || (!prev.second && mobile)) found[name] = num to mobile
                }
            }
            val names = found.keys.sortedWith(
                compareBy({ it.lowercase() != q }, { !it.lowercase().startsWith(q) }, { it })
            )
            val exactCount = names.count { it.lowercase() == q }
            val arr = JSONArray()
            names.take(5).forEachIndexed { idx, n ->
                val raw = found[n]!!.first
                val e164 = PhoneNumberUtils.formatNumberToE164(raw, country) ?: raw.filter { it.isDigit() || it == '+' }
                arr.put(
                    JSONObject().put("name", n).put("number", raw).put("e164", e164)
                        .put("exact", idx == 0 && exactCount == 1)
                )
            }
            return JSONObject().put("matches", arr).toString()
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        recognizer?.destroy()
        super.onDestroy()
    }
}
