package com.jumura.translate.core

import android.content.Context
import com.jumura.translate.BuildConfig

/**
 * Préférences persistantes : clé Groq, modèles, gain micro, langue source,
 * affichage du texte original, taille du texte. Tout est modifiable dans l'app.
 */
class Config(context: Context) {

    private val prefs = context.getSharedPreferences("jumura_prefs", Context.MODE_PRIVATE)

    /** Clé Groq (gratuite, console.groq.com). Injectée au build ou saisie dans les réglages. */
    var groqKey: String
        get() = (prefs.getString(KEY_GROQ, BuildConfig.GROQ_API_KEY) ?: BuildConfig.GROQ_API_KEY).trim()
        set(v) { prefs.edit().putString(KEY_GROQ, v.trim()).apply() }

    val hasKey: Boolean get() = groqKey.startsWith("gsk_")

    /** Modèle de transcription audio (Whisper). */
    var sttModel: String
        get() = prefs.getString(KEY_STT, DEFAULT_STT) ?: DEFAULT_STT
        set(v) { prefs.edit().putString(KEY_STT, v.trim()).apply() }

    /** Modèle de traduction (LLM). */
    var translateModel: String
        get() = prefs.getString(KEY_TR, DEFAULT_TR) ?: DEFAULT_TR
        set(v) { prefs.edit().putString(KEY_TR, v.trim()).apply() }

    /**
     * Indice de langue de l'imam : "" = auto, "ar" = arabe (littéraire ET darija),
     * "fr" = français. Forcer "ar" fiabilise la darija dans une salle bruyante.
     */
    var sourceLang: String
        get() = prefs.getString(KEY_LANG, "ar") ?: "ar"
        set(v) { prefs.edit().putString(KEY_LANG, v).apply() }

    /**
     * Sensibilité micro (1..5) → boost maximal de normalisation 10×..50× appliqué pour
     * remonter la voix lointaine de l'imam. Monté par défaut (imam éloigné).
     */
    var micGain: Float
        get() = prefs.getFloat(KEY_GAIN, 2.5f)
        set(v) { prefs.edit().putFloat(KEY_GAIN, v).apply() }

    /** Afficher le texte original (arabe/français) au-dessus de la traduction. */
    var showOriginal: Boolean
        get() = prefs.getBoolean(KEY_SHOW_ORIG, true)
        set(v) { prefs.edit().putBoolean(KEY_SHOW_ORIG, v).apply() }

    /** Taille du texte de traduction (sp). Grand par défaut : lecture facile en mosquée. */
    var textSize: Float
        get() = prefs.getFloat(KEY_TEXT_SIZE, 22f)
        set(v) { prefs.edit().putFloat(KEY_TEXT_SIZE, v).apply() }

    /** Garder l'écran allumé pendant l'écoute. */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_SCREEN, true)
        set(v) { prefs.edit().putBoolean(KEY_SCREEN, v).apply() }

    companion object {
        private const val KEY_GROQ = "groq_key"
        private const val KEY_STT = "stt_model"
        private const val KEY_TR = "translate_model"
        private const val KEY_LANG = "source_lang"
        private const val KEY_GAIN = "mic_gain"
        private const val KEY_SHOW_ORIG = "show_original"
        private const val KEY_TEXT_SIZE = "text_size"
        private const val KEY_SCREEN = "keep_screen_on"

        // Whisper large v3 : multilingue de référence (arabe littéraire + dialectal + français),
        // très rapide sur Groq. "turbo" existe mais v3 est plus fidèle sur l'arabe.
        const val DEFAULT_STT = "whisper-large-v3"

        // LLM de traduction (Groq, actuel au 08/2026). Remplaçant de llama-3.3, très bon en FR/AR.
        const val DEFAULT_TR = "openai/gpt-oss-120b"
    }
}
