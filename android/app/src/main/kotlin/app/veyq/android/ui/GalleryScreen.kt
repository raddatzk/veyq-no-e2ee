package app.veyq.android.ui

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.veyq.crypto.Curate
import app.veyq.crypto.Groups
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.DisposableEffect
import app.veyq.crypto.Journal
import app.veyq.crypto.JournalLiveProvider
import app.veyq.crypto.JournalPersistence
import app.veyq.crypto.Login
import app.veyq.crypto.Media
import app.veyq.crypto.Share
import app.veyq.crypto.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant

class GalleryController(
    private val apiBase: String,
    private val session: Login.UnlockedSession,
    private val scopeId: String,
    private val contentKey: ByteArray,
    private val nodeId: String,
) {
    // `image` is the thumbnail (grid). `preview` (≈2048px) backs the lightbox; `original` is only fetched on
    // an explicit download. Both refs fall back to the original for media without that derivative.
    data class Tile(val id: String, val image: ImageBitmap, val aspectRatio: Float, val takenAt: String, val lat: Double?, val lng: Double?, val preview: Media.BlobRef, val original: Media.BlobRef, val mimeType: String)

    /** A short label for live-presence (the session carries no display name yet — use the email local-part). */
    val displayName: String get() = session.email.substringBefore("@")

    var tiles by mutableStateOf<List<Tile>>(emptyList())
    var loading by mutableStateOf(true)
    var loadFailed by mutableStateOf(false) // a systemic load failure vs. a genuinely empty memory (offer a retry)
    var uploading by mutableStateOf(false)
    var journalMarkdown by mutableStateOf("")
    var journalSaving by mutableStateOf(false)
    private var journalLoaded = false
    var tracks by mutableStateOf<List<Track.TrackItem>>(emptyList())
    var importing by mutableStateOf(false)
    private var tracksLoaded = false

    suspend fun loadTracks() {
        if (tracksLoaded) return
        runCatching { withContext(Dispatchers.IO) { Track.getTracks(apiBase, session, scopeId, contentKey, nodeId) } }.onSuccess { tracks = it }
        tracksLoaded = true
    }

    suspend fun importTrack(data: ByteArray) {
        val route = Gpx.parse(data) ?: return
        importing = true
        runCatching { withContext(Dispatchers.IO) { Track.createTrack(apiBase, session, scopeId, contentKey, nodeId, route.first, route.second) } }
            .onSuccess { runCatching { withContext(Dispatchers.IO) { Track.getTracks(apiBase, session, scopeId, contentKey, nodeId) } }.onSuccess { tracks = it } }
        importing = false
    }

    suspend fun deleteTrack(id: String) {
        runCatching { withContext(Dispatchers.IO) { Track.deleteTrack(apiBase, session, scopeId, nodeId, id) } }
        tracks = tracks.filterNot { it.id == id }
    }

    suspend fun loadJournal() {
        if (journalLoaded) return
        runCatching { withContext(Dispatchers.IO) { Journal.getJournal(apiBase, session, scopeId, contentKey, nodeId) } }
            .onSuccess { journalMarkdown = it.markdown }
        journalLoaded = true
    }

    suspend fun saveJournal(markdown: String) {
        journalSaving = true
        runCatching { withContext(Dispatchers.IO) { JournalPersistence.saveJournalMarkdown(apiBase, session, scopeId, nodeId, contentKey, markdown) } }
            .onSuccess { journalMarkdown = markdown }
        journalSaving = false
    }

    /** A live-journal client for this memory (native Yrs over the operator-blind relay + CRDT store). */
    fun newLiveProvider(): JournalLiveProvider {
        val wsBase = apiBase.replace("http", "ws")
        // Fresh single-use ticket per (re)connect (fetched on the provider's background thread) — never the
        // session token in the URL.
        return JournalLiveProvider({
            "$wsBase/ws/journal/$scopeId/$nodeId?ticket=${JournalPersistence.fetchLiveTicket(apiBase, session)}"
        }, contentKey).also {
            it.configurePersistence(apiBase, session, scopeId, nodeId)
        }
    }

    suspend fun deleteMedia(mediaId: String) {
        runCatching { withContext(Dispatchers.IO) { Groups.deleteMedia(apiBase, session, scopeId, nodeId, mediaId) } }
        load()
    }

    suspend fun setCover(mediaId: String) {
        runCatching { withContext(Dispatchers.IO) { Curate.setCover(apiBase, session, scopeId, nodeId, mediaId) } }
    }

    /** Mint a public share link for this memory (origin = the instance serving the web viewer). */
    suspend fun createShareLink(role: String): String? =
        runCatching { withContext(Dispatchers.IO) { Share.createShare(apiBase, session, scopeId, contentKey, nodeId, role, apiBase) } }.getOrNull()

    suspend fun upload(items: List<ByteArray>) {
        uploading = true
        for (data in items) {
            val exif = exifGps(data)
            runCatching {
                withContext(Dispatchers.IO) {
                    Media.uploadMedia(apiBase, session, scopeId, contentKey, nodeId, data, thumbnail(data), preview(data), exif.takenAt ?: Instant.now().toString(), "image/jpeg", exif.lat, exif.lng)
                }
            }
        }
        load()
        uploading = false
    }

    private data class Exif(val lat: Double?, val lng: Double?, val takenAt: String?)

    /** Pull GPS + capture time from a JPEG's EXIF (sealed client-side — the server never sees either). */
    private fun exifGps(data: ByteArray): Exif {
        return runCatching {
            val exif = ExifInterface(java.io.ByteArrayInputStream(data))
            val ll = exif.latLong
            val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            val takenAt = raw?.let {
                runCatching {
                    val parser = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                    parser.parse(it)?.toInstant()?.toString()
                }.getOrNull()
            }
            Exif(ll?.get(0), ll?.get(1), takenAt)
        }.getOrDefault(Exif(null, null, null))
    }

    /** Downscale to fit `maxDim` (longest edge) as JPEG at `quality`; null for non-images. */
    private fun downscale(data: ByteArray, maxDim: Int, quality: Int): ByteArray? {
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        val scale = minOf(1f, maxDim.toFloat() / maxOf(bmp.width, bmp.height))
        val w = maxOf(1, (bmp.width * scale).toInt())
        val h = maxOf(1, (bmp.height * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    private fun thumbnail(data: ByteArray): ByteArray? = downscale(data, 400, 80)    // gallery grid
    private fun preview(data: ByteArray): ByteArray? = downscale(data, 2048, 85)     // lightbox

    private fun blobRef(o: JSONObject): Media.BlobRef = Media.BlobRef(o.getString("blobId"), o.getInt("totalChunks"), o.getLong("byteSize"))
    private fun derivativeRef(m: JSONObject, kind: String): Media.BlobRef {
        val derivs = m.getJSONArray("derivatives")
        for (i in 0 until derivs.length()) if (derivs.getJSONObject(i).getString("kind") == kind) return blobRef(derivs.getJSONObject(i).getJSONObject("blob"))
        return blobRef(m.getJSONObject("original")) // fall back to the original
    }

    suspend fun load() {
        loadFailed = false
        try {
            val media = withContext(Dispatchers.IO) { Media.listMedia(apiBase, session, scopeId, nodeId) }
            // Sort by decrypted capture time (the server lists in upload order, blind to takenAt).
            val withMeta = media.mapNotNull { m -> runCatching { m to Media.decodeMediaMeta(contentKey, m) }.getOrNull() }
                .sortedBy { it.second.takenAt }
            // Download the (small) thumbnails bound-parallel rather than one-by-one; preserves the takenAt order.
            val sem = Semaphore(6)
            tiles = withContext(Dispatchers.IO) {
                coroutineScope {
                    withMeta.map { (m, meta) ->
                        async {
                            sem.withPermit {
                                val bytes = runCatching { Media.downloadBlob(apiBase, session, scopeId, contentKey, derivativeRef(m, "THUMBNAIL")) }.getOrNull() ?: return@async null
                                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@async null
                                Tile(m.getString("id"), bmp.asImageBitmap(), JustifiedLayout.clampAspect(bmp.width.toFloat(), bmp.height.toFloat()),
                                    meta.takenAt, meta.lat, meta.lng, derivativeRef(m, "PREVIEW"), blobRef(m.getJSONObject("original")), m.optString("mimeType", "image/jpeg"))
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
            }
        } catch (e: Exception) {
            // systemic failure (network/auth) — not an empty memory; surface a retry rather than a silent blank grid
            loadFailed = true
        }
        loading = false
    }

    /** Decrypt a tile's PREVIEW (≈2048px) for the lightbox, downsampled to a sane display size to bound memory. */
    suspend fun loadPreviewImage(tile: Tile): ImageBitmap? = withContext(Dispatchers.IO) {
        val bytes = runCatching { Media.downloadBlob(apiBase, session, scopeId, contentKey, tile.preview) }.getOrNull() ?: return@withContext null
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 } // ~halves the decoded bitmap; preview is already ≤2048px
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }

    /** Decrypt a tile's FULL-resolution original bytes (only on an explicit download). */
    suspend fun loadOriginalData(tile: Tile): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { Media.downloadBlob(apiBase, session, scopeId, contentKey, tile.original) }.getOrNull()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, nodeId: String, name: String, onBack: () -> Unit) {
    val controller = remember { GalleryController(apiBase, session, scopeId, contentKey, nodeId) }
    var lightboxTile by remember { mutableStateOf<GalleryController.Tile?>(null) }
    var lightboxPreview by remember { mutableStateOf<ImageBitmap?>(null) } // higher-res preview, loaded async
    var mediaDelArm by remember { mutableStateOf(false) }
    var showDownload by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var savingOriginal by remember { mutableStateOf(false) }
    var showShareRole by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { controller.load() }
    // When the lightbox opens on a tile, show its thumbnail instantly then swap in the higher-res preview.
    LaunchedEffect(lightboxTile?.id) {
        lightboxPreview = null
        lightboxTile?.let { lightboxPreview = controller.loadPreviewImage(it) }
    }

    /** Fetch THIS medium's full-resolution original and save it (MediaStore on API 29+, app storage below). */
    fun saveOriginal(tile: GalleryController.Tile) {
        if (savingOriginal) return
        savingOriginal = true
        scope.launch {
            try {
                val data = controller.loadOriginalData(tile) ?: return@launch
                val ext = tile.mimeType.substringAfter('/', "bin").replace("jpeg", "jpg")
                val fileName = "veyq-${tile.id.take(8)}.$ext"
                withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                            put(MediaStore.Images.Media.MIME_TYPE, tile.mimeType)
                            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Veyq")
                        }
                        context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                            context.contentResolver.openOutputStream(uri)?.use { it.write(data) }
                        }
                    } else {
                        java.io.File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), fileName).writeBytes(data)
                    }
                }
            } finally {
                savingOriginal = false
            }
        }
    }

    fun download(withChapters: Boolean) {
        showDownload = false
        downloading = true
        scope.launch {
            runCatching {
                val file = withContext(Dispatchers.IO) { DownloadService.buildZip(context, apiBase, session, scopeId, contentKey, nodeId, name, withChapters) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(share, "Teilen"))
            }
            downloading = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val datas = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            }
            controller.upload(datas)
        }
    }

    val gpxPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val data = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            if (data != null) controller.importTrack(data)
        }
    }

    Scaffold(
        containerColor = Veyq.Bg,
        topBar = {
            TopAppBar(
                title = { Text(name, color = Veyq.Text, fontWeight = FontWeight.Bold, maxLines = 1) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Veyq.Surface),
                navigationIcon = { IconButton(onClick = onBack) { Text("‹", color = Veyq.Text, fontSize = 24.sp) } },
                actions = {
                    IconButton(onClick = { showShareRole = true }) { Text("↗", color = Veyq.Accent, fontSize = 20.sp) }
                    IconButton(onClick = { showDownload = true }, enabled = !downloading && controller.tiles.isNotEmpty()) {
                        Text(if (downloading) "…" else "⬇", color = Veyq.Text, fontSize = 20.sp)
                    }
                    IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !controller.uploading) {
                        Text(if (controller.uploading) "…" else "+", color = Veyq.Accent, fontSize = 22.sp)
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                TabLabel(L10n.t("detail.gallery"), tab == 0) { tab = 0 }
                TabLabel(L10n.t("detail.journal"), tab == 1) { tab = 1 }
                TabLabel(L10n.t("detail.map"), tab == 2) { tab = 2 }
            }
            if (tab == 1) {
                JournalTab(controller, scope)
            } else if (tab == 2) {
                LaunchedEffect(Unit) { controller.loadTracks() }
                val points = controller.tiles.mapNotNull { t -> if (t.lat != null && t.lng != null) Triple(t.id, t.lat, t.lng) else null }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { gpxPicker.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*")) }, enabled = !controller.importing) {
                        Text(if (controller.importing) L10n.t("track.importing") else L10n.t("track.import"), color = Veyq.Accent)
                    }
                }
                if (points.isEmpty() && controller.tracks.isEmpty()) Text(L10n.t("map.empty"), color = Veyq.Muted, fontSize = 14.sp)
                else MemoryMap(points, controller.tracks) { id -> controller.tiles.firstOrNull { it.id == id }?.let { lightboxTile = it } }
                controller.tracks.forEach { tr ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("🛤 ${tr.name}", color = Veyq.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { val id = tr.id; scope.launch { controller.deleteTrack(id) } }) { Text(L10n.t("track.delete"), color = Color(0xFFF87171)) }
                    }
                }
            } else {
                Text("${controller.tiles.size} ${L10n.t("detail.media")}", color = Veyq.Muted, fontSize = 13.sp)
                when {
                    controller.loading -> Text(L10n.t("detail.loadingGallery"), color = Veyq.Muted, fontSize = 14.sp)
                    controller.loadFailed -> {
                        Text(L10n.t("detail.loadFailed"), color = Veyq.Muted, fontSize = 14.sp)
                        TextButton(onClick = { scope.launch { controller.loading = true; controller.load() } }) { Text(L10n.t("common.retry"), color = Veyq.Accent) }
                    }
                    controller.tiles.isEmpty() -> Text(L10n.t("detail.noMedia"), color = Veyq.Muted, fontSize = 14.sp)
                    else -> {
                        val range = DateBuckets.dateRange(controller.tiles) { it.takenAt }
                        if (range.isNotEmpty()) Text(range, color = Veyq.Muted, fontSize = 13.sp)
                        DateBuckets.groupByDay(controller.tiles) { it.takenAt }.forEach { (key, items) ->
                            Text(DateBuckets.dayLabel(key), color = Veyq.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Gallery(items) { lightboxTile = it }
                        }
                    }
                }
            }
        }
    }

    lightboxTile?.let { tile ->
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Image(lightboxPreview ?: tile.image, null, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { val id = tile.id; lightboxTile = null; scope.launch { controller.setCover(id) } }) { Text(L10n.t("cover.setAsCover"), color = Veyq.Accent) }
                    TextButton(enabled = !savingOriginal, onClick = { saveOriginal(tile) }) { Text(L10n.t("detail.download"), color = Color.White) }
                    if (!mediaDelArm) {
                        TextButton(onClick = { mediaDelArm = true }) { Text(L10n.t("common.delete"), color = Veyq.Danger) }
                    } else {
                        TextButton(onClick = { val id = tile.id; lightboxTile = null; mediaDelArm = false; scope.launch { controller.deleteMedia(id) } }) { Text(L10n.t("delete.confirm"), color = Veyq.Danger) }
                    }
                    TextButton(onClick = { lightboxTile = null; mediaDelArm = false }) { Text(L10n.t("common.close"), color = Color.White) }
                }
            }
        }
    }

    if (showShareRole) {
        fun share(role: String) {
            showShareRole = false
            scope.launch {
                val link = controller.createShareLink(role)
                if (link != null) {
                    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, link) }
                    context.startActivity(Intent.createChooser(send, L10n.t("share.share")))
                }
            }
        }
        AlertDialog(
            onDismissRequest = { showShareRole = false },
            containerColor = Veyq.Surface,
            title = { Text(L10n.t("share.share"), color = Veyq.Text) },
            text = {
                Column {
                    Text(L10n.t("share.hint"), color = Veyq.Muted)
                    TextButton(onClick = { share("VIEWER") }, modifier = Modifier.fillMaxWidth()) { Text(L10n.t("share.roleViewer"), color = Veyq.Text, modifier = Modifier.fillMaxWidth()) }
                    TextButton(onClick = { share("CONTRIBUTOR") }, modifier = Modifier.fillMaxWidth()) { Text(L10n.t("share.roleContributor"), color = Veyq.Text, modifier = Modifier.fillMaxWidth()) }
                    TextButton(onClick = { share("UPLOADER") }, modifier = Modifier.fillMaxWidth()) { Text(L10n.t("share.roleUploader"), color = Veyq.Text, modifier = Modifier.fillMaxWidth()) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showShareRole = false }) { Text(L10n.t("common.cancel"), color = Veyq.Muted) } },
        )
    }

    if (showDownload) {
        AlertDialog(
            onDismissRequest = { showDownload = false },
            containerColor = Veyq.Surface,
            title = { Text(L10n.t("download.title"), color = Veyq.Text) },
            text = { Text(L10n.t("download.text"), color = Veyq.Muted) },
            confirmButton = { TextButton(onClick = { download(true) }) { Text(L10n.t("download.withChapters"), color = Veyq.Accent) } },
            dismissButton = { TextButton(onClick = { download(false) }) { Text(L10n.t("download.onlyThis"), color = Veyq.Accent) } },
        )
    }
}

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val items: List<String>) : MdBlock
    data class Ordered(val items: List<String>) : MdBlock
    data class Paragraph(val text: String) : MdBlock
}

