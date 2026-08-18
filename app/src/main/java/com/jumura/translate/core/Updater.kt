package com.jumura.translate.core

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.jumura.translate.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Résultat d'une vérification de mise à jour. */
data class UpdateInfo(
    val available: Boolean,
    val latestCode: Int,
    val latestName: String,
    val apkUrl: String,
    val notes: String,
    val message: String
)

/**
 * Mise à jour intégrée : compare la version installée à la dernière "Release" GitHub,
 * télécharge le nouvel APK et lance l'installation (par-dessus, grâce à la clé de
 * signature stable). Aucune clé/token requis : les Release d'un dépôt public sont
 * téléchargeables directement.
 */
object Updater {

    // Dépôt GitHub qui héberge les Releases (APK publics). À adapter si tu renommes le dépôt.
    private const val REPO = "HeavenWays/JumuraTraduction"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Vérifie s'il existe une version plus récente que celle installée. */
    suspend fun check(): UpdateInfo = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Jumura")
                .build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val friendly = if (resp.code == 404)
                        "Aucune version publiée pour l'instant."
                    else
                        "Impossible de vérifier les mises à jour (code ${resp.code})."
                    return@withContext UpdateInfo(false, 0, "", "", "", friendly)
                }
                val json = JSONObject(raw)
                val tag = json.optString("tag_name")                 // ex. "v3"
                val name = json.optString("name").ifBlank { tag }
                val notes = json.optString("body").take(400)
                val latestCode = Regex("\\d+").find(tag)?.value?.toIntOrNull() ?: 0

                var apkUrl = ""
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        if (a.optString("name").endsWith(".apk", true)) {
                            apkUrl = a.optString("browser_download_url")
                            break
                        }
                    }
                }

                val installed = BuildConfig.VERSION_CODE
                val isNew = latestCode > installed && apkUrl.isNotBlank()
                UpdateInfo(
                    available = isNew,
                    latestCode = latestCode,
                    latestName = name,
                    apkUrl = apkUrl,
                    notes = notes,
                    message = when {
                        isNew -> "Nouvelle version disponible : $name"
                        latestCode == 0 -> "Aucune version publiée pour l'instant."
                        else -> "Tu as déjà la dernière version (installée : ${BuildConfig.VERSION_NAME})."
                    }
                )
            }
        } catch (e: Exception) {
            UpdateInfo(false, 0, "", "", "", "Erreur réseau lors de la vérification.")
        }
    }

    /** Télécharge l'APK puis lance l'installation. onProgress renvoie le % (0-100). */
    suspend fun downloadAndInstall(
        context: Context,
        apkUrl: String,
        onProgress: (Int) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.filesDir, "updates").apply { mkdirs() }
            val apk = File(dir, "jumura-update.apk")
            if (apk.exists()) apk.delete()

            val req = Request.Builder().url(apkUrl).header("User-Agent", "Jumura").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext "Téléchargement échoué (code ${resp.code})."
                val stream = resp.body?.byteStream() ?: return@withContext "Réponse vide du serveur."
                val total = resp.body?.contentLength() ?: -1L
                apk.outputStream().use { out ->
                    val buf = ByteArray(8 * 1024)
                    var read: Int
                    var done = 0L
                    while (stream.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        done += read
                        if (total > 0) onProgress(((done * 100) / total).toInt())
                    }
                }
            }
            onProgress(100)
            launchInstall(context, apk)
            "Téléchargement terminé — installation lancée."
        } catch (e: Exception) {
            "Erreur pendant le téléchargement de la mise à jour."
        }
    }

    private fun launchInstall(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
