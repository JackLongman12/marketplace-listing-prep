package com.listingprep.ml

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.listingprep.data.Item
import kotlinx.coroutines.tasks.await
import java.io.Closeable
import java.io.File

/** Reads text (brands, model numbers, labels) from photos. On-device. */
object Ocr {
    suspend fun read(images: List<Bitmap>): String {
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val lines = images.flatMap { bmp ->
                runCatching { client.process(InputImage.fromBitmap(bmp, 0)).await() }
                    .getOrNull()?.textBlocks?.flatMap { b -> b.lines.map { it.text.trim() } }.orEmpty()
            }
            return lines
                .filter { it.length >= 2 && it.any(Char::isLetterOrDigit) }
                .distinctBy { it.lowercase() }
                .take(40)
                .joinToString("\n")
                .take(800)
        } finally {
            client.close()
        }
    }
}

/** Finds barcodes (EAN/UPC/ISBN, Code 128, …) and QR codes in photos. On-device; no lookup. */
object Codes {
    suspend fun read(images: List<Bitmap>): List<String> {
        val scanner = BarcodeScanning.getClient()
        try {
            return images.flatMap { bmp ->
                runCatching { scanner.process(InputImage.fromBitmap(bmp, 0)).await() }.getOrNull().orEmpty()
            }.mapNotNull { b ->
                val value = (b.rawValue ?: b.displayValue)?.trim()?.take(300) ?: return@mapNotNull null
                if (value.isEmpty()) null else "${formatName(b.format)}: $value"
            }.distinct().take(8)
        } finally {
            scanner.close()
        }
    }

    private fun formatName(f: Int) = when (f) {
        Barcode.FORMAT_QR_CODE -> "QR"
        Barcode.FORMAT_EAN_13 -> "EAN-13"
        Barcode.FORMAT_EAN_8 -> "EAN-8"
        Barcode.FORMAT_UPC_A -> "UPC-A"
        Barcode.FORMAT_UPC_E -> "UPC-E"
        Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_AZTEC -> "Aztec"
        else -> "Barcode"
    }
}

/** Runs Gemma 3n on the phone (GPU, falls back to CPU) to write listings from photos. */
class Describer(private val context: Context) : Closeable {
    private var llm: LlmInference? = null
    private var loadedPath: String? = null

    fun isLoaded(path: String) = llm != null && loadedPath == path

    fun ensureLoaded(path: String) {
        if (isLoaded(path)) return
        close()
        llm = try {
            create(path, LlmInference.Backend.GPU)
        } catch (e: Exception) {
            create(path, LlmInference.Backend.CPU)
        }
        loadedPath = path
    }

    private fun create(path: String, backend: LlmInference.Backend): LlmInference =
        LlmInference.createFromOptions(
            context,
            LlmInference.LlmInferenceOptions.builder()
                .setModelPath(path)
                .setMaxTokens(2048)
                .setMaxNumImages(MAX_IMAGES)
                .setPreferredBackend(backend)
                .build(),
        )

    fun describe(images: List<Bitmap>, prompt: String): String {
        val engine = llm ?: error("Model not loaded")
        val options = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(40)
            .setTemperature(0.4f)
            .setGraphOptions(GraphOptions.builder().setEnableVisionModality(true).build())
            .build()
        LlmInferenceSession.createFromOptions(engine, options).use { session ->
            session.addQueryChunk(prompt)
            images.take(MAX_IMAGES).forEach { session.addImage(BitmapImageBuilder(it).build()) }
            return session.generateResponse()
        }
    }

    override fun close() {
        llm?.close()
        llm = null
        loadedPath = null
    }

