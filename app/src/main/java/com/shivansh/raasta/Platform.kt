package com.shivansh.raasta

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.shivansh.raasta.core.Fix
import java.util.Locale

object Config {
    /**
     * Delhi road tiles (built by `tools tiles`, see README) hosted on GitHub. Tried in order.
     * Repo layout: data/index.json + data/tiles/<row>_<col>.json.gz
     */
    val TILE_BASES = listOf(
        "https://raw.githubusercontent.com/Shivanshxsharma/vays-maps/main/data",
        "https://cdn.jsdelivr.net/gh/Shivanshxsharma/vays-maps@main/data",
    )
}

/** Offline text-to-speech with ducking, Indian English voice when the phone has one. */
class Speaker(context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs).build()
    private var ready = false
    private val pending = ArrayList<Pair<String, Boolean>>()
    private var id = 0
    var muted = false

    /** False when the chosen voice needs internet (the user should install an offline voice). */
    var offlineVoice = true
        private set

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            setup()
            ready = true
            pending.forEach { (t, u) -> say(t, u) }
            pending.clear()
        }
    }

    private fun setup() {
        tts.setAudioAttributes(attrs)
        val india = Locale.forLanguageTag("en-IN")
        val r = tts.setLanguage(india)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.UK)
        // Prefer an installed (offline) voice for the language.
        val lang = tts.voice?.locale?.language ?: "en"
        val offline = tts.voices?.filter {
            it.locale.language == lang && !it.isNetworkConnectionRequired &&
                !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }.orEmpty()
        val pick = offline.firstOrNull { it.locale.country == "IN" } ?: offline.firstOrNull()
        if (pick != null) tts.voice = pick
        offlineVoice = tts.voice?.isNetworkConnectionRequired != true
        tts.setSpeechRate(1.0f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (!tts.isSpeaking) audio.abandonAudioFocusRequest(focus) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { audio.abandonAudioFocusRequest(focus) }
        })
    }

    fun say(text: String, urgent: Boolean) {
        if (muted) return
        if (!ready) { pending.add(text to urgent); return }
        audio.requestAudioFocus(focus)
        tts.speak(text, if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), "u${id++}")
    }

    fun stop() { tts.stop(); audio.abandonAudioFocusRequest(focus) }
    fun shutdown() { tts.stop(); tts.shutdown() }
}

/** Raw GPS (no Google Play services needed). */
class Gps(context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var listener: LocationListener? = null

    @SuppressLint("MissingPermission")
    fun start(onFix: (Fix) -> Unit) {
        stop()
        val l = LocationListener { loc -> onFix(loc.toFix()) }
        listener = l
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, Looper.getMainLooper())
    }

    fun stop() {
        listener?.let { lm.removeUpdates(it) }
        listener = null
    }

    @SuppressLint("MissingPermission")
    fun lastKnown(): Fix? = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
        .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
        .maxByOrNull { it.time }?.toFix()

    private fun Location.toFix() = Fix(
        lat = latitude, lon = longitude,
        accuracy = if (hasAccuracy()) accuracy.toDouble() else 15.0,
        speed = if (hasSpeed()) speed.toDouble() else -1.0,
        bearing = if (hasBearing()) bearing.toDouble() else -1.0,
        time = time,
    )
}
