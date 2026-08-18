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
 * demande `verbose_json` pour récupérer la langue détectée (utile pour dire au traducteur
 * si l'imam parlait arabe, darija ou français).
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

        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", config.sttModel)
            .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("temperature", "0")
            // Guide Whisper vers un contexte de prêche (améliore la ponctuation/segmentation).
            .addFormDataPart("prompt", "Prêche du vendredi (khoutba) à la mosquée.")

        val lang = config.sourceLang
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
                val text = json.optString("text").trim()
                val detected = json.optString("language").trim()
                Transcript(true, text, detected)
            }
        } catch (e: Exception) {
            Transcript(false, "Réseau indisponible pour la transcription.", "")
        }
    }

    private fun extractError(raw: String): String? = try {
        JSONObject(raw).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }
}
