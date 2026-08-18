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
        Tu es un interprète professionnel spécialisé dans les prêches du vendredi (la khoutba) en Islam.
        On te transmet, morceau par morceau, la parole d'un imam captée au micro. L'imam peut parler en
        arabe littéraire (fusha), en arabe dialectal maghrébin (darija), ou en français — parfois mélangés.

        Ta mission : produire une traduction FRANÇAISE claire, fluide et FIDÈLE AU SENS. JAMAIS de mot-à-mot.
        Fais en sorte qu'un francophone comprenne RÉELLEMENT le message, l'intention et la portée spirituelle.

        Règles :
        - Rends l'idée, pas les mots. Reformule naturellement si le littéral serait bancal ou incompréhensible.
        - Conserve les termes religieux consacrés (Allah, le Prophète ﷺ, salat, zakat, taqwa, dounia, âkhira…)
          et, seulement si c'est utile à la compréhension, ajoute UN mot d'explication entre parenthèses.
        - Traduis versets du Coran et hadiths de façon compréhensible et respectueuse, sans inventer de contenu.
        - Style sobre, direct, digne d'un sermon. Pas de commentaire, pas de préambule, pas de guillemets.
        - Si le fragment est inaudible, vide ou n'est que du bruit, réponds uniquement par un tiret : -
        - Ne réponds QUE par la traduction française, rien d'autre.
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
                    "Fragment capté ($langLabel) :\n\"$original\"\n\nDonne uniquement la traduction française fidèle au sens :"
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
