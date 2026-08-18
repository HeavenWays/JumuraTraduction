package com.jumura.translate.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Capture micro pensée pour un imam éloigné dans une salle qui résonne.
 *
 *  - Source VOICE_RECOGNITION (repli DEFAULT) : optimisée pour la parole.
 *  - NoiseSuppressor + AutomaticGainControl matériels si le téléphone les propose.
 *  - Gain logiciel supplémentaire réglable (l'imam est loin → on remonte le niveau).
 *  - Découpage INTELLIGENT sur les silences : on accumule tant que ça parle, et on
 *    émet un segment dès qu'il y a ~0,7 s de silence (fin de phrase de l'imam) ou au
 *    bout de 12 s max. Chaque segment part ensuite en transcription.
 *
 * Émet aussi le niveau sonore en continu (0..1) pour animer l'UI.
 */
class AudioCapture(
    private val gainProvider: () -> Float,
    private val onLevel: (Float) -> Unit,
    private val onSegment: (ShortArray) -> Unit
) {
    private val sampleRate = 16000
    private val channel = AudioFormat.CHANNEL_IN_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true

        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
        if (minBuf <= 0) return false
        val bufSize = maxOf(minBuf, sampleRate)  // ~1 s de marge

        var ar = tryCreate(MediaRecorder.AudioSource.VOICE_RECOGNITION, bufSize)
        if (ar == null || ar.state != AudioRecord.STATE_INITIALIZED) {
            ar?.release()
            ar = tryCreate(MediaRecorder.AudioSource.MIC, bufSize)
        }
        if (ar == null || ar.state != AudioRecord.STATE_INITIALIZED) {
            ar?.release()
            return false
        }
        record = ar
        enableEffects(ar.audioSessionId)

        running = true
        ar.startRecording()
        worker = Thread({ loop() }, "jumura-capture").apply { start() }
        return true
    }

    private fun tryCreate(source: Int, bufSize: Int): AudioRecord? = try {
        AudioRecord(source, sampleRate, channel, encoding, bufSize)
    } catch (_: Exception) {
        null
    }

    private fun enableEffects(sessionId: Int) {
        try {
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) {}
        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) {}
    }

    private fun loop() {
        val frameSize = sampleRate / 10                 // fenêtre de 100 ms
        val frame = ShortArray(frameSize)

        // Tampon du segment en cours (max ~13 s pour rester sous la limite d'émission).
        val maxSamples = sampleRate * 13
        val buffer = ShortArray(maxSamples)
        var len = 0

        // Pré-amorce : on garde ~300 ms avant le début de parole pour ne pas couper l'attaque.
        val preRoll = ArrayDeque<ShortArray>()

        var inSpeech = false
        var speechMs = 0
        var silenceMs = 0

        val startThresh = 0.018f      // RMS pour DÉMARRER un segment (parole nette)
        val keepThresh = 0.010f       // en-dessous = considéré silence
        val hangoverMs = 700          // silence toléré avant de clore la phrase
        val minKeepMs = 500           // en-deçà, segment jugé trop court (bruit) → jeté
        val maxSpeechMs = 12000       // clôture forcée sur une longue tirade

        while (running) {
            val n = record?.read(frame, 0, frameSize) ?: break
            if (n <= 0) continue

            val gain = gainProvider().coerceIn(1f, 6f)
            applyGain(frame, n, gain)

            val rms = rms(frame, n)
            onLevel(min(1f, rms * 7f))
            val frameMs = n * 1000 / sampleRate

            if (!inSpeech) {
                // Mémorise la pré-amorce glissante (3 x 100 ms).
                preRoll.addLast(frame.copyOf(n))
                while (preRoll.size > 3) preRoll.removeFirst()

                if (rms >= startThresh) {
                    inSpeech = true
                    speechMs = 0
                    silenceMs = 0
                    len = 0
                    // Injecte la pré-amorce puis la trame courante.
                    for (pr in preRoll) {
                        val c = min(pr.size, maxSamples - len)
                        System.arraycopy(pr, 0, buffer, len, c); len += c
                    }
                    preRoll.clear()
                }
            } else {
                val c = min(n, maxSamples - len)
                System.arraycopy(frame, 0, buffer, len, c); len += c
                speechMs += frameMs
                silenceMs = if (rms < keepThresh) silenceMs + frameMs else 0

                val bufferFull = len >= maxSamples
                val ended = silenceMs >= hangoverMs || speechMs >= maxSpeechMs || bufferFull
                if (ended) {
                    val voicedMs = speechMs - silenceMs
                    if (voicedMs >= minKeepMs) {
                        onSegment(buffer.copyOf(len))
                    }
                    inSpeech = false
                    len = 0
                    speechMs = 0
                    silenceMs = 0
                }
            }
        }
    }

    private fun applyGain(buf: ShortArray, n: Int, gain: Float) {
        if (gain == 1f) return
        for (i in 0 until n) {
            val v = (buf[i] * gain).toInt()
            buf[i] = when {
                v > Short.MAX_VALUE -> Short.MAX_VALUE
                v < Short.MIN_VALUE -> Short.MIN_VALUE
                else -> v.toShort()
            }
        }
    }

    private fun rms(buf: ShortArray, n: Int): Float {
        if (n == 0) return 0f
        var sum = 0.0
        for (i in 0 until n) {
            val s = buf[i] / 32768.0
            sum += s * s
        }
        return sqrt(sum / n).toFloat()
    }

    fun stop() {
        running = false
        try { worker?.join(500) } catch (_: Exception) {}
        worker = null
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        try { ns?.release() } catch (_: Exception) {}
        try { agc?.release() } catch (_: Exception) {}
        ns = null; agc = null
        onLevel(0f)
    }
}
