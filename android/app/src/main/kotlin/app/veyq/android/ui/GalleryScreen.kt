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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import app.veyq.crypto.Browse
import app.veyq.crypto.Buckets
import app.veyq.crypto.Curate
import app.veyq.crypto.Groups
import app.veyq.crypto.MemoryTree
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.DisposableEffect
import app.veyq.crypto.Journal
import app.veyq.crypto.JournalLiveProvider
import app.veyq.crypto.JournalPersistence
import app.veyq.crypto.Login
import app.veyq.crypto.Media
import app.veyq.crypto.Playlists
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
    private val tenantId: String? = null,   // set for group/tenant scopes → resolve other members' bucket names
) {
    // `image` is the thumbnail (grid). `preview` (≈2048px) backs the lightbox; `original` is only fetched on
    // an explicit download. Both refs fall back to the original for media without that derivative.
    data class Tile(
        val id: String, val image: ImageBitmap, val aspectRatio: Float, val takenAt: String, val lat: Double?, val lng: Double?,
        val preview: Media.BlobRef, val original: Media.BlobRef, val mimeType: String, val highlighted: Boolean,
        val createdBy: String? = null, val bucketId: String? = null,
        val cameraMake: String? = null, val cameraModel: String? = null, val lens: String? = null,
        val width: Int? = null, val height: Int? = null, val fileSize: Int? = null, val exif: Map<String, String>? = null,
    )

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

    suspend fun renameTrack(track: Track.TrackItem, name: String) {
        runCatching { withContext(Dispatchers.IO) { Track.updateTrack(apiBase, session, scopeId, contentKey, nodeId, track.id, name, track.points) } }
            .onSuccess { runCatching { withContext(Dispatchers.IO) { Track.getTracks(apiBase, session, scopeId, contentKey, nodeId) } }.onSuccess { tracks = it } }
    }

    var playlists by mutableStateOf<List<Playlists.Item>>(emptyList())
    private var playlistsLoaded = false

    suspend fun loadPlaylists() {
        if (playlistsLoaded) return
        runCatching { withContext(Dispatchers.IO) { Playlists.list(apiBase, session, scopeId, contentKey, nodeId) } }.onSuccess { playlists = it }
        playlistsLoaded = true
    }

    private suspend fun reloadPlaylists() {
        runCatching { withContext(Dispatchers.IO) { Playlists.list(apiBase, session, scopeId, contentKey, nodeId) } }.onSuccess { playlists = it }
    }

    suspend fun addPlaylist(url: String, title: String) {
        runCatching { withContext(Dispatchers.IO) { Playlists.create(apiBase, session, scopeId, contentKey, nodeId, url, title.ifEmpty { null }, null, false) } }
            .onSuccess { reloadPlaylists() }
    }

    suspend fun featurePlaylist(id: String, featured: Boolean) {
        runCatching { withContext(Dispatchers.IO) { Playlists.setFeatured(apiBase, session, scopeId, nodeId, id, featured) } }
        reloadPlaylists()
    }

    suspend fun deletePlaylist(id: String) {
        runCatching { withContext(Dispatchers.IO) { Playlists.delete(apiBase, session, scopeId, nodeId, id) } }
        playlists = playlists.filterNot { it.id == id }
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

    /** Toggle a medium's highlight flag (server-visible benign metadata) and patch the local tile in place. */
    suspend fun toggleHighlight(tile: Tile) {
        val next = !tile.highlighted
        runCatching { withContext(Dispatchers.IO) { Media.updateMedia(apiBase, session, scopeId, nodeId, tile.id, highlighted = next) } }
            .onSuccess { tiles = tiles.map { if (it.id == tile.id) it.copy(highlighted = next) else it } }
    }

    /** Re-seal a medium's capture date (keeping its location) and patch sealedMeta, then reload to re-sort. */
    suspend fun updateTakenAt(tile: Tile, iso: String) {
        runCatching {
            withContext(Dispatchers.IO) {
                // Re-seal the new capture date while preserving the medium's existing GPS + EXIF camera details.
                val meta = Media.MediaMeta(iso, tile.lat, tile.lng, tile.cameraMake, tile.cameraModel, tile.lens, tile.width, tile.height, tile.fileSize, tile.exif)
                val sealed = Media.sealMediaMeta(contentKey, meta)
                Media.updateMedia(apiBase, session, scopeId, nodeId, tile.id, sealedMeta = sealed)
            }
        }.onSuccess { load() }
    }

    /** Child nodes (sub-folders/chapters/memories) shown as cover cards in the gallery tab + counted in Info. */
    data class Child(val id: String, val type: String, val name: String, val cover: ImageBitmap?)
    var children by mutableStateOf<List<Child>>(emptyList())
    private var childrenLoaded = false
    val chapterCount: Int get() = children.count { it.type == "CHAPTER" }

    suspend fun loadChildren() {
        if (childrenLoaded) return
        childrenLoaded = true
        runCatching {
            withContext(Dispatchers.IO) {
                Browse.listChildren(apiBase, session, scopeId, nodeId).map { node ->
                    val id = node.getString("id")
                    val type = node.getString("type")
                    val nm = runCatching { MemoryTree.decodeNodeName(contentKey, node) }.getOrDefault("—")
                    val cover = runCatching { childCover(id, node.optString("coverMediaId", null)) }.getOrNull()
                    Child(id, type, nm, cover)
                }
            }
        }.onSuccess { children = it }
    }

    /** Resolve a node by id (any node in the scope) into a navigable Child — backs ::chapter journal embeds. */
    suspend fun resolveChild(id: String): Child? = withContext(Dispatchers.IO) {
        runCatching {
            val all = MemoryTree.listNodes(apiBase, session, scopeId)
            val node = (0 until all.length()).map { all.getJSONObject(it) }.firstOrNull { it.getString("id") == id } ?: return@runCatching null
            val name = runCatching { MemoryTree.decodeNodeName(contentKey, node) }.getOrDefault("—")
            Child(id, node.getString("type"), name, null)
        }.getOrNull()
    }

    // ---- contribution buckets (ADR 0023) ----
    var buckets by mutableStateOf<List<Buckets.Bucket>>(emptyList())
    private var memberNames = mapOf<String, String>()   // userId → display name (group scopes), other members
    private var memberNamesLoaded = false
    val ownerKey: String get() = "user:${session.userId}"

    suspend fun loadBuckets() {
        runCatching { withContext(Dispatchers.IO) { Buckets.list(apiBase, session, scopeId, nodeId) } }.onSuccess { buckets = it }
        // Resolve other members' names from the group directory (account-level, server-visible) — operator-blind
        // re: content; the server never sees the sealed bucket names. Mirrors what Legacy returned server-side.
        val tid = tenantId
        if (!memberNamesLoaded && tid != null) {
            memberNamesLoaded = true
            runCatching {
                withContext(Dispatchers.IO) { Groups.listGroupMembers(apiBase, session, tid, scopeId) }
            }.onSuccess { arr ->
                memberNames = (0 until arr.length()).associate { arr.getJSONObject(it).let { o -> o.getString("userId") to o.getString("displayName") } }
            }
        }
    }
    fun bucketLabel(b: Buckets.Bucket): String {
        if (b.primary && b.ownerKey == ownerKey) return session.displayName.ifEmpty { session.email.substringBefore("@") }
        val sealed = Buckets.name(contentKey, b)
        if (sealed.isNotEmpty()) return sealed
        if (b.ownerKey.startsWith("user:")) return memberNames[b.ownerKey.removePrefix("user:")] ?: ""
        return ""
    }
    fun isOwnBucket(b: Buckets.Bucket) = b.ownerKey == ownerKey
    /** A tile belongs to a bucket: sub-bucket matches bucketId; primary matches the contributor's un-bucketed media. */
    fun tileInBucket(t: Tile, b: Buckets.Bucket): Boolean =
        if (b.primary) t.createdBy == b.ownerKey && t.bucketId == null else t.bucketId == b.id
    fun bucketCount(b: Buckets.Bucket) = tiles.count { tileInBucket(it, b) }

    suspend fun createBucket(name: String) {
        runCatching { withContext(Dispatchers.IO) { Buckets.create(apiBase, session, scopeId, contentKey, nodeId, name) } }
        loadBuckets()
    }
    suspend fun renameBucket(id: String, name: String) {
        runCatching { withContext(Dispatchers.IO) { Buckets.rename(apiBase, session, scopeId, contentKey, nodeId, id, name) } }
        loadBuckets()
    }
    suspend fun deleteBucket(id: String) {
        runCatching { withContext(Dispatchers.IO) { Buckets.delete(apiBase, session, scopeId, nodeId, id) } }
        loadBuckets(); load()
    }
    suspend fun moveMedia(tile: Tile, bucketId: String) {
        runCatching { withContext(Dispatchers.IO) { Media.updateMedia(apiBase, session, scopeId, nodeId, tile.id, bucketId = bucketId) } }
        load(); loadBuckets()
    }

    private fun childCover(childNodeId: String, coverMediaId: String?): ImageBitmap? {
        val media = runCatching { Media.listMedia(apiBase, session, scopeId, childNodeId) }.getOrNull() ?: return null
        val chosen = (coverMediaId?.let { cid -> media.firstOrNull { it.getString("id") == cid } }) ?: media.firstOrNull() ?: return null
        val ref = derivativeRef(chosen, "THUMBNAIL")
        val bytes = runCatching { Media.downloadBlob(apiBase, session, scopeId, contentKey, ref) }.getOrNull() ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }

    /** Mint a public share link for this memory (origin = the instance serving the web viewer). */
    suspend fun createShareLink(role: String): String? =
        runCatching { withContext(Dispatchers.IO) { Share.createShare(apiBase, session, scopeId, contentKey, nodeId, role, apiBase) } }.getOrNull()

    suspend fun upload(items: List<ByteArray>) {
        uploading = true
        for (data in items) {
            val meta = extractMeta(data)
            runCatching {
                withContext(Dispatchers.IO) {
                    Media.uploadMedia(apiBase, session, scopeId, contentKey, nodeId, data, thumbnail(data), preview(data), meta.takenAt, "image/jpeg", meta = meta)
                }
            }
        }
        load()
        uploading = false
    }

    /** Pull capture time + GPS + a broad set of EXIF camera details from a JPEG (sealed client-side — the
     *  server never sees any of it). Best-effort: fields absent in the file are simply omitted. */
    private fun extractMeta(data: ByteArray): Media.MediaMeta {
        val fallback = Instant.now().toString()
        return runCatching {
            val ex = ExifInterface(java.io.ByteArrayInputStream(data))
            val ll = ex.latLong
            val takenAt = ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let {
                runCatching { java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).parse(it)?.toInstant()?.toString() }.getOrNull()
            } ?: fallback
            fun s(tag: String) = ex.getAttribute(tag)?.trim()?.ifEmpty { null }
            fun trim(n: Double) = (Math.round(n * 100) / 100.0).let { if (it == Math.floor(it)) it.toInt().toString() else it.toString() }
            val exif = linkedMapOf<String, String>()
            ex.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.let { exif["Aperture"] = "f/${trim(it)}" }
            ex.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let { exif["Exposure"] = if (it < 1) "1/${Math.round(1 / it)} s" else "${trim(it)} s" }
            s(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { exif["ISO"] = it }
            ex.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.let { exif["Focal length"] = "${trim(it)} mm" }
            s(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)?.let { exif["Focal length (35mm)"] = "$it mm" }
            ex.getAttributeInt(ExifInterface.TAG_FLASH, -1).takeIf { it >= 0 }?.let { exif["Flash"] = if (it and 1 != 0) "On" else "Off" }
            s(ExifInterface.TAG_SOFTWARE)?.let { exif["Software"] = it }
            ex.getAltitude(Double.NaN).takeIf { !it.isNaN() }?.let { exif["Altitude"] = "${trim(it)} m" }
            val w = ex.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).takeIf { it > 0 } ?: ex.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0).takeIf { it > 0 }
            val h = ex.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).takeIf { it > 0 } ?: ex.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0).takeIf { it > 0 }
            Media.MediaMeta(
                takenAt, ll?.get(0), ll?.get(1),
                s(ExifInterface.TAG_MAKE), s(ExifInterface.TAG_MODEL), s(ExifInterface.TAG_LENS_MODEL),
                w, h, data.size, exif.ifEmpty { null },
            )
        }.getOrDefault(Media.MediaMeta(fallback, null, null, fileSize = data.size))
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
                                    meta.takenAt, meta.lat, meta.lng, derivativeRef(m, "PREVIEW"), blobRef(m.getJSONObject("original")), m.optString("mimeType", "image/jpeg"), m.optBoolean("highlighted", false),
                                    m.optString("createdBy", null), m.optString("bucketId", null),
                                    meta.cameraMake, meta.cameraModel, meta.lens, meta.width, meta.height, meta.fileSize, meta.exif)
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
fun GalleryScreen(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, nodeId: String, name: String, tenantId: String? = null, onBack: () -> Unit) {
    val controller = remember { GalleryController(apiBase, session, scopeId, contentKey, nodeId, tenantId) }
    var lightboxTile by remember { mutableStateOf<GalleryController.Tile?>(null) }
    var lightboxPreview by remember { mutableStateOf<ImageBitmap?>(null) } // higher-res preview, loaded async
    var mediaDelArm by remember { mutableStateOf(false) }
    var showDownload by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var savingOriginal by remember { mutableStateOf(false) }
    var showShareRole by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }
    var showHighlightsOnly by remember { mutableStateOf(false) }
    var lightboxInfo by remember { mutableStateOf(false) }
    var editingDate by remember { mutableStateOf(false) }
    var childNav by remember { mutableStateOf<GalleryController.Child?>(null) }
    var selectedBucketId by remember { mutableStateOf<String?>(null) }
    var cameraFilter by remember { mutableStateOf<String?>(null) }
    var creatingBucket by remember { mutableStateOf(false) }
    var renamingBucket by remember { mutableStateOf<Buckets.Bucket?>(null) }
    var hiddenTracks by remember { mutableStateOf(setOf<String>()) }
    var renamingTrack by remember { mutableStateOf<Track.TrackItem?>(null) }
    var addingPlaylist by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { controller.load(); controller.loadChildren(); controller.loadBuckets() }
    val selectedBucket = controller.buckets.firstOrNull { it.id == selectedBucketId }
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

    // Navigate into a child chapter/folder/memory by recursively rendering its own gallery screen.
    childNav?.let { child ->
        GalleryScreen(apiBase, session, scopeId, contentKey, child.id, child.name, tenantId, onBack = { childNav = null })
        return
    }
    fun onOpenChild(child: GalleryController.Child) { childNav = child }

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
            // Cover hero: the first (earliest) photo, like the web detail header.
            controller.tiles.firstOrNull()?.let { hero ->
                Image(hero.image, null, modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                TabLabel(L10n.t("detail.gallery"), tab == 0) { tab = 0 }
                if (controller.children.isNotEmpty()) TabLabel(L10n.t("detail.chapters"), tab == 5) { tab = 5 }
                TabLabel(L10n.t("detail.map"), tab == 2) { tab = 2 }
                TabLabel(L10n.t("detail.journal"), tab == 1) { tab = 1 }
                TabLabel(L10n.t("detail.playlists"), tab == 6) { tab = 6 }
                TabLabel(L10n.t("detail.contributions"), tab == 4) { tab = 4 }
                TabLabel(L10n.t("detail.info"), tab == 3) { tab = 3 }
            }
            if (tab == 5) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    controller.children.forEach { child ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Veyq.Surface).clickable { onOpenChild(child) }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            val cover = child.cover
                            if (cover != null) Image(cover, null, Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                            else Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Veyq.Bg), contentAlignment = Alignment.Center) { Text(if (child.type == "CHAPTER") "📖" else if (child.type == "FOLDER") "🗂" else "🖼", fontSize = 22.sp) }
                            Text(child.name, color = Veyq.Text, modifier = Modifier.weight(1f))
                            Text("›", color = Veyq.Muted, fontSize = 20.sp)
                        }
                    }
                }
            } else if (tab == 4) {
                LaunchedEffect(Unit) { controller.loadBuckets() }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (controller.buckets.isEmpty()) Text(L10n.t("contrib.empty"), color = Veyq.Muted, fontSize = 14.sp)
                    controller.buckets.forEach { b ->
                        val label = controller.bucketLabel(b).ifEmpty { L10n.t("contrib.member") }
                        Row(
                            Modifier.fillMaxWidth().padding(start = if (b.primary) 0.dp else 16.dp).clip(RoundedCornerShape(8.dp)).background(Veyq.Surface).padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${if (b.primary) "👤" else "📁"} $label", color = Veyq.Text, modifier = Modifier.weight(1f).clickable { selectedBucketId = b.id; cameraFilter = null; tab = 0 })
                            Text("${controller.bucketCount(b)}", color = Veyq.Muted, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                            if (controller.isOwnBucket(b)) {
                                if (b.primary) {
                                    TextButton(onClick = { creatingBucket = true }) { Text("+", color = Veyq.Accent) }
                                } else {
                                    TextButton(onClick = { renamingBucket = b }) { Text("✎", color = Veyq.Accent) }
                                    TextButton(onClick = { val id = b.id; scope.launch { controller.deleteBucket(id); if (selectedBucketId == id) selectedBucketId = null } }) { Text("🗑", color = Veyq.Danger) }
                                }
                            }
                        }
                    }
                }
            } else if (tab == 6) {
                LaunchedEffect(Unit) { controller.loadPlaylists() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { addingPlaylist = true }) { Text(L10n.t("playlist.add"), color = Veyq.Accent) }
                }
                if (controller.playlists.isEmpty()) Text(L10n.t("playlist.empty"), color = Veyq.Muted, fontSize = 14.sp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    controller.playlists.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Veyq.Surface).padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(p.provider.label, color = Veyq.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                p.title?.ifEmpty { null } ?: p.url, color = Veyq.Accent, fontSize = 14.sp, maxLines = 1,
                                modifier = Modifier.weight(1f).clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(p.url))) } },
                            )
                            TextButton(onClick = { val id = p.id; val f = !p.featured; scope.launch { controller.featurePlaylist(id, f) } }) { Text(if (p.featured) "★" else "☆", color = if (p.featured) Veyq.Accent else Veyq.Muted) }
                            TextButton(onClick = { val id = p.id; scope.launch { controller.deletePlaylist(id) } }) { Text("🗑", color = Veyq.Danger) }
                        }
                    }
                }
            } else if (tab == 3) {
                LaunchedEffect(Unit) { controller.loadTracks(); controller.loadChildren() }
                InfoTab(controller)
            } else if (tab == 1) {
                JournalTab(controller, scope, onOpenChapter = { id -> scope.launch { controller.resolveChild(id)?.let { childNav = it } } }) { tile -> lightboxTile = tile }
            } else if (tab == 2) {
                LaunchedEffect(Unit) { controller.loadTracks() }
                val points = controller.tiles.mapNotNull { t -> if (t.lat != null && t.lng != null) Triple(t.id, t.lat, t.lng) else null }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { gpxPicker.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*")) }, enabled = !controller.importing) {
                        Text(if (controller.importing) L10n.t("track.importing") else L10n.t("track.import"), color = Veyq.Accent)
                    }
                }
                if (points.isEmpty() && controller.tracks.isEmpty()) Text(L10n.t("map.empty"), color = Veyq.Muted, fontSize = 14.sp)
                else MemoryMap(points, controller.tracks.filter { it.id !in hiddenTracks }) { id -> controller.tiles.firstOrNull { it.id == id }?.let { lightboxTile = it } }
                controller.tracks.forEach { tr ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { hiddenTracks = if (tr.id in hiddenTracks) hiddenTracks - tr.id else hiddenTracks + tr.id }) {
                            Text(if (tr.id in hiddenTracks) "🚫" else "👁", fontSize = 13.sp)
                        }
                        Box(Modifier.weight(1f)) { TrackStatsRow(tr) }
                        TextButton(onClick = { renamingTrack = tr }) { Text(L10n.t("common.rename"), color = Veyq.Accent) }
                        TextButton(onClick = { val id = tr.id; scope.launch { controller.deleteTrack(id) } }) { Text(L10n.t("track.delete"), color = Color(0xFFF87171)) }
                    }
                }
            } else {
                var displayTiles = controller.tiles
                if (showHighlightsOnly) displayTiles = displayTiles.filter { it.highlighted }
                selectedBucket?.let { b -> displayTiles = displayTiles.filter { controller.tileInBucket(it, b) } }
                cameraFilter?.let { cam -> displayTiles = displayTiles.filter { listOfNotNull(it.cameraMake, it.cameraModel).joinToString(" ") == cam } }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${displayTiles.size} ${L10n.t("detail.media")}", color = Veyq.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (controller.tiles.any { it.highlighted }) {
                        TextButton(onClick = { showHighlightsOnly = !showHighlightsOnly }) {
                            Text((if (showHighlightsOnly) "★ " else "☆ ") + L10n.t("detail.highlights"), color = if (showHighlightsOnly) Veyq.Accent else Veyq.Muted, fontSize = 13.sp)
                        }
                    }
                }
                selectedBucket?.let { b ->
                    val cams = controller.tiles.filter { controller.tileInBucket(it, b) }.mapNotNull { listOfNotNull(it.cameraMake, it.cameraModel).joinToString(" ").ifEmpty { null } }.distinct().sorted()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { selectedBucketId = null; cameraFilter = null }) { Text("✕ ${controller.bucketLabel(b).ifEmpty { L10n.t("contrib.member") }}", color = Veyq.Accent, fontSize = 13.sp) }
                        if (cams.isNotEmpty()) {
                            var camMenu by remember(b.id) { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { camMenu = true }) { Text("📷 ${cameraFilter ?: L10n.t("contrib.allCameras")}", color = Veyq.Muted, fontSize = 13.sp) }
                                DropdownMenu(expanded = camMenu, onDismissRequest = { camMenu = false }) {
                                    DropdownMenuItem(text = { Text(L10n.t("contrib.allCameras")) }, onClick = { cameraFilter = null; camMenu = false })
                                    cams.forEach { cam -> DropdownMenuItem(text = { Text(cam) }, onClick = { cameraFilter = cam; camMenu = false }) }
                                }
                            }
                        }
                    }
                }
                when {
                    controller.loading -> Text(L10n.t("detail.loadingGallery"), color = Veyq.Muted, fontSize = 14.sp)
                    controller.loadFailed -> {
                        Text(L10n.t("detail.loadFailed"), color = Veyq.Muted, fontSize = 14.sp)
                        TextButton(onClick = { scope.launch { controller.loading = true; controller.load() } }) { Text(L10n.t("common.retry"), color = Veyq.Accent) }
                    }
                    displayTiles.isEmpty() -> Text(L10n.t("detail.noMedia"), color = Veyq.Muted, fontSize = 14.sp)
                    else -> {
                        val range = DateBuckets.dateRange(displayTiles) { it.takenAt }
                        if (range.isNotEmpty()) Text(range, color = Veyq.Muted, fontSize = 13.sp)
                        DateBuckets.groupByDay(displayTiles) { it.takenAt }.forEach { (key, items) ->
                            Text(DateBuckets.dayLabel(key), color = Veyq.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Gallery(items) { lightboxTile = it }
                        }
                    }
                }
            }
        }
    }

    lightboxTile?.let { opened ->
        // Re-derive the live tile from the (observable) list so the favourite star updates after a toggle.
        val tile = controller.tiles.firstOrNull { it.id == opened.id } ?: opened
        val idx = controller.tiles.indexOfFirst { it.id == tile.id }
        fun goto(i: Int) { if (i in controller.tiles.indices) { lightboxTile = controller.tiles[i]; lightboxInfo = false; editingDate = false; mediaDelArm = false } }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(16.dp)) {
                Box(Modifier.fillMaxWidth()) {
                    var zoom by remember(tile.id) { mutableStateOf(1f) }
                    Image(
                        lightboxPreview ?: tile.image, null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth()
                            .graphicsLayer(scaleX = zoom, scaleY = zoom)
                            .pointerInput(tile.id) { detectTransformGestures { _, _, z, _ -> zoom = (zoom * z).coerceIn(1f, 5f) } }
                            .pointerInput(tile.id) { detectTapGestures(onDoubleTap = { zoom = if (zoom > 1f) 1f else 2.5f }) },
                    )
                    if (idx > 0) TextButton(onClick = { goto(idx - 1) }, modifier = Modifier.align(Alignment.CenterStart)) { Text("‹", color = Color.White, fontSize = 34.sp) }
                    if (idx < controller.tiles.size - 1) TextButton(onClick = { goto(idx + 1) }, modifier = Modifier.align(Alignment.CenterEnd)) { Text("›", color = Color.White, fontSize = 34.sp) }
                }
                if (lightboxInfo) {
                    Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InfoLine(L10n.t("info.takenAt"), DateBuckets.dayLabel(tile.takenAt.take(10)))
                        InfoLine(L10n.t("info.type"), tile.mimeType)
                        if (tile.lat != null && tile.lng != null) InfoLine(L10n.t("info.location"), String.format(java.util.Locale.US, "%.5f, %.5f", tile.lat, tile.lng))
                        tile.cameraModel?.let { InfoLine(L10n.t("info.camera"), listOfNotNull(tile.cameraMake, it).joinToString(" ")) }
                        tile.lens?.let { InfoLine(L10n.t("info.lens"), it) }
                        if (tile.width != null && tile.height != null) InfoLine(L10n.t("info.dimensions"), "${tile.width}×${tile.height}")
                        tile.fileSize?.let { InfoLine(L10n.t("info.fileSize"), formatBytes(it)) }
                        tile.exif?.toSortedMap()?.forEach { (k, v) -> InfoLine(k, v) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = { scope.launch { controller.toggleHighlight(tile) } }) { Text(if (tile.highlighted) "★" else "☆", color = if (tile.highlighted) Veyq.Accent else Color.White, fontSize = 18.sp) }
                    TextButton(onClick = { lightboxInfo = !lightboxInfo }) { Text("ⓘ", color = Color.White, fontSize = 18.sp) }
                    TextButton(onClick = { editingDate = true }) { Text("📅", fontSize = 16.sp) }
                    if (tile.createdBy == controller.ownerKey && controller.buckets.any { controller.isOwnBucket(it) }) {
                        var moveMenu by remember(tile.id) { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { moveMenu = true }) { Text("🗂", fontSize = 16.sp) }
                            DropdownMenu(expanded = moveMenu, onDismissRequest = { moveMenu = false }) {
                                controller.buckets.filter { controller.isOwnBucket(it) }.forEach { b ->
                                    DropdownMenuItem(text = { Text(if (b.primary) L10n.t("contrib.moveToPrimary") else controller.bucketLabel(b).ifEmpty { L10n.t("contrib.member") }) }, onClick = { moveMenu = false; scope.launch { controller.moveMedia(tile, b.id) } })
                                }
                            }
                        }
                    }
                    TextButton(onClick = { val id = tile.id; lightboxTile = null; scope.launch { controller.setCover(id) } }) { Text(L10n.t("cover.setAsCover"), color = Veyq.Accent) }
                    TextButton(enabled = !savingOriginal, onClick = { saveOriginal(tile) }) { Text(L10n.t("detail.download"), color = Color.White) }
                    if (!mediaDelArm) {
                        TextButton(onClick = { mediaDelArm = true }) { Text(L10n.t("common.delete"), color = Veyq.Danger) }
                    } else {
                        TextButton(onClick = { val id = tile.id; lightboxTile = null; mediaDelArm = false; scope.launch { controller.deleteMedia(id) } }) { Text(L10n.t("delete.confirm"), color = Veyq.Danger) }
                    }
                    TextButton(onClick = { lightboxTile = null; mediaDelArm = false; lightboxInfo = false }) { Text(L10n.t("common.close"), color = Color.White) }
                }
            }
        }
        if (editingDate) {
            DateEditDialog(tile.takenAt, onDismiss = { editingDate = false }) { iso ->
                editingDate = false
                scope.launch { controller.updateTakenAt(tile, iso) }
            }
        }
    }

    if (creatingBucket) BucketNameDialog(L10n.t("contrib.newBucket"), "", onDismiss = { creatingBucket = false }) { name -> creatingBucket = false; scope.launch { controller.createBucket(name) } }
    renamingBucket?.let { b -> BucketNameDialog(L10n.t("common.rename"), controller.bucketLabel(b), onDismiss = { renamingBucket = null }) { name -> renamingBucket = null; scope.launch { controller.renameBucket(b.id, name) } } }
    renamingTrack?.let { tr -> BucketNameDialog(L10n.t("track.renamePrompt"), tr.name, onDismiss = { renamingTrack = null }) { name -> renamingTrack = null; scope.launch { controller.renameTrack(tr, name) } } }
    if (addingPlaylist) PlaylistDialog(onDismiss = { addingPlaylist = false }) { url, title -> addingPlaylist = false; scope.launch { controller.addPlaylist(url, title) } }

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
    // The osmdroid tiles reveal the decrypted coordinates to OpenStreetMap, so the map loads only after consent.
    // Stored locally (per device), revocable in Settings — never synced to the operator-blind server.
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("veyq", android.content.Context.MODE_PRIVATE) }
    var consent by remember { mutableStateOf(prefs.getBoolean("veyq.privacy.mapTiles", false)) }
    if (!consent) {
        Column(
            modifier = Modifier.fillMaxWidth().height(420.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(L10n.t("map.consentText"), color = Veyq.Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
            TextButton(onClick = { prefs.edit().putBoolean("veyq.privacy.mapTiles", true).apply(); consent = true }) {
                Text(L10n.t("map.consentEnable"), color = Veyq.Accent)
            }
        }
        return
    }
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.fillMaxWidth().height(420.dp).clip(RoundedCornerShape(Veyq.Radius)),
        factory = { ctx ->
            org.osmdroid.config.Configuration.getInstance().userAgentValue = ctx.packageName
            org.osmdroid.config.Configuration.getInstance().osmdroidBasePath = ctx.cacheDir
            org.osmdroid.views.MapView(ctx).apply {
                setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                val geo = mutableListOf<org.osmdroid.util.GeoPoint>()
                // A small palette so multiple tracks are visually distinguishable (mirrors web TRACK_COLORS).
                val trackColors = longArrayOf(0xFF9B8CFF, 0xFF34C796, 0xFFF7944A, 0xFF66AAFA, 0xFFF073A3, 0xFFD9C746)
                tracks.forEachIndexed { i, t ->
                    val line = t.points.map { org.osmdroid.util.GeoPoint(it.lat, it.lng) }
                    if (line.size > 1) {
                        val poly = org.osmdroid.views.overlay.Polyline().apply {
                            setPoints(line); title = t.name; outlinePaint.color = trackColors[i % trackColors.size].toInt(); outlinePaint.strokeWidth = 8f
                        }
                        overlays.add(poly)
                        // Start (green) and end (red) endpoint dots per track, like the web map.
                        fun endpoint(p: org.osmdroid.util.GeoPoint, argb: Int) {
                            overlays.add(org.osmdroid.views.overlay.Polyline().apply {
                                setPoints(listOf(p, p)); outlinePaint.color = argb; outlinePaint.strokeWidth = 18f; outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                            })
                        }
                        endpoint(line.first(), 0xFF2ECC71.toInt())
                        endpoint(line.last(), 0xFFE74C3C.toInt())
                    }
                    geo.addAll(line)
                }
                points.forEach { (_, lat, lng) -> geo.add(org.osmdroid.util.GeoPoint(lat, lng)) }
                // Media markers are clustered by on-screen proximity, re-clustered on every zoom/scroll (Legacy parity).
                val mv = this
                val clusterOverlays = mutableListOf<org.osmdroid.views.overlay.Overlay>()
                val clusterPx = 60.0
                fun rebuildClusters() {
                    clusterOverlays.forEach { mv.overlays.remove(it) }
                    clusterOverlays.clear()
                    val proj = mv.projection ?: return
                    data class G(val px: android.graphics.Point, val pts: MutableList<Triple<String, Double, Double>>)
                    val groups = mutableListOf<G>()
                    points.sortedBy { it.first }.forEach { p ->
                        val pt = proj.toPixels(org.osmdroid.util.GeoPoint(p.second, p.third), null)
                        val g = groups.firstOrNull { kotlin.math.hypot((it.px.x - pt.x).toDouble(), (it.px.y - pt.y).toDouble()) <= clusterPx }
                        if (g != null) g.pts.add(p) else groups.add(G(pt, mutableListOf(p)))
                    }
                    groups.forEach { g ->
                        val lat = g.pts.map { it.second }.average(); val lng = g.pts.map { it.third }.average()
                        val m = org.osmdroid.views.overlay.Marker(mv)
                        m.position = org.osmdroid.util.GeoPoint(lat, lng)
                        m.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, if (g.pts.size == 1) org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM else org.osmdroid.views.overlay.Marker.ANCHOR_CENTER)
                        if (g.pts.size == 1) {
                            val id = g.pts[0].first
                            m.setOnMarkerClickListener { _, _ -> onSelect(id); true }
                        } else {
                            m.icon = clusterIconDrawable(mv.resources, g.pts.size)
                            m.setOnMarkerClickListener { _, _ -> mv.controller.setZoom(mv.zoomLevelDouble + 2.0); mv.controller.animateTo(m.position); true }
                        }
                        mv.overlays.add(m); clusterOverlays.add(m)
                    }
                    mv.invalidate()
                }
                addMapListener(object : org.osmdroid.events.MapListener {
                    override fun onScroll(e: org.osmdroid.events.ScrollEvent?): Boolean { rebuildClusters(); return false }
                    override fun onZoom(e: org.osmdroid.events.ZoomEvent?): Boolean { rebuildClusters(); return false }
                })
                if (geo.size == 1) {
                    controller.setCenter(geo[0]); controller.setZoom(13.0)
                    post { rebuildClusters() }
                } else if (geo.size > 1) {
                    post { zoomToBoundingBox(org.osmdroid.util.BoundingBox.fromGeoPoints(geo).increaseByScale(1.4f), false); rebuildClusters() }
                } else {
                    post { rebuildClusters() }
                }
            }
        },
    )
}

