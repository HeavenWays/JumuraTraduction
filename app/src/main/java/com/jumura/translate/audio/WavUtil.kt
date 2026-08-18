package com.jumura.translate.audio

import java.io.ByteArrayOutputStream

/** Emballe du PCM 16 bits mono en fichier WAV (in-memory) pour l'API Whisper. */
object WavUtil {

    fun pcm16ToWav(pcm: ShortArray, sampleRate: Int = 16000): ByteArray {
        val dataSize = pcm.size * 2
        val out = ByteArrayOutputStream(44 + dataSize)

        // --- En-tête RIFF/WAVE ---
        writeAscii(out, "RIFF")
        writeIntLE(out, 36 + dataSize)          // taille du fichier - 8
        writeAscii(out, "WAVE")

        writeAscii(out, "fmt ")
        writeIntLE(out, 16)                      // taille du sous-chunk fmt (PCM)
        writeShortLE(out, 1)                     // format = PCM
        writeShortLE(out, 1)                     // canaux = mono
        writeIntLE(out, sampleRate)             // échantillonnage
        writeIntLE(out, sampleRate * 2)         // byte rate = sr * canaux * 2
        writeShortLE(out, 2)                     // block align = canaux * 2
        writeShortLE(out, 16)                    // bits par échantillon

        writeAscii(out, "data")
        writeIntLE(out, dataSize)

        // --- Données (little-endian) ---
        val bytes = ByteArray(dataSize)
        var j = 0
        for (s in pcm) {
            val v = s.toInt()
            bytes[j++] = (v and 0xFF).toByte()
            bytes[j++] = ((v shr 8) and 0xFF).toByte()
        }
        out.write(bytes)
        return out.toByteArray()
    }

    private fun writeAscii(out: ByteArrayOutputStream, s: String) {
        for (c in s) out.write(c.code)
    }

    private fun writeIntLE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 24) and 0xFF)
    }

    private fun writeShortLE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }
}
