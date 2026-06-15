package app.veyq.android.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.veyq.crypto.Login
import app.veyq.crypto.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    data class Tile(val id: String, val image: ImageBitmap, val aspectRatio: Float)

    var tiles by mutableStateOf<List<Tile>>(emptyList())
    var loading by mutableStateOf(true)
    var uploading by mutableStateOf(false)

    suspend fun upload(items: List<ByteArray>) {
        uploading = true
        val takenAt = Instant.now().toString()
        for (data in items) {
            runCatching {
                withContext(Dispatchers.IO) {
                    Media.uploadMedia(apiBase, session, scopeId, contentKey, nodeId, data, thumbnail(data), takenAt, "image/jpeg")
                }
            }
        }
        load()
        uploading = false
    }

    private fun thumbnail(data: ByteArray, maxDim: Int = 400): ByteArray? {
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        val scale = minOf(1f, maxDim.toFloat() / maxOf(bmp.width, bmp.height))
        val w = maxOf(1, (bmp.width * scale).toInt())
        val h = maxOf(1, (bmp.height * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }

    suspend fun load() {
        try {
            val media = withContext(Dispatchers.IO) { Media.listMedia(apiBase, session, scopeId, nodeId) }
            val result = mutableListOf<Tile>()
            for (m in media) {
                val derivs = m.getJSONArray("derivatives")
                var blob: JSONObject = m.getJSONObject("original")
                for (i in 0 until derivs.length()) {
                    if (derivs.getJSONObject(i).getString("kind") == "THUMBNAIL") blob = derivs.getJSONObject(i).getJSONObject("blob")
                }
                val ref = Media.BlobRef(blob.getString("blobId"), blob.getInt("totalChunks"), blob.getLong("byteSize"))
                val bytes = withContext(Dispatchers.IO) { Media.downloadBlob(apiBase, session, scopeId, contentKey, ref) }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                result.add(Tile(m.getString("id"), bmp.asImageBitmap(), JustifiedLayout.clampAspect(bmp.width.toFloat(), bmp.height.toFloat())))
            }
            tiles = result
        } catch (e: Exception) {
            // leave empty
        }
        loading = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, nodeId: String, name: String, onBack: () -> Unit) {
    val controller = remember { GalleryController(apiBase, session, scopeId, contentKey, nodeId) }
    var lightbox by remember { mutableStateOf<ImageBitmap?>(null) }
    var showDownload by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { controller.load() }

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

    Scaffold(
        containerColor = Veyq.Bg,
        topBar = {
            TopAppBar(
                title = { Text(name, color = Veyq.Text, fontWeight = FontWeight.Bold, maxLines = 1) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Veyq.Surface),
                navigationIcon = { IconButton(onClick = onBack) { Text("‹", color = Veyq.Text, fontSize = 24.sp) } },
                actions = {
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
            Text("${controller.tiles.size} ${L10n.t("detail.media")}", color = Veyq.Muted, fontSize = 13.sp)
            when {
                controller.loading -> Text(L10n.t("detail.loadingGallery"), color = Veyq.Muted, fontSize = 14.sp)
                controller.tiles.isEmpty() -> Text(L10n.t("detail.noMedia"), color = Veyq.Muted, fontSize = 14.sp)
                else -> Gallery(controller.tiles) { lightbox = it }
            }
        }
    }

    lightbox?.let { img ->
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)).clickable { lightbox = null }, contentAlignment = Alignment.Center) {
            Image(img, null, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
        }
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

@Composable
private fun Gallery(tiles: List<GalleryController.Tile>, onTap: (ImageBitmap) -> Unit) {
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
                            .clickable { onTap(tile.image) },
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
    }
}