/** A violet count-badge drawable for a cluster of overlapping media markers. */
private fun clusterIconDrawable(resources: android.content.res.Resources, count: Int): android.graphics.drawable.Drawable {
    val dp = resources.displayMetrics.density
    val sz = (30 * dp).toInt().coerceAtLeast(1)
    val bmp = android.graphics.Bitmap.createBitmap(sz, sz, android.graphics.Bitmap.Config.ARGB_8888)
    val cv = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.color = 0xFF9B8CFF.toInt()
    cv.drawCircle(sz / 2f, sz / 2f, sz / 2f - 2 * dp, paint)
    paint.color = android.graphics.Color.WHITE; paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 2 * dp
    cv.drawCircle(sz / 2f, sz / 2f, sz / 2f - 2 * dp, paint)
    paint.style = android.graphics.Paint.Style.FILL; paint.textAlign = android.graphics.Paint.Align.CENTER; paint.textSize = 12 * dp; paint.isFakeBoldText = true
    val fm = paint.fontMetrics
    cv.drawText(count.toString(), sz / 2f, sz / 2f - (fm.ascent + fm.descent) / 2, paint)
    return android.graphics.drawable.BitmapDrawable(resources, bmp)
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
private fun JournalTab(controller: GalleryController, scope: kotlinx.coroutines.CoroutineScope, onOpenChapter: (String) -> Unit = {}, onOpenMedia: (GalleryController.Tile) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(false) }
    val blocks = remember { mutableStateListOf<EditBlock>() }
    LaunchedEffect(Unit) { controller.loadJournal() }
    // Track embeds need the tracks loaded (inline map); playlist embeds need the playlists (link card).
    LaunchedEffect(controller.journalMarkdown) {
        val embedKinds = JournalEmbedParser.splitBlocks(controller.journalMarkdown).filterIsInstance<JournalContentBlock.Embed>().map { it.embed.kind }
        if (JournalEmbedKind.TRACK in embedKinds) controller.loadTracks()
        if (JournalEmbedKind.PLAYLIST in embedKinds) controller.loadPlaylists()
    }

    if (live) {
        // The live section persists via the CRDT store (compaction on close); just reflect the text + exit.
        LiveJournalSection(controller) { md -> controller.journalMarkdown = md; live = false }
    } else if (editing) {
        JournalBlockEditor(controller, blocks, scope)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { editing = false }) { Text(L10n.t("common.cancel"), color = Veyq.Muted) }
            TextButton(onClick = { val md = journalMarkdownFrom(blocks); scope.launch { controller.saveJournal(md); editing = false } }, enabled = !controller.journalSaving) {
                Text(if (controller.journalSaving) L10n.t("journal.saving") else L10n.t("journal.save"), color = Veyq.Accent)
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { live = true }) { Text(L10n.t("journal.live"), color = Veyq.Accent) }
            TextButton(onClick = { blocks.clear(); blocks.addAll(journalEditBlocks(controller.journalMarkdown)); scope.launch { controller.loadTracks() }; editing = true }) { Text(L10n.t("journal.edit"), color = Veyq.Accent) }
        }
        if (controller.journalMarkdown.isBlank()) {
            Text(L10n.t("journal.empty"), color = Veyq.Muted, fontSize = 14.sp)
        } else {
            JournalBody(controller.journalMarkdown, controller.tiles, controller.tracks, controller.playlists, onOpenMedia, onOpenChapter)
        }
    }
}

