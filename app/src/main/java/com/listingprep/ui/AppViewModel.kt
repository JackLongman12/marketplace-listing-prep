package com.listingprep.ui

import android.app.Application
import android.app.DownloadManager
import com.listingprep.ml.ModelDownloader
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.listingprep.data.Item
import com.listingprep.data.Settings
import com.listingprep.data.Status
import com.listingprep.data.Store
import com.listingprep.media.MediaInfo
import com.listingprep.ml.Analyzed
import com.listingprep.ml.Describer
import com.listingprep.ml.Embedder
import com.listingprep.ml.Grouper
import com.listingprep.ml.ModelFiles
import com.listingprep.ml.Ocr
import com.listingprep.data.Photo
import kotlinx.coroutines.CancellationException
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
    /** Non-null while the model is downloading in the background. */
    val download: DownloadUi? = null,
)

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
        )
    )
    val state = _state.asStateFlow()

    private var downloadWatcher: Job? = null

    init {
        // Pick up a download that was running (or finished) while the app was closed.
        if (store.modelDownloadId >= 0) watchDownload(store.modelDownloadId)
    }

    // ---- item edits -------------------------------------------------------

    private fun setItems(transform: (List<Item>) -> List<Item>) {
        val items = _state.updateAndGet { it.copy(items = transform(it.items)) }.items
        viewModelScope.launch(saveDispatcher) { store.saveItems(items) }
    }

    fun updateItem(id: String, transform: (Item) -> Item) =
        setItems { items -> items.map { if (it.id == id) transform(it) else it } }

    fun deleteItem(id: String) = setItems { items -> items.filterNot { it.id == id } }

    fun setCover(id: String, index: Int) = updateItem(id) { item ->
        val p = item.photos.getOrNull(index) ?: return@updateItem item
        item.copy(photos = listOf(p) + item.photos.filterIndexed { i, _ -> i != index })
    }

    fun removePhoto(id: String, index: Int) = setItems { items ->
        items.mapNotNull { item ->
            if (item.id != id) item
            else item.copy(photos = item.photos.filterIndexed { i, _ -> i != index }).takeIf { it.photos.isNotEmpty() }
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

    fun mergeWithNext(id: String) = setItems { items ->
        val i = items.indexOfFirst { it.id == id }
        if (i < 0 || i >= items.lastIndex) return@setItems items
        items.toMutableList().apply {
            val a = this[i]
            val b = removeAt(i + 1)
            this[i] = a.copy(
                photos = a.photos + b.photos,
                notes = listOf(a.notes, b.notes).filter { it.isNotBlank() }.joinToString("; "),
            )
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

    fun importToday() = viewModelScope.launch {
        val uris = withContext(Dispatchers.IO) { MediaInfo.todayCameraPhotos(ctx.contentResolver) }
        if (uris.isEmpty()) showError("No camera photos from today found.") else importPhotos(uris)
    }

    fun generate(id: String) = work("Writing description…") { generateOne(id) }

    fun generateAll() = work("Writing descriptions…") {
        val ids = _state.value.items
            .filter { it.description.isBlank() && it.status != Status.POSTED }
            .map { it.id }
        ids.forEachIndexed { i, id ->
            progress("Writing description ${i + 1} of ${ids.size}", i.toFloat() / ids.size)
            generateOne(id)
        }
    }

    private suspend fun generateOne(id: String) {
        val item = _state.value.items.find { it.id == id } ?: return
        val path = _state.value.modelPath
            ?: throw IllegalStateException("No AI model yet. Add one in Settings.")
        _state.update { it.copy(generatingId = id) }
        val label = _state.value.busy
        val cr = ctx.contentResolver
        val (title, description) = withContext(Dispatchers.Default) {
            if (!describer.isLoaded(path)) {
                progress("Loading AI model (first time takes a few seconds)…", null)
                describer.ensureLoaded(path)
                progress(label ?: "Writing description…", _state.value.progress)
            }
            val uris = item.photos.map { Uri.parse(it.uri) }
            val ocrImages = uris.take(4).mapNotNull { runCatching { MediaInfo.decode(cr, it, 1280) }.getOrNull() }
            val ocrText = Ocr.read(ocrImages)
            ocrImages.forEach { it.recycle() }
            val llmImages = uris.take(Describer.MAX_IMAGES).mapNotNull { runCatching { MediaInfo.decode(cr, it, 768) }.getOrNull() }
            val raw = describer.describe(llmImages, Describer.prompt(item, ocrText))
            llmImages.forEach { it.recycle() }
            Describer.parse(raw)
        }
        updateItem(id) {
            it.copy(
                title = title.ifBlank { it.title },
                description = description,
                status = if (it.status == Status.POSTED) it.status else Status.READY,
            )
        }
        _state.update { it.copy(generatingId = null) }
    }

    // ---- model file -------------------------------------------------------

    fun rescanModel() {
        describer.close()
        _state.update { it.copy(modelPath = ModelFiles.find(ctx)?.absolutePath) }
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
                throw IllegalArgumentException("That's not a .task model file ($name).")
            }
            val dir = ModelFiles.dir(ctx)
            val part = File(dir, "$name.part")
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
            dir.listFiles()?.filter { it.name.endsWith(".task") }?.forEach { it.delete() }
            part.renameTo(File(dir, ModelDownloader.FILE_NAME))
        }
        rescanModel()
    }

    // ---- one-tap model download -------------------------------------------

    fun saveHfToken(token: String) {
        store.hfToken = token.trim().ifBlank { null }
        _state.update { it.copy(hasHfToken = !store.hfToken.isNullOrBlank()) }
    }

    fun downloadModel(wifiOnly: Boolean = true) {
        val token = store.hfToken
        if (token.isNullOrBlank()) {
            showError("Add your Hugging Face token first.")
            return
        }
        if (ModelDownloader.freeBytes(ctx) < ModelDownloader.SIZE_BYTES + 300_000_000L) {
            showError("Not enough free space: the model needs about 4.7 GB.")
            return
        }
        if (store.modelDownloadId >= 0) ModelDownloader.cancel(ctx, store.modelDownloadId)
        downloadWatcher?.cancel()
        downloadWatcher = viewModelScope.launch {
            _state.update { it.copy(download = DownloadUi("Contacting Hugging Face…", null)) }
            val id = try {
                withContext(Dispatchers.IO) { ModelDownloader.start(ctx, token, wifiOnly) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelDownloader.Refused) {
                _state.update { it.copy(download = null, error = ModelDownloader.refusedMessage(e.code)) }
                return@launch
            } catch (e: Exception) {
                _state.update { it.copy(download = null, error = "Couldn't start the download: ${e.message}") }
                return@launch
            }
            store.modelDownloadId = id
            watchDownload(id)
        }
    }

    fun cancelDownload() {
        val id = store.modelDownloadId
        if (id >= 0) ModelDownloader.cancel(ctx, id)
        store.modelDownloadId = -1
        downloadWatcher?.cancel()
        _state.update { it.copy(download = null) }
    }

    private fun watchDownload(id: Long) {
        downloadWatcher?.cancel()
        downloadWatcher = viewModelScope.launch {
            _state.update { it.copy(download = DownloadUi("Starting download…", null)) }
            while (true) {
                val p = withContext(Dispatchers.IO) { ModelDownloader.query(ctx, id) }
                when (p?.status) {
                    null -> {
                        store.modelDownloadId = -1
                        _state.update { it.copy(download = null) }
                        return@launch
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        _state.update { it.copy(download = DownloadUi("Verifying download (checksum)…", null)) }
                        val result = withContext(Dispatchers.IO) { ModelDownloader.finish(ctx) }
                        store.modelDownloadId = -1
                        _state.update { it.copy(download = null) }
                        when (result) {
                            ModelDownloader.Finish.OK -> rescanModel()
                            ModelDownloader.Finish.INCOMPLETE ->
                                showError("The download finished but the file is incomplete. Tap Download again.")
                            ModelDownloader.Finish.BAD_CHECKSUM ->
                                showError("The downloaded file didn't match the official checksum and was deleted. Tap Download again.")
                        }
                        return@launch
                    }
                    DownloadManager.STATUS_FAILED -> {
                        ModelDownloader.cancel(ctx, id)
                        store.modelDownloadId = -1
                        _state.update { it.copy(download = null, error = ModelDownloader.failureMessage(p.reason)) }
                        return@launch
                    }
                    else -> {
                        val total = if (p.total > 0) p.total else ModelDownloader.SIZE_BYTES
                        val gb = "%.1f / %.1f GB".format(p.downloaded / 1e9, total / 1e9)
                        val label = if (p.status == DownloadManager.STATUS_PAUSED) {
                            "${ModelDownloader.pausedMessage(p.reason)} ($gb)"
                        } else {
                            "Downloading AI model… $gb"
                        }
                        _state.update { it.copy(download = DownloadUi(label, p.downloaded.toFloat() / total)) }
                    }
                }
                delay(1500)
            }
        }
    }

    override fun onCleared() {
        describer.close()
    }
}
