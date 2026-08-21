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
 * Corrections v2 (retour « transcription catastrophique / hallucinations ») :
 *  1. ENREGISTREMENT CONTINU — aucun portillon de volume en amont, rien n'est jeté avant analyse.
 *  2. PAS de NoiseSuppressor ni d'AGC matériels (ils suppriment la voix lointaine).
 *  3. Source VOICE_RECOGNITION en priorité (niveau fiable sur Samsung), MIC/DEFAULT en repli.
 *  4. FILTRE PASSE-HAUT ~80 Hz sur chaque segment : retire le grondement/soufflet de la salle,
 *     ce qui réduit fortement les hallucinations de Whisper sur du bruit basse fréquence.
 *  5. NORMALISATION MODÉRÉE (~×12 max, plus ×50) : on remonte la voix faible sans gonfler le bruit
 *     et l'écho au point de faire halluciner Whisper.
 *  6. DÉCOUPAGE sur de VRAIES pauses (silence soutenu ~450 ms), pas au premier micro-blanc, et
 *     segments plus longs (jusqu'à 13 s) → on ne coupe plus au milieu des mots, Whisper garde le fil.
 */
class AudioCapture(
    private val gainProvider: () -> Float,     // « sensibilité » 1..5 → boost max ~5..12×
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

        val maxSamples = sampleRate * 15           // tampon max 15 s
        val buffer = ShortArray(maxSamples)
        var len = 0

        var noiseFloor = 0.02f                     // niveau de bruit de fond estimé (adaptatif)
        var quietMs = 0                            // durée de silence continu en cours
        var accMs: Int

        val targetMs = 5000                        // longueur visée d'un segment
        val maxMs = 13000                          // clôture forcée (parole continue)
        val minMs = 900                            // en-deçà : trop court, on ignore
        val pauseMs = 450                          // silence continu requis pour couper « proprement »

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

            // Silence SOUTENU (et non un simple micro-blanc) avant de couper : évite de trancher un mot.
            val quiet = rms < floor * 2.5f
            quietMs = if (quiet) quietMs + (n * 1000 / sampleRate) else 0

            val cutOnPause = accMs >= targetMs && quietMs >= pauseMs
            val cutForced = accMs >= maxMs || len >= maxSamples
            if (cutOnPause || cutForced) {
                if (accMs >= minMs) flush(buffer, len)
                len = 0
                quietMs = 0
            }
        }
        if (len > 0) flush(buffer, len)
    }

    /**
     * Filtre passe-haut + normalisation modérée, puis émission — sauf segment quasi silencieux.
     * Le passe-haut (~80 Hz) enlève le grondement de la salle AVANT de mesurer le niveau, ce qui
     * évite d'amplifier un bruit basse fréquence et fait chuter les hallucinations de Whisper.
     */
    private fun flush(buffer: ShortArray, len: Int) {
        // Passe-haut à un pôle (~80 Hz) : y[n] = a*(y[n-1] + x[n] - x[n-1]).
        val hp = FloatArray(len)
        val a = 0.97f
        var prevX = 0f
        var prevY = 0f
        for (i in 0 until len) {
            val x = buffer[i].toFloat()
            val y = a * (prevY + x - prevX)
            hp[i] = y
            prevX = x
            prevY = y
        }

        var sum = 0.0
        var peak = 1f
        for (i in 0 until len) {
            val s = hp[i]
            sum += s.toDouble() * s
            val abs = if (s < 0) -s else s
            if (abs > peak) peak = abs
        }
        val rms = sqrt(sum / len) / 32768.0
        if (rms < SILENCE_RMS) return              // silence/bruit résiduel → on n'envoie pas

        // Boost MODÉRÉ pour remonter la voix lointaine sans gonfler le bruit (≈ ×5..12).
        val maxGain = 3f + gainProvider().coerceIn(1f, 5f) * 1.8f
        var gain = (TARGET_RMS / rms).toFloat()
        if (gain < 1f) gain = 1f
        if (gain > maxGain) gain = maxGain
        // Limite anti-écrêtage : garde le pic sous le plein échelle.
        val peakLimit = 30000f / peak
        if (peakLimit > 1f && gain > peakLimit) gain = peakLimit

        val out = ShortArray(len)
        for (i in 0 until len) {
            val v = (hp[i] * gain).toInt()
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
        private const val TARGET_RMS = 0.12        // niveau cible après normalisation (modéré)
        // Seuil permissif mais mesuré APRÈS passe-haut (le grondement ne compte plus) : on jette
        // le vrai silence/bruit résiduel sans risquer la voix faible de l'imam.
        private const val SILENCE_RMS = 0.0015
    }
}
