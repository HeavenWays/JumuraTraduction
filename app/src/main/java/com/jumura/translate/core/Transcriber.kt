package com.jumura.translate.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Texte transcrit + langue détectée par Whisper. `ok=false` → message d'erreur dans `text`. */
data class Transcript(val ok: Boolean, val text: String, val lang: String)

/**
 * Transcription audio via l'API Groq (Whisper large v3). Envoie le WAV en multipart,
 * demande `verbose_json` pour récupérer la langue ET la confiance par segment.
 *
 * Deux garde-fous ajoutés (retour « ça hallucine / charabia ») :
 *  - AMORCE arabe : oriente Whisper vers le vocabulaire réel d'une khoutba (meilleure darija/termes).
 *  - FILTRE anti-hallucination : segments peu sûrs (silence) ou textes inventés/en boucle → ignorés.
 */
class Transcriber(
    private val config: Config,
    private val client: OkHttpClient
) {
    private val endpoint = "https://api.groq.com/openai/v1/audio/transcriptions"

    // Amorce (prompt Whisper) en arabe : cadre le modèle sur une khoutba. Volontairement COURTE :
    // une amorce trop longue pousserait Whisper à recracher ces mots sur du silence.
    private val arabicPrimer =
        "خطبة الجمعة في المسجد. الحمد لله والصلاة والسلام على رسول الله. اتقوا الله عباد الله."

    suspend fun transcribe(wav: ByteArray): Transcript = withContext(Dispatchers.IO) {
        val key = config.groqKey
        if (!key.startsWith("gsk_")) {
            return@withContext Transcript(false, "Clé Groq manquante", "")
        }

        // Défaut = arabe (fiabilise fusha ET darija dans une salle bruyante). "fr" ou "" possibles.
        val lang = config.sourceLang.trim()

        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", config.sttModel)
            .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("temperature", "0")
            .addFormDataPart("prompt", if (lang == "fr") "Prêche du vendredi à la mosquée." else arabicPrimer)

        if (lang.isNotBlank()) builder.addFormDataPart("language", lang)

        val req = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $key")
            .post(builder.build())
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = extractError(raw) ?: "Erreur transcription (code ${resp.code})"
                    return@withContext Transcript(false, msg, "")
                }
                val json = JSONObject(raw)
                var text = json.optString("text").trim()
                val detected = json.optString("language").trim()

                // 1) Confiance faible sur tout le segment (Whisper « n'entend pas de parole ») → silence.
                if (lowConfidence(json)) return@withContext Transcript(true, "", detected)

                // 2) Texte manifestement halluciné (crédits sous-titres / boucle de répétitions) → jeté.
                if (text.isNotBlank() && looksHallucinated(text)) text = ""

                Transcript(true, text, detected)
            }
        } catch (e: Exception) {
            Transcript(false, "Réseau indisponible pour la transcription.", "")
        }
    }

    /** Vrai si les segments Whisper indiquent surtout du silence / une transcription peu fiable. */
    private fun lowConfidence(json: JSONObject): Boolean {
        val segs = json.optJSONArray("segments") ?: return false
        if (segs.length() == 0) return false
        var noSpeech = 0.0
        var logprob = 0.0
        var n = 0
        for (i in 0 until segs.length()) {
            val s = segs.optJSONObject(i) ?: continue
            noSpeech += s.optDouble("no_speech_prob", 0.0)
            logprob += s.optDouble("avg_logprob", 0.0)
            n++
        }
        if (n == 0) return false
        val avgNoSpeech = noSpeech / n
        val avgLogprob = logprob / n
        // Beaucoup de « pas de parole » OU log-prob très bas = bruit/silence/charabia.
        return avgNoSpeech > 0.8 || avgLogprob < -1.2
    }

    /** Détecte les hallucinations classiques de Whisper (crédits, phrases inventées en boucle). */
    private fun looksHallucinated(text: String): Boolean {
        val t = text.trim()
        val low = t.lowercase()
        for (p in HALLUCINATIONS) if (low.contains(p)) return true

        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        // Boucle : peu de mots distincts sur un texte assez long (ex. « المال المال المال … »).
        if (words.size >= 6 && words.distinct().size.toDouble() / words.size < 0.35) return true
        // Une même courte séquence répétée plusieurs fois.
        val phrase = words.take(3).joinToString(" ")
        if (phrase.length >= 4) {
            val occ = Regex(Regex.escape(phrase.lowercase())).findAll(low).count()
            if (occ >= 4) return true
        }
        return false
    }

    private fun extractError(raw: String): String? = try {
        JSONObject(raw).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    companion object {
        // Phrases typiquement hallucinées par Whisper sur du silence/bruit (sous-titres, crédits…).
        private val HALLUCINATIONS = listOf(
            "sous-titres", "sous titres", "amara.org", "merci d'avoir regardé",
            "merci de votre attention", "abonnez-vous", "thank you for watching",
            "please subscribe", "ترجمة نانسي قنقر", "اشتركوا في القناة", "شكرا للمشاهدة"
        )
    }
}
