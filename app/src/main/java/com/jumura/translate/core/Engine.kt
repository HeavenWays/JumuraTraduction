package com.jumura.translate.core

import android.content.Context
import com.jumura.translate.audio.AudioCapture
import com.jumura.translate.audio.WavUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient

/**
 * Moteur central (singleton, comme l'app entière tourne autour de lui) :
 * capture micro → transcription Whisper → traduction FR → publication à l'écran.
 *
 * Tout est exposé en `StateFlow` pour que l'UI Compose observe l'état en direct.
 * Le traitement est séquentiel (une file `Channel`) pour garder l'ordre des phrases
 * et un contexte de traduction cohérent, pendant que le micro continue d'écouter.
 */
object Engine {

    enum class Status { WAITING, TRANSLATING, DONE, ERROR }

    data class Line(
        val id: Long,
        val original: String,
        val lang: String,
        val french: String,
        val status: Status,
        val time: Long = System.currentTimeMillis()
    )

    private lateinit var appContext: Context
    lateinit var config: Config
        private set
    private lateinit var transcriber: Transcriber
    private lateinit var translator: Translator

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    // --- État observable ---
    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines = _lines.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()

    private val _level = MutableStateFlow(0f)
    val level = _level.asStateFlow()

    private val _status = MutableStateFlow("Prêt")
    val status = _status.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val ids = AtomicLong(0)
    private var scope: CoroutineScope? = null
    private var queue: Channel<ShortArray>? = null
    private var capture: AudioCapture? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        config = Config(appContext)
        transcriber = Transcriber(config, client)
        translator = Translator(config, client)
    }

    /** Démarre la capture + le pipeline. Renvoie false si le micro n'a pas pu s'ouvrir. */
    fun start(): Boolean {
        if (_running.value) return true
        _error.value = null

        // File ILLIMITÉE : on ne jette JAMAIS un segment de l'imam. Le traitement rattrape
        // le retard pendant les pauses (chaque segment ~4,5 s se traite en ~3-4 s).
        val q = Channel<ShortArray>(capacity = Channel.UNLIMITED)
        queue = q
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc

        // Consommateur : traite les segments un par un.
        sc.launch {
            for (seg in q) {
                try { process(seg) } catch (_: Exception) { /* on ne casse jamais la boucle */ }
            }
        }

        val cap = AudioCapture(
            gainProvider = { config.micGain },
            onLevel = { _level.value = it },
            onSegment = { seg -> queue?.trySend(seg) }
        )
        val ok = cap.start()
        if (!ok) {
            sc.cancel()
            q.close()
            _status.value = "Micro indisponible"
            _error.value = "Impossible d'ouvrir le micro. Vérifie l'autorisation Microphone."
            return false
        }
        capture = cap
        _running.value = true
        _status.value = "À l'écoute…"
        return true
    }

    fun stop() {
        if (!_running.value && capture == null) return
        _running.value = false
        try { capture?.stop() } catch (_: Exception) {}
        capture = null
        try { queue?.close() } catch (_: Exception) {}
        queue = null
        try { scope?.cancel() } catch (_: Exception) {}
        scope = null
        _level.value = 0f
        _status.value = "En pause"
    }

    fun clear() {
        _lines.value = emptyList()
    }

    private suspend fun process(seg: ShortArray) {
        _status.value = "Transcription…"
        val wav = WavUtil.pcm16ToWav(seg)
        val t = transcriber.transcribe(wav)

        if (!t.ok) {
            _error.value = t.text
            _status.value = "Erreur : ${t.text}"
            return
        }
        if (t.text.isBlank() || t.text == "-") {
            _status.value = "À l'écoute…"
            return
        }

        // Publie la ligne originale immédiatement (retour visuel rapide), traduction en cours.
        val id = ids.incrementAndGet()
        append(Line(id, t.text, t.lang, "", Status.TRANSLATING))
        _status.value = "Traduction…"

        val context = _lines.value.filter { it.status == Status.DONE }.map { it.french }
        val tr = translator.translate(t.text, t.lang, context)

        if (!tr.ok) {
            _error.value = tr.text
            update(id) { it.copy(french = "⚠︎ ${tr.text}", status = Status.ERROR) }
            _status.value = "Erreur : ${tr.text}"
            return
        }

        val fr = tr.text
        if (fr == "-" || fr.isBlank()) {
            // Rien d'exploitable : on retire la ligne pour ne pas polluer l'écran.
            remove(id)
        } else {
            update(id) { it.copy(french = fr, status = Status.DONE) }
            _error.value = null
        }
        _status.value = if (_running.value) "À l'écoute…" else "En pause"
    }

    // --- Helpers de mutation de la liste ---
    private fun append(line: Line) {
        _lines.value = _lines.value + line
    }

    private fun update(id: Long, transform: (Line) -> Line) {
        _lines.value = _lines.value.map { if (it.id == id) transform(it) else it }
    }

    private fun remove(id: Long) {
        _lines.value = _lines.value.filterNot { it.id == id }
    }
}
