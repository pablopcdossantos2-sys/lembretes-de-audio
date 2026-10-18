package com.example.util

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Utilitário para gerar arquivos de áudio WAV reais (chimes e melodias harmônicas)
 * salvos localmente no armazenamento do app. Garante que o aplicativo NUNCA dependa
 * exclusivamente de sintetizadores TTS externos para reproduzir sons com sucesso.
 */
object AudioSampleGenerator {

    private const val SAMPLE_RATE = 44100

    /**
     * Gera os áudios de demonstração padrão no armazenamento interno do app
     * caso ainda não existam.
     */
    fun ensureDefaultAudioFiles(context: Context): List<File> {
        val audioDir = File(context.filesDir, "bundled_audios").apply { mkdirs() }

        val waterFile = File(audioDir, "chime_agua.wav")
        if (!waterFile.exists() || waterFile.length() < 1000) {
            generateMelodicWav(
                outputFile = waterFile,
                frequencies = doubleArrayOf(523.25, 659.25, 783.99, 1046.50), // C5, E5, G5, C6 (Arp Maior)
                noteDurationMs = 280,
                decay = true
            )
        }

        val postureFile = File(audioDir, "chime_postura.wav")
        if (!postureFile.exists() || postureFile.length() < 1000) {
            generateMelodicWav(
                outputFile = postureFile,
                frequencies = doubleArrayOf(440.0, 554.37, 659.25, 880.0), // A4, C#5, E5, A5
                noteDurationMs = 300,
                decay = true
            )
        }

        val breathFile = File(audioDir, "chime_respiracao.wav")
        if (!breathFile.exists() || breathFile.length() < 1000) {
            generateMelodicWav(
                outputFile = breathFile,
                frequencies = doubleArrayOf(392.0, 523.25, 659.25), // G4, C5, E5
                noteDurationMs = 450,
                decay = true
            )
        }

        val testToneFile = File(audioDir, "test_loudspeaker.wav")
        if (!testToneFile.exists() || testToneFile.length() < 1000) {
            generateMelodicWav(
                outputFile = testToneFile,
                frequencies = doubleArrayOf(587.33, 880.0, 1174.66), // D5, A5, D6 (Alerta brilhante)
                noteDurationMs = 250,
                decay = false
            )
        }

        return listOf(waterFile, postureFile, breathFile)
    }

    /**
     * Retorna o arquivo de som do teste de alto-falante.
     */
    fun getTestSpeakerFile(context: Context): File {
        val audioDir = File(context.filesDir, "bundled_audios").apply { mkdirs() }
        val testFile = File(audioDir, "test_loudspeaker.wav")
        if (!testFile.exists() || testFile.length() < 1000) {
            generateMelodicWav(
                outputFile = testFile,
                frequencies = doubleArrayOf(587.33, 880.0, 1174.66),
                noteDurationMs = 250,
                decay = false
            )
        }
        return testFile
    }

    /**
     * Sintetiza uma sequência de frequências senoidais com envelope suave e salva como WAV PCM 16-bit.
     */
    fun generateMelodicWav(
        outputFile: File,
        frequencies: DoubleArray,
        noteDurationMs: Int = 300,
        decay: Boolean = true
    ) {
        try {
            val totalNotes = frequencies.size
            val samplesPerNote = (SAMPLE_RATE * (noteDurationMs / 1000.0)).toInt()
            val totalSamples = samplesPerNote * totalNotes
            val pcmData = ShortArray(totalSamples)

            for (noteIdx in frequencies.indices) {
                val freq = frequencies[noteIdx]
                val offset = noteIdx * samplesPerNote

                for (s in 0 until samplesPerNote) {
                    val t = s.toDouble() / SAMPLE_RATE
                    val sine = sin(2.0 * PI * freq * t)

                    // Envelope ADSR suave (ataque rápido de 15ms, sustain e decay)
                    val progress = s.toDouble() / samplesPerNote
                    val envelope = when {
                        progress < 0.08 -> progress / 0.08 // Attack
                        decay -> (1.0 - progress).coerceIn(0.0, 1.0) // Gentle decay
                        else -> 0.95
                    }

                    val sampleValue = (sine * envelope * 28000.0).toInt().coerceIn(-32767, 32767)
                    pcmData[offset + s] = sampleValue.toShort()
                }
            }

            writeWavFile(outputFile, pcmData, SAMPLE_RATE)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun writeWavFile(file: File, pcmData: ShortArray, sampleRate: Int) {
        val totalAudioLen = pcmData.size * 2L
        val totalDataLen = totalAudioLen + 36
        val channels = 1
        val byteRate = sampleRate * channels * 2L

        FileOutputStream(file).use { out ->
            val header = ByteBuffer.allocate(44).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                // RIFF chunk
                put('R'.code.toByte()); put('I'.code.toByte()); put('F'.code.toByte()); put('F'.code.toByte())
                putInt(totalDataLen.toInt())
                put('W'.code.toByte()); put('A'.code.toByte()); put('V'.code.toByte()); put('E'.code.toByte())
                // fmt subchunk
                put('f'.code.toByte()); put('m'.code.toByte()); put('t'.code.toByte()); put(' '.code.toByte())
                putInt(16) // Subchunk1Size (16 for PCM)
                putShort(1.toShort()) // AudioFormat (1 = PCM)
                putShort(channels.toShort()) // NumChannels
                putInt(sampleRate) // SampleRate
                putInt(byteRate.toInt()) // ByteRate
                putShort((channels * 2).toShort()) // BlockAlign
                putShort(16.toShort()) // BitsPerSample
                // data subchunk
                put('d'.code.toByte()); put('a'.code.toByte()); put('t'.code.toByte()); put('a'.code.toByte())
                putInt(totalAudioLen.toInt())
            }

            out.write(header.array())

            val audioBuffer = ByteBuffer.allocate(pcmData.size * 2).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                for (s in pcmData) {
                    putShort(s)
                }
            }
            out.write(audioBuffer.array())
            out.flush()
        }
    }
}