private fun parseMarkdownBlocks(markdown: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraph = mutableListOf<String>()
    val bullets = mutableListOf<String>()
    val ordered = mutableListOf<String>()
    fun flushParagraph() { if (paragraph.isNotEmpty()) { blocks.add(MdBlock.Paragraph(paragraph.joinToString("\n"))); paragraph.clear() } }
    fun flushBullets() { if (bullets.isNotEmpty()) { blocks.add(MdBlock.Bullet(bullets.toList())); bullets.clear() } }
    fun flushOrdered() { if (ordered.isNotEmpty()) { blocks.add(MdBlock.Ordered(ordered.toList())); ordered.clear() } }
    val heading = Regex("^(#{1,3})\\s+(.*)$")
    val bullet = Regex("^[-*]\\s+(.*)$")
    val number = Regex("^\\d+\\.\\s+(.*)$")
    for (raw in markdown.replace("\r\n", "\n").split("\n")) {
        val line = raw.trimEnd()
        val h = heading.find(line)
        val b = bullet.find(line)
        val n = number.find(line)
        when {
            line.isBlank() -> { flushParagraph(); flushBullets(); flushOrdered() }
            h != null -> { flushParagraph(); flushBullets(); flushOrdered(); blocks.add(MdBlock.Heading(h.groupValues[1].length, h.groupValues[2])) }
            b != null -> { flushParagraph(); flushOrdered(); bullets.add(b.groupValues[1]) }
            n != null -> { flushParagraph(); flushBullets(); ordered.add(n.groupValues[1]) }
            else -> { flushBullets(); flushOrdered(); paragraph.add(line) }
        }
    }
    flushParagraph(); flushBullets(); flushOrdered()
    return blocks
}

