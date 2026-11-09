package com.listingprep.ui

import android.app.Application
import com.listingprep.ml.DownloadState
import com.listingprep.ml.ModelDownloadService
import com.listingprep.ml.ModelDownloader
import kotlinx.coroutines.delay
import android.content.ContentUris
import android.content.Intent
import android.content.IntentSender
import android.provider.MediaStore
import com.listingprep.data.isActive
import com.listingprep.data.Regions
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.listingprep.media.Exporter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.listingprep.data.Item
import com.listingprep.data.Settings
import com.listingprep.data.Status
import com.listingprep.data.Store
import com.listingprep.data.TokenVault
import com.listingprep.media.MediaInfo
import com.listingprep.ml.Analyzed
import com.listingprep.ml.Describer
import com.listingprep.ml.Embedder
import com.listingprep.ml.Grouper
import com.listingprep.ml.ModelFiles
import com.listingprep.ml.Ocr
import com.listingprep.ml.Codes
import com.listingprep.ml.ProductLookup
import com.listingprep.ml.PriceLookup
import com.listingprep.ml.Gemini
import com.listingprep.data.Photo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import android.graphics.Bitmap
import kotlinx.coroutines.Job
import com.listingprep.ml.WritingService
import com.listingprep.update.UpdateState
import com.listingprep.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class UiState(
    val items: List<Item> = emptyList(),
    val settings: Settings = Settings(),
    val modelPath: String? = null,
    val busy: String? = null,
    val progress: Float? = null,
    val generatingId: String? = null,
    val error: String? = null,
    val hasHfToken: Boolean = false,
    /** Set after a manual import: the original file the user may want deleted. */
    val offerDeleteImportSource: String? = null,
    /** Non-null while the model is downloading in the background. */
    val download: DownloadUi? = null,
    /** "Item deleted · Undo" bar; [UndoUi.stamp] changes for every new action. */
    val undo: UndoUi? = null,
    /** App update status (checking, downloading, ready to install). */
    val update: UpdateUi? = null,
    /** Pop-up for a manual "Check for updates": open, and its result text when there's no update to show. */
    val updatePopup: Boolean = false,
    val updateNote: String? = null,
    val hasPriceKey: Boolean = false,
    /** Result of "Find second-hand price" for one item. */
    val priceHint: PriceHint? = null,
    /** Shown once after the app updated itself: the new build and what changed. */
    val justUpdated: UpdateUi? = null,
) {
    /** Descriptions can be written: the on-phone model is there, or Google writing is on with a key. */
    val canWrite: Boolean get() = modelPath != null || (settings.cloudWriting && hasPriceKey)
}

data class PriceHint(val itemId: String, val text: String, val value: String?, val sources: List<String> = emptyList())

data class UpdateUi(
    val build: Int,
    val label: String,
    val progress: Float? = null,
    val ready: Boolean = false,
    val notes: String = "",
    /** Found but not downloaded (auto-update is off). */
    val offerOnly: Boolean = false,
)

data class UndoUi(val message: String, val stamp: Long = System.nanoTime())

data class DownloadUi(val label: String, val progress: Float?)

