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
    fun client() = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun linesIn(client: com.google.mlkit.vision.text.TextRecognizer, bmp: Bitmap): List<String> =
        runCatching { client.process(InputImage.fromBitmap(bmp, 0)).await() }
            .getOrNull()?.textBlocks?.flatMap { b -> b.lines.map { it.text.trim() } }.orEmpty()

    fun clean(lines: List<String>): String = lines
        .filter { line ->
            val letters = line.count { it.isLetter() }
            line.length >= 3 && letters >= 2 && line.count { it.isLetterOrDigit() || it == ' ' } >= line.length * 0.8
        }
        .distinctBy { it.lowercase() }
        .take(20)
        .joinToString("\n")
        .take(500)

    suspend fun read(images: List<Bitmap>): String {
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val lines = images.flatMap { bmp ->
                runCatching { client.process(InputImage.fromBitmap(bmp, 0)).await() }
                    .getOrNull()?.textBlocks?.flatMap { b -> b.lines.map { it.text.trim() } }.orEmpty()
            }
            return lines
                .filter { line ->
                    val letters = line.count { it.isLetter() }
                    line.length >= 3 && letters >= 2 && line.count { it.isLetterOrDigit() || it == ' ' } >= line.length * 0.8
                }
                .distinctBy { it.lowercase() }
                .take(20)
                .joinToString("\n")
                .take(500)
        } finally {
            client.close()
        }
    }
}

/** Finds barcodes (EAN/UPC/ISBN, Code 128, …) and QR codes in photos. On-device; no lookup. */
object Codes {
    fun scanner() = BarcodeScanning.getClient()

    suspend fun foundIn(scanner: com.google.mlkit.vision.barcode.BarcodeScanner, bmp: Bitmap): List<String> =
        runCatching { scanner.process(InputImage.fromBitmap(bmp, 0)).await() }.getOrNull().orEmpty().mapNotNull { b ->
            val value = (b.rawValue ?: b.displayValue)?.trim()?.take(300) ?: return@mapNotNull null
            if (value.isEmpty()) null else "${formatName(b.format)}: $value"
        }

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
    private val prefs = context.getSharedPreferences("ai_engine", Context.MODE_PRIVATE)

