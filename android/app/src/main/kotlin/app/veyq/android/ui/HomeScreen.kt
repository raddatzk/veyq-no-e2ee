package app.veyq.android.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import app.veyq.crypto.Browse
import app.veyq.crypto.Login
import app.veyq.crypto.Media
import app.veyq.crypto.MemoryTree
import app.veyq.crypto.ScopeContent
import app.veyq.crypto.SwitchActiveSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** MemoryHome state holder. Blocking core calls run on IO; Compose state is updated on the caller's (main). */
class HomeController(val apiBase: String, val session: Login.UnlockedSession) {
    data class Entry(val id: String, val type: String, val name: String)
    data class Crumb(val nodeId: String?, val name: String)

    var spaces by mutableStateOf<List<JSONObject>>(emptyList())
    var activeScopeId by mutableStateOf<String?>(null)
    var folders by mutableStateOf<List<Entry>>(emptyList())
    var memories by mutableStateOf<List<Entry>>(emptyList())
    var covers by mutableStateOf<Map<String, ImageBitmap>>(emptyMap())
    var crumbs by mutableStateOf<List<Crumb>>(emptyList())
    var error by mutableStateOf<String?>(null)
    var searchText by mutableStateOf("")
    var searchHits by mutableStateOf<List<Entry>>(emptyList())

    var scope: ScopeContent.OpenScope? = null
        private set
    var contentKey: ByteArray? = null
        private set
    private var allNodes: List<JSONObject>? = null

    suspend fun start() {
        try {
            val arr = withContext(Dispatchers.IO) { SwitchActiveSpace.listMySpaces(apiBase, session) }
            spaces = (0 until arr.length()).map { arr.getJSONObject(it) }
            val personal = spaces.firstOrNull { it.getString("kind") == "PERSONAL" } ?: spaces.firstOrNull()
            if (personal != null) switchSpace(personal)
        } catch (e: Exception) {
            error = "Konnte Bereiche nicht laden."
        }
    }

    suspend fun switchSpace(entry: JSONObject) {
        try {
            val opened = withContext(Dispatchers.IO) { SwitchActiveSpace.openSpace(apiBase, session, entry) }
            val key = withContext(Dispatchers.IO) { ScopeContent.getOrCreateContentKey(apiBase, session, opened) }
            scope = opened; contentKey = key; activeScopeId = entry.getString("scopeId")
            allNodes = null
            crumbs = listOf(Crumb(null, entry.getString("displayName")))
            openLevel()
        } catch (e: Exception) {
            error = "Konnte den Bereich nicht öffnen."
        }
    }

    suspend fun openFolder(e: Entry) {
        crumbs = crumbs + Crumb(e.id, e.name)
        openLevel()
    }

    suspend fun gotoCrumb(index: Int) {
        if (index >= crumbs.size - 1) return
        crumbs = crumbs.take(index + 1)
        openLevel()
    }

    /** Client-side search over the active space's decrypted node names (graph loaded once, cached). */
    suspend fun search() {
        val q = searchText.trim().lowercase()
        val s = scope; val key = contentKey
        if (q.isEmpty() || s == null || key == null) { searchHits = emptyList(); return }
        if (allNodes == null) {
            allNodes = withContext(Dispatchers.IO) { runCatching { Browse.listNodes(apiBase, session, s.scopeId) }.getOrDefault(emptyList()) }
        }
        searchHits = (allNodes ?: emptyList()).mapNotNull { n ->
            val name = runCatching { MemoryTree.decodeNodeName(key, n) }.getOrDefault("")
            if (name.lowercase().contains(q)) Entry(n.getString("id"), n.getString("type"), name) else null
        }
    }

    private suspend fun openLevel() {
        val s = scope ?: return
        val key = contentKey ?: return
        try {
            val parentId = crumbs.lastOrNull()?.nodeId
            val nodes = withContext(Dispatchers.IO) { Browse.listChildren(apiBase, session, s.scopeId, parentId) }
            val entries = nodes.map { n ->
                val name = runCatching { MemoryTree.decodeNodeName(key, n) }.getOrDefault("—")
                Entry(n.getString("id"), n.getString("type"), name)
            }
            folders = entries.filter { it.type == "FOLDER" }
            memories = entries.filter { it.type == "MEMORY" }
            covers = emptyMap()
            for (m in memories) loadCover(m, s.scopeId, key)
        } catch (e: Exception) {
            error = "Konnte Inhalte nicht laden."
        }
    }