/** Read-only journal renderer with rich embeds (matches web/iOS): Markdown text interleaved with ::media
 *  (thumbnail / horizontal carousel → lightbox) and ::track (inline map). ::chapter renders as a simple chip. */
@Composable
private fun JournalBody(markdown: String, tiles: List<GalleryController.Tile>, tracks: List<Track.TrackItem>, playlists: List<Playlists.Item>, onOpenMedia: (GalleryController.Tile) -> Unit, onOpenChapter: (String) -> Unit = {}) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        JournalEmbedParser.splitBlocks(markdown).forEach { block ->
            when (block) {
                is JournalContentBlock.Markdown -> if (block.text.isNotBlank()) MarkdownText(block.text)
                is JournalContentBlock.Embed -> {
                    val e = block.embed
                    when (e.kind) {
                        JournalEmbedKind.MEDIA -> {
                            val items = e.ids.mapNotNull { id -> tiles.firstOrNull { it.id == id } }
                            if (items.isEmpty()) Text(L10n.t("journal.embedMissing"), color = Veyq.Muted, fontSize = 13.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                            else if (items.size == 1) Image(items[0].image, null, modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(10.dp)).clickable { onOpenMedia(items[0]) }, contentScale = ContentScale.Fit)
                            else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items.forEach { tile -> Image(tile.image, null, modifier = Modifier.height(180.dp).clip(RoundedCornerShape(8.dp)).clickable { onOpenMedia(tile) }, contentScale = ContentScale.Fit) }
                            }
                            e.label?.let { Text(it, color = Veyq.Muted, fontSize = 12.sp) }
                        }
                        JournalEmbedKind.TRACK -> {
                            val routes = tracks.filter { e.ids.contains(it.id) }
                            if (routes.isEmpty()) Text(L10n.t("journal.embedMissing"), color = Veyq.Muted, fontSize = 13.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                            else {
                                MemoryMap(emptyList(), routes) {}
                                routes.forEach { TrackStatsRow(it) }
                            }
                            e.label?.let { Text(it, color = Veyq.Muted, fontSize = 12.sp) }
                        }
                        JournalEmbedKind.CHAPTER -> {
                            val cid = e.ids.firstOrNull() ?: ""
                            Row(
                                Modifier.clip(CircleShape).background(Veyq.Surface).border(1.dp, Veyq.Line, CircleShape)
                                    .clickable(enabled = cid.isNotEmpty()) { onOpenChapter(cid) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) { Text("📖"); Text(e.label ?: cid, color = Veyq.Accent, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline) }
                        }
                        JournalEmbedKind.PLAYLIST -> {
                            val p = playlists.firstOrNull { it.id == e.ids.firstOrNull() }
                            if (p == null) Text(L10n.t("journal.embedMissing"), color = Veyq.Muted, fontSize = 13.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                            else Row(
                                Modifier.clip(RoundedCornerShape(10.dp)).background(Veyq.Surface).border(1.dp, Veyq.Line, RoundedCornerShape(10.dp))
                                    .clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(p.url))) } }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(p.provider.label, color = Veyq.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(e.label ?: (p.title?.ifEmpty { null } ?: p.url), color = Veyq.Accent, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline, maxLines = 1)
                            }
                        }
                    }
                }
            }
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
            provider.seedIfEmpty(initial) // bootstrap a legacy Markdown-only journal into the CRDT (one source of truth)
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