    companion object {
        const val MAX_IMAGES = 2

        fun prompt(item: Item, ocrText: String, style: String): String = buildString {
            appendLine("You are helping someone sell a used item on Facebook Marketplace. Look at the photo(s) of the item.")
            appendLine()
            appendLine("Text read automatically from the item or its labels (may contain errors):")
            appendLine(ocrText.ifBlank { "(none)" })
            appendLine()
            if (item.codes.isNotEmpty()) {
                appendLine("Barcodes / QR codes scanned from the item:")
                item.codes.forEach { appendLine(it) }
                appendLine("(A QR code may contain the product name, model or a product web link: use what it clearly says. Never guess a product from a barcode number alone.)")
                appendLine()
            }
            appendLine("Seller's condition rating: ${item.condition}")
            appendLine("Seller's notes: ${item.notes.ifBlank { "(none)" }}")
            appendLine()
            appendLine("Write the listing in exactly this format:")
            appendLine("TITLE: <under 70 characters. Brand and model first if known, then the item type and one key detail.>")
            appendLine("DESCRIPTION:")
            appendLine("<2-3 sentences on what it is and what it's good for, then 2-5 short lines starting with \"- \" covering specs, size, what's included, and any visible wear or flaws.>")
            appendLine()
            appendLine("Rules:")
            appendLine("- Only state a brand, model, or spec if you can see it in the photo, read it in the text above, or it is in the seller's notes. Otherwise describe the item generically.")
            appendLine("- Plain, friendly language. No emojis, no hashtags, no price, no pickup or payment details.")
            if (style.isNotBlank()) {
                appendLine()
                appendLine("The seller's own style instructions (follow them, but keep the TITLE/DESCRIPTION format and never invent facts):")
                appendLine(style.trim().take(1000))
            }
        }

        /** Splits the model's answer into (title, description). */
        fun parse(raw: String): Pair<String, String> {
            val text = raw.replace("**", "").trim()
            val title = Regex("""TITLE:\s*(.+)""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.trim().orEmpty()
            val dIdx = text.indexOf("DESCRIPTION:", ignoreCase = true)
            val description = if (dIdx >= 0) {
                text.substring(dIdx + "DESCRIPTION:".length).trim()
            } else {
                text.lines().filterNot { it.trim().startsWith("TITLE:", ignoreCase = true) }.joinToString("\n").trim()
            }
            return title.take(100) to description
        }
    }
}

/**
 * Where the Gemma model file lives: the app's private internal storage. Installing a new
 * build over the old one keeps it, and so does uninstalling with Android's "Keep app data"
 * box ticked (see android:hasFragileUserData in the manifest). Only a file that passed the
 * official SHA-256 check is ever returned by [find].
 */
object ModelFiles {
    fun dir(context: Context): File = File(context.filesDir, "model").apply { mkdirs() }

    /** Older builds kept the model here (deleted on every uninstall). Still read, and moved. */
    fun legacyDir(context: Context): File? = context.getExternalFilesDir(null)

    /** Internal (private) record of the verified file: path, size and modified time. */
    private fun marker(context: Context) = File(context.filesDir, "model.verified")

    private fun fingerprint(f: File) = "${f.absolutePath}|${f.length()}|${f.lastModified()}"

    private fun allDirs(context: Context) = listOfNotNull(dir(context), legacyDir(context), context.filesDir).distinct()

    fun candidates(context: Context): List<File> =
        allDirs(context)
            .flatMap { it.listFiles()?.toList().orEmpty() }
            .filter { it.isFile && it.name.endsWith(".task") && it.length() > 100_000_000 }
            .sortedByDescending { it.lastModified() }

    /** Deletes every model file except [keep]. */
    fun deleteOthers(context: Context, keep: File?) {
        allDirs(context).flatMap { it.listFiles()?.toList().orEmpty() }
            .filter { it.isFile && it.name.endsWith(".task") && it.absolutePath != keep?.absolutePath }
            .forEach { it.delete() }
    }

    fun markVerified(context: Context, f: File) = marker(context).writeText(fingerprint(f))

    private fun isVerified(context: Context, f: File) =
        runCatching { marker(context).readText() == fingerprint(f) }.getOrDefault(false)

    fun find(context: Context): File? = candidates(context).firstOrNull { isVerified(context, it) }

    /** Hashes unverified candidates (slow: ~4 GB each) and records the first official one. */
    fun verifyCandidates(context: Context): File? {
        find(context)?.let { return it }
        for (f in candidates(context)) {
            if (ModelDownloader.isOfficialModel(f)) {
                markVerified(context, f)
                return f
            }
        }
        return null
    }
}
