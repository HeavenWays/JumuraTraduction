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
 * Traduction INTELLIGENTE vers le français via Groq (LLM).
 * Objectif : rendre le SENS réel de ce que dit l'imam (arabe littéraire, darija maghrébine
 * ou français), jamais du mot-à-mot. On fournit au modèle un petit contexte des dernières
 * phrases déjà traduites pour garder la cohérence du discours.
 */
class Translator(
    private val config: Config,
    private val client: OkHttpClient
) {
    private val endpoint = "https://api.groq.com/openai/v1/chat/completions"
    private val json = "application/json".toMediaType()

    private val system = """
        Tu es un INTERPRÈTE professionnel de la khoutba (prêche du vendredi), de l'arabe vers le français.
        On te donne, morceau par morceau, une transcription AUTOMATIQUE et IMPARFAITE de la voix d'un imam,
        captée de loin dans une mosquée qui résonne. L'imam parle en arabe littéraire (fusha), en dialecte
        maghrébin (darija) ou en français, parfois mêlés. La transcription contient souvent des mots mal
        entendus, des fautes phonétiques et des coupures : c'est normal, c'est ton travail de les redresser.

        Ta mission : restituer en français clair et fidèle le SENS réel de ce que dit l'imam.

        Règles :
        - REDRESSE l'entrée : reconstitue la phrase la plus plausible à partir de la transcription, même
          fautive. Un mot qui « sonne » comme un terme religieux ou arabe courant du prêche doit être rétabli
          d'après le contexte (ex. une transliteration approximative → le vrai mot). Sers-toi du contexte fourni.
        - FIDÉLITÉ AU SENS : rends ce que l'imam veut dire, sans prêcher à sa place, sans ajouter d'idées,
          sans rallonger ni commenter. Reste au plus près de son propos.
        - N'INVENTE PAS de contenu religieux. Si un fragment est vraiment incompréhensible ou n'est que du
          bruit, réponds UNIQUEMENT par un tiret : -   (ne fabrique jamais une phrase à partir de rien).
        - Garde les termes consacrés (Allah, le Prophète ﷺ, salât, zakât, taqwa, dounia, âkhira, hadith,
          soubhânahou wa ta'âlâ…). Traduis versets et hadiths de façon sobre et juste, sans les paraphraser.
        - Français NATUREL et lisible, destiné à être lu en direct par des fidèles francophones. Sois concis :
          une à trois phrases par fragment.
        - Réponds UNIQUEMENT par la traduction française : aucun préambule, aucun guillemet, aucune note.
    """.trimIndent()

    suspend fun translate(original: String, detectedLang: String, context: List<String>): Translation =
        withContext(Dispatchers.IO) {
            val key = config.groqKey
            if (!key.startsWith("gsk_")) return@withContext Translation(false, "Clé Groq manquante")
            if (original.isBlank()) return@withContext Translation(true, "-")

            val messages = JSONArray()
            messages.put(msg("system", system))

            if (context.isNotEmpty()) {
                val recap = context.takeLast(3).joinToString(" ")
                messages.put(
                    msg(
                        "system",
                        "Contexte déjà traduit (pour la continuité, ne le retraduis pas) : $recap"
                    )
                )
            }

            val langLabel = when (detectedLang.lowercase()) {
                "ar", "arabic" -> "arabe (littéraire ou darija)"
                "fr", "french" -> "français"
                "" -> "langue à déterminer"
                else -> detectedLang
            }
            messages.put(
                msg(
                    "user",
                    "Transcription automatique du fragment ($langLabel), possiblement fautive :\n\"$original\"\n\nRedresse-la et rends en français clair et fidèle ce que l'imam veut dire :"
                )
            )

            val body = JSONObject()
                .put("model", config.translateModel)
                .put("messages", messages)
                .put("temperature", 0.2)
                .put("max_tokens", 400)
                .toString()

            val req = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $key")
                .post(body.toRequestBody(json))
                .build()

            try {
                client.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val msg = extractError(raw) ?: "Erreur traduction (code ${resp.code})"
                        return@withContext Translation(false, msg)
                    }
                    val content = JSONObject(raw)
                        .optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("message")?.optString("content").orEmpty().trim()
                    Translation(true, content.ifBlank { "-" })
                }
            } catch (e: Exception) {
                Translation(false, "Réseau indisponible pour la traduction.")
            }
        }

    private fun msg(role: String, content: String): JSONObject =
        JSONObject().put("role", role).put("content", content)

    private fun extractError(raw: String): String? = try {
        JSONObject(raw).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }
}
