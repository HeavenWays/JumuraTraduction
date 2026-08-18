package com.jumura.translate.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Capture micro conçue pour un imam ÉLOIGNÉ dans une salle qui résonne.
 *
 * Principes (corrigés après le retour « ça ne capte que 10 % ») :
 *  1. ENREGISTREMENT CONTINU — on n'utilise AUCUN portillon de volume en amont.
 *     Tant que l'app écoute, tout l'audio est accumulé. Rien n'est jeté avant analyse.
 *  2. PAS de NoiseSuppressor ni d'AGC matériels : ils suppriment la voix lointaine
 *     (considérée comme du « bruit ») et sont la cause principale des pertes.
 *  3. Source MICRO BRUTE (MIC), moins « filtrée » que VOICE_RECOGNITION qui est réglée
 *     pour une bouche proche du téléphone.
 *  4. NORMALISATION de chaque segment : on remonte la voix faible à un niveau fort et
 *     lisible par Whisper (jusqu'à ~50× selon le réglage de sensibilité). C'est ce qui
 *     permet de transcrire un imam loin, là où l'audio brut serait trop bas.
 *  5. Découpage sur les micro-pauses (sinon toutes les 8 s), pour envoyer des phrases
 *     entières sans jamais perdre le fil.
 */
class AudioCapture(
    private val gainProvider: () -> Float,     // « sensibilité » 1..5 → boost max 10..50×
    private val onLevel: (Float) -> Unit,
    private val onSegment: (ShortArray) -> Unit
) {
    private val sampleRate = 16000
    private val channelCfg = AudioFormat.CHANNEL_IN_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true

        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelCfg, encoding)
        if (minBuf <= 0) return false
        val bufSize = maxOf(minBuf, sampleRate * 2) // ~2 s de marge → aucune perte (overrun)

        // VOICE_RECOGNITION en priorité : c'est la source qui délivrait bien de l'audio en v1
        // (surtout sur Samsung, où le MIC brut sort à un niveau trop bas). On garde MIC/DEFAULT
        // en repli. Le vrai gain vient de la suppression du portillon + la normalisation forte.
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT
        )
        var ar: AudioRecord? = null
        for (src in sources) {
            val cand = try {
                AudioRecord(src, sampleRate, channelCfg, encoding, bufSize)
            } catch (_: Exception) { null }
            if (cand != null && cand.state == AudioRecord.STATE_INITIALIZED) { ar = cand; break }
            cand?.release()
        }
        if (ar == null) return false
        record = ar

        // IMPORTANT : on n'active PAS NoiseSuppressor/AGC (ils tuent la voix lointaine).
        running = true
        ar.startRecording()
        worker = Thread({ loop() }, "jumura-capture").apply { start() }
        return true
    }

    private fun loop() {
        val frameSize = 480                        // 30 ms
        val frame = ShortArray(frameSize)

        val maxSamples = sampleRate * 10           // tampon max 10 s
        val buffer = ShortArray(maxSamples)
        var len = 0

        var noiseFloor = 0.02f                     // niveau de bruit de fond estimé (adaptatif)
        var accMs: Int

        val targetMs = 4500                        // longueur visée d'un segment
        val maxMs = 8000                           // clôture forcée (parole continue)
        val minMs = 900                            // en-deçà : trop court, on ignore

        while (running) {
            val n = record?.read(frame, 0, frameSize) ?: break
            if (n <= 0) continue

            val rms = rms(frame, n)
            onLevel(min(1f, rms * 12f))

            // Bruit de fond : descend instantanément vers un nouveau minimum, remonte très lentement.
            noiseFloor = if (rms < noiseFloor) rms else noiseFloor + (rms - noiseFloor) * 0.0006f
            val floor = maxOf(noiseFloor, 0.0006f)

            // Accumulation CONTINUE : on garde tout.
            val c = min(n, maxSamples - len)
            System.arraycopy(frame, 0, buffer, len, c)
            len += c
            accMs = len * 1000 / sampleRate

            val quiet = rms < floor * 2.5f
            val cutOnPause = accMs >= targetMs && quiet
            val cutForced = accMs >= maxMs || len >= maxSamples
            if (cutOnPause || cutForced) {
                if (accMs >= minMs) flush(buffer, len)
                len = 0
            }
        }
        if (len > 0) flush(buffer, len)
    }

    /** Normalise le segment et l'émet — sauf s'il est quasi silencieux (inutile d'appeler l'API). */
    private fun flush(buffer: ShortArray, len: Int) {
        var sum = 0.0
        var peak = 1
        for (i in 0 until len) {
            val s = buffer[i].toInt()
            sum += s.toDouble() * s
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
        }
        val rms = sqrt(sum / len) / 32768.0
        if (rms < SILENCE_RMS) return              // vrai silence → on n'envoie pas

        // Boost pour remonter la voix lointaine à un niveau lisible par Whisper.
        val maxGain = gainProvider().coerceIn(1f, 5f) * 10f
        var gain = (TARGET_RMS / rms).toFloat()
        if (gain < 1f) gain = 1f
        if (gain > maxGain) gain = maxGain
        // Limite anti-écrêtage : garde le pic sous le plein échelle.
        val peakLimit = 30000f / peak
        if (peakLimit > 1f && gain > peakLimit) gain = peakLimit

        val out = ShortArray(len)
        for (i in 0 until len) {
            val v = (buffer[i] * gain).toInt()
            out[i] = when {
                v > 32767 -> 32767
                v < -32768 -> -32768
                else -> v.toShort()
            }
        }
        onSegment(out)
    }

    private fun rms(buf: ShortArray, n: Int): Float {
        if (n == 0) return 0f
        var sum = 0.0
        for (i in 0 until n) { val s = buf[i] / 32768.0; sum += s * s }
        return sqrt(sum / n).toFloat()
    }

    fun stop() {
        running = false
        try { worker?.join(600) } catch (_: Exception) {}
        worker = null
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        onLevel(0f)
    }

    companion object {
        private const val TARGET_RMS = 0.16        // niveau cible après normalisation
        // Seuil TRÈS permissif : on préfère envoyer un segment un peu faible (Whisper renverra
        // du vide si ce n'est que du bruit) plutôt que de risquer de jeter la voix de l'imam.
        private const val SILENCE_RMS = 0.0008
    }
}
