package app.veyq.android.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import app.veyq.crypto.Browse
import app.veyq.crypto.Curate
import app.veyq.crypto.Groups
import app.veyq.crypto.InviteMembers
import app.veyq.crypto.Login
import app.veyq.crypto.Media
import app.veyq.crypto.MemoryTree
import app.veyq.crypto.RegisterViaInvitation
import app.veyq.crypto.ScopeContent
import app.veyq.crypto.SwitchActiveSpace
import app.veyq.crypto.Tenant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** MemoryHome state holder. Blocking core calls run on IO; Compose state is updated on the caller's (main). */
class HomeController(val apiBase: String, val appBaseUrl: String, val session: Login.UnlockedSession) {
    data class Entry(val node: JSONObject, val name: String) {
        val id: String get() = node.getString("id")
        val type: String get() = node.getString("type")
        val coverMediaId: String? get() = if (node.has("coverMediaId") && !node.isNull("coverMediaId")) node.getString("coverMediaId") else null
    }
    data class Crumb(val nodeId: String?, val name: String)

    var spaces by mutableStateOf<List<JSONObject>>(emptyList())
    var activeScopeId by mutableStateOf<String?>(null)
    /** The active space's tenantId (group/tenant scopes) — enables resolving other members' bucket names. */
    val activeTenantId: String? get() = spaces.firstOrNull { it.optString("scopeId") == activeScopeId }?.optString("tenantId")?.ifEmpty { null }
    var folders by mutableStateOf<List<Entry>>(emptyList())
    var memories by mutableStateOf<List<Entry>>(emptyList())
    var covers by mutableStateOf<Map<String, ImageBitmap>>(emptyMap())
    var crumbs by mutableStateOf<List<Crumb>>(emptyList())
    var error by mutableStateOf<String?>(null)
    var searchText by mutableStateOf("")
    var searchHits by mutableStateOf<List<Entry>>(emptyList())
    var groupNames by mutableStateOf<Map<String, String>>(emptyMap()) // groupScopeId → decrypted name

    var scope: ScopeContent.OpenScope? = null
        private set
    var contentKey: ByteArray? = null
        private set
    private var allNodes: List<JSONObject>? = null

    suspend fun start() {
        try {
            reloadSpaces()
            val personal = spaces.firstOrNull { it.getString("kind") == "PERSONAL" } ?: spaces.firstOrNull()
            if (personal != null) switchSpace(personal)
        } catch (e: Exception) {
            error = "Konnte Bereiche nicht laden."
        }
    }

    private suspend fun reloadSpaces() {
        val arr = withContext(Dispatchers.IO) { SwitchActiveSpace.listMySpaces(apiBase, session) }
        spaces = (0 until arr.length()).map { arr.getJSONObject(it) }
        groupNames = emptyMap()
        resolveGroupNames()
    }

    /** Decrypt the sealed name of each group so the switcher can show it (opens the group scope + content key). */
    private suspend fun resolveGroupNames() {
        val resolved = groupNames.toMutableMap()
        for (s in spaces) {
            if (s.getString("kind") != "GROUP" || resolved.containsKey(s.getString("scopeId")) || !s.has("sealedName")) continue
            runCatching {
                withContext(Dispatchers.IO) {
                    val opened = SwitchActiveSpace.openSpace(apiBase, session, s)
                    val ck = ScopeContent.getOrCreateContentKey(apiBase, session, opened)
                    Groups.openSealedName(ck, s.getJSONObject("sealedName").getString("bytes"))
                }
            }.onSuccess { resolved[s.getString("scopeId")] = it }
        }
        groupNames = resolved
    }

    /** The label to show for a space: its plaintext name, or the decrypted group name, else a lock. */
    fun displayName(entry: JSONObject): String {
        val dn = entry.optString("displayName")
        if (dn.isNotEmpty()) return dn
        return groupNames[entry.getString("scopeId")] ?: "🔒"
    }