// ---- block-based journal editor (mobile parity with iOS) ----------------------------------------------------
// The journal is a vertical list of editable text blocks interleaved with embed CHIPS. Tapping a chip opens a
// picker to re-select or delete it; the embed buttons append a new embed. Embeds sit on their own line, so the
// Markdown round-trips with the web format. Android authors ::media / ::track (legacy had no ::chapter).

data class EmbedData(val kind: JournalEmbedKind, val ids: List<String>, val label: String?)

class EditBlock(initialText: String = "", val embed: EmbedData? = null) {
    val id: String = java.util.UUID.randomUUID().toString()
    var text by mutableStateOf(initialText)
}

fun journalEditBlocks(markdown: String): List<EditBlock> {
    val list = JournalEmbedParser.splitBlocks(markdown).mapNotNull { b ->
        when (b) {
            is JournalContentBlock.Markdown -> b.text.trim().ifEmpty { null }?.let { EditBlock(initialText = it) }
            is JournalContentBlock.Embed -> EditBlock(embed = EmbedData(b.embed.kind, b.embed.ids, b.embed.label))
        }
    }.toMutableList()
    if (list.isEmpty() || list.last().embed != null) list.add(EditBlock(initialText = ""))
    return list
}

fun journalMarkdownFrom(blocks: List<EditBlock>): String =
    blocks.mapNotNull { b ->
        if (b.embed != null) JournalEmbedParser.embedMarkdown(b.embed.kind, b.embed.ids, b.embed.label)
        else b.text.trim().ifEmpty { null }
    }.joinToString("\n\n")

