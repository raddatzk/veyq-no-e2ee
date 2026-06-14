package app.veyq.android.ui

import android.graphics.BitmapFactory
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.veyq.crypto.Login
import app.veyq.crypto.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
    LaunchedEffect(Unit) { controller.load() }

    Scaffold(
        containerColor = Veyq.Bg,
        topBar = {
            TopAppBar(
                title = { Text(name, color = Veyq.Text, fontWeight = FontWeight.Bold, maxLines = 1) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Veyq.Surface),
                navigationIcon = { IconButton(onClick = onBack) { Text("‹", color = Veyq.Text, fontSize = 24.sp) } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${controller.tiles.size} Medien", color = Veyq.Muted, fontSize = 13.sp)
            when {
                controller.loading -> Text("Lädt Galerie…", color = Veyq.Muted, fontSize = 14.sp)
                controller.tiles.isEmpty() -> Text("Noch keine Medien.", color = Veyq.Muted, fontSize = 14.sp)
                else -> Gallery(controller.tiles) { lightbox = it }
            }
        }
    }

    lightbox?.let { img ->
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)).clickable { lightbox = null }, contentAlignment = Alignment.Center) {
            Image(img, null, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
        }
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
