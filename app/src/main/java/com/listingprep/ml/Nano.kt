package com.listingprep.ml

import android.graphics.Bitmap
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest

/**
 * The phone's built-in AI: Google's Gemini Nano, run by Android (AICore) on the phone's AI chip.
 * Nothing is uploaded, and there's no 4.4 GB download for this app: Android manages the model.
 * Only on phones that support it (Galaxy S25 and newer, Pixel 9 and newer…).
 */
object Nano {
    enum class State { READY, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE }

    private val model by lazy { Generation.getClient() }
    private const val MAX_OUTPUT = 1024

    suspend fun state(): State = runCatching {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> State.READY
            FeatureStatus.DOWNLOADABLE -> State.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> State.DOWNLOADING
            else -> State.UNAVAILABLE
        }
    }.getOrDefault(State.UNAVAILABLE)

    /** Asks Android to fetch the built-in model; returns true once it's ready. */
    suspend fun download(onProgress: (Long) -> Unit = {}): Boolean {
        var done = false
        runCatching {
            model.download().collect { s ->
                when (s) {
                    is DownloadStatus.DownloadProgress -> onProgress(s.totalBytesDownloaded)
                    DownloadStatus.DownloadCompleted -> done = true
                    else -> Unit
                }
            }
        }
        return done
    }

    /** One answer from Gemini Nano, from the prompt and (optionally) one photo. */
    suspend fun generate(prompt: String, photo: Bitmap?, temperature: Float): String {
        val request = if (photo != null) {
            generateContentRequest(ImagePart(photo), TextPart(prompt)) {
                this.temperature = temperature
                maxOutputTokens = MAX_OUTPUT // the default is short and cut listings off mid-sentence
            }
        } else {
            generateContentRequest(TextPart(prompt)) {
                this.temperature = temperature
                maxOutputTokens = MAX_OUTPUT
            }
        }
        return model.generateContent(request).candidates.firstOrNull()?.text.orEmpty()
    }
}