    private suspend fun loadCover(m: Entry, scopeId: String, key: ByteArray) {
        try {
            val media = withContext(Dispatchers.IO) { Media.listMedia(apiBase, session, scopeId, m.id) }
            val first = media.firstOrNull() ?: return
            val derivs = first.getJSONArray("derivatives")
            var blob: JSONObject? = first.getJSONObject("original")
            for (i in 0 until derivs.length()) {
                if (derivs.getJSONObject(i).getString("kind") == "THUMBNAIL") blob = derivs.getJSONObject(i).getJSONObject("blob")
            }
            val b = blob ?: return
            val ref = Media.BlobRef(b.getString("blobId"), b.getInt("totalChunks"), b.getLong("byteSize"))
            val bytes = withContext(Dispatchers.IO) { Media.downloadBlob(apiBase, session, scopeId, key, ref) }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            covers = covers + (m.id to bmp.asImageBitmap())
        } catch (e: Exception) {
            // no cover
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(apiBase: String, session: Login.UnlockedSession, onSignOut: () -> Unit) {
    val controller = remember { HomeController(apiBase, session) }
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<HomeController.Entry?>(null) }
    LaunchedEffect(Unit) { controller.start() }

    val openMemory = detail
    val activeScope = controller.scope
    val activeKey = controller.contentKey
    if (openMemory != null && activeScope != null && activeKey != null) {
        GalleryScreen(apiBase, session, activeScope.scopeId, activeKey, openMemory.id, openMemory.name) { detail = null }
        return
    }

    Scaffold(
        containerColor = Veyq.Bg,
        topBar = {
            TopAppBar(
                title = { Text(controller.crumbs.lastOrNull()?.name ?: "veyq", color = Veyq.Text, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Veyq.Surface),
                navigationIcon = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Text("▦", color = Veyq.Text, fontSize = 18.sp) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            controller.spaces.forEach { s ->
                                DropdownMenuItem(text = { Text(s.getString("displayName")) }, onClick = {
                                    menuOpen = false; scope.launch { controller.switchSpace(s) }
                                })
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onSignOut) { Text("⏻", color = Veyq.Muted, fontSize = 18.sp) }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            controller.error?.let { MessageBox(it) }

            OutlinedTextField(
                value = controller.searchText,
                onValueChange = { controller.searchText = it; scope.launch { controller.search() } },
                placeholder = { Text("Suche einfach überall…", color = Veyq.Muted) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Veyq.SurfaceMuted, unfocusedContainerColor = Veyq.SurfaceMuted,
                    focusedBorderColor = Veyq.Accent, unfocusedBorderColor = Veyq.Line,
                    focusedTextColor = Veyq.Text, unfocusedTextColor = Veyq.Text,
                ),
            )

            if (controller.searchText.isNotBlank()) {
                if (controller.searchHits.isEmpty()) EmptyText("Nichts gefunden.")
                else controller.searchHits.forEach { hit ->
                    SearchRow(hit) {
                        if (hit.type == "MEMORY") detail = hit
                        else { controller.searchText = ""; scope.launch { controller.openFolder(hit) } }
                    }
                }
            } else {
                if (controller.crumbs.size > 1) Breadcrumb(controller.crumbs) { scope.launch { controller.gotoCrumb(it) } }

                SectionHeader("Memories", controller.memories.size)
                if (controller.memories.isEmpty()) EmptyText("Noch keine Memories hier.")
                else MemoryGrid(controller.memories, controller.covers) { detail = it }

                SectionHeader("Ordner", controller.folders.size)
                if (controller.folders.isEmpty()) EmptyText("Keine Ordner.")
                else controller.folders.forEach { f -> FolderRow(f) { scope.launch { controller.openFolder(f) } } }
            }
        }
    }
}

@Composable
private fun Breadcrumb(crumbs: List<HomeController.Crumb>, onClick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        crumbs.forEachIndexed { i, c ->
            if (i > 0) Text("/", color = Veyq.Muted, fontSize = 14.sp)
            val last = i == crumbs.size - 1
            Text(
                c.name,
                color = if (last) Veyq.Text else Veyq.Muted,
                fontSize = 14.sp,
                fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clickable { onClick(i) },
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Veyq.Text)
        Text("$count", fontSize = 14.sp, color = Veyq.Muted)
    }
}

@Composable
private fun EmptyText(text: String) = Text(text, fontSize = 14.sp, color = Veyq.Muted)

@Composable
private fun MemoryGrid(memories: List<HomeController.Entry>, covers: Map<String, ImageBitmap>, onOpen: (HomeController.Entry) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        memories.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { m -> Box(Modifier.weight(1f)) { MemoryCard(m, covers[m.id], onOpen) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MemoryCard(m: HomeController.Entry, cover: ImageBitmap?, onOpen: (HomeController.Entry) -> Unit) {
    Column(
        Modifier
            .border(1.dp, Veyq.Line, RoundedCornerShape(Veyq.Radius))
            .background(Veyq.Surface, RoundedCornerShape(Veyq.Radius))
            .clickable { onOpen(m) },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(Veyq.SurfaceMuted), contentAlignment = Alignment.Center) {
            if (cover != null) Image(cover, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Text(m.name, color = Veyq.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(8.dp))
    }
}

@Composable
private fun FolderRow(f: HomeController.Entry, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Veyq.Line, RoundedCornerShape(Veyq.Radius))
            .background(Veyq.Surface, RoundedCornerShape(Veyq.Radius))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("📁", fontSize = 18.sp)
        Text(f.name, color = Veyq.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun SearchRow(hit: HomeController.Entry, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Veyq.Line, RoundedCornerShape(Veyq.Radius))
            .background(Veyq.Surface, RoundedCornerShape(Veyq.Radius))
            .clickable(onClick = onOpen)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (hit.type == "FOLDER") "📁" else "🖼️", fontSize = 18.sp)
        Text(hit.name, color = Veyq.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
        Text(if (hit.type == "FOLDER") "Ordner" else "Memory", color = Veyq.Accent, fontSize = 12.sp)
    }
}
