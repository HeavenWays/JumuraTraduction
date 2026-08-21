package com.jumura.translate.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Résultat de traduction. `ok=false` → `text` contient le message d'erreur. */
data class Translation(val ok: Boolean, val text: String)

/**
 * Traduction vers le français via Groq (LLM), en mode INTERPRÈTE : on redresse la transcription
 * automatique (souvent fautive) et on rend le SENS réel de ce que dit l'imam.
 *
 * Frugalité en tokens (quota gratuit = 200 000 tokens/jour) : prompt système COMPACT, contexte
 * réduit, sortie plafonnée. Un khoutba entier tient ainsi largement dans le budget du jour.
 *
 * Robustesse : si le modèle choisi est à court de quota (rate limit), on RÉESSAIE automatiquement
 * avec un modèle de repli (même famille, quota journalier séparé) pour ne pas bloquer la traduction.
 */
class Translator(
    private val config: Config,
    private val client: OkHttpClient
) {
    private val endpoint = "https://api.groq.com/openai/v1/chat/completions"
    private val json = "application/json".toMediaType()

    // Prompt système volontairement COURT (renvoyé à chaque phrase → coûte des tokens à chaque appel).
    private val system = """
        Tu es un interprète de la khoutba (prêche du vendredi), de l'arabe vers le français.
        On te donne une transcription automatique IMPARFAITE de la voix de l'imam (arabe littéraire,
        darija maghrébine ou français, souvent mal transcrits). Redresse-la et rends en français clair
        et FIDÈLE le sens réel de ce qu'il dit : ne rallonge pas, n'ajoute pas d'idées, ne commente pas.
        Garde les termes religieux (Allah, le Prophète ﷺ, salât, zakât, taqwa, dounia, âkhira, hadith…).
        Si le fragment est incompréhensible ou n'est que du bruit, réponds seulement : -
        Réponds UNIQUEMENT par la traduction française, rien d'autre.
    """.trimIndent()

    suspend fun translate(original: String, detectedLang: String, context: List<String>): Translation =
        withContext(Dispatchers.IO) {
            val key = config.groqKey
            if (!key.startsWith("gsk_")) return@withContext Translation(false, "Clé Groq manquante")
            if (original.isBlank()) return@withContext Translation(true, "-")

            val messages = JSONArray()
            messages.put(msg("system", system))

            // Contexte réduit : 2 dernières phrases, tronquées (continuité sans exploser les tokens).
            if (context.isNotEmpty()) {
                val recap = context.takeLast(2).joinToString(" ") { it.take(140) }
                if (recap.isNotBlank()) {
                    messages.put(msg("system", "Contexte précédent (ne pas retraduire) : $recap"))
                }
            }

            val langLabel = when (detectedLang.lowercase()) {
                "ar", "arabic" -> "arabe/darija"
                "fr", "french" -> "français"
                else -> "à déterminer"
            }
            messages.put(
                msg("user", "Transcription ($langLabel), possiblement fautive :\n\"$original\"\n\nRends le sens en français :")
            )

            // Modèle choisi ; repli automatique si le quota du jour est atteint.
            val primary = config.translateModel
            var res = call(primary, messages, key)
            if (res.rateLimited && !primary.equals(FALLBACK_MODEL, ignoreCase = true)) {
                res = call(FALLBACK_MODEL, messages, key)
            }
            res.translation
        }

    private data class Result(val translation: Translation, val rateLimited: Boolean)

    private fun call(model: String, messages: JSONArray, key: String): Result {
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.2)
            .put("max_tokens", 256)
            .toString()

        val req = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $key")
            .post(body.toRequestBody(json))
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = extractError(raw) ?: "Erreur traduction (code ${resp.code})"
                    val rl = resp.code == 429 || raw.contains("rate_limit", true) ||
                        msg.contains("rate limit", true)
                    return Result(Translation(false, msg), rl)
                }
                val content = JSONObject(raw)
                    .optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content").orEmpty().trim()
                Result(Translation(true, content.ifBlank { "-" }), false)
            }
        } catch (e: Exception) {
            Result(Translation(false, "Réseau indisponible pour la traduction."), false)
        }
    }

    private fun msg(role: String, content: String): JSONObject =
        JSONObject().put("role", role).put("content", content)

    private fun extractError(raw: String): String? = try {
        JSONObject(raw).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    companion object {
        // Repli quand le modèle principal a épuisé son quota du jour : même famille (gpt-oss),
        // quota journalier séparé et plus large → la traduction continue de fonctionner.
        const val FALLBACK_MODEL = "openai/gpt-oss-20b"
    }
}
