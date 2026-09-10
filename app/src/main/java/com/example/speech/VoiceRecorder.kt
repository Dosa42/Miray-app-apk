package com.example.speech

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var currentOutput: File? = null

    val isRecording: Boolean
        get() = recorder != null

    fun start(): Result<File> = runCatching {
        check(recorder == null) { "A recording is already active." }
        val output = File(context.cacheDir, "groq-stt-${System.currentTimeMillis()}.m4a")
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioSamplingRate(16_000)
            mediaRecorder.setAudioEncodingBitRate(64_000)
            mediaRecorder.setMaxDuration(MAX_DURATION_MS)
            mediaRecorder.setOutputFile(output.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()
        } catch (error: Throwable) {
            runCatching { mediaRecorder.reset() }
            runCatching { mediaRecorder.release() }
            throw error
        }

        recorder = mediaRecorder
        currentOutput = output
        output
    }

    fun stop(): Result<File> = runCatching {
        val activeRecorder = recorder ?: error("No recording is active.")
        val output = currentOutput ?: error("Recording output is unavailable.")
        try {
            activeRecorder.stop()
        } finally {
            runCatching { activeRecorder.reset() }
            runCatching { activeRecorder.release() }
            recorder = null
            currentOutput = null
        }
        require(output.isFile && output.length() > 0L) { "Recording was too short or empty." }
        output
    }

    fun release() {
        recorder?.let { activeRecorder ->
            runCatching { activeRecorder.stop() }
            runCatching { activeRecorder.reset() }
            runCatching { activeRecorder.release() }
        }
        recorder = null
        currentOutput = null
    }

    companion object {
        private const val MAX_DURATION_MS = 120_000
    }
}