    /** Some phone GPUs make the model print garbage; once seen, the CPU is used from then on. */
    // Remembered per app build, so a new build (or GPU driver update) gets another try on the fast GPU.
    private val build = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0L)
    var cpuOnly: Boolean
        get() = prefs.getLong("cpuOnlyBuild", -1L) == build
        private set(v) { prefs.edit().putLong("cpuOnlyBuild", if (v) build else -1L).apply() }

    var onGpu = false
        private set

    fun isLoaded(path: String) = llm != null && loadedPath == path

    fun ensureLoaded(path: String) {
        if (isLoaded(path)) return
        close()
        if (!cpuOnly) {
            llm = runCatching { create(path, LlmInference.Backend.GPU) }.getOrNull()
            onGpu = llm != null
        }
        if (llm == null) {
            llm = create(path, LlmInference.Backend.CPU)
            onGpu = false
        }
        loadedPath = path
    }

    /** Switches to the CPU for good (called when GPU output was garbage). */
    fun fallBackToCpu(path: String) {
        cpuOnly = true
        close()
        ensureLoaded(path)
    }

    private fun create(path: String, backend: LlmInference.Backend): LlmInference =
        LlmInference.createFromOptions(
            context,
            LlmInference.LlmInferenceOptions.builder()
                .setModelPath(path)
                // Prompt + one photo (256 tokens) + answer fit in ~1k; a smaller cache is faster.
                .setMaxTokens(1280)
                .setMaxNumImages(MAX_IMAGES)
                .setPreferredBackend(backend)
                .build(),
        )

    fun describe(images: List<Bitmap>, prompt: String): String {
        val engine = llm ?: error("Model not loaded")
        val options = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(20)
            .setTemperature(0.2f) // low: stick to what's in the photos
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
        /** One photo (the cover): each photo costs the model ~256 tokens; the others are still read for text and codes. */
        const val MAX_IMAGES = 1

        fun prompt(item: Item, ocrText: String, style: String, place: String = "", currency: String = ""): String = buildString {
            appendLine("You write product descriptions for second-hand items on Facebook Marketplace. Look carefully at the photo(s) first, work out what the product is, then write the kind of description a good shop would write for it: what it is, what it's for, its key features and specs, then its condition.")
            appendLine("Don't narrate the photo: never write things like \"This is a black leather wallet\" or \"The image shows\". Write about the product itself.")
            if (style.isNotBlank()) {
                appendLine("WRITING STYLE (the seller's own instructions; follow them for tone, length and wording): ${style.trim().take(800)}")
            }
            appendLine("Only state facts you can see, read below, or reliably know about this exact product. If you can't tell the exact product, describe it by type (for example \"genuine leather bifold wallet\") without inventing a brand or model.")
            appendLine()
            appendLine("FACTS")
            if (item.identity.isNotBlank()) appendLine(
                "WHAT THE ITEM IS (typed by the seller; it is the right product but may be abbreviated or misspelled, " +
                    "e.g. \"bose qc45\" = Bose QuietComfort 45; use the correct full product name): ${item.identity.trim().take(200)}"
            )
            appendLine("Condition (from the seller): ${item.condition}")
            if (item.notes.isNotBlank()) appendLine("Seller's notes (true): ${item.notes.trim().take(600)}")
            if (item.product.isNotBlank()) appendLine("Product facts found online (probably right; prefer what you see in the photo if they disagree): ${item.product}")
            val qr = item.codes.filter { it.startsWith("QR:") }
            if (qr.isNotEmpty()) appendLine("QR code text: ${qr.joinToString(" | ").take(300)}")
            if (ocrText.isNotBlank()) {
                appendLine("Words printed on the item or its labels (machine-read, may contain mistakes; ignore anything that isn't a real word, brand or model number):")
                appendLine(ocrText)
            }
            if (place.isNotBlank()) appendLine("Seller location: $place (use the spelling and units normal there)")
            appendLine()
            appendLine(if (style.isNotBlank()) "EXAMPLE of the format only (a different item; use the seller's WRITING STYLE, not this tone):" else "EXAMPLE of the format and tone (a different item):")
            appendLine("TITLE: Bose QuietComfort 45 noise cancelling headphones, black")
            appendLine("DESCRIPTION:")
            appendLine("Wireless over-ear headphones with Bose's excellent active noise cancelling and up to 24 hours of battery life. Light and comfortable for long flights, commutes or working from home, with Aware mode when you need to hear what's around you.")
            appendLine("- Bluetooth 5.1, USB-C charging (15 min charge = 3 hours)")
            appendLine("- Includes carry case, USB-C cable and audio cable")
            appendLine("- Fully working, light wear on the headband")
            appendLine("IDENTIFIED: yes")
            appendLine("MSRP: 329")
            appendLine()
            appendLine("Now write the listing for the item in the photo(s), in exactly that format:")
            appendLine("TITLE: under 70 characters; brand and model first if known, then what it is and one key detail")
            appendLine("DESCRIPTION: two or three sentences selling the product (what it is, what it's good for, standout features), then 2 to 5 lines starting with \"- \" (key specs, what's included, condition and any visible wear)")
            appendLine("IDENTIFIED: yes if you know the exact brand and model or title, otherwise no")
            appendLine("MSRP: the product's original retail price when new as a plain number" + (if (currency.isNotBlank()) " in $currency" else "") + ", only if you know this exact product; otherwise unknown")
            appendLine()
            appendLine("Rules: English only. No emojis or hashtags. No prices, pickup or payment details in the title or description. Never invent a brand, model, size or spec.")
            if (style.isNotBlank()) {
                appendLine("Remember the seller's WRITING STYLE: ${style.trim().take(800)}")
            }
        }

        /** Text-only rewrite of an existing listing in another tone (fast: no photo to look at). */
        fun rewritePrompt(title: String, description: String, tone: String): String = buildString {
            appendLine("Rewrite this Facebook Marketplace listing in a different tone.")
            appendLine("Tone wanted: ${tone.trim().take(400)}")
            appendLine("Keep every fact exactly (brand, model, size, condition, flaws, what's included). Do not add any new facts, prices, pickup or payment details. English only, no emojis or hashtags.")
            appendLine()
            appendLine("LISTING:")
            appendLine("TITLE: ${title.trim().take(120)}")
            appendLine("DESCRIPTION:")
            appendLine(description.trim().take(2500))
            appendLine()
            appendLine("Answer in exactly this format:")
            appendLine("TITLE: <rewritten title, under 70 characters>")
            appendLine("DESCRIPTION: <rewritten description; keep short \"- \" lines for the details>")
        }

        data class Listing(val title: String, val description: String, val identified: Boolean, val msrp: Double? = null)

        /** Splits the model's answer into title, description and whether it knew the product. */
        fun parse(raw: String): Listing {
            val idLine = Regex("""(?im)^\s*IDENTIFIED:\s*(\w+).*$""")
            val msrpLine = Regex("""(?im)^\s*MSRP:\s*(.*)$""")
            val identified = idLine.find(raw)?.groupValues?.get(1)?.lowercase()?.startsWith("y") ?: true
            val msrp = msrpLine.find(raw)?.groupValues?.get(1)
                ?.let { Regex("""[0-9][0-9,]*(\.[0-9]+)?""").find(it)?.value }
                ?.replace(",", "")?.toDoubleOrNull()?.takeIf { it > 0 && it < 1_000_000 }
            val text = raw.replace("**", "").replace(idLine, "").replace(msrpLine, "").trim()
            val title = Regex("""TITLE:\s*(.+)""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.trim().orEmpty()
            val dIdx = text.indexOf("DESCRIPTION:", ignoreCase = true)
            val description = if (dIdx >= 0) {
                text.substring(dIdx + "DESCRIPTION:".length).trim()
            } else {
                text.lines().filterNot { it.trim().startsWith("TITLE:", ignoreCase = true) }.joinToString("\n").trim()
            }
            return Listing(title.take(100), description, identified, msrp)
        }

        /**
         * Catches garbage output: missing parts, repetition loops, made-up "words" and stray
         * symbols. A broken answer is retried (on the CPU), never saved.
         */
        fun looksBroken(l: Listing): Boolean {
            val text = l.title + "\n" + l.description
            if (l.title.length < 4 || l.description.length < 30) return true
            val words = Regex("[A-Za-z]{2,}").findAll(text).map { it.value.lowercase() }.toList()
            if (words.size < 8) return true
            if (words.toSet().size < words.size * 0.35) return true // stuck repeating itself
            val noVowel = words.count { w -> w.length >= 5 && w.none { it in "aeiouy" } }
            if (noVowel > words.size * 0.12) return true
            val odd = text.count { !(it.isLetterOrDigit() || it.isWhitespace() || it in ".,;:!?'\"()-–—/&%+°×#*\u2019") }
            if (odd > text.length * 0.06) return true
            val lines = l.description.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size >= 4 && lines.toSet().size <= lines.size / 2) return true
            return false
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