/** Inline bold (**), italic (*) and code (`) → an AnnotatedString. Links render as their text. */
private fun inlineMd(text: String): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    var i = 0
    fun take(marker: String, style: androidx.compose.ui.text.SpanStyle): Boolean {
        if (!text.startsWith(marker, i)) return false
        val end = text.indexOf(marker, i + marker.length)
        if (end < 0) return false
        pushStyle(style); append(text.substring(i + marker.length, end)); pop()
        i = end + marker.length
        return true
    }
    while (i < text.length) {
        when {
            take("**", androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) -> {}
            take("`", androidx.compose.ui.text.SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) -> {}
            take("*", androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) -> {}
            else -> { append(text[i]); i++ }
        }
    }
}

/** An osmdroid (OpenStreetMap) map with a marker per geotagged medium and a polyline per GPX track. */
@Composable
private fun MemoryMap(points: List<Triple<String, Double, Double>>, tracks: List<Track.TrackItem>, onSelect: (String) -> Unit) {
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.fillMaxWidth().height(420.dp).clip(RoundedCornerShape(Veyq.Radius)),
        factory = { ctx ->
            org.osmdroid.config.Configuration.getInstance().userAgentValue = ctx.packageName
            org.osmdroid.config.Configuration.getInstance().osmdroidBasePath = ctx.cacheDir
            org.osmdroid.views.MapView(ctx).apply {
                setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                val geo = mutableListOf<org.osmdroid.util.GeoPoint>()
                tracks.forEach { t ->
                    val line = t.points.map { org.osmdroid.util.GeoPoint(it.lat, it.lng) }
                    if (line.size > 1) {
                        val poly = org.osmdroid.views.overlay.Polyline().apply {
                            setPoints(line); title = t.name; outlinePaint.color = 0xFF9B8CFF.toInt(); outlinePaint.strokeWidth = 8f
                        }
                        overlays.add(poly)
                    }
                    geo.addAll(line)
                }
                points.forEach { (id, lat, lng) ->
                    val marker = org.osmdroid.views.overlay.Marker(this)
                    marker.position = org.osmdroid.util.GeoPoint(lat, lng)
                    marker.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM)
                    marker.setOnMarkerClickListener { _, _ -> onSelect(id); true }
                    overlays.add(marker)
                    geo.add(org.osmdroid.util.GeoPoint(lat, lng))
                }
                if (geo.size == 1) {
                    controller.setCenter(geo[0]); controller.setZoom(13.0)
                } else if (geo.size > 1) {
                    post { zoomToBoundingBox(org.osmdroid.util.BoundingBox.fromGeoPoints(geo).increaseByScale(1.4f), false) }
                }
            }
        },
    )
}

