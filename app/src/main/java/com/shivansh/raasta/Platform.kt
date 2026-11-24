package com.shivansh.raasta

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.shivansh.raasta.core.Fix
import com.shivansh.raasta.core.Geo
import com.shivansh.raasta.core.Place
import com.shivansh.raasta.core.PlaceKind
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object Config {
    /**
     * Delhi road tiles (built by `tools tiles`, see README) hosted on GitHub. Tried in order.
     * Repo layout: data/index.json + data/tiles/<row>_<col>.json.gz
     */
    val TILE_BASES = listOfNotNull(
        BuildConfig.TILE_BASE.ifEmpty { null },
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

/** Location straight from Android (no Google Play services needed). */
class Gps(context: Context) {
    private val app = context.applicationContext
    private val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val listeners = ArrayList<LocationListener>()

    val permitted get() = fine || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    val fine get() = granted(Manifest.permission.ACCESS_FINE_LOCATION)
    /** Location switched on in the phone's settings. */
    val switchedOn get() = LocationManagerCompat.isLocationEnabled(lm)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED

    /** For riding: precise GPS only, every second. */
    fun start(onFix: (Fix) -> Unit) { stop(); listen(LocationManager.GPS_PROVIDER, 1000L, onFix) }

    /**
     * For "where am I": every source the phone has. Network gives a rough spot at once, even indoors; GPS sharpens
     * it. A worse reading never replaces a better one that is still fresh.
     */
    fun startLive(onFix: (Fix) -> Unit) {
        stop()
        if (!permitted) return
        var best: Fix? = null
        var bestAt = 0L
        val offer = { f: Fix ->
            val now = System.currentTimeMillis()
            val b = best
            if (b == null || f.accuracy <= b.accuracy * 1.4 || now - bestAt > 9_000) { best = f; bestAt = now; onFix(f) }
        }
        lastKnown(maxAgeMs = 2 * 60_000L)?.let { best = it; bestAt = it.time; onFix(it) }
        val have = lm.allProviders
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")) if (p in have) listen(p, 1500L, offer)
    }

    @SuppressLint("MissingPermission")
    private fun listen(provider: String, ms: Long, onFix: (Fix) -> Unit) {
        // Not a lambda: Android 8–10 call the other methods, which a lambda doesn't have (AbstractMethodError).
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) = onFix(location.toFix())
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        try {
            lm.requestLocationUpdates(provider, ms, 0f, l, Looper.getMainLooper())
            listeners.add(l)
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    fun stop() {
        listeners.forEach { lm.removeUpdates(it) }
        listeners.clear()
    }

    @SuppressLint("MissingPermission")
    fun lastKnown(maxAgeMs: Long): Fix? = lm.allProviders
        .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
        .filter { System.currentTimeMillis() - it.time < maxAgeMs }
        .minByOrNull { if (it.hasAccuracy()) it.accuracy else 1000f }?.toFix()

    private fun Location.toFix() = Fix(
        lat = latitude, lon = longitude,
        accuracy = if (hasAccuracy()) accuracy.toDouble() else 15.0,
        speed = if (hasSpeed()) speed.toDouble() else -1.0,
        bearing = if (hasBearing()) bearing.toDouble() else -1.0,
        time = time,
    )
}

/** Home, Work and recent places, kept on the phone only. */
class SavedPlaces(context: Context) {
    private val sp = context.getSharedPreferences("places", Context.MODE_PRIVATE)

    fun get(key: String): Place? = sp.getString(key, null)?.let { runCatching { read(JSONObject(it)) }.getOrNull() }
    fun set(key: String, p: Place?) = sp.edit().apply { if (p == null) remove(key) else putString(key, write(p).toString()) }.apply()

    fun recents(): List<Place> = runCatching {
        val a = JSONArray(sp.getString("recents", "[]"))
        List(a.length()) { read(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun addRecent(p: Place) {
        val list = (listOf(p) + recents().filter { it.name != p.name || Geo.dist(it.latLon, p.latLon) > 100 }).take(12)
        sp.edit().putString("recents", JSONArray(list.map { write(it) }).toString()).apply()
    }

    /** Places the rider added themself. */
    fun mine(): List<Place> = runCatching {
        val a = JSONArray(sp.getString("mine", "[]"))
        List(a.length()) { read(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun setMine(l: List<Place>) = sp.edit().putString("mine", JSONArray(l.map { write(it) }).toString()).apply()

    private fun write(p: Place) = JSONObject().put("n", p.name).put("d", p.detail).put("a", p.lat).put("o", p.lon).put("k", p.kind.name).put("l", p.label)
    private fun read(o: JSONObject) = Place(o.getString("n"), o.optString("d"), o.getDouble("a"), o.getDouble("o"),
        PlaceKind.entries.firstOrNull { it.name == o.optString("k") } ?: PlaceKind.OTHER, o.optString("l"))
}
