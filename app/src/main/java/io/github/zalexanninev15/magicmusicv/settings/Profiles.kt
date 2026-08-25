package io.github.zalexanninev15.magicmusicv.settings

import android.content.Context
import io.github.zalexanninev15.magicmusicv.AppTheme
import io.github.zalexanninev15.magicmusicv.Mode
import io.github.zalexanninev15.magicmusicv.audio.SourceKind
import io.github.zalexanninev15.magicmusicv.haptics.BackendChoice
import io.github.zalexanninev15.magicmusicv.haptics.MagicFeedback
import org.json.JSONObject

/** Every persisted tunable, as one immutable value. */
data class SettingsSnapshot(
    val mode: Mode,
    val source: SourceKind,
    val intensity: Float,
    val sensitivity: Float,
    val offsetMs: Int,
    val bandLow: Boolean,
    val bandMid: Boolean,
    val bandHigh: Boolean,
    val effectLow: Int,
    val effectMid: Int,
    val effectHigh: Int,
    val bypassSystemScaling: Boolean,
    val backendChoice: BackendChoice,
    val theme: AppTheme,
    /** MagicFeedback preset id, or "" for off. */
    val magicPreset: String,
)

/**
 * JSON in and out.
 *
 * Enums are written by name, not ordinal: an exported profile should survive someone
 * inserting a new mode in the middle of the enum in a later release, and a name that no
 * longer exists degrades to the default instead of silently selecting the wrong thing.
 *
 * `org.json` rather than a serialization library — it is in the platform, the schema is
 * fifteen scalars, and a dependency here would be pure overhead.
 */
object SettingsCodec {

    private const val FORMAT = "magic-music-v/settings"
    private const val VERSION = 1

    fun encode(s: SettingsSnapshot): String = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("mode", s.mode.name)
        put("source", s.source.name)
        put("intensity", s.intensity.toDouble())
        put("sensitivity", s.sensitivity.toDouble())
        put("offsetMs", s.offsetMs)
        put("bandLow", s.bandLow)
        put("bandMid", s.bandMid)
        put("bandHigh", s.bandHigh)
        put("effectLow", s.effectLow)
        put("effectMid", s.effectMid)
        put("effectHigh", s.effectHigh)
        put("bypassSystemScaling", s.bypassSystemScaling)
        put("backendChoice", s.backendChoice.name)
        put("theme", s.theme.name)
        put("magicPreset", s.magicPreset)
    }.toString(2)

    /** Returns null when the text is not one of our files. */
    fun decode(text: String, defaults: SettingsSnapshot): SettingsSnapshot? {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (o.optString("format") != FORMAT) return null
        return SettingsSnapshot(
            mode = enumOr(o.optString("mode"), Mode.entries, defaults.mode),
            source = enumOr(o.optString("source"), SourceKind.entries, defaults.source),
            intensity = o.optDouble("intensity", defaults.intensity.toDouble()).toFloat()
                .coerceIn(0.2f, 1f),
            sensitivity = o.optDouble("sensitivity", defaults.sensitivity.toDouble()).toFloat()
                .coerceIn(1.05f, 3f),
            offsetMs = o.optInt("offsetMs", defaults.offsetMs).coerceIn(-60, 60),
            bandLow = o.optBoolean("bandLow", defaults.bandLow),
            bandMid = o.optBoolean("bandMid", defaults.bandMid),
            bandHigh = o.optBoolean("bandHigh", defaults.bandHigh),
            effectLow = o.optInt("effectLow", defaults.effectLow),
            effectMid = o.optInt("effectMid", defaults.effectMid),
            effectHigh = o.optInt("effectHigh", defaults.effectHigh),
            bypassSystemScaling = o.optBoolean(
                "bypassSystemScaling", defaults.bypassSystemScaling
            ),
            backendChoice = enumOr(
                o.optString("backendChoice"), BackendChoice.entries, defaults.backendChoice
            ),
            theme = enumOr(o.optString("theme"), AppTheme.entries, defaults.theme),
            // Unknown preset ids degrade to off rather than to an arbitrary preset.
            magicPreset = o.optString("magicPreset", defaults.magicPreset)
                .takeIf { it.isEmpty() || MagicFeedback.byId(it) != null } ?: "",
        )
    }

    private fun <T : Enum<T>> enumOr(raw: String?, values: List<T>, fallback: T): T =
        values.firstOrNull { it.name == raw } ?: fallback
}

/**
 * Named profiles, stored as JSON blobs in their own SharedPreferences file.
 *
 * Separate from the live settings file on purpose: wiping settings with "reset to
 * defaults" must not take the user's saved profiles with it.
 */
object ProfileStore {

    private const val PREFS = "magicmusicv_profiles"

    fun list(context: Context): List<String> =
        prefs(context).all.keys.sortedBy { it.lowercase() }

    fun save(context: Context, name: String, snapshot: SettingsSnapshot): Boolean {
        val clean = name.trim()
        if (clean.isEmpty()) return false
        prefs(context).edit().putString(clean, SettingsCodec.encode(snapshot)).apply()
        return true
    }

    fun load(context: Context, name: String, defaults: SettingsSnapshot): SettingsSnapshot? {
        val raw = prefs(context).getString(name, null) ?: return null
        return SettingsCodec.decode(raw, defaults)
    }

    fun delete(context: Context, name: String) {
        prefs(context).edit().remove(name).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