private sealed interface PickerState {
    data class Insert(val kind: JournalEmbedKind) : PickerState
    data class Edit(val index: Int) : PickerState
}
private data class PickerResult(val ids: List<String>, val label: String?, val delete: Boolean = false)

private fun chipLabel(e: EmbedData, controller: GalleryController): String {
    if (!e.label.isNullOrEmpty()) return e.label
    return when (e.kind) {
        JournalEmbedKind.MEDIA -> "${e.ids.size} ${L10n.t("detail.media")}"
        JournalEmbedKind.TRACK -> controller.tracks.firstOrNull { it.id == e.ids.firstOrNull() }?.name ?: "${e.ids.size}"
        JournalEmbedKind.CHAPTER -> e.ids.firstOrNull() ?: ""
        JournalEmbedKind.PLAYLIST -> controller.playlists.firstOrNull { it.id == e.ids.firstOrNull() }?.let { it.title?.ifEmpty { null } ?: it.url } ?: (e.ids.firstOrNull() ?: "")
    }
}

@Composable
private fun JournalBlockEditor(controller: GalleryController, blocks: SnapshotStateList<EditBlock>, scope: kotlinx.coroutines.CoroutineScope) {
    var picker by remember { mutableStateOf<PickerState?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { picker = PickerState.Insert(JournalEmbedKind.MEDIA) }) { Text("🖼 ${L10n.t("journal.embedKind.media")}", color = Veyq.Accent) }
            TextButton(onClick = { scope.launch { controller.loadTracks() }; picker = PickerState.Insert(JournalEmbedKind.TRACK) }) { Text("🛤 ${L10n.t("journal.embedKind.track")}", color = Veyq.Accent) }
            TextButton(onClick = { scope.launch { controller.loadPlaylists() }; picker = PickerState.Insert(JournalEmbedKind.PLAYLIST) }) { Text("🎵 ${L10n.t("journal.embedKind.playlist")}", color = Veyq.Accent) }
        }
        blocks.forEachIndexed { idx, block ->
            val e = block.embed
            if (e != null) {
                Row(
                    Modifier.clip(CircleShape).background(Veyq.Surface).border(1.dp, Veyq.Line, CircleShape).clickable { picker = PickerState.Edit(idx) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (e.kind == JournalEmbedKind.MEDIA) "🖼" else if (e.kind == JournalEmbedKind.TRACK) "🛤" else if (e.kind == JournalEmbedKind.PLAYLIST) "🎵" else "🗂")
                    Text(chipLabel(e, controller), color = Veyq.Text)
                    Text("✎", color = Veyq.Muted, fontSize = 12.sp)
                }
            } else {
                OutlinedTextField(
                    value = block.text, onValueChange = { block.text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp), shape = RoundedCornerShape(Veyq.Radius),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Veyq.Surface, unfocusedContainerColor = Veyq.Surface,
                        focusedBorderColor = Veyq.Accent, unfocusedBorderColor = Veyq.Line,
                        focusedTextColor = Veyq.Text, unfocusedTextColor = Veyq.Text,
                    ),
                )
            }
        }
    }
    picker?.let { st ->
        val kind = when (st) { is PickerState.Insert -> st.kind; is PickerState.Edit -> blocks[st.index].embed!!.kind }
        val initial = (st as? PickerState.Edit)?.let { blocks[it.index].embed }
        EmbedPickerDialog(kind, controller, initial, onDismiss = { picker = null }) { result ->
            when (st) {
                is PickerState.Insert -> if (result != null && !result.delete && result.ids.isNotEmpty()) {
                    blocks.add(EditBlock(embed = EmbedData(kind, result.ids, result.label)))
                    blocks.add(EditBlock(initialText = ""))
                }
                is PickerState.Edit -> if (result != null) {
                    if (result.delete || result.ids.isEmpty()) blocks.removeAt(st.index)
                    else blocks[st.index] = EditBlock(embed = EmbedData(kind, result.ids, result.label))
                }
            }
            picker = null
        }
    }
}

