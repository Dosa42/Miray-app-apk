package com.example.speech

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

enum class SttLanguage(val code: String, val label: String, val shortLabel: String) {
    ENGLISH("en", "English", "EN"),
    DUTCH("nl", "Nederlands", "NL"),
    TURKISH("tr", "Türkçe", "TR");

    companion object {
        fun fromCode(code: String?): SttLanguage = entries.firstOrNull { it.code == code } ?: DUTCH
        fun isAllowed(code: String): Boolean = entries.any { it.code == code }
    }
}

class GroqSpeechService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
) {
    fun transcribe(audioFile: File, language: SttLanguage, apiKey: String): Result<String> = runCatching {
        require(audioFile.isFile && audioFile.length() > 0L) { "Audio recording is empty." }
        require(apiKey.startsWith("gsk_")) { "A valid Groq API key is required." }
        require(SttLanguage.isAllowed(language.code)) { "Unsupported STT language." }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", MODEL)
            .addFormDataPart("language", language.code)
            .addFormDataPart("response_format", "json")
            .addFormDataPart(
                "file",
                audioFile.name,
                audioFile.asRequestBody("audio/mp4".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url(TRANSCRIPTIONS_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    val json = JSONObject(responseBody)
                    json.optJSONObject("error")?.optString("message")
                        ?: json.optString("message")
                        ?: responseBody
                }.getOrDefault(responseBody)
                error("Groq STT HTTP ${response.code}: ${detail.ifBlank { response.message }}")
            }

            val transcript = JSONObject(responseBody).optString("text").trim()
            require(transcript.isNotBlank()) { "Groq returned an empty transcription." }
            transcript
        }
    }

    companion object {
        const val MODEL = "whisper-large-v3"
        const val TRANSCRIPTIONS_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
    }
}