    /** Create a tenant (workspace) the caller owns: tenant + self owner-claim (retries on handle conflict). */
    suspend fun createTenant(name: String, domain: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || domain.isBlank()) return false
        return try {
            val tenant = withContext(Dispatchers.IO) {
                val base = slugify(trimmed)
                var made: JSONObject? = null
                for (attempt in 0 until 25) {
                    val handle = if (attempt == 0) base else "$base-${attempt + 1}"
                    try { made = Tenant.createTenant(apiBase, session.sessionToken, handle, trimmed, domain.trim().lowercase()); break }
                    catch (e: Exception) { if (!"${e.message}".contains("409") && !"${e.message}".lowercase().contains("exists")) throw e }
                }
                val t = made ?: throw RuntimeException("could not create tenant (handle conflict)")
                val inv = Tenant.issueOwnerInvite(apiBase, session.sessionToken, t.getString("id"))
                Tenant.claimTenantOwnership(apiBase, session.sessionToken, inv.getString("inviteId"), inv.getString("bootstrapSecret"), session.kemPublicKey)
                t
            }
            reloadSpaces()
            spaces.firstOrNull { it.getString("kind") == "TENANT" && it.optString("tenantId") == tenant.getString("id") }?.let { switchSpace(it) }
            true
        } catch (e: Exception) { error = L10n.t("groups.createFailed"); false }
    }

    /** Create a group within a tenant (the caller becomes its GroupOwner). */
    suspend fun createGroupSpace(tenantId: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        return try {
            val group = withContext(Dispatchers.IO) { Groups.createGroup(apiBase, session, tenantId, trimmed) }
            reloadSpaces()
            spaces.firstOrNull { it.getString("scopeId") == group.getString("scopeId") }?.let { switchSpace(it) }
            true
        } catch (e: Exception) { error = L10n.t("groups.createFailed"); false }
    }

    /** Look up an invite's tenant + role for a confirmation preview (public metadata; no secret needed). */
    suspend fun inviteInfo(inviteId: String): Pair<String, String>? = runCatching {
        withContext(Dispatchers.IO) {
            val m = RegisterViaInvitation.getInviteMetadata(apiBase, inviteId)
            m.getJSONObject("tenant").getString("displayName") to m.getString("role")
        }
    }.getOrNull()

    /** Accept an invite onto this (logged-in) account, then refresh the space list so the group appears. A
     *  passphrase-protected invite needs the out-of-band passphrase to resolve its (encrypted) temp key. */
    suspend fun joinGroup(parts: InviteLink.Parts, passphrase: String? = null): Boolean = try {
        val tempKey = if (parts.isProtected) {
            if (passphrase.isNullOrEmpty()) throw RuntimeException("passphrase required")
            withContext(Dispatchers.IO) { InviteMembers.unprotectTempKey(parts.encTempKey!!, parts.pwSalt!!, passphrase) }
        } else parts.tempPrivateKey ?: ""
        withContext(Dispatchers.IO) { InviteMembers.acceptMemberInvite(apiBase, session, parts.inviteId, parts.bootstrapSecret, tempKey) }
        reloadSpaces()
        true
    } catch (e: Exception) {
        error = if (parts.isProtected) L10n.t("accept.wrongPassphrase") else L10n.t("accept.failed")
        false
    }

    /** Owner action: mint a member invite and have the server email the link (pragmatic or passphrase-protected). */
    suspend fun emailMemberInvite(tenantId: String, role: String, email: String, passphrase: String?): Boolean = try {
        withContext(Dispatchers.IO) {
            val invite = InviteMembers.issueMemberInvite(apiBase, session, tenantId, role)
            val link = if (!passphrase.isNullOrEmpty()) {
                val (ek, ps) = InviteMembers.protectTempKey(invite.tempPrivateKey, passphrase)
                InviteLink.buildProtected(appBaseUrl, invite.inviteId, invite.bootstrapSecret, ek, ps)
            } else InviteLink.build(appBaseUrl, invite.inviteId, invite.bootstrapSecret, invite.tempPrivateKey)
            InviteMembers.emailMemberInvite(apiBase, session.sessionToken, tenantId, email, invite.inviteId, link)
        }
        true
    } catch (e: Exception) {
        error = L10n.t("invite.emailFailed")
        false
    }

    /** Owner action: mint a member invite and return a shareable link (pragmatic or passphrase-protected). */
    suspend fun buildMemberInviteLink(tenantId: String, role: String, passphrase: String?): String? = try {
        withContext(Dispatchers.IO) {
            val invite = InviteMembers.issueMemberInvite(apiBase, session, tenantId, role)
            if (!passphrase.isNullOrEmpty()) {
                val (ek, ps) = InviteMembers.protectTempKey(invite.tempPrivateKey, passphrase)
                InviteLink.buildProtected(appBaseUrl, invite.inviteId, invite.bootstrapSecret, ek, ps)
            } else InviteLink.build(appBaseUrl, invite.inviteId, invite.bootstrapSecret, invite.tempPrivateKey)
        }
    } catch (e: Exception) {
        error = L10n.t("invite.emailFailed")
        null
    }

    // --- group member management (GroupOwner) ---
    suspend fun groupMembers(entry: JSONObject): JSONArray = withContext(Dispatchers.IO) { Groups.listGroupMembers(apiBase, session, entry.optString("tenantId"), entry.getString("scopeId")) }
    suspend fun tenantMembers(tenantId: String): JSONArray = withContext(Dispatchers.IO) { InviteMembers.listTenantMembers(apiBase, session.sessionToken, tenantId) }
    suspend fun addGroupMember(entry: JSONObject, userId: String, kemB64: String): Boolean = runCatching {
        withContext(Dispatchers.IO) { Groups.addGroupMember(apiBase, session, entry.optString("tenantId"), entry.getString("scopeId"), userId, kemB64) }
    }.isSuccess
    suspend fun setGroupRole(entry: JSONObject, userId: String, role: String): Boolean = runCatching {
        withContext(Dispatchers.IO) { Groups.setGroupMemberRole(apiBase, session, entry.optString("tenantId"), entry.getString("scopeId"), userId, role) }
    }.isSuccess
    suspend fun removeGroupMember(entry: JSONObject, userId: String): Boolean = runCatching {
        withContext(Dispatchers.IO) { Groups.removeGroupMember(apiBase, session, entry.optString("tenantId"), entry.getString("scopeId"), userId) }
    }.isSuccess

    // --- deletes ---
    suspend fun deleteNode(nodeId: String) {
        val s = scope ?: return
        try { withContext(Dispatchers.IO) { Groups.deleteNode(apiBase, session, s.scopeId, nodeId) }; allNodes = null; openLevel() }
        catch (e: Exception) { error = "Konnte nicht löschen." }
    }
    suspend fun deleteGroup(entry: JSONObject) {
        try { withContext(Dispatchers.IO) { Groups.deleteGroup(apiBase, session, entry.optString("tenantId"), entry.getString("scopeId")) }; afterSpaceDeleted(entry.getString("scopeId")) }
        catch (e: Exception) { error = "Konnte Gruppe nicht löschen." }
    }
    /** Schedule the tenant for deletion (grace period). It stays usable, so refresh + re-select it to
     *  surface the scheduled-deletion banner with its cancel action. */
    suspend fun deleteTenant(entry: JSONObject) {
        try {
            withContext(Dispatchers.IO) { Groups.deleteTenant(apiBase, session, entry.optString("tenantId")) }
            reloadSpaces()
            spaces.firstOrNull { it.getString("scopeId") == entry.getString("scopeId") }?.let { switchSpace(it) }
        } catch (e: Exception) { error = "Konnte Tenant nicht löschen." }
    }
    /** Cancel a scheduled tenant deletion and refresh so the banner clears. */
    suspend fun cancelTenantDeletion(entry: JSONObject) {
        try {
            withContext(Dispatchers.IO) { Groups.cancelTenantDeletion(apiBase, session, entry.optString("tenantId")) }
            reloadSpaces()
            spaces.firstOrNull { it.getString("scopeId") == entry.getString("scopeId") }?.let { switchSpace(it) }
        } catch (e: Exception) { error = "Konnte Löschung nicht abbrechen." }
    }
    private suspend fun afterSpaceDeleted(scopeId: String) {
        val wasActive = activeScopeId == scopeId
        reloadSpaces()
        if (wasActive) {
            scope = null; contentKey = null; activeScopeId = null; folders = emptyList(); memories = emptyList()
            (spaces.firstOrNull { it.getString("kind") == "PERSONAL" } ?: spaces.firstOrNull())?.let { switchSpace(it) }
        }
    }

    suspend fun switchSpace(entry: JSONObject) {
        try {
            val opened = withContext(Dispatchers.IO) { SwitchActiveSpace.openSpace(apiBase, session, entry) }
            val key = withContext(Dispatchers.IO) { ScopeContent.getOrCreateContentKey(apiBase, session, opened) }
            scope = opened; contentKey = key; activeScopeId = entry.getString("scopeId")
            allNodes = null
            crumbs = listOf(Crumb(null, displayName(entry)))
            openLevel()
        } catch (e: Exception) {
            error = "Konnte den Bereich nicht öffnen."
        }
    }

    suspend fun openFolder(e: Entry) {
        crumbs = crumbs + Crumb(e.id, e.name)
        openLevel()
    }

    /** Open a member deep-link path (`/@reisegruppe/urlaube/italien`): switch into the space, set the folder
     *  chain as the breadcrumb back-stack, and return the trailing memory to show (so closing it lands on its folder). */
    suspend fun openPath(path: String): Entry? {
        if (spaces.isEmpty()) reloadSpaces()
        val parsed = HomeRoute.parsePath(path)
        val host = runCatching { java.net.URI(apiBase).host }.getOrNull() // the instance host resolves the tenant
        val target = when (parsed.kind) {
            "PERSONAL" -> spaces.firstOrNull { it.getString("kind") == "PERSONAL" }
            "GROUP" -> spaces.firstOrNull { it.getString("kind") == "GROUP" && HomeRoute.slugify(displayName(it)) == parsed.groupSlug }
            else -> spaces.firstOrNull { it.getString("kind") == "TENANT" && it.optString("domain") == host }
                ?: spaces.firstOrNull { it.getString("kind") == "TENANT" }
        } ?: run { error = "Bereich nicht gefunden."; return null }
        switchSpace(target) // sets crumbs = [root] and opens the level
        val sc = scope ?: return null
        val key = contentKey ?: return null
        if (parsed.segments.isEmpty()) return null
        return try {
            val chain = withContext(Dispatchers.IO) { Browse.resolvePath(apiBase, session, sc.scopeId, key, parsed.segments) }
            var folders = chain
            var memory: JSONObject? = null
            if (chain.isNotEmpty() && chain.last().getString("type") == "MEMORY") { memory = chain.last(); folders = chain.dropLast(1) }
            var newCrumbs = listOf(Crumb(null, displayName(target)))
            for (f in folders) {
                val name = runCatching { MemoryTree.decodeNodeName(key, f) }.getOrDefault("")
                newCrumbs = newCrumbs + Crumb(f.getString("id"), name)
            }
            crumbs = newCrumbs
            openLevel()
            memory?.let { Entry(it, runCatching { MemoryTree.decodeNodeName(key, it) }.getOrDefault("")) }
        } catch (e: Exception) {
            error = "Konnte den Pfad nicht öffnen."
            null
        }
    }

    /** Create a folder/memory at the current level. Name is sealed; the slug retries on collision. */
    suspend fun create(type: String, name: String) {
        val trimmed = name.trim()
        val s = scope; val key = contentKey
        if (trimmed.isEmpty() || s == null || key == null) return
        allNodes = null
        val parentId = crumbs.lastOrNull()?.nodeId
        val orderIndex = folders.size + memories.size
        val base = slugify(trimmed)
        for (attempt in 0 until 25) {
            val slug = if (attempt == 0) base else "$base-${attempt + 1}"
            try {
                withContext(Dispatchers.IO) { MemoryTree.createNode(apiBase, session, s.scopeId, key, type, trimmed, slug, orderIndex, parentId) }
                openLevel()
                return
            } catch (e: Exception) {
                if (e.message?.contains("SLUG_EXISTS") != true && e.message?.contains("409") != true) { error = "Konnte nicht anlegen."; return }
            }
        }
    }

    /** Valid move targets: the root + every folder not in the node's own subtree. */
    suspend fun moveDestinations(entry: Entry): List<Pair<String, String>> {
        val s = scope ?: return emptyList()
        val key = contentKey ?: return emptyList()
        val all = withContext(Dispatchers.IO) { runCatching { Browse.listNodes(apiBase, session, s.scopeId) }.getOrDefault(emptyList()) }
        val root = s.scopeId
        fun childrenOf(id: String) = all.filter { (if (it.has("parentId") && !it.isNull("parentId")) it.getString("parentId") else root) == id }
        val banned = mutableSetOf(entry.id)
        val stack = ArrayDeque(listOf(entry.id))
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            for (c in childrenOf(cur)) if (banned.add(c.getString("id"))) stack.addLast(c.getString("id"))
        }
        val dests = mutableListOf("root" to L10n.t("move.toRoot"))
        for (n in all) if (n.getString("type") == "FOLDER" && n.getString("id") !in banned) {
            dests.add(n.getString("id") to runCatching { MemoryTree.decodeNodeName(key, n) }.getOrDefault("—"))
        }
        return dests
    }

    suspend fun move(entry: Entry, destId: String) {
        val s = scope; val key = contentKey
        if (s == null || key == null) return
        allNodes = null
        try {
            withContext(Dispatchers.IO) { Curate.moveNode(apiBase, session, s.scopeId, key, entry.node, destId) }
            openLevel()
        } catch (e: Exception) {
            error = "Konnte nicht verschieben."
        }
    }

    suspend fun rename(entry: Entry, name: String) {
        val trimmed = name.trim()
        val s = scope; val key = contentKey
        if (trimmed.isEmpty() || s == null || key == null) return
        allNodes = null
        try {
            withContext(Dispatchers.IO) { Curate.renameNode(apiBase, session, s.scopeId, key, entry.node, trimmed) }
            openLevel()
        } catch (e: Exception) {
            error = "Konnte nicht umbenennen."
        }
    }

    private fun slugify(name: String): String {
        val allowed = (('a'..'z') + ('0'..'9')).toSet()
        val folded = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKD).replace("\\p{M}".toRegex(), "").lowercase()
        val mapped = folded.map { if (it in allowed) it else '-' }.joinToString("")
        return mapped.split("-").filter { it.isNotEmpty() }.joinToString("-").ifEmpty { "item" }
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
            if (name.lowercase().contains(q)) Entry(n, name) else null
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
                Entry(n, name)
            }
            folders = entries.filter { it.type == "FOLDER" }
            memories = entries.filter { it.type == "MEMORY" }
            // Keep covers already decrypted for still-visible memories (no re-download on back/forward); fetch
            // the missing ones bound-parallel and merge once (not a whole-map rebuild per cover).
            val visible = memories.map { it.id }.toSet()
            val kept = covers.filterKeys { it in visible }
            val missing = memories.filter { it.id !in kept }
            val fetched = withContext(Dispatchers.IO) {
                val sem = Semaphore(6)
                coroutineScope { missing.map { m -> async { sem.withPermit { m.id to loadCoverBitmap(m, s.scopeId, key) } } }.awaitAll() }
            }.mapNotNull { (id, bmp) -> bmp?.let { id to it } }
            covers = kept + fetched
        } catch (e: Exception) {
            error = "Konnte Inhalte nicht laden."
        }
    }

    private fun loadCoverBitmap(m: Entry, scopeId: String, key: ByteArray): ImageBitmap? = runCatching {
        val media = Media.listMedia(apiBase, session, scopeId, m.id)
        val first = (m.coverMediaId?.let { cid -> media.firstOrNull { it.getString("id") == cid } } ?: media.firstOrNull()) ?: return null
        val derivs = first.getJSONArray("derivatives")
        var blob = first.getJSONObject("original")
        for (i in 0 until derivs.length()) {
            if (derivs.getJSONObject(i).getString("kind") == "THUMBNAIL") blob = derivs.getJSONObject(i).getJSONObject("blob")
        }
        val ref = Media.BlobRef(blob.getString("blobId"), blob.getInt("totalChunks"), blob.getLong("byteSize"))
        val bytes = Media.downloadBlob(apiBase, session, scopeId, key, ref)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(apiBase: String, appBaseUrl: String, session: Login.UnlockedSession, theme: String, onTheme: (String) -> Unit, onLocale: (String) -> Unit, pendingOpenPath: String? = null, onOpenHandled: () -> Unit = {}, onSignOut: () -> Unit) {
    val controller = remember { HomeController(apiBase, appBaseUrl.ifEmpty { apiBase }, session) }
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var joinOpen by remember { mutableStateOf(false) }
    var createTenantOpen by remember { mutableStateOf(false) }
    var createGroupOpen by remember { mutableStateOf(false) }
    var newSpaceName by remember { mutableStateOf("") }
    var newTenantDomain by remember { mutableStateOf("") }
    var newGroupTenant by remember { mutableStateOf("") }
    var manageGroupEntry by remember { mutableStateOf<JSONObject?>(null) }
    var confirmDelName by remember { mutableStateOf<String?>(null) }
    var confirmDelAction by remember { mutableStateOf<(suspend () -> Unit)?>(null) }
    var confirmDelText by remember { mutableStateOf("") }
    var joinLink by remember { mutableStateOf("") }
    var joinInfo by remember { mutableStateOf<Pair<String, String>?>(null) }
    var joinBusy by remember { mutableStateOf(false) }
    var joinPassphrase by remember { mutableStateOf("") }
    var inviteTenant by remember { mutableStateOf<JSONObject?>(null) }
    var detail by remember { mutableStateOf<HomeController.Entry?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var createMenuOpen by remember { mutableStateOf(false) }
    var createType by remember { mutableStateOf("MEMORY") }
    var showNameDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var renameEntry by remember { mutableStateOf<HomeController.Entry?>(null) }
    var renameName by remember { mutableStateOf("") }
    var actionEntry by remember { mutableStateOf<HomeController.Entry?>(null) }
    var moveEntry by remember { mutableStateOf<HomeController.Entry?>(null) }
    var moveDests by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val startAction: (HomeController.Entry) -> Unit = { actionEntry = it }
    // Default land on personal — unless a deep-link path is pending (then openPath sets the space/back-stack).
    LaunchedEffect(Unit) { if (pendingOpenPath == null) controller.start() }
    LaunchedEffect(pendingOpenPath) {
        val p = pendingOpenPath ?: return@LaunchedEffect
        val m = controller.openPath(p)
        if (m != null) detail = m
        onOpenHandled()
    }

    if (settingsOpen) {
        SettingsScreen(apiBase, session, theme, onTheme, onLocale, onBack = { settingsOpen = false }, onSignOut = onSignOut)
        return
    }

    val openMemory = detail
    val activeScope = controller.scope
    val activeKey = controller.contentKey
    if (openMemory != null && activeScope != null && activeKey != null) {
        GalleryScreen(apiBase, session, activeScope.scopeId, activeKey, openMemory.id, openMemory.name, controller.activeTenantId) { detail = null }
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
                                DropdownMenuItem(text = { Text(controller.displayName(s)) }, onClick = {
                                    menuOpen = false; scope.launch { controller.switchSpace(s) }
                                })
                            }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text(L10n.t("tenants.create")) }, onClick = { menuOpen = false; newSpaceName = ""; createTenantOpen = true })
                            if (controller.spaces.any { it.getString("kind") == "TENANT" }) {
                                DropdownMenuItem(text = { Text(L10n.t("groups.create")) }, onClick = {
                                    menuOpen = false; newSpaceName = ""
                                    newGroupTenant = controller.spaces.firstOrNull { it.getString("kind") == "TENANT" }?.optString("tenantId") ?: ""
                                    createGroupOpen = true
                                })
                            }
                            DropdownMenuItem(text = { Text(L10n.t("groups.join")) }, onClick = { menuOpen = false; joinLink = ""; joinInfo = null; joinPassphrase = ""; joinOpen = true })
                            // Management actions for the ACTIVE space (owner only)
                            val activeSpace = controller.spaces.firstOrNull { it.getString("scopeId") == controller.activeScopeId }
                            if (activeSpace != null && activeSpace.getString("kind") == "GROUP" && activeSpace.optString("role") == "GroupOwner") {
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text(L10n.t("groups.members")) }, onClick = { menuOpen = false; manageGroupEntry = activeSpace })
                                DropdownMenuItem(text = { Text(L10n.t("delete.group"), color = Veyq.Danger) }, onClick = {
                                    menuOpen = false; confirmDelText = ""; confirmDelName = controller.displayName(activeSpace); confirmDelAction = { controller.deleteGroup(activeSpace) }
                                })
                            }
                            if (activeSpace != null && activeSpace.getString("kind") == "TENANT" && activeSpace.optString("role") == "Owner") {
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text(L10n.t("invite.title")) }, onClick = { menuOpen = false; inviteTenant = activeSpace })
                                DropdownMenuItem(text = { Text(L10n.t("delete.tenant"), color = Veyq.Danger) }, onClick = {
                                    menuOpen = false; confirmDelText = ""; confirmDelName = controller.displayName(activeSpace); confirmDelAction = { controller.deleteTenant(activeSpace) }
                                })
                            }
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { createMenuOpen = true }) { Text("＋", color = Veyq.Accent, fontSize = 22.sp) }
                        DropdownMenu(expanded = createMenuOpen, onDismissRequest = { createMenuOpen = false }) {
                            DropdownMenuItem(text = { Text(L10n.t("home.createMemory")) }, onClick = { createMenuOpen = false; createType = "MEMORY"; newName = ""; showNameDialog = true })
                            DropdownMenuItem(text = { Text(L10n.t("home.createFolder")) }, onClick = { createMenuOpen = false; createType = "FOLDER"; newName = ""; showNameDialog = true })
                        }
                    }
                    IconButton(onClick = { settingsOpen = true }) { Text("⚙", color = Veyq.Muted, fontSize = 20.sp) }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            controller.error?.let { MessageBox(it) }

            val active = controller.spaces.firstOrNull { it.getString("scopeId") == controller.activeScopeId }
            val deadline = active?.optString("scheduledDeletionAt")?.ifEmpty { null }
            if (active != null && deadline != null) DeletionBanner(deadline, active.optString("role") == "Owner") {
                scope.launch { controller.cancelTenantDeletion(active) }
            }

            OutlinedTextField(
                value = controller.searchText,
                onValueChange = { controller.searchText = it; scope.launch { controller.search() } },
                placeholder = { Text(L10n.t("home.search"), color = Veyq.Muted) },
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
                if (controller.searchHits.isEmpty()) EmptyText(L10n.t("search.nothing"))
                else controller.searchHits.forEach { hit ->
                    SearchRow(hit) {
                        if (hit.type == "MEMORY") detail = hit
                        else { controller.searchText = ""; scope.launch { controller.openFolder(hit) } }
                    }
                }
            } else {
                if (controller.crumbs.size > 1) Breadcrumb(controller.crumbs) { scope.launch { controller.gotoCrumb(it) } }

                SectionHeader(L10n.t("home.memories"), controller.memories.size)
                if (controller.memories.isEmpty()) EmptyText(L10n.t("home.noMemories"))
                else MemoryGrid(controller.memories, controller.covers, onOpen = { detail = it }, onRename = startAction)

                SectionHeader(L10n.t("home.folders"), controller.folders.size)
                if (controller.folders.isEmpty()) EmptyText(L10n.t("home.noFolders"))
                else controller.folders.forEach { f -> FolderRow(f, onClick = { scope.launch { controller.openFolder(f) } }, onLongClick = { startAction(f) }) }
            }
        }
    }

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            containerColor = Veyq.Surface,
            title = { Text(if (createType == "FOLDER") L10n.t("home.createFolder") else L10n.t("home.createMemory"), color = Veyq.Text) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    placeholder = { Text(L10n.t("common.name"), color = Veyq.Muted) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Veyq.SurfaceMuted, unfocusedContainerColor = Veyq.SurfaceMuted,
                        focusedBorderColor = Veyq.Accent, unfocusedBorderColor = Veyq.Line,
                        focusedTextColor = Veyq.Text, unfocusedTextColor = Veyq.Text,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { val n = newName; showNameDialog = false; scope.launch { controller.create(createType, n) } }) {
                    Text(L10n.t("common.create"), color = Veyq.Accent)
                }
            },
            dismissButton = { TextButton(onClick = { showNameDialog = false }) { Text(L10n.t("common.cancel"), color = Veyq.Muted) } },
        )
    }

    if (createTenantOpen) {
        AlertDialog(
            onDismissRequest = { createTenantOpen = false },
            containerColor = Veyq.Surface,
            title = { Text(L10n.t("tenants.create"), color = Veyq.Text) },
            text = {
                val fc = OutlinedTextFieldDefaults.colors(focusedContainerColor = Veyq.SurfaceMuted, unfocusedContainerColor = Veyq.SurfaceMuted, focusedBorderColor = Veyq.Accent, unfocusedBorderColor = Veyq.Line, focusedTextColor = Veyq.Text, unfocusedTextColor = Veyq.Text)
                Column {
                    OutlinedTextField(value = newSpaceName, onValueChange = { newSpaceName = it }, singleLine = true, placeholder = { Text(L10n.t("common.name"), color = Veyq.Muted) }, colors = fc)
                    OutlinedTextField(value = newTenantDomain, onValueChange = { newTenantDomain = it }, singleLine = true, placeholder = { Text(L10n.t("tenants.domain"), color = Veyq.Muted) }, modifier = Modifier.padding(top = 8.dp), colors = fc)
                    Text(L10n.t("tenants.domainHint"), color = Veyq.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            },
            confirmButton = { TextButton(onClick = { val n = newSpaceName; val d = newTenantDomain; createTenantOpen = false; newTenantDomain = ""; scope.launch { controller.createTenant(n, d) } }) { Text(L10n.t("common.create"), color = Veyq.Accent) } },
            dismissButton = { TextButton(onClick = { createTenantOpen = false }) { Text(L10n.t("common.cancel"), color = Veyq.Muted) } },
        )
    }

    if (createGroupOpen) {
        val tenants = controller.spaces.filter { it.getString("kind") == "TENANT" }
        AlertDialog(
            onDismissRequest = { createGroupOpen = false },
            containerColor = Veyq.Surface,
            title = { Text(L10n.t("groups.createTitle"), color = Veyq.Text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (tenants.size > 1) {
                        Text(L10n.t("groups.inTenant"), color = Veyq.Muted, fontSize = 12.sp)
                        tenants.forEach { tn ->
                            val tid = tn.optString("tenantId")
                            TextButton(onClick = { newGroupTenant = tid }) {
                                Text((if (newGroupTenant == tid) "✓ " else "") + controller.displayName(tn), color = if (newGroupTenant == tid) Veyq.Accent else Veyq.Text)
                            }
                        }
                    }
                    OutlinedTextField(
                        value = newSpaceName, onValueChange = { newSpaceName = it }, singleLine = true,
                        placeholder = { Text(L10n.t("groups.name"), color = Veyq.Muted) },
                        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Veyq.SurfaceMuted, unfocusedContainerColor = Veyq.SurfaceMuted, focusedBorderColor = Veyq.Accent, unfocusedBorderColor = Veyq.Line, focusedTextColor = Veyq.Text, unfocusedTextColor = Veyq.Text),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = newSpaceName
                    val tid = newGroupTenant.ifEmpty { tenants.firstOrNull()?.optString("tenantId") ?: "" }
                    createGroupOpen = false
                    if (tid.isNotEmpty()) scope.launch { controller.createGroupSpace(tid, n) }
                }) { Text(L10n.t("common.create"), color = Veyq.Accent) }
            },
            dismissButton = { TextButton(onClick = { createGroupOpen = false }) { Text(L10n.t("common.cancel"), color = Veyq.Muted) } },
        )
    }

    if (joinOpen) {
        AlertDialog(
            onDismissRequest = { joinOpen = false },
            containerColor = Veyq.Surface,
            title = { Text(L10n.t("groups.join"), color = Veyq.Text) },
            text = {
                Column {
                    OutlinedTextField(
                        value = joinLink,
                        onValueChange = { v ->
                            joinLink = v; joinInfo = null
                            InviteLink.parse(v)?.let { p -> scope.launch { joinInfo = controller.inviteInfo(p.inviteId) } }
                        },
                        placeholder = { Text(L10n.t("accept.paste"), color = Veyq.Muted) },
                        singleLine = false,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Veyq.SurfaceMuted, unfocusedContainerColor = Veyq.SurfaceMuted,
                            focusedBorderColor = Veyq.Accent, unfocusedBorderColor = Veyq.Line,
                            focusedTextColor = Veyq.Text, unfocusedTextColor = Veyq.Text,
                        ),
                    )
                    joinInfo?.let { (name, role) ->
                        val roleLabel = if (role == "Owner") L10n.t("invite.owner") else L10n.t("invite.member")
                        Text("$name — ${L10n.t("accept.as")} $roleLabel", color = Veyq.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                    if (InviteLink.parse(joinLink)?.isProtected == true) {
                        OutlinedTextField(
                            value = joinPassphrase,
                            onValueChange = { joinPassphrase = it },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            placeholder = { Text(L10n.t("accept.passphraseHint"), color = Veyq.Muted) },
                            modifier = Modifier.padding(top = 8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Veyq.SurfaceMuted, unfocusedContainerColor = Veyq.SurfaceMuted,
                                focusedBorderColor = Veyq.Accent, unfocusedBorderColor = Veyq.Line,
                                focusedTextColor = Veyq.Text, unfocusedTextColor = Veyq.Text,
                            ),
                        )
                    }
                }
            },
            confirmButton = {
                val parsed = InviteLink.parse(joinLink)
                TextButton(
                    onClick = {
                        val p = parsed ?: return@TextButton
                        joinBusy = true
                        scope.launch { if (controller.joinGroup(p, joinPassphrase)) joinOpen = false; joinBusy = false }
                    },
                    enabled = !joinBusy && parsed != null && !(parsed.isProtected && joinPassphrase.isBlank()),
                ) { Text(if (joinBusy) L10n.t("accept.joining") else L10n.t("accept.join"), color = Veyq.Accent) }
            },
            dismissButton = { TextButton(onClick = { joinOpen = false }) { Text(L10n.t("common.cancel"), color = Veyq.Muted) } },
        )
    }

    inviteTenant?.let { t -> InviteMemberDialog(controller, t, onClose = { inviteTenant = null }) }

    renameEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { renameEntry = null },
            containerColor = Veyq.Surface,
            title = { Text(L10n.t("common.rename"), color = Veyq.Text) },
            text = {
                OutlinedTextField(
                    value = renameName,
                    onValueChange = { renameName = it },
                    singleLine = true,
                    placeholder = { Text(L10n.t("common.name"), color = Veyq.Muted) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Veyq.SurfaceMuted, unfocusedContainerColor = Veyq.SurfaceMuted,
                        focusedBorderColor = Veyq.Accent, unfocusedBorderColor = Veyq.Line,
                        focusedTextColor = Veyq.Text, unfocusedTextColor = Veyq.Text,
                    ),
                )
            },
            confirmButton = { TextButton(onClick = { val n = renameName; renameEntry = null; scope.launch { controller.rename(entry, n) } }) { Text(L10n.t("common.rename"), color = Veyq.Accent) } },
            dismissButton = { TextButton(onClick = { renameEntry = null }) { Text(L10n.t("common.cancel"), color = Veyq.Muted) } },
        )
    }

    actionEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { actionEntry = null },
            containerColor = Veyq.Surface,
            title = { Text(entry.name, color = Veyq.Text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { actionEntry = null; renameEntry = entry; renameName = entry.name }, modifier = Modifier.fillMaxWidth()) {
                        Text(L10n.t("common.rename"), color = Veyq.Text, modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = { val e = entry; actionEntry = null; scope.launch { moveDests = controller.moveDestinations(e); moveEntry = e } }, modifier = Modifier.fillMaxWidth()) {
                        Text(L10n.t("common.move"), color = Veyq.Text, modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = { val e = entry; actionEntry = null; confirmDelText = ""; confirmDelName = e.name; confirmDelAction = { controller.deleteNode(e.id) } }, modifier = Modifier.fillMaxWidth()) {
                        Text(L10n.t("common.delete"), color = Veyq.Danger, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { actionEntry = null }) { Text(L10n.t("common.close"), color = Veyq.Muted) } },
        )
    }

    // Confirm-by-name gate for every destructive delete (node/group/tenant).
    confirmDelName?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmDelName = null; confirmDelAction = null },
            containerColor = Veyq.Surface,
            title = { Text(L10n.t("delete.title"), color = Veyq.Text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(L10n.t("delete.warning"), color = Veyq.Muted, fontSize = 12.sp)
                    OutlinedTextField(
                        value = confirmDelText, onValueChange = { confirmDelText = it }, singleLine = true,
                        placeholder = { Text(name, color = Veyq.Muted) },
                        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Veyq.SurfaceMuted, unfocusedContainerColor = Veyq.SurfaceMuted, focusedBorderColor = Veyq.Accent, unfocusedBorderColor = Veyq.Line, focusedTextColor = Veyq.Text, unfocusedTextColor = Veyq.Text),
                    )
                }
            },
            confirmButton = {
                val act = confirmDelAction
                TextButton(enabled = confirmDelText == name, onClick = { confirmDelName = null; confirmDelAction = null; scope.launch { act?.invoke() } }) {
                    Text(L10n.t("delete.confirm"), color = if (confirmDelText == name) Veyq.Danger else Veyq.Muted)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelName = null; confirmDelAction = null }) { Text(L10n.t("common.cancel"), color = Veyq.Muted) } },
        )
    }

    manageGroupEntry?.let { g ->
        GroupMembersDialog(controller, g, onClose = { manageGroupEntry = null })
    }

    moveEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { moveEntry = null },
            containerColor = Veyq.Surface,
            title = { Text(L10n.t("move.title"), color = Veyq.Text) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    moveDests.forEach { (id, name) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { val e = entry; moveEntry = null; scope.launch { controller.move(e, id) } }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(if (id == "root") "🏠" else "📁", fontSize = 16.sp)
                            Text(name, color = Veyq.Text, fontSize = 15.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moveEntry = null }) { Text(L10n.t("common.close"), color = Veyq.Muted) } },
        )
    }
}