@Composable
private fun EmbedPickerDialog(kind: JournalEmbedKind, controller: GalleryController, initial: EmbedData?, onDismiss: () -> Unit, onResult: (PickerResult?) -> Unit) {
    var selected by remember { mutableStateOf(initial?.ids ?: emptyList()) }
    var label by remember { mutableStateOf(initial?.label ?: "") }
    LaunchedEffect(Unit) {
        if (kind == JournalEmbedKind.TRACK) controller.loadTracks()
        if (kind == JournalEmbedKind.PLAYLIST) controller.loadPlaylists()
    }
    fun toggle(id: String) { selected = if (selected.contains(id)) selected - id else selected + id }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Veyq.Surface,
        title = { Text(when (kind) { JournalEmbedKind.MEDIA -> L10n.t("journal.mediaPickerTitle"); JournalEmbedKind.PLAYLIST -> L10n.t("journal.playlistPickerTitle"); else -> L10n.t("journal.trackPickerTitle") }, color = Veyq.Text) },
        text = {
            Column(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label, onValueChange = { label = it }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(if (kind == JournalEmbedKind.MEDIA) L10n.t("journal.caption") else L10n.t("journal.label"), color = Veyq.Muted) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Veyq.Bg, unfocusedContainerColor = Veyq.Bg,
                        focusedBorderColor = Veyq.Accent, unfocusedBorderColor = Veyq.Line,
                        focusedTextColor = Veyq.Text, unfocusedTextColor = Veyq.Text,
                    ),
                )
                if (kind == JournalEmbedKind.MEDIA) {
                    if (controller.tiles.isEmpty()) Text(L10n.t("journal.mediaPickerEmpty"), color = Veyq.Muted, fontSize = 13.sp)
                    else Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        controller.tiles.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.forEach { tile ->
                                    Box(
                                        Modifier.size(92.dp).clip(RoundedCornerShape(8.dp)).clickable { toggle(tile.id) }
                                            .border(3.dp, if (selected.contains(tile.id)) Veyq.Accent else Color.Transparent, RoundedCornerShape(8.dp)),
                                    ) { Image(tile.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                }
                            }
                        }
                    }
                } else if (kind == JournalEmbedKind.PLAYLIST) {
                    if (controller.playlists.isEmpty()) Text(L10n.t("journal.pickerEmpty"), color = Veyq.Muted, fontSize = 13.sp)
                    else Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        controller.playlists.forEach { p ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { selected = listOf(p.id) }.background(Veyq.Bg).padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(p.provider.label, color = Veyq.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(p.title?.ifEmpty { null } ?: p.url, color = Veyq.Text, modifier = Modifier.weight(1f), maxLines = 1)
                                if (selected.contains(p.id)) Text("✓", color = Veyq.Accent)
                            }
                        }
                    }
                } else {
                    if (controller.tracks.isEmpty()) Text(L10n.t("journal.pickerEmpty"), color = Veyq.Muted, fontSize = 13.sp)
                    else Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        controller.tracks.forEach { tr ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { toggle(tr.id) }.background(Veyq.Bg).padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("🛤 ${tr.name}", color = Veyq.Text, modifier = Modifier.weight(1f))
                                if (selected.contains(tr.id)) Text("✓", color = Veyq.Accent)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = selected.isNotEmpty(), onClick = { onResult(PickerResult(selected, label.ifEmpty { null })) }) { Text(L10n.t("journal.insert"), color = Veyq.Accent) } },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = { onResult(PickerResult(emptyList(), null, delete = true)) }) { Text(L10n.t("common.delete"), color = Veyq.Danger) }
                TextButton(onClick = { onResult(null) }) { Text(L10n.t("common.cancel"), color = Veyq.Muted) }
            }
        },
    )
}

