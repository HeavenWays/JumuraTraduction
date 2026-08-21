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
            // PAS d'amorce/prompt : Whisper recrache le texte du prompt sur un son peu net
            // (c'était la cause du « Craignez Allah, serviteurs d'Allah » jamais prononcé).

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

    /** Détecte les hallucinations de Whisper : fillers sur silence, crédits, répétitions en boucle. */
    private fun looksHallucinated(text: String): Boolean {
        val norm = normalize(text)
        if (norm.isBlank()) return true
        val words = norm.split(" ").filter { it.isNotBlank() }

        // 1) Filler halluciné sur du silence : segment court réduit à « merci / شكرا / thank you »…
        if (words.size <= 4 && norm in FILLER_EXACT) return true
        if (words.size in 1..6 && words.all { it in FILLER_EXACT }) return true
        // 2) Crédits de sous-titres (phrases spécifiques → filtrables même en sous-chaîne).
        for (p in CREDITS) if (norm.contains(p)) return true
        // 3) Boucle : peu de mots distincts sur un texte assez long (« المال المال المال … »).
        if (words.size >= 6 && words.distinct().size.toDouble() / words.size < 0.35) return true
        // 4) Une même courte séquence répétée plusieurs fois.
        val phrase = words.take(3).joinToString(" ")
        if (phrase.length >= 4 && Regex(Regex.escape(phrase)).findAll(norm).count() >= 4) return true
        return false
    }

    /** minuscule, sans ponctuation ni diacritiques arabes, espaces normalisés. */
    private fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[\\u064B-\\u065F\\u0670\\u0640]"), "")   // diacritiques arabes + tatweel
            .replace(Regex("[^\\p{L}\\p{Nd} ]"), " ")                // ponctuation/symboles → espace
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun extractError(raw: String): String? = try {
        JSONObject(raw).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    companion object {
        // Fillers que Whisper invente sur du silence/bruit (segment court réduit à ça → jeté).
        private val FILLER_EXACT = setOf(
            "شكرا", "شكرا لكم", "شكرا جزيلا", "المشاهدة", "ترجمة", "اشتركوا",
            "merci", "merci beaucoup", "thank you", "thanks", "you", "bye", "sous titres"
        )
        // Crédits de sous-titres (spécifiques → filtrables même en sous-chaîne, quelle que soit la taille).
        private val CREDITS = listOf(
            "sous titres", "abonnez vous", "merci d avoir regarde", "merci de votre attention",
            "thanks for watching", "please subscribe", "amara org",
            "اشتركوا في القناة", "شكرا للمشاهدة", "ترجمة نانسي"
        )
    }
}
