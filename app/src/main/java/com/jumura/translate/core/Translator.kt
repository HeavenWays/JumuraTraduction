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
 * Frugalité en tokens (quota gratuit = 200 000 tokens/jour PAR MODÈLE) : prompt système COMPACT,
 * contexte réduit, sortie plafonnée.
 *
 * Robustesse : si un modèle est à court de quota (rate limit) ou indisponible, on ESSAIE les suivants
 * d'une chaîne de repli (chacun a son propre quota journalier) → la traduction ne s'arrête jamais.
 */
class Translator(
    private val config: Config,
    private val client: OkHttpClient
) {
    private val endpoint = "https://api.groq.com/openai/v1/chat/completions"
    private val json = "application/json".toMediaType()

    // Prompt système COURT (renvoyé à chaque phrase → coûte des tokens à chaque appel).
    private val system = """
        Tu es un interprète de la khoutba (prêche du vendredi), de l'arabe vers le français.
        On te donne une transcription automatique IMPARFAITE de la voix de l'imam (arabe littéraire,
        darija maghrébine ou français, souvent mal transcrits). Redresse-la et rends en français clair
        et FIDÈLE le sens réel de ce qu'il dit : ne rallonge pas, n'ajoute pas d'idées, ne commente pas.
        Garde les termes religieux (Allah, le Prophète ﷺ, salât, zakât, taqwa, dounia, âkhira, hadith…).
        Donne TOUJOURS ta meilleure traduction, même si la transcription est approximative.
        Ne réponds « - » QUE si le fragment est réellement vide ou n'est que du bruit sans aucun mot.
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

            // Modèle choisi puis chaîne de repli (quotas journaliers séparés).
            val models = buildList {
                add(config.translateModel)
                for (m in FALLBACK_CHAIN) if (none { it.equals(m, ignoreCase = true) }) add(m)
            }
            var last: Result? = null
            for (model in models) {
                val r = call(model, messages, key)
                if (r.translation.ok) return@withContext r.translation
                last = r
                if (!r.retryable) break        // erreur non liée au quota/modèle → inutile d'insister
            }
            last?.translation ?: Translation(false, "Traduction indisponible")
        }

    private data class Result(val translation: Translation, val retryable: Boolean)

    private fun call(model: String, messages: JSONArray, key: String): Result {
        // Les modèles gpt-oss RAISONNENT avant de répondre, et ces tokens de réflexion sont
        // décomptés du budget de sortie. Avec un budget trop bas, tout part dans le raisonnement
        // et `content` revient VIDE (finish_reason = "length") → la traduction n'arrivait jamais.
        // Donc : effort de raisonnement minimal + budget large pour ces modèles.
        val isReasoning = model.contains("gpt-oss", ignoreCase = true)

        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.2)
            .put("max_tokens", if (isReasoning) 1024 else 300)
        if (isReasoning) payload.put("reasoning_effort", "low")

        val req = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $key")
            .post(payload.toString().toRequestBody(json))
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = extractError(raw) ?: "Erreur traduction (code ${resp.code})"
                    // Quota atteint (429), modèle invalide/retiré (400/404) ou service indispo (503)
                    // → on tente le modèle suivant de la chaîne.
                    val retry = resp.code in intArrayOf(429, 400, 404, 503) ||
                        raw.contains("rate_limit", true)
                    return Result(Translation(false, msg), retry)
                }

                val choice = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)
                val finish = choice?.optString("finish_reason").orEmpty()
                val content = clean(choice?.optJSONObject("message")?.optString("content").orEmpty())

                // Réponse vide malgré un HTTP 200 : ne JAMAIS la faire passer pour une traduction.
                // On bascule sur le modèle suivant (le dernier de la chaîne ne raisonne pas).
                if (content.isBlank()) {
                    val why = if (finish == "length")
                        "Réponse coupée par le modèle (budget épuisé)."
                    else
                        "Le modèle a renvoyé une réponse vide."
                    return Result(Translation(false, why), true)
                }

                Result(Translation(true, content), false)
            }
        } catch (e: Exception) {
            Result(Translation(false, "Réseau indisponible pour la traduction."), false)
        }
    }

    /** Retire un éventuel bloc de raisonnement ou les jetons de contrôle du modèle. */
    private fun clean(text: String): String =
        text.replace(Regex("(?s)<think>.*?</think>"), " ")
            .replace(Regex("<\\|[a-z_]+\\|>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun msg(role: String, content: String): JSONObject =
        JSONObject().put("role", role).put("content", content)

    private fun extractError(raw: String): String? = try {
        JSONObject(raw).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    companion object {
        // Repli quand le modèle principal a épuisé son quota du jour ou est indisponible.
        // Ordre = qualité décroissante ; le dernier (8b) a un très grand quota gratuit → filet de sécurité.
        val FALLBACK_CHAIN = listOf("openai/gpt-oss-20b", "llama-3.1-8b-instant")
    }
}