/** One track's derived statistics line: name + distance, duration (when present), elevation (when present).
 *  Computed client-side from the route points (the operator-blind server never sees them). */
@Composable
private fun TrackStatsRow(route: Track.TrackItem) {
    val stats = remember(route.id) { Track.stats(route.points) }
    val ele = Track.formatElevation(stats)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("🛤 ${route.name}", color = Veyq.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(Track.formatDistance(stats.distanceM), color = Veyq.Muted, fontSize = 13.sp)
        stats.durationS?.let { Text(Track.formatDuration(it), color = Veyq.Muted, fontSize = 13.sp) }
        if (ele.isNotEmpty()) Text(ele, color = Veyq.Muted, fontSize = 13.sp)
    }
}

/** The Info tab: media count, date range, track count, chapter count (matches web/iOS). */
@Composable
private fun InfoTab(controller: GalleryController) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Veyq.Radius)).background(Veyq.Surface)) {
        InfoTabRow(L10n.t("detail.gallery"), "${controller.tiles.size}")
        val range = DateBuckets.dateRange(controller.tiles) { it.takenAt }
        if (range.isNotEmpty()) InfoTabRow(L10n.t("info.dateRange"), range)
        InfoTabRow(L10n.t("detail.map"), "${controller.tracks.size}")
        InfoTabRow(L10n.t("info.chapters"), "${controller.chapterCount}")
    }
}