/**
 * Owner-side member invite for a tenant: role + protection mode, then email the invite or copy the link.
 * Pragmatic = the temp key rides the link in the clear; passphrase = it's encrypted under an out-of-band
 * passphrase (operator-blind, even against the email relay), so the link alone can't join.
 */
@Composable
private fun InviteMemberDialog(controller: HomeController, tenant: JSONObject, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val tenantId = tenant.optString("tenantId")
    var role by remember { mutableStateOf("Member") }
    var passphraseMode by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val passMissing = passphraseMode && passphrase.isBlank()
    val passOrNull = if (passphraseMode) passphrase else null

    @Composable
    fun toggle(label: String, active: Boolean, onClick: () -> Unit) {
        TextButton(onClick = onClick) {
            Text(label, color = if (active) Veyq.Accent else Veyq.Muted, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = Veyq.SurfaceMuted, unfocusedContainerColor = Veyq.SurfaceMuted,
        focusedBorderColor = Veyq.Accent, unfocusedBorderColor = Veyq.Line,
        focusedTextColor = Veyq.Text, unfocusedTextColor = Veyq.Text,
    )

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Veyq.Surface,
        title = { Text(L10n.t("invite.title"), color = Veyq.Text) },
        text = {
            Column {
                Text(L10n.t("invite.role"), color = Veyq.Muted, fontSize = 13.sp)
                Row { toggle(L10n.t("invite.member"), role == "Member") { role = "Member" }; toggle(L10n.t("invite.owner"), role == "Owner") { role = "Owner" } }
                Text(L10n.t("invite.protection"), color = Veyq.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                Row { toggle(L10n.t("invite.protLink"), !passphraseMode) { passphraseMode = false }; toggle(L10n.t("invite.protPassphrase"), passphraseMode) { passphraseMode = true } }
                if (passphraseMode) {
                    OutlinedTextField(
                        value = passphrase, onValueChange = { passphrase = it }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        placeholder = { Text(L10n.t("invite.passphrasePlaceholder"), color = Veyq.Muted) },
                        colors = fieldColors,
                    )
                    Text(L10n.t("invite.passphraseOob"), color = Veyq.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Text(L10n.t("invite.emailHint"), color = Veyq.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(
                    value = email, onValueChange = { email = it }, singleLine = true,
                    placeholder = { Text(L10n.t("invite.emailPlaceholder"), color = Veyq.Muted) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    colors = fieldColors,
                )
                TextButton(
                    onClick = {
                        busy = true
                        scope.launch {
                            if (!passMissing && controller.buildMemberInviteLink(tenantId, role, passOrNull)?.also { link ->
                                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(link))
                                } != null) copied = true
                            busy = false
                        }
                    },
                    enabled = !busy && !passMissing,
                ) { Text(if (copied) L10n.t("invite.linkCopied") else L10n.t("invite.copyLink"), color = Veyq.Accent) }
                controller.error?.let { MessageBox(it) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    busy = true
                    scope.launch { sent = controller.emailMemberInvite(tenantId, role, email, passOrNull); busy = false; if (sent) email = "" }
                },
                enabled = !busy && email.isNotBlank() && !passMissing,
            ) { Text(if (sent) L10n.t("invite.emailSent") else L10n.t("invite.emailSend"), color = Veyq.Accent) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(L10n.t("common.close"), color = Veyq.Muted) } },
    )
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

/** Warns that the active tenant is scheduled for deletion; owner gets an inline cancel action. */
@Composable
private fun DeletionBanner(deadlineIso: String, isOwner: Boolean, onCancel: () -> Unit) {
    val date = runCatching {
        java.time.OffsetDateTime.parse(deadlineIso)
            .format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
    }.getOrDefault(deadlineIso)
    Row(
        Modifier.fillMaxWidth()
            .background(Veyq.Danger.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .border(1.dp, Veyq.Danger.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(L10n.t("delete.scheduledBanner").format(date), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Veyq.Danger, modifier = Modifier.weight(1f))
        if (isOwner) TextButton(onClick = onCancel) { Text(L10n.t("delete.cancelDeletion"), color = Veyq.Danger, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun MemoryGrid(memories: List<HomeController.Entry>, covers: Map<String, ImageBitmap>, onOpen: (HomeController.Entry) -> Unit, onRename: (HomeController.Entry) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        memories.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { m -> Box(Modifier.weight(1f)) { MemoryCard(m, covers[m.id], onOpen, onRename) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemoryCard(m: HomeController.Entry, cover: ImageBitmap?, onOpen: (HomeController.Entry) -> Unit, onRename: (HomeController.Entry) -> Unit) {
    Column(
        Modifier
            .border(1.dp, Veyq.Line, RoundedCornerShape(Veyq.Radius))
            .background(Veyq.Surface, RoundedCornerShape(Veyq.Radius))
            .combinedClickable(onClick = { onOpen(m) }, onLongClick = { onRename(m) }),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(Veyq.SurfaceMuted), contentAlignment = Alignment.Center) {
            if (cover != null) Image(cover, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Text(m.name, color = Veyq.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(8.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(f: HomeController.Entry, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Veyq.Line, RoundedCornerShape(Veyq.Radius))
            .background(Veyq.Surface, RoundedCornerShape(Veyq.Radius))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
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
        Text(if (hit.type == "FOLDER") L10n.t("search.folder") else L10n.t("search.memory"), color = Veyq.Accent, fontSize = 12.sp)
    }
}

/** Manage a group's members (GroupOwner): list, add a tenant member, toggle role, remove. */
@Composable
fun GroupMembersDialog(controller: HomeController, group: JSONObject, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var candidates by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var addMenuOpen by remember { mutableStateOf(false) }

    suspend fun reload() {
        val m = controller.groupMembers(group)
        members = (0 until m.length()).map { m.getJSONObject(it) }
        val inGroup = members.map { it.getString("userId") }.toSet()
        val tm = controller.tenantMembers(group.optString("tenantId"))
        candidates = (0 until tm.length()).map { tm.getJSONObject(it) }.filter { it.getString("status") == "ACTIVE" && it.getString("userId") !in inGroup }
    }
    LaunchedEffect(group) { reload() }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Veyq.Surface,
        title = { Text(L10n.t("groups.members"), color = Veyq.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                members.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.getString("displayName"), color = Veyq.Text, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            val newRole = if (m.getString("role") == "GroupOwner") "GroupMember" else "GroupOwner"
                            scope.launch { if (controller.setGroupRole(group, m.getString("userId"), newRole)) reload() }
                        }) { Text(if (m.getString("role") == "GroupOwner") L10n.t("groups.roleOwner") else L10n.t("groups.roleMember"), color = Veyq.Accent, fontSize = 12.sp) }
                        TextButton(onClick = { scope.launch { if (controller.removeGroupMember(group, m.getString("userId"))) reload() } }) {
                            Text(L10n.t("common.remove"), color = Veyq.Danger, fontSize = 12.sp)
                        }
                    }
                }
                if (candidates.isNotEmpty()) {
                    HorizontalDivider()
                    Box {
                        TextButton(onClick = { addMenuOpen = true }) { Text(L10n.t("groups.addMember"), color = Veyq.Accent) }
                        DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                            candidates.forEach { c ->
                                DropdownMenuItem(text = { Text(c.getString("displayName")) }, onClick = {
                                    addMenuOpen = false
                                    scope.launch { if (controller.addGroupMember(group, c.getString("userId"), c.getJSONObject("identityPublicKey").getString("kemPublicKey"))) reload() }
                                })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text(L10n.t("common.close"), color = Veyq.Muted) } },
    )
}