@Composable
private fun TabLabel(text: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (active) Veyq.Text else Veyq.Muted,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    )
}

@Composable
private fun JournalTab(controller: GalleryController, scope: kotlinx.coroutines.CoroutineScope) {
    var editing by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { controller.loadJournal() }

    if (live) {
        // The live section persists via the CRDT store (compaction on close); just reflect the text + exit.
        LiveJournalSection(controller) { md -> controller.journalMarkdown = md; live = false }
    } else if (editing) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = { Text(L10n.t("journal.placeholder"), color = Veyq.Muted) },
            modifier = Modifier.fillMaxWidth().height(280.dp),
            shape = RoundedCornerShape(Veyq.Radius),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Veyq.Surface, unfocusedContainerColor = Veyq.Surface,
                focusedBorderColor = Veyq.Accent, unfocusedBorderColor = Veyq.Line,
                focusedTextColor = Veyq.Text, unfocusedTextColor = Veyq.Text,
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { editing = false }) { Text(L10n.t("common.cancel"), color = Veyq.Muted) }
            TextButton(onClick = { val md = draft; scope.launch { controller.saveJournal(md); editing = false } }, enabled = !controller.journalSaving) {
                Text(if (controller.journalSaving) L10n.t("journal.saving") else L10n.t("journal.save"), color = Veyq.Accent)
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { live = true }) { Text(L10n.t("journal.live"), color = Veyq.Accent) }
            TextButton(onClick = { draft = controller.journalMarkdown; editing = true }) { Text(L10n.t("journal.edit"), color = Veyq.Accent) }
        }
        if (controller.journalMarkdown.isBlank()) {
            Text(L10n.t("journal.empty"), color = Veyq.Muted, fontSize = 14.sp)
        } else {
            MarkdownText(controller.journalMarkdown)
        }
    }
}

