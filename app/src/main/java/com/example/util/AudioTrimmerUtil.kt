package com.example.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object AudioTrimmerUtil {

    private const val TAG = "AudioTrimmerUtil"

    data class AudioFileInfo(
        val durationMs: Long,
        val waveformPoints: FloatArray, // 50 normalized amplitudes (0.0f .. 1.0f)
        val suggestedStartMs: Long,     // Auto-detected silence cut at start
        val suggestedEndMs: Long,       // Auto-detected silence cut at end
        val isWav: Boolean
    )

    /**
     * Analyzes audio file to get duration, waveform amplitude bars, and auto-detect silence.
     */
    suspend fun analyzeAudioFile(context: Context, uriString: String): AudioFileInfo = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        val file = resolveFileFromUri(context, uri)

        var durationMs = 0L
        val retriever = MediaMetadataRetriever()
        try {
            if (file != null && file.exists()) {
                retriever.setDataSource(file.absolutePath)
            } else {
                retriever.setDataSource(context, uri)
            }
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationMs = durStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "MediaMetadataRetriever failed to get duration", e)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        val isWav = (file?.name?.endsWith(".wav", ignoreCase = true) == true) || uriString.endsWith(".wav", ignoreCase = true)

        if (isWav && file != null && file.exists()) {
            val wavInfo = analyzeWavFile(file)
            if (wavInfo != null) {
                return@withContext wavInfo
            }
        }

        // Fallback for non-WAV or if wav analysis failed
        if (durationMs <= 0L) {
            durationMs = 3000L // safe fallback duration
        }

        val barCount = 50
        val waveform = FloatArray(barCount) { idx ->
            val t = idx.toFloat() / barCount
            val amp = (0.2f + 0.6f * kotlin.math.sin(t * 3.14159f) + (idx % 3) * 0.08f).coerceIn(0.1f, 1.0f)
            amp.toFloat()
        }

        // Suggested silence cut
        val startSilence = min(400L, durationMs / 10)
        val endSilence = max(durationMs - 400L, durationMs * 9 / 10)

        AudioFileInfo(
            durationMs = durationMs,
            waveformPoints = waveform,
            suggestedStartMs = startSilence,
            suggestedEndMs = endSilence,
            isWav = isWav
        )
    }

    private fun analyzeWavFile(wavFile: File): AudioFileInfo? {
        try {
            RandomAccessFile(wavFile, "r").use { raf ->
                if (raf.length() < 44) return null

                // Verify RIFF header
                val header = ByteArray(12)
                raf.readFully(header)
                val riff = String(header, 0, 4)
                val wave = String(header, 8, 4)
                if (riff != "RIFF" || wave != "WAVE") return null

                var sampleRate = 44100
                var channels = 1
                var bitsPerSample = 16
                var dataOffset = 44L
                var dataSize = 0L

                // Scan chunks until "data" is found
                var pos = 12L
                while (pos < raf.length() - 8) {
                    raf.seek(pos)
                    val chunkHeader = ByteArray(8)
                    raf.readFully(chunkHeader)
                    val chunkId = String(chunkHeader, 0, 4)
                    val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

                    if (chunkId == "fmt ") {
                        val fmtData = ByteArray(min(chunkSize.toInt(), 16))
                        raf.readFully(fmtData)
                        val bb = ByteBuffer.wrap(fmtData).order(ByteOrder.LITTLE_ENDIAN)
                        val formatTag = bb.short // 1 = PCM
                        channels = bb.short.toInt()
                        sampleRate = bb.int
                        bb.int // byteRate
                        bb.short // blockAlign
                        bitsPerSample = bb.short.toInt()
                    } else if (chunkId == "data") {
                        dataOffset = pos + 8
                        dataSize = chunkSize
                        break
                    }
                    pos += 8 + chunkSize
                }

                if (dataSize <= 0) {
                    dataSize = raf.length() - dataOffset
                }

                val bytesPerSample = (bitsPerSample / 8).coerceAtLeast(1)
                val bytesPerFrame = channels * bytesPerSample
                val totalFrames = dataSize / bytesPerFrame
                val durationMs = (totalFrames * 1000L) / sampleRate

                if (durationMs <= 0) return null

                // Read and compute RMS amplitudes across 50 bins
                val barCount = 50
                val waveform = FloatArray(barCount)
                val framesPerBar = (totalFrames / barCount).coerceAtLeast(1)
                var maxRms = 0.01f

                var firstAudibleFrame = -1L
                var lastAudibleFrame = -1L
                val silenceThreshold = 0.04f

                raf.seek(dataOffset)
                val sampleBuffer = ShortArray(2048)
                val byteBuffer = ByteArray(4096)

                for (bar in 0 until barCount) {
                    val barStartFrame = bar * framesPerBar
                    val barEndFrame = min(totalFrames, barStartFrame + framesPerBar)
                    val barFrames = barEndFrame - barStartFrame

                    raf.seek(dataOffset + barStartFrame * bytesPerFrame)
                    var sumSquares = 0.0
                    var samplesRead = 0

                    var remainingFrames = barFrames
                    while (remainingFrames > 0) {
                        val toReadBytes = min(remainingFrames * bytesPerFrame, byteBuffer.size.toLong()).toInt()
                        val bytesRead = raf.read(byteBuffer, 0, toReadBytes)
                        if (bytesRead <= 0) break

                        val shorts = bytesRead / 2
                        val bb = ByteBuffer.wrap(byteBuffer, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until shorts) {
                            val sample = bb.short.toFloat() / 32768.0f
                            sumSquares += sample * sample
                            samplesRead++

                            val frameIndex = barStartFrame + (samplesRead / channels)
                            if (abs(sample) > silenceThreshold) {
                                if (firstAudibleFrame == -1L) {
                                    firstAudibleFrame = frameIndex
                                }
                                lastAudibleFrame = frameIndex
                            }
                        }
                        remainingFrames -= (bytesRead / bytesPerFrame)
                    }

                    val rms = if (samplesRead > 0) sqrt(sumSquares / samplesRead).toFloat() else 0.02f
                    waveform[bar] = rms
                    if (rms > maxRms) maxRms = rms
                }

                // Normalize waveform 0.05f .. 1.0f
                for (i in waveform.indices) {
                    waveform[i] = (waveform[i] / maxRms).coerceIn(0.06f, 1.0f)
                }

                val suggestedStartMs = if (firstAudibleFrame > 0) {
                    val ms = (firstAudibleFrame * 1000L) / sampleRate
                    max(0L, ms - 80L) // 80ms padding so initial consonant isn't clipped
                } else 0L

                val suggestedEndMs = if (lastAudibleFrame > 0) {
                    val ms = (lastAudibleFrame * 1000L) / sampleRate
                    min(durationMs, ms + 100L) // 100ms padding
                } else durationMs

                return AudioFileInfo(
                    durationMs = durationMs,
                    waveformPoints = waveform,
                    suggestedStartMs = suggestedStartMs,
                    suggestedEndMs = min(durationMs, max(suggestedStartMs + 500L, suggestedEndMs)),
                    isWav = true
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing WAV file", e)
            return null
        }
    }

    /**
     * Trims audio from startMs to endMs and writes result into outputFile.
     */
    suspend fun trimAudio(
        context: Context,
        sourceUriString: String,
        startMs: Long,
        endMs: Long,
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        val uri = Uri.parse(sourceUriString)
        val file = resolveFileFromUri(context, uri)

        val isWav = (file?.name?.endsWith(".wav", ignoreCase = true) == true) || sourceUriString.endsWith(".wav", ignoreCase = true)

        if (isWav && file != null && file.exists()) {
            val success = trimWavFile(file, startMs, endMs, outputFile)
            if (success) return@withContext true
        }

        // If not WAV or WAV trim failed, attempt MediaExtractor + MediaMuxer
        if (file != null && file.exists()) {
            val success = trimM4aFile(file.absolutePath, startMs * 1000L, endMs * 1000L, outputFile)
            if (success) return@withContext true
        }

        false
    }

    private fun trimWavFile(sourceWav: File, startMs: Long, endMs: Long, outputFile: File): Boolean {
        return try {
            RandomAccessFile(sourceWav, "r").use { raf ->
                if (raf.length() < 44) return false

                val header = ByteArray(12)
                raf.readFully(header)
                if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") return false

                var sampleRate = 44100
                var channels = 1
                var bitsPerSample = 16
                var dataOffset = 44L
                var totalDataSize = 0L

                var pos = 12L
                while (pos < raf.length() - 8) {
                    raf.seek(pos)
                    val chunkHeader = ByteArray(8)
                    raf.readFully(chunkHeader)
                    val chunkId = String(chunkHeader, 0, 4)
                    val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

                    if (chunkId == "fmt ") {
                        val fmtData = ByteArray(min(chunkSize.toInt(), 16))
                        raf.readFully(fmtData)
                        val bb = ByteBuffer.wrap(fmtData).order(ByteOrder.LITTLE_ENDIAN)
                        bb.short // format
                        channels = bb.short.toInt()
                        sampleRate = bb.int
                        bb.int // byteRate
                        bb.short // blockAlign
                        bitsPerSample = bb.short.toInt()
                    } else if (chunkId == "data") {
                        dataOffset = pos + 8
                        totalDataSize = chunkSize
                        break
                    }
                    pos += 8 + chunkSize
                }

                if (totalDataSize <= 0) {
                    totalDataSize = raf.length() - dataOffset
                }

                val bytesPerFrame = channels * (bitsPerSample / 8)
                val byteRate = sampleRate * bytesPerFrame

                val startByteOffset = ((startMs * byteRate / 1000L) / bytesPerFrame) * bytesPerFrame
                val endByteOffset = min(
                    totalDataSize,
                    (((endMs * byteRate / 1000L) / bytesPerFrame) * bytesPerFrame)
                )

                val trimmedPcmBytes = max(0L, endByteOffset - startByteOffset)
                if (trimmedPcmBytes <= 0) return false

                outputFile.parentFile?.mkdirs()
                FileOutputStream(outputFile).use { fos ->
                    // 1. Write standard 44-byte WAV header for trimmed data
                    val wavHeader = createWavHeader(trimmedPcmBytes, sampleRate, channels, bitsPerSample)
                    fos.write(wavHeader)

                    // 2. Stream sliced PCM data
                    raf.seek(dataOffset + startByteOffset)
                    val buffer = ByteArray(8192)
                    var bytesRemaining = trimmedPcmBytes
                    while (bytesRemaining > 0) {
                        val toRead = min(bytesRemaining, buffer.size.toLong()).toInt()
                        val read = raf.read(buffer, 0, toRead)
                        if (read <= 0) break
                        fos.write(buffer, 0, read)
                        bytesRemaining -= read
                    }
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error trimming WAV file", e)
            false
        }
    }

    private fun trimM4aFile(sourcePath: String, startUs: Long, endUs: Long, outputFile: File): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(sourcePath)
            var audioTrackIndex = -1
            var trackFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    trackFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || trackFormat == null) {
                return false
            }

            extractor.selectTrack(audioTrackIndex)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            outputFile.parentFile?.mkdirs()
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrack = muxer.addTrack(trackFormat)
            muxer.start()

            val maxInputSize = if (trackFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else 128 * 1024

            val buffer = ByteBuffer.allocate(max(maxInputSize, 32 * 1024))
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break

                val sampleTime = extractor.sampleTime
                if (sampleTime > endUs) break

                if (sampleTime >= startUs) {
                    bufferInfo.presentationTimeUs = sampleTime - startUs
                    bufferInfo.flags = extractor.sampleFlags
                    muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                }
                extractor.advance()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error trimming M4A file with MediaMuxer", e)
            false
        } finally {
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun createWavHeader(pcmDataSize: Long, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val totalDataLen = pcmDataSize + 36
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // PCM
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * (bitsPerSample / 8)).toByte()
        header[33] = 0
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (pcmDataSize and 0xff).toByte()
        header[41] = ((pcmDataSize shr 8) and 0xff).toByte()
        header[42] = ((pcmDataSize shr 16) and 0xff).toByte()
        header[43] = ((pcmDataSize shr 24) and 0xff).toByte()

        return header
    }

    fun resolveFileFromUri(context: Context, uri: Uri): File? {
        return if (uri.scheme == null || uri.scheme == "file") {
            val path = uri.path ?: uri.toString()
            File(path)
        } else {
            // Check if it's cached in filesDir
            null
        }
    }

    fun formatTimeMs(millis: Long): String {
        val totalSeconds = millis / 1000
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        val tenths = (millis % 1000) / 100
        return String.format(java.util.Locale.US, "%02d:%02d.%01d", m, s, tenths)
    }
}
