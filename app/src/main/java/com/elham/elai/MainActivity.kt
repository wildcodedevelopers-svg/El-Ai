package com.elham.elai

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class MainActivity : Activity() {

    private val appHost = "wildcodedevelopers-svg.github.io"

    // GitHub Pages project site: https://<user>.github.io/<repo>/
    private val appUrl = "https://wildcodedevelopers-svg.github.io/El-Ai/"

    private lateinit var web: WebView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null

    private fun askPermission(permission: String, code: Int) {
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(permission), code)
        }
    }

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
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                if (url.host == appHost && url.scheme == "https") {
                    return false
                }
                return openExternal(url.toString())
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val allowed = request.resources.filter {
                        it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                    }.toTypedArray()

                    if (allowed.isNotEmpty()) {
                        request.grant(allowed)
                    } else {
                        request.deny()
                    }
                }
            }
        }

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }

        askPermission(Manifest.permission.RECORD_AUDIO, 1)
        web.loadUrl(appUrl)
    }

    // Back button goes back inside El-AI instead of closing the app
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack()
        else super.onBackPressed()
    }

    private fun js(code: String) {
        runOnUiThread {
            if (::web.isInitialized) web.evaluateJavascript(code, null)
        }
    }

    private fun openExternal(url: String): Boolean {
        return try {
            val intent = if (url.startsWith("intent:")) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }

            startActivity(intent)
            true
        } catch (_: Exception) {
            true
        }
    }

    private fun pickVoice(
        engine: TextToSpeech,
        source: String
    ): Voice? {
        val language = Locale.getDefault().language
        val voices = engine.voices?.filter {
            it.locale.language == language &&
                it.features?.contains("notInstalled") != true
        } ?: return null

        return when (source) {
            "offline" -> voices.firstOrNull {
                !it.isNetworkConnectionRequired
            } ?: voices.firstOrNull()

            "online" -> voices.firstOrNull {
                it.isNetworkConnectionRequired
            } ?: voices.firstOrNull()

            else -> voices.firstOrNull()
        }
    }

    inner class Bridge {

        @JavascriptInterface
        fun hasTts(): Boolean = ttsReady

        @JavascriptInterface
        fun hasStt(): Boolean =
            SpeechRecognizer.isRecognitionAvailable(this@MainActivity)

        @JavascriptInterface
        fun speak(
            text: String,
            pitch: Float,
            rate: Float,
            volume: Float,
            source: String
        ) {
            runOnUiThread {
                val engine = tts ?: return@runOnUiThread
                engine.stop()

                pickVoice(engine, source)?.let {
                    engine.voice = it
                }

                engine.setPitch(pitch.coerceIn(0.5f, 2f))
                engine.setSpeechRate(rate.coerceIn(0.5f, 2f))

                engine.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}

                        override fun onDone(utteranceId: String?) {
                            js("ElCallbacks.onSpeakDone&&ElCallbacks.onSpeakDone()")
                        }

                        override fun onError(utteranceId: String?) {
                            js("ElCallbacks.onSpeakDone&&ElCallbacks.onSpeakDone()")
                        }

                        override fun onRangeStart(
                            utteranceId: String?,
                            start: Int,
                            end: Int,
                            frame: Int
                        ) {
                            js("ElCallbacks.onSpeakPulse&&ElCallbacks.onSpeakPulse()")
                        }
                    }
                )

                val params = Bundle().apply {
                    putFloat(
                        TextToSpeech.Engine.KEY_PARAM_VOLUME,
                        volume.coerceIn(0f, 1f)
                    )
                }

                engine.speak(
                    text.take(3900),
                    TextToSpeech.QUEUE_FLUSH,
                    params,
                    "el"
                )
            }
        }

        @JavascriptInterface
        fun stopSpeaking() {
            runOnUiThread { tts?.stop() }
        }

        @JavascriptInterface
        fun startListening(lang: String, offline: Boolean) {
            runOnUiThread {
                if (!SpeechRecognizer.isRecognitionAvailable(this@MainActivity)) {
                    js("ElCallbacks.onError&&ElCallbacks.onError(9)")
                    js("ElCallbacks.onListenEnd&&ElCallbacks.onListenEnd()")
                    return@runOnUiThread
                }

                recognizer?.destroy()

                val speech = SpeechRecognizer.createSpeechRecognizer(
                    this@MainActivity
                )
                recognizer = speech

                val endCallback =
                    "ElCallbacks.onListenEnd&&ElCallbacks.onListenEnd()"

                speech.setRecognitionListener(object : RecognitionListener {

                    private fun recognizedText(bundle: Bundle?): String? =
                        bundle?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )?.firstOrNull()

                    override fun onPartialResults(bundle: Bundle?) {
                        recognizedText(bundle)?.let {
                            js(
                                "ElCallbacks.onResult&&ElCallbacks.onResult(" +
                                    "${JSONObject.quote(it)},false)"
                            )
                        }
                    }

                    override fun onResults(bundle: Bundle?) {
                        recognizedText(bundle)?.let {
                            js(
                                "ElCallbacks.onResult&&ElCallbacks.onResult(" +
                                    "${JSONObject.quote(it)},true)"
                            )
                        }
                        js(endCallback)
                    }

                    override fun onError(error: Int) {
                        js("ElCallbacks.onError&&ElCallbacks.onError($error)")
                        js(endCallback)
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        js("ElCallbacks.onLevel&&ElCallbacks.onLevel($rmsdB)")
                    }

                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(
                    RecognizerIntent.ACTION_RECOGNIZE_SPEECH
                ).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
                }

                try {
                    speech.startListening(intent)
                } catch (_: Exception) {
                    js("ElCallbacks.onError&&ElCallbacks.onError(9)")
                    js(endCallback)
                }
            }
        }

        @JavascriptInterface
        fun stopListening() {
            runOnUiThread {
                recognizer?.cancel()
            }
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            runOnUiThread {
                openExternal(url)
            }
        }

        @JavascriptInterface
        fun openAppByName(name: String): Boolean {
            val query = name.lowercase().trim()
            if (query.length < 2) return false

            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val apps = pm.queryIntentActivities(intent, 0)

            val match = apps.firstOrNull {
                it.loadLabel(pm).toString().lowercase() == query
            } ?: if (query.length >= 3) {
                apps.firstOrNull {
                    it.loadLabel(pm).toString()
                        .lowercase().contains(query)
                }
            } else {
                null
            }

            val launchIntent = match?.let {
                pm.getLaunchIntentForPackage(it.activityInfo.packageName)
            } ?: return false

            runOnUiThread {
                startActivity(launchIntent)
            }

            return true
        }

        @JavascriptInterface
        fun findContacts(query: String): String {
            if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                runOnUiThread {
                    askPermission(Manifest.permission.READ_CONTACTS, 2)
                }
                return """{"error":"permission"}"""
            }

            val search = query.trim().lowercase()
            if (search.isEmpty()) return """{"matches":[]}"""

            val telephony = getSystemService(
                Context.TELEPHONY_SERVICE
            ) as? TelephonyManager

            val country = (
                telephony?.networkCountryIso
                    ?.takeIf { it.isNotBlank() }
                    ?: Locale.getDefault().country
                ).uppercase()

            val contacts = LinkedHashMap<String, Pair<String, Boolean>>()

            contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(
                    Phone.DISPLAY_NAME,
                    Phone.NUMBER,
                    Phone.TYPE
                ),
                "${Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$search%"),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    val number = cursor.getString(1) ?: continue
                    val mobile = cursor.getInt(2) == Phone.TYPE_MOBILE

                    val previous = contacts[name]
                    if (previous == null || (!previous.second && mobile)) {
                        contacts[name] = number to mobile
                    }
                }
            }

            val names = contacts.keys.sortedWith(
                compareBy<String>(
                    { it.lowercase() != search },
                    { !it.lowercase().startsWith(search) },
                    { it }
                )
            )

            val exactCount = names.count {
                it.lowercase() == search
            }

            val results = JSONArray()

            names.take(5).forEachIndexed { index, name ->
                val number = contacts[name]!!.first
                val e164 = PhoneNumberUtils.formatNumberToE164(
                    number,
                    country
                ) ?: number.filter {
                    it.isDigit() || it == '+'
                }

                results.put(
                    JSONObject()
                        .put("name", name)
                        .put("number", number)
                        .put("e164", e164)
                        .put(
                            "exact",
                            index == 0 && exactCount == 1
                        )
                )
            }

            return JSONObject()
                .put("matches", results)
                .toString()
        }
    }

    override fun onDestroy() {
        web.stopLoading()
        web.removeJavascriptInterface("ElNative")
        tts?.stop()
        tts?.shutdown()
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }
}