/** Live collaborative journal editing — native Yrs over the encrypted relay, wire-compatible with web/iOS.
 *  Co-editors' carets (a coloured line + name chip) are overlaid at their reported char index. */
@Composable
private fun LiveJournalSection(controller: GalleryController, onClose: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(controller.journalMarkdown)) }
    var connected by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var peers by remember { mutableStateOf<List<JournalLiveProvider.Peer>>(emptyList()) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val provider = remember { controller.newLiveProvider() }
    val palette = remember { listOf("#5b4df5", "#0ea5e9", "#10b981", "#f59e0b", "#ef4444", "#ec4899", "#8b5cf6") }
    val flushHolder = remember { arrayOfNulls<Runnable>(1) }
    // Coalesce local edits into a debounced append to the store (the live relay broadcast is immediate).
    fun scheduleFlush() {
        flushHolder[0]?.let { main.removeCallbacks(it) }
        val r = Runnable { Thread { provider.flushPersist() }.start() }
        flushHolder[0] = r
        main.postDelayed(r, 1200)
    }
    DisposableEffect(Unit) {
        provider.onStatus = { c -> main.post { connected = c } }
        provider.onText = { s -> main.post { if (value.text != s) value = value.copy(text = s, selection = androidx.compose.ui.text.TextRange(minOf(value.selection.start, s.length))) } }
        provider.onPeers = { list -> main.post { peers = list } }
        provider.setUser(controller.displayName, palette[(controller.displayName.hashCode() and 0x7fffffff) % palette.size])
        val initial = controller.journalMarkdown
        // Reconstruct from the operator-blind store (blocking HTTP), then bootstrap a legacy Markdown-only journal.
        Thread {
            provider.loadPersisted()
            if (provider.currentText.isEmpty() && initial.isNotEmpty()) provider.applyLocalText(initial)
            main.post { value = value.copy(text = provider.currentText); ready = true }
        }.start()
        onDispose {
            flushHolder[0]?.let { main.removeCallbacks(it) }
            // flush + compact (refresh snapshot + Markdown projection, prune the log) then close — on a thread
            // that survives the composable's disposal.
            Thread { provider.flushPersist(); provider.compactPersisted(); provider.destroy() }.start()
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) Color(0xFF10B981) else Veyq.Muted))
        Text(if (connected) L10n.t("journal.live") else L10n.t("journal.connecting"), color = Veyq.Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
        peers.forEach { peer ->
            Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(hexColor(peer.color)))
                Text(peer.name, color = Veyq.Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 3.dp))
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        TextButton(onClick = { onClose(provider.currentText) }) { Text(L10n.t("journal.done"), color = Veyq.Accent) }
    }
    val density = LocalDensity.current
    Box(
        Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(Veyq.Radius)).background(Veyq.Surface)
            .border(1.dp, Veyq.Line, RoundedCornerShape(Veyq.Radius)).padding(10.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = { v ->
                val textChanged = v.text != value.text
                value = v
                if (ready && textChanged) { provider.applyLocalText(v.text); scheduleFlush() }
                if (ready) provider.setCursor(v.selection.start)
            },
            modifier = Modifier.fillMaxSize(),
            textStyle = TextStyle(color = Veyq.Text, fontSize = 15.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(Veyq.Accent),
            onTextLayout = { layout = it },
        )
        // overlay remote carets at their char index
        val lr = layout
        if (lr != null) {
            peers.forEach { peer ->
                val idx = peer.cursor
                if (idx != null && idx in 0..value.text.length) {
                    val rect = lr.getCursorRect(idx)
                    val color = hexColor(peer.color)
                    Box(
                        Modifier.offset { IntOffset(rect.left.toInt(), rect.top.toInt()) }
                            .size(width = 2.dp, height = with(density) { rect.height.toDp() })
                            .background(color),
                    )
                    Text(
                        peer.name, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .offset { IntOffset(rect.left.toInt(), (rect.top - with(density) { 14.dp.toPx() }).toInt()) }
                            .clip(RoundedCornerShape(3.dp)).background(color).padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

/** Parse a "#rrggbb" string into a Compose Color (falls back to grey). */
private fun hexColor(hex: String): Color =
    runCatching { Color(("ff" + hex.trim().removePrefix("#")).toLong(16)) }.getOrDefault(Color(0xFF888888))

/** Renders a safe Markdown subset (headings, bullet/ordered lists, paragraphs) with inline bold/italic/code. */
@Composable
private fun MarkdownText(markdown: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(inlineMd(block.text), color = Veyq.Text, fontWeight = FontWeight.Bold, fontSize = when (block.level) { 1 -> 22.sp; 2 -> 19.sp; else -> 17.sp })
                is MdBlock.Bullet -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    block.items.forEach { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Text("•", color = Veyq.Muted); Text(inlineMd(it), color = Veyq.Text) } }
                }
                is MdBlock.Ordered -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    block.items.forEachIndexed { i, it -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Text("${i + 1}.", color = Veyq.Muted); Text(inlineMd(it), color = Veyq.Text) } }
                }
                is MdBlock.Paragraph -> Text(inlineMd(block.text), color = Veyq.Text)
            }
        }
    }
}

@Composable
private fun Gallery(tiles: List<GalleryController.Tile>, onTap: (GalleryController.Tile) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val (laid, height) = JustifiedLayout.calculate(
            tiles.map { JustifiedLayout.Source(it.id, it.aspectRatio) },
            maxWidth.value, 160f, 6f,
        )
        Box(Modifier.fillMaxWidth().height(height.dp)) {
            laid.forEach { t ->
                tiles.firstOrNull { it.id == t.id }?.let { tile ->
                    Image(
                        tile.image,
                        null,
                        modifier = Modifier
                            .offset(t.left.dp, t.top.dp)
                            .size(t.width.dp, t.height.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onTap(tile) },
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
    }
}