enum class MoveTarget { PREVIOUS, NEXT, NEW }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Application get() = getApplication()
    private val store = Store(app)
    private val describer = Describer(app)
    private val workMutex = Mutex()
    private val saveDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val _state = MutableStateFlow(
        UiState(
            items = store.loadItems(),
            settings = store.loadSettings(),
            modelPath = ModelFiles.find(app)?.absolutePath,
            hasHfToken = !store.hfToken.isNullOrBlank(),
            hasPriceKey = !store.geminiKey.isNullOrBlank(),
        )
    )
    val state = _state.asStateFlow()

    init {
        // Pick up a download that was running (or finished) while the app was closed.
        if (store.modelDownloadId >= 0) {
            // Left over from builds that used Android's DownloadManager.
            ModelDownloader.removeLegacyDownload(app, store.modelDownloadId)
            store.modelDownloadId = -1
        }
        watchDownload()
        moveModelToKeptStorage()
        viewModelScope.launch { WritingService.stopRequests.collect { if (it > 0) stopWriting() } }
        viewModelScope.launch { UpdateState.error.collect { e -> if (e != null) { showError(e); UpdateState.error.value = null } } }
        announceUpdate()
        // Nothing is mid-install at launch, so any downloaded update file is leftover: remove it.
        viewModelScope.launch(Dispatchers.IO) { Updater.apkFile(ctx).parentFile?.deleteRecursively() }
        if (System.currentTimeMillis() - store.lastUpdateCheck > 30 * 60 * 1000L) checkForUpdate(manual = false)
        // Leftover from builds that had a "Share photos" button.
        viewModelScope.launch(Dispatchers.IO) {
            File(app.cacheDir, "share").deleteRecursively()
            // Half-finished download from build 15, which saved to the old folder.
            ModelFiles.legacyDir(app)?.listFiles()?.filter { it.name.endsWith(".download") }?.forEach { it.delete() }
            // In-app photos no item refers to (left over if the app closed during an Undo window).
            val used = store.loadItems().flatMap { it.photos }.flatMap { listOfNotNull(it.uri, it.cutout) }.toSet()
            val cutoff = System.currentTimeMillis() - 10 * 60 * 1000L
            cameraPhotoDir(app).listFiles()
                ?.filter { Uri.fromFile(it).toString() !in used && it.lastModified() < cutoff }
                ?.forEach { it.delete() }
        }
    }

    // ---- item edits -------------------------------------------------------

    private fun setItems(transform: (List<Item>) -> List<Item>) {
        val items = _state.updateAndGet { it.copy(items = transform(it.items)) }.items
        viewModelScope.launch(saveDispatcher) { store.saveItems(items) }
    }

    fun updateItem(id: String, transform: (Item) -> Item) =
        setItems { items -> items.map { if (it.id == id) transform(it) else it } }

    // ---- undo -------------------------------------------------------------
    //
    // Destructive edits apply at once and show an Undo bar. File clean-up (deleting in-app
    // photos, Facebook copies, photo grants) waits until the bar is gone, so Undo is complete.

    private var undoBefore: List<Pair<Int, Item>> = emptyList()
    private var pendingCleanup: (suspend () -> Unit)? = null
    private var undoTimer: Job? = null

    private fun undoable(message: String, ids: Collection<String>, cleanup: (suspend () -> Unit)? = null, change: () -> Unit) {
        commitUndo()
        undoBefore = _state.value.items.withIndex().filter { it.value.id in ids }.map { it.index to it.value }
        change()
        pendingCleanup = cleanup
        _state.update { it.copy(undo = UndoUi(message)) }
        undoTimer = viewModelScope.launch {
            delay(6_000)
            undoTimer = null
            commitUndo()
            _state.update { it.copy(undo = null) }
        }
    }

    /** Makes the last action final: runs its clean-up now. */
    private fun commitUndo() {
        undoTimer?.cancel()
        undoTimer = null
        undoBefore = emptyList()
        val cleanup = pendingCleanup ?: return
        pendingCleanup = null
        CoroutineScope(Dispatchers.IO).launch { cleanup() }
    }

    fun undo() {
        undoTimer?.cancel()
        undoTimer = null
        pendingCleanup = null
        val before = undoBefore
        undoBefore = emptyList()
        _state.update { it.copy(undo = null) }
        if (before.isEmpty()) return
        val ids = before.map { it.second.id }.toSet()
        setItems { current ->
            current.filterNot { it.id in ids }.toMutableList().apply {
                before.sortedBy { it.first }.forEach { (i, item) -> add(i.coerceAtMost(size), item) }
            }
        }
    }

    fun hideUndo() = _state.update { it.copy(undo = null) }

    // ---- status ---------------------------------------------------------------

    private fun setStatus(ids: List<String>, message: String, cleanup: (suspend () -> Unit)? = null, transform: (Item) -> Item) {
        if (ids.isEmpty()) return
        undoable(message, ids, cleanup) { setItems { items -> items.map { if (it.id in ids) transform(it) else it } } }
    }

    private fun count(n: Int, one: String) = if (n == 1) one else "$n items"

    fun markSold(id: String) = markSoldAll(listOf(id))
    fun markSoldAll(ids: List<String>) =
        setStatus(ids, "${count(ids.size, "Item")} marked sold") { it.copy(status = Status.SOLD) }

    fun markActive(id: String) = markActiveAll(listOf(id))
    fun markActiveAll(ids: List<String>) = setStatus(ids, "${count(ids.size, "Item")} moved back to To post") {
        it.copy(status = if (it.description.isBlank()) Status.DRAFT else Status.READY)
    }

    /** Marks posted and (after the Undo window) removes the copies saved to Pictures/ListingPrep. */
    fun markPosted(id: String) = markPostedAll(listOf(id))
    fun markPostedAll(ids: List<String>) {
        val copies = _state.value.items.filter { it.id in ids }.flatMap { it.exportedUris }
        setStatus(ids, "${count(ids.size, "Item")} marked posted", cleanup = { Exporter.deleteExported(ctx, copies) }) {
            it.copy(status = Status.POSTED, exportedUris = emptyList())
        }
    }

    /** Gallery originals of these items, as MediaStore links Android can delete (camera shots excluded). */
    fun galleryOriginals(ids: List<String>): List<Uri> =
        _state.value.items.filter { it.id in ids }.flatMap { it.photos }.mapNotNull { p ->
            val uri = Uri.parse(p.uri)
            if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) return@mapNotNull null
            // Photo picker links end in the MediaStore id of the local photo.
            val id = uri.lastPathSegment?.toLongOrNull() ?: return@mapNotNull null
            ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), id)
        }.distinct()

    fun photoCount(ids: List<String>) =
        _state.value.items.filter { it.id in ids }.sumOf { item -> item.photos.count { it.uri.startsWith("file:") } }

    /** Android's own "Allow Listing Prep to delete these photos?" dialog, or null if it can't be built. */
    fun galleryDeleteRequest(uris: List<Uri>): IntentSender? =
        if (uris.isEmpty()) null
        else runCatching { MediaStore.createDeleteRequest(ctx.contentResolver, uris).intentSender }.getOrNull()

    /** Deletes items; their photos and copies are cleaned up once Undo is no longer offered. */
    fun deleteItems(ids: List<String>, allowUndo: Boolean = true) {
        val gone = _state.value.items.filter { it.id in ids }
        if (gone.isEmpty()) return
        val cleanup: suspend () -> Unit = {
            gone.forEach { Exporter.deleteExported(ctx, it.exportedUris) }
            val used = _state.value.items.flatMap { it.photos }.flatMap { it.files() }.toSet()
            releasePhotoAccess(gone.flatMap { it.photos }.flatMap { it.files() }.filterNot { it in used })
        }
        if (_state.value.generatingId in ids) currentDescribe?.cancel()
        val change = { setItems { items -> items.filterNot { it.id in ids } } }
        if (allowUndo) {
            undoable(if (gone.size == 1) "Item deleted" else "${gone.size} items deleted", ids, cleanup, change)
        } else {
            commitUndo()
            change()
            CoroutineScope(Dispatchers.IO).launch { cleanup() }
        }
    }

    fun deleteItem(id: String) = deleteItems(listOf(id))

    /**
     * Lets go of photos no item uses any more: in-app camera shots are deleted, and gallery
     * photos get their long-term read access (granted by the picker) handed back.
     */
    private fun releasePhotoAccess(uris: List<String>) {
        val ownDir = cameraPhotoDir(ctx).canonicalPath
        uris.forEach {
            val uri = Uri.parse(it)
            if (uri.scheme == "file") {
                val f = File(uri.path ?: return@forEach)
                if (f.canonicalPath.startsWith(ownDir)) f.delete()
            } else runCatching {
                ctx.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private fun releaseUnused(uris: List<String>) {
        val used = _state.value.items.flatMap { it.photos }.flatMap { it.files() }.toSet()
        val gone = uris.filterNot { it in used }
        if (gone.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) { releasePhotoAccess(gone) }
    }

    private fun Photo.files() = listOfNotNull(uri, cutout)

    /** Stores the white-background version of a photo; the original stays for "Restore". */
    fun setCutout(id: String, index: Int, cutout: File?) {
        val old = _state.value.items.find { it.id == id }?.photos?.getOrNull(index)?.cutout
        updateItem(id) { item ->
            item.copy(photos = item.photos.mapIndexed { i, p -> if (i == index) p.copy(cutout = cutout?.let { Uri.fromFile(it).toString() }) else p })
        }
        if (old != null) releaseUnused(listOf(old))
    }

    /** Photos from the in-app camera, already grouped by item (one group per "Next item"). */
    fun addCameraItems(groups: List<List<File>>): List<String> {
        if (groups.isEmpty()) return emptyList()
        val condition = _state.value.settings.defaultCondition
        val newItems = groups.map { files ->
            Item(
                photos = files.map { Photo(Uri.fromFile(it).toString(), it.lastModified(), 0.0) },
                condition = condition,
            )
        }
        setItems { it + newItems }
        return newItems.map { it.id }
    }

    /** After shooting: write the new items' descriptions in the background, if that's on. */
    fun autoWrite() {
        if (_state.value.settings.autoWrite && _state.value.canWrite) generateAll()
    }

    fun setCover(id: String, index: Int) = updateItem(id) { item ->
        val p = item.photos.getOrNull(index) ?: return@updateItem item
        item.copy(photos = listOf(p) + item.photos.filterIndexed { i, _ -> i != index })
    }

    fun removePhoto(id: String, index: Int) {
        val photo = _state.value.items.find { it.id == id }?.photos?.getOrNull(index) ?: return
        val cleanup: suspend () -> Unit = {
            val used = _state.value.items.flatMap { it.photos }.flatMap { it.files() }.toSet()
            releasePhotoAccess(photo.files().filterNot { it in used })
        }
        undoable("Photo removed", listOf(id), cleanup) {
            setItems { items ->
                items.mapNotNull { item ->
                    if (item.id != id) item
                    else item.copy(photos = item.photos.filterIndexed { i, _ -> i != index }).takeIf { it.photos.isNotEmpty() }
                }
            }
        }
    }

    fun movePhoto(id: String, index: Int, target: MoveTarget) = setItems { items ->
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return@setItems items
        val src = items[i]
        val photo = src.photos.getOrNull(index) ?: return@setItems items
        val list = items.toMutableList()
        when (target) {
            MoveTarget.PREVIOUS -> {
                if (i == 0) return@setItems items
                list[i - 1] = list[i - 1].let { it.copy(photos = it.photos + photo) }
            }
            MoveTarget.NEXT -> {
                if (i == items.lastIndex) return@setItems items
                list[i + 1] = list[i + 1].let { it.copy(photos = it.photos + photo) }
            }
            MoveTarget.NEW -> list.add(i + 1, Item(photos = listOf(photo), condition = src.condition))
        }
        val remaining = src.photos.filterIndexed { j, _ -> j != index }
        if (remaining.isEmpty()) list.removeAt(i) else list[i] = src.copy(photos = remaining)
        list
    }

    fun mergeWithNext(id: String) {
        val items = _state.value.items
        val i = items.indexOfFirst { it.id == id }
        items.getOrNull(i + 1)?.let { mergeAll(listOf(id, it.id)) }
    }

    fun mergeWith(id: String, otherId: String) = mergeAll(listOf(id, otherId))

    /** Merges all [ids] (photos and notes) into the first one. */
    fun mergeAll(ids: List<String>) {
        if (ids.size < 2) return
        undoable("${ids.size} items merged", ids) {
            setItems { items ->
                val parts = ids.mapNotNull { id -> items.find { it.id == id } }
                if (parts.size < 2) return@setItems items
                val first = parts.first()
                val merged = first.copy(
                    photos = parts.flatMap { it.photos }.distinctBy { it.uri },
                    notes = parts.map { it.notes }.filter { it.isNotBlank() }.joinToString("; "),
                    codes = parts.flatMap { it.codes }.distinct(),
                )
                val drop = parts.drop(1).map { it.id }.toSet()
                items.filterNot { it.id in drop }.map { if (it.id == first.id) merged else it }
            }
        }
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        val s = _state.updateAndGet { it.copy(settings = transform(it.settings)) }.settings
        store.saveSettings(s)
    }

    fun showError(message: String) = _state.update { it.copy(error = message) }
    fun clearError() = _state.update { it.copy(error = null) }

    // ---- background work (one job at a time) -----------------------------

    private fun work(label: String, block: suspend () -> Unit) = viewModelScope.launch {
        workMutex.withLock {
            _state.update { it.copy(busy = label, progress = null, error = null) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _state.update { it.copy(busy = null, progress = null, generatingId = null) }
            }
        }
    }

    private fun progress(label: String, value: Float?) =
        _state.update { it.copy(busy = label, progress = value) }

    fun importPhotos(uris: List<Uri>) = work("Analyzing photos…") {
        val known = _state.value.items.flatMap { it.photos }.map { it.uri }.toSet()
        val fresh = uris.filter { it.toString() !in known }
        if (fresh.isEmpty()) {
            showError("Those photos are already in your items.")
            return@work
        }
        val cr = ctx.contentResolver
        fresh.forEach {
            runCatching { cr.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        val analyzed = withContext(Dispatchers.Default) {
            Embedder(ctx).use { embedder ->
                fresh.mapIndexedNotNull { i, uri ->
                    progress("Analyzing photo ${i + 1} of ${fresh.size}", (i + 1f) / fresh.size)
                    runCatching {
                        val bmp = MediaInfo.decode(cr, uri, 512)
                        val photo = Photo(uri.toString(), MediaInfo.takenAt(cr, uri), MediaInfo.sharpness(bmp))
                        Analyzed(photo, embedder.embed(bmp)).also { bmp.recycle() }
                    }.getOrNull()
                }
            }
        }
        val condition = _state.value.settings.defaultCondition
        val newItems = Grouper.group(analyzed).map { g ->
            Item(photos = Grouper.orderWithCover(g.map { it.photo }), condition = condition)
        }
        setItems { it + newItems }
    }

    fun generate(id: String) { writingJob = work("Writing description…") { generateOne(id) } }

    fun generateAll() { writingJob = generateAllJob() }

    private fun generateAllJob() = work("Writing descriptions…") {
        val ids = _state.value.items
            .filter { it.description.isBlank() && it.isActive }
            .map { it.id }
        if (ids.isEmpty()) return@work
        // Keeps going if you leave the app; a notification says when it's done.
        WritingService.start(ctx, "Writing description 1 of ${ids.size}…")
        var done = 0
        try {
            var failed: String? = null
            var next: Deferred<Analysis>? = null
            ids.forEachIndexed { i, id ->
                val ready = next
                // Start reading the next item's photos now, while the model writes this one.
                next = ids.getOrNull(i + 1)?.let { nid ->
                    _state.value.items.find { it.id == nid }?.let { nextItem -> viewModelScope.async { analyze(nextItem) } }
                }
                if (_state.value.items.none { it.id == id }) { ready?.cancel(); return@forEachIndexed } // deleted meanwhile
                progress("Writing description ${i + 1} of ${ids.size}", i.toFloat() / ids.size)
                WritingService.update(ctx, "Writing description ${i + 1} of ${ids.size}…")
                try {
                    generateOne(id, ready)
                    done++
                } catch (e: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw e // Stop pressed
                    // Only this item was cancelled (deleted while writing): carry on with the rest.
                } catch (e: Exception) {
                    failed = e.message
                }
                _state.update { it.copy(generatingId = null) }
            }
            failed?.let { showError(it) }
        } finally {
            WritingService.finish(
                ctx,
                if (done > 0) "$done description${if (done == 1) "" else "s"} ready. Check them before posting." else null,
            )
        }
    }

    // ---- second-hand price search (Google AI search) ---------------------------

    fun savePriceKey(key: String) {
        store.geminiKey = key.trim().ifBlank { null }
        _state.update { it.copy(hasPriceKey = !store.geminiKey.isNullOrBlank()) }
    }

    fun findPrice(id: String) = work("Searching second-hand prices with Google…") {
        val item = _state.value.items.find { it.id == id } ?: return@work
        val key = store.geminiKey ?: return@work
        val settings = _state.value.settings
        val region = Regions.current(settings)
        val result = withContext(Dispatchers.IO) {
            runCatching { PriceLookup.estimate(key, item.title, item.condition, Regions.describe(settings), region.currency) }
        }
        val hint = result.fold(
            onSuccess = { e ->
                val p = e.price
                if (p == null) PriceHint(id, e.summary.ifBlank { "Google couldn't find reliable second-hand prices for this. Try brand + model in the title." }, null, e.sources)
                else {
                    val cur = e.currency ?: region.currency
                    val range = if (e.low != null && e.high != null) " (usually ${PriceLookup.round(e.low)}–${PriceLookup.round(e.high)})" else ""
                    val other = region.currency.isNotBlank() && cur != region.currency
                    val text = "Typical used price: $cur ${PriceLookup.round(p)}$range.\n${e.summary}" +
                        if (other) "\nNote: the prices found are in $cur, not ${region.currency}." else ""
                    PriceHint(id, text, if (other) null else PriceLookup.round(p), e.sources)
                }
            },
            onFailure = { e ->
                PriceHint(
                    id,
                    if (e is Gemini.BadKey) "Google didn't accept the API key. Check it in Settings → Google AI."
                    else "Price search failed: ${e.message}",
                    null,
                )
            },
        )
        val value = hint.value
        if (value != null) {
            undoable("Price set to ${region.symbol}$value", listOf(id)) { updateItem(id) { it.copy(price = value) } }
            _state.update { it.copy(priceHint = hint.copy(value = null, text = "Filled in: " + hint.text)) }
        } else {
            _state.update { it.copy(priceHint = hint) }
        }
    }

    fun dismissPriceHint() = _state.update { it.copy(priceHint = null) }

    // ---- running the model (stoppable) --------------------------------------

    private val describeLock = Mutex()
    @Volatile private var currentDescribe: Deferred<String>? = null
    private var writingJob: Job? = null

    /**
     * Runs the model. Stop and Delete cancel it right away; the native call can't be
     * interrupted, so it finishes in the background and its answer is thrown away.
     */
    private suspend fun describeOnce(images: List<Bitmap>, prompt: String): String {
        val d = CoroutineScope(Dispatchers.Default).async { describeLock.withLock { describer.describe(images, prompt) } }
        currentDescribe = d
        try {
            return d.await()
        } finally {
            if (currentDescribe === d) currentDescribe = null
        }
    }

    /** One listing, checked for garbage; retried on the CPU if the GPU produced nonsense. */
    private suspend fun writeListing(path: String, images: List<Bitmap>, prompt: String) = run {
        var listing = Describer.parse(describeOnce(images, prompt))
        if (Describer.looksBroken(listing) && describer.onGpu) {
            progress("The GPU gave a garbled answer; retrying on the CPU (slower, but reliable)…", _state.value.progress)
            describeLock.withLock { describer.fallBackToCpu(path) }
            listing = Describer.parse(describeOnce(images, prompt))
        }
        listing.takeUnless { Describer.looksBroken(it) }
    }

    /** Rewrites the title and description in another tone; Undo brings the old text back. */
    fun rewriteTone(id: String, tone: String) {
        writingJob = work("Rewriting in a new tone…") {
            val item = _state.value.items.find { it.id == id } ?: return@work
            if (item.description.isBlank()) return@work
            val key = store.geminiKey?.takeIf { _state.value.settings.cloudWriting }
            val prompt = Describer.rewritePrompt(item.title, item.description, tone)
            _state.update { it.copy(generatingId = id) }
            val fromCloud = key?.let {
                runCatching {
                    withContext(Dispatchers.IO) { Gemini.generate(it, listOf(Gemini.textPart(prompt)), search = false, temperature = 0.5) }
                }.getOrNull()?.let { a -> Describer.parse(a.text).takeUnless { l -> Describer.looksBroken(l) } }
            }
            val path = _state.value.modelPath
            if (fromCloud == null && path == null) throw IllegalStateException("No AI model yet. Add one in Settings, or turn on writing with Google.")
            val listing = fromCloud ?: withContext(Dispatchers.Default) {
                if (!describer.isLoaded(path!!)) {
                    progress("Loading AI model…", null)
                    describeLock.withLock { describer.ensureLoaded(path) }
                    progress("Rewriting in a new tone…", null)
                }
                writeListing(path, emptyList(), prompt)
            } ?: throw IllegalStateException("The rewrite didn't come out right, so your text was kept. Try again or pick another tone.")
            undoable("Rewritten in a new tone", listOf(id)) {
                updateItem(id) { it.copy(title = listing.title.ifBlank { it.title }, description = listing.description, checked = false) }
            }
        }
    }

    /**
     * Item page "refresh": reads the photos again (text, barcodes, online barcode info), rewrites
     * the title and description from scratch, and searches the price again.
     */
    fun refreshItem(id: String) {
        val before = _state.value.items.find { it.id == id } ?: return
        updateItem(id) { it.copy(product = "", checked = false) }
        writingJob = work("Refreshing this item…") {
            try {
                generateOne(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateItem(id) { it.copy(product = before.product) }
                throw e
            }
        }
        if (store.geminiKey != null) findPrice(id) // queued: runs right after the rewrite
    }

    /** Stops writing descriptions (the one in progress is discarded). */
    fun stopWriting() {
        writingJob?.cancel()
        currentDescribe?.cancel()
    }

    /** Clears an AI description (and title) so you can write your own or try the AI again. */
    fun clearDescription(id: String) =
        undoable("Description deleted", listOf(id)) {
            updateItem(id) { it.copy(title = "", description = "", status = if (it.isActive) Status.DRAFT else it.status, checked = false) }
        }

    /** What the photos say, worked out before the model runs (text, codes, the cover for the AI). */
    private class Analysis(val ocr: String, val codes: List<String>, val cover: Bitmap?)

    /**
     * Each photo is decoded once and read for text and codes, three at a time. Runs on the CPU,
     * so for a batch it overlaps with the model writing the previous item on the GPU.
     */
    private suspend fun analyze(item: Item): Analysis = withContext(Dispatchers.Default) {
        val cr = ctx.contentResolver
        val ocr = Ocr.client()
        val scanner = Codes.scanner()
        try {
            val gate = Semaphore(3)
            val parts = item.photos.take(12).mapIndexed { i, p ->
                async {
                    gate.withPermit {
                        val bmp = runCatching { MediaInfo.decode(cr, Uri.parse(p.uri), 1600) }.getOrNull() ?: return@withPermit null
                        val codes = Codes.foundIn(scanner, bmp)
                        val text = if (i < 6) Ocr.linesIn(ocr, bmp) else emptyList()
                        val cover = if (i == 0) {
                            val s = 768f / maxOf(bmp.width, bmp.height)
                            if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false)
                        } else null
                        bmp.recycle()
                        Triple(text, codes, cover)
                    }
                }
            }.awaitAll()
            Analysis(
                ocr = Ocr.clean(parts.flatMap { it?.first.orEmpty() }),
                codes = parts.flatMap { it?.second.orEmpty() }.distinct().take(8),
                cover = parts.firstOrNull()?.third,
            )
        } finally {
            ocr.close()
            scanner.close()
        }
    }

    /** Loads the model in the background (e.g. while you're still taking photos) so writing starts at once. */
    fun warmUp() {
        val path = _state.value.modelPath ?: return
        if (describer.isLoaded(path)) return
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { describeLock.withLock { describer.ensureLoaded(path) } }
        }
    }

    private suspend fun generateOne(id: String, ready: Deferred<Analysis>? = null) {
        val item = _state.value.items.find { it.id == id } ?: return
        val settings = _state.value.settings
        val key = store.geminiKey?.takeIf { settings.cloudWriting }
        val path = _state.value.modelPath
        if (key == null && path == null) throw IllegalStateException("No AI model yet. Add one in Settings, or turn on writing with Google.")
        _state.update { it.copy(generatingId = id) }
        val label = _state.value.busy
        val listing = withContext(Dispatchers.Default) {
            val pending = ready ?: async { analyze(item) } // reads photos while the model loads
            val analysis = pending.await()
            val codes = analysis.codes
            if (codes != item.codes) updateItem(id) { it.copy(codes = codes) }
            val style = settings.writingStyle
            val place = Regions.describe(settings)
            var known = item.copy(codes = codes)
            var cloudFailed: String? = null

            // One attempt with whichever engine is in use; Google falls back to the phone if it can't answer.
            suspend fun attempt(): Describer.Companion.Listing? {
                val prompt = Describer.prompt(known, analysis.ocr, style, place)
                if (key != null && cloudFailed == null) {
                    try {
                        return cloudListing(key, item, prompt)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        cloudFailed = when (e) {
                            is Gemini.RateLimited -> "Google's free limit was reached"
                            is Gemini.BadKey -> "Google didn't accept the API key"
                            else -> "Google couldn't be reached (${e.message})"
                        }
                        if (path == null) throw IllegalStateException("$cloudFailed, and there's no on-phone model to fall back on.")
                        progress("$cloudFailed, so this one is written on the phone…", _state.value.progress)
                    }
                }
                val p = path ?: return null
                if (!describer.isLoaded(p)) {
                    progress("Loading AI model (first time takes a few seconds)…", null)
                    describeLock.withLock { describer.ensureLoaded(p) }
                    progress(label ?: "Writing description…", _state.value.progress)
                }
                val local = writeListing(p, listOfNotNull(analysis.cover), prompt) ?: return null
                if (style.isBlank()) return local
                // Small on-phone models often ignore the tone; a short text-only pass applies it.
                progress("Applying your writing style…", _state.value.progress)
                return writeListing(p, emptyList(), Describer.rewritePrompt(local.title, local.description, style))
                    ?.copy(identified = local.identified) ?: local
            }

            var result = attempt()
            // The AI couldn't tell what it is: look the barcode number up online (if allowed) and try once more.
            val code = ProductLookup.retailCode(codes)
            if (result != null && !result.identified && code != null && known.product.isBlank() && settings.onlineLookup) {
                progress("Looking up the barcode online…", _state.value.progress)
                val product = withContext(Dispatchers.IO) { runCatching { ProductLookup.lookup(code) }.getOrNull() }
                if (product != null) {
                    updateItem(id) { it.copy(product = product) }
                    known = known.copy(product = product)
                    progress(label ?: "Writing description…", _state.value.progress)
                    result = attempt() ?: result
                }
            }
            analysis.cover?.recycle()
            result ?: throw IllegalStateException(
                "The AI's answer didn't make sense, so it wasn't saved. Add a few words in Notes " +
                    "(what the item is) and tap Write description again."
            )
        }
        updateItem(id) {
            it.copy(
                title = listing.title.ifBlank { it.title },
                description = listing.description,
                status = if (it.isActive) Status.READY else it.status,
            )
        }
        _state.update { it.copy(generatingId = null) }
    }

    /**
     * Writes with Google Gemini: the same instructions as on the phone, plus up to three photos
     * (cover first) re-encoded as fresh JPEGs, so no EXIF or location goes along. Opt-in only.
     */
    private suspend fun cloudListing(key: String, item: Item, prompt: String): Describer.Companion.Listing? {
        val cr = ctx.contentResolver
        val photos = item.photos.take(3).mapNotNull { p ->
            runCatching { MediaInfo.decode(cr, Uri.parse(p.shown), 1024) }.getOrNull()
        }
        val parts = photos.map { bmp -> Gemini.imagePart(bmp).also { bmp.recycle() } } + Gemini.textPart(prompt)
        val answer = withContext(Dispatchers.IO) { Gemini.generate(key, parts, search = false, temperature = 0.3) }
        return Describer.parse(answer.text).takeUnless { Describer.looksBroken(it) }
    }

    /** Reads barcodes / QR codes from every photo (the code is often on just one angle). */
    private suspend fun scanCodesIn(uris: List<Uri>): List<String> {
        val cr = ctx.contentResolver
        val found = mutableListOf<String>()
        uris.take(16).forEach { uri ->
            val bmp = runCatching { MediaInfo.decode(cr, uri, 1600) }.getOrNull() ?: return@forEach
            found += Codes.read(listOf(bmp))
            bmp.recycle()
        }
        return found.distinct().take(8)
    }

    fun scanCodes(id: String) = work("Looking for barcodes and QR codes…") {
        val item = _state.value.items.find { it.id == id } ?: return@work
        val codes = withContext(Dispatchers.Default) { scanCodesIn(item.photos.map { Uri.parse(it.uri) }) }
        updateItem(id) { it.copy(codes = codes) }
        if (codes.isEmpty()) showError("No barcode or QR code found. Take a close, sharp photo of it and try again.")
    }

    // ---- model file -------------------------------------------------------

    /**
     * One-time move of a model from older builds' external folder (always wiped on uninstall)
     * into internal storage, which can survive an uninstall with "Keep app data".
     */
    private fun moveModelToKeptStorage() {
        val current = ModelFiles.find(ctx) ?: return
        val target = File(ModelFiles.dir(ctx), ModelDownloader.FILE_NAME)
        if (current.parentFile == ModelFiles.dir(ctx)) return
        if (ModelDownloader.freeBytes(ctx) < current.length() + 200_000_000L) return // try again another day
        work("Moving AI model so it can survive updates…") {
            withContext(Dispatchers.IO) {
                val tmp = File(ModelFiles.dir(ctx), "move.part")
                if (!current.renameTo(target)) {
                    current.inputStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(1 shl 20)
                            var copied = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                copied += n
                                if (copied % (256L shl 20) < buf.size) progress("Moving AI model… ${"%.1f".format(copied / 1e9)} GB", copied.toFloat() / current.length())
                            }
                        }
                    }
                    if (tmp.length() != current.length() || !tmp.renameTo(target)) {
                        tmp.delete()
                        return@withContext
                    }
                }
                ModelFiles.markVerified(ctx, target)
                ModelFiles.deleteOthers(ctx, target)
            }
            refreshModelPath()
        }
    }

    /** Re-reads the model folder without hashing; only files already verified count. */
    private fun refreshModelPath() {
        describer.close()
        _state.update { it.copy(modelPath = ModelFiles.find(ctx)?.absolutePath) }
    }

    /** Checks any model file in the folder (e.g. one copied over USB) against the official checksum. */
    fun rescanModel() = work("Checking model file (checksum)…") {
        withContext(Dispatchers.IO) { ModelFiles.verifyCandidates(ctx) }
        refreshModelPath()
        val path = _state.value.modelPath
        val part = ModelDownloader.partFile(ctx).length()
        showError(
            when {
                path != null -> "AI model found and verified: ${File(path).name}."
                ModelFiles.candidates(ctx).isNotEmpty() ->
                    "Found a model file, but it isn't the official ${ModelDownloader.FILE_NAME} (checksum mismatch), so it won't be used."
                part > 0 -> "No finished model yet. A download is ${"%.1f".format(part / 1e9)} GB along: tap Download to resume it."
                else -> "No model file on this phone yet. Tap Download, or Import model file to pick a " +
                    "${ModelDownloader.FILE_NAME} you already have (e.g. in Downloads); it's copied in and checked."
            }
        )
    }

    fun importModel(uri: Uri) = work("Copying model…") {
        withContext(Dispatchers.IO) {
            val cr = ctx.contentResolver
            var name = "model.task"
            var total = -1L
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) total = c.getLong(1)
                }
            }
            if (!name.endsWith(".task")) {
                throw IllegalArgumentException("That's not a .task model file.")
            }
            val dir = ModelFiles.dir(ctx)
            // Fixed name: the display name comes from another app and could contain "../".
            val part = File(dir, "import.part")
            cr.openInputStream(uri)!!.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var copied = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (copied - lastReport > 64L shl 20) {
                            lastReport = copied
                            val gb = "%.1f".format(copied / 1e9)
                            progress("Copying model… $gb GB", if (total > 0) copied.toFloat() / total else null)
                        }
                    }
                }
            }
            // Only the official file is loaded into the native model runtime.
            progress("Verifying model file (checksum)…", null)
            if (!ModelDownloader.isOfficialModel(part)) {
                part.delete()
                throw IllegalArgumentException(
                    "That file isn't the official ${ModelDownloader.FILE_NAME} (checksum mismatch), so it wasn't installed."
                )
            }
            describer.close()
            val installed = File(dir, ModelDownloader.FILE_NAME)
            installed.delete()
            if (!part.renameTo(installed)) throw IllegalStateException("Couldn't move the model into place.")
            ModelFiles.markVerified(ctx, installed)
            ModelFiles.deleteOthers(ctx, installed)
        }
        refreshModelPath()
        // The 4.4 GB original is still in Downloads; offer to remove it.
        _state.update { it.copy(offerDeleteImportSource = uri.toString()) }
    }

    fun deleteImportSource() {
        val uri = _state.value.offerDeleteImportSource ?: return
        _state.update { it.copy(offerDeleteImportSource = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val deleted = runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, Uri.parse(uri)) }
                .getOrDefault(false)
            if (!deleted) showError("Couldn't delete the original here. Delete it in the Files app (Downloads).")
        }
    }

    fun keepImportSource() = _state.update { it.copy(offerDeleteImportSource = null) }

    // ---- leftovers: shared photo copies -------------------------------------

    /**
     * Wipes everything the app has stored: items, shared photo copies, the token, the
     * model, caches. Your original photos are never touched.
     */
    fun eraseEverything() = work("Erasing app data…") {
        ModelDownloadService.cancel(ctx)
        describer.close()
        withContext(Dispatchers.IO) {
            Exporter.deleteAllOwned(ctx)
            ctx.contentResolver.persistedUriPermissions.forEach {
                runCatching { ctx.contentResolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            listOf(ModelFiles.dir(ctx), ModelFiles.legacyDir(ctx), ctx.filesDir, ctx.cacheDir, ctx.noBackupFilesDir, ctx.externalCacheDir)
                .filterNotNull()
                .forEach { dir -> dir.listFiles()?.forEach { it.deleteRecursively() } }
            // Bundled libraries (ML Kit, MediaPipe) keep their own databases and prefs.
            ctx.databaseList().forEach { ctx.deleteDatabase(it) }
            File(ctx.dataDir, "shared_prefs").listFiles()
                ?.filter { it.name != "settings.xml" }
                ?.forEach { it.delete() }
        }
        store.hfToken = null
        store.geminiKey = null
        TokenVault.deleteKey()
        setItems { emptyList() } // goes through the same save queue, so no stale write lands after
        store.saveSettings(Settings())
        _state.update { UiState(settings = Settings()) }
    }

    /** Deletes every photo copy this app ever saved to the shared gallery. */
    fun deleteAllSharedCopies() = work("Deleting shared photo copies…") {
        val n = withContext(Dispatchers.IO) { Exporter.deleteAllOwned(ctx) }
        setItems { items -> items.map { it.copy(exportedUris = emptyList()) } }
        showError("Deleted $n photo copies from Pictures/ListingPrep.")
    }

    // ---- one-tap model download -------------------------------------------

    fun saveHfToken(token: String) {
        store.hfToken = token.trim().ifBlank { null }
        _state.update { it.copy(hasHfToken = !store.hfToken.isNullOrBlank()) }
    }

    fun downloadModel(wifiOnly: Boolean = true) {
        if (store.hfToken.isNullOrBlank()) {
            showError("Add your Hugging Face token first.")
            return
        }
        val remaining = ModelDownloader.SIZE_BYTES - ModelDownloader.partFile(ctx).length()
        if (ModelDownloader.freeBytes(ctx) < remaining + 300_000_000L) {
            showError("Not enough free space: the model needs about 4.7 GB.")
            return
        }
        // Resumes from any bytes already downloaded.
        ModelDownloadService.start(ctx, wifiOnly)
    }

    fun cancelDownload() = ModelDownloadService.cancel(ctx)

    private fun watchDownload() {
        viewModelScope.launch {
            ModelDownloadService.state.collect { s ->
                when (s) {
                    DownloadState.Idle -> _state.update { it.copy(download = null) }
                    is DownloadState.Running -> _state.update { it.copy(download = DownloadUi(s.label, s.progress)) }
                    is DownloadState.Failed -> {
                        _state.update { it.copy(download = null, error = s.message) }
                        ModelDownloadService.acknowledge()
                    }
                    is DownloadState.Done -> {
                        _state.update { it.copy(download = null) }
                        when (s.result) {
                            ModelDownloader.Finish.OK -> {
                                refreshModelPath()
                                // The token is only needed to download; don't keep a secret we no longer use.
                                saveHfToken("")
                            }
                            ModelDownloader.Finish.INCOMPLETE ->
                                showError("The download finished but the file is incomplete. Tap Download again.")
                            ModelDownloader.Finish.BAD_CHECKSUM ->
                                showError("The downloaded file didn't match the official checksum and was deleted. Tap Download again.")
                        }
                        ModelDownloadService.acknowledge()
                    }
                }
            }
        }
    }

    // ---- app updates (from GitHub releases) ----------------------------------

    private var updateApk: File? = null
    private var updateJob: Job? = null

    /** Checks GitHub for a newer build and downloads + verifies it. */
    fun checkForUpdate(manual: Boolean = true) {
        if (updateJob?.isActive == true) return
        if (manual && _state.value.update?.offerOnly == true) _state.update { it.copy(update = null) }
        updateJob = viewModelScope.launch {
            if (manual) _state.update { it.copy(updatePopup = true, updateNote = "Checking for updates…") }
            val release = withContext(Dispatchers.IO) { runCatching { Updater.check(ctx) } }
            store.lastUpdateCheck = System.currentTimeMillis()
            val r = release.getOrElse {
                _state.update { s -> s.copy(update = null) }
                if (manual) _state.update { s -> s.copy(updateNote = "Couldn't check for updates: ${it.message}") }
                return@launch
            }
            if (r == null) {
                _state.update { it.copy(update = null) }
                if (manual) _state.update { it.copy(updateNote = "You're on the latest build (${Updater.installedBuild(ctx)}).") }
                return@launch
            }
            if (!manual && !_state.value.settings.autoUpdate) {
                // Auto-update is off: just suggest it on the home screen; downloading waits for a tap.
                _state.update { it.copy(update = UpdateUi(r.build, "Build ${r.build} is available.", notes = r.notes, offerOnly = true)) }
                return@launch
            }
            _state.update { it.copy(update = UpdateUi(r.build, "Downloading build ${r.build}…", 0f, notes = r.notes)) }
            var last = 0L
            val apk = withContext(Dispatchers.IO) {
                runCatching {
                    Updater.download(ctx, r) { p ->
                        val now = System.currentTimeMillis()
                        if (now - last > 400) { last = now; _state.update { it.copy(update = UpdateUi(r.build, "Downloading build ${r.build}…", p, notes = r.notes)) } }
                    }
                }
            }.getOrElse {
                _state.update { s -> s.copy(update = null) }
                if (_state.value.updatePopup) _state.update { s -> s.copy(updateNote = "Update not installed: ${it.message}") }
                else showError("Update not installed: ${it.message}")
                return@launch
            }
            updateApk = apk
            installOnLeave = false
            store.pendingNotes = r.build to r.notes
            _state.update {
                it.copy(
                    update = UpdateUi(r.build, "Build ${r.build} is ready to install.", ready = true, notes = r.notes),
                    updatePopup = true, // show what's changing before anything is installed
                    updateNote = null,
                )
            }
        }
    }

    fun canInstallUpdates() = Updater.canInstall(ctx)

    /** After an update, the first launch says so (with what changed) instead of you checking Settings. */
    private fun announceUpdate() {
        val installed = Updater.installedBuild(ctx)
        val seen = store.lastSeenBuild
        store.lastSeenBuild = installed
        if (seen <= 0L || installed <= seen) return // first install, or nothing new
        val notes = store.pendingNotes?.takeIf { it.first.toLong() == installed }?.second.orEmpty()
        store.pendingNotes = null
        _state.update { it.copy(justUpdated = UpdateUi(installed.toInt(), "Listing Prep is now on build $installed.", notes = notes)) }
    }

    fun dismissJustUpdated() = _state.update { it.copy(justUpdated = null) }

    fun openUpdatePopup() = _state.update { it.copy(updatePopup = true) }

    fun closeUpdatePopup() = _state.update { it.copy(updatePopup = false, updateNote = null) }

    /** Installs now (the app closes and reopens updated). */
    fun installUpdate() {
        val apk = updateApk ?: return
        if (!Updater.canInstall(ctx)) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { Updater.install(ctx, apk) }.onFailure { showError("Update failed: ${it.message}") }
        }
    }

    /** Called when you leave the app: installs a ready update unless something is still running. */
    /** Set when you tap "When I leave the app" after reading the update summary. */
    private var installOnLeave = false

    fun installWhenLeaving() {
        installOnLeave = true
        _state.update { s ->
            s.copy(
                updatePopup = false,
                update = s.update?.copy(label = "Build ${s.update.build} installs when you leave the app."),
            )
        }
    }

    fun installUpdateIfIdle() {
        val s = _state.value
        if (installOnLeave && s.update?.ready == true && s.busy == null && s.download == null && s.generatingId == null) {
            installUpdate()
        }
    }

    override fun onCleared() {
        commitUndo()
        describer.close()
    }
}