@Composable
private fun InfoTabRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Veyq.Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = Veyq.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BucketNameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Veyq.Surface,
        title = { Text(title, color = Veyq.Text) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Veyq.Bg, unfocusedContainerColor = Veyq.Bg,
                    focusedBorderColor = Veyq.Accent, unfocusedBorderColor = Veyq.Line,
                    focusedTextColor = Veyq.Text, unfocusedTextColor = Veyq.Text,
                ),
            )
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text(L10n.t("journal.save"), color = Veyq.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L10n.t("common.cancel"), color = Veyq.Muted) } },
    )
}

@Composable
private fun PlaylistDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = Veyq.Bg, unfocusedContainerColor = Veyq.Bg,
        focusedBorderColor = Veyq.Accent, unfocusedBorderColor = Veyq.Line,
        focusedTextColor = Veyq.Text, unfocusedTextColor = Veyq.Text,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Veyq.Surface,
        title = { Text(L10n.t("playlist.add"), color = Veyq.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(L10n.t("playlist.urlPrompt"), color = Veyq.Muted) }, colors = fieldColors)
                OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(L10n.t("playlist.titlePrompt"), color = Veyq.Muted) }, colors = fieldColors)
            }
        },
        confirmButton = { TextButton(enabled = url.isNotBlank(), onClick = { onConfirm(url.trim(), title.trim()) }) { Text(L10n.t("playlist.add"), color = Veyq.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L10n.t("common.cancel"), color = Veyq.Muted) } },
    )
}

private fun formatBytes(n: Int): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "${n / 1024} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", n / 1024.0 / 1024.0)
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 13.sp)
    }
}

/** A child chapter/folder/memory shown as a tappable cover card (cover thumbnail or an emoji fallback). */
@Composable
private fun ChildCard(child: GalleryController.Child, onClick: () -> Unit) {
    Column(Modifier.width(132.dp).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(132.dp, 92.dp).clip(RoundedCornerShape(8.dp)).background(Veyq.Surface), contentAlignment = Alignment.Center) {
            if (child.cover != null) Image(child.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(if (child.type == "CHAPTER") "📖" else if (child.type == "FOLDER") "🗂" else "🖼", fontSize = 28.sp)
        }
        Text(child.name, color = Veyq.Text, fontSize = 12.sp, maxLines = 1)
    }
}

/** A capture-date editor: pick a new date, keeping the original time-of-day; re-seals sealedMeta on confirm. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateEditDialog(currentIso: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val origMillis = runCatching { java.time.Instant.parse(currentIso).toEpochMilli() }.getOrDefault(System.currentTimeMillis())
    val state = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = origMillis)
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val sel = state.selectedDateMillis
                if (sel != null) {
                    val orig = java.time.Instant.ofEpochMilli(origMillis).atZone(java.time.ZoneOffset.UTC)
                    val chosen = java.time.Instant.ofEpochMilli(sel).atZone(java.time.ZoneOffset.UTC)
                    val merged = orig.withYear(chosen.year).withDayOfYear(chosen.dayOfYear).toInstant()
                    onConfirm(merged.toString())
                } else onDismiss()
            }) { Text(L10n.t("journal.save"), color = Veyq.Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L10n.t("common.cancel"), color = Veyq.Muted) } },
    ) { androidx.compose.material3.DatePicker(state = state) }
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
                    Box(Modifier.offset(t.left.dp, t.top.dp).size(t.width.dp, t.height.dp)) {
                        Image(
                            tile.image,
                            null,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).clickable { onTap(tile) },
                            contentScale = ContentScale.Crop,
                        )
                        if (tile.highlighted) {
                            Box(Modifier.align(Alignment.TopEnd).padding(4.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).padding(3.dp)) {
                                Text("★", color = Color(0xFFFFD23F), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
