package com.veyq.app.ui.screens

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veyq.app.i18n.LocalL10n
import com.veyq.app.ui.AppModel
import com.veyq.app.ui.HomeModel
import com.veyq.app.ui.components.MessageBox
import com.veyq.app.ui.theme.VeyqTokens
import com.veyq.app.ui.theme.veyqColors
import com.veyq.client.VeyqClient
import kotlinx.coroutines.launch

/**
 * The Memory shell (cr-0039) — the Android twin of Apple's `HomeView`, built fresh in parallel: open the
 * personal scope, browse the on-device-decrypted tree (folders + Memories), and create / rename / delete.
 * **Responsive** by [WindowWidthSizeClass], mirroring Apple's `NavigationSplitView` (spaces rail + content):
 * a `PermanentNavigationDrawer` on Medium/Expanded (tablet, landscape), a `ModalNavigationDrawer` on Compact
 * (phone, open straight to the content). Drill-in is **state-driven** (a path stack in [HomeModel] + a
 * breadcrumb), not list/detail; the hardware back steps one level up. Operator-blind: names are decrypted on
 * device; the server computes no navigation. The rail's gear opens theme + language Settings (cr-0043,
 * [SettingsSheet]); the real space switcher is a later slice (the rail is a thin static "Personal" for now).
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun HomeScreen(app: AppModel) {
    val activity = LocalContext.current as Activity
    val expanded = calculateWindowSizeClass(activity).widthSizeClass != WindowWidthSizeClass.Compact
    val l = LocalL10n.current

    val model = remember(app) { HomeModel(app.activeClient) }
    LaunchedEffect(Unit) { model.start() }

    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // create kind: true = folder, false = memory, null = hidden
    var createIsFolder by remember { mutableStateOf<Boolean?>(null) }
    var renameTarget by remember { mutableStateOf<VeyqClient.TreeNode?>(null) }
    var deleteTarget by remember { mutableStateOf<VeyqClient.TreeNode?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    // Back: close the drawer first (compact), otherwise step one level up the drill path.
    BackHandler(enabled = (!expanded && drawerState.isOpen) || model.path.isNotEmpty()) {
        if (!expanded && drawerState.isOpen) scope.launch { drawerState.close() } else model.goUp()
    }

    val onSignOut: () -> Unit = { scope.launch { app.signOut() } }
    val onUntrust: () -> Unit = { scope.launch { app.untrust() } }
    val onOpenSettings: () -> Unit = { showSettings = true }
    val browse: @Composable () -> Unit = {
        BrowsePane(
            model = model,
            showMenuButton = !expanded,
            onOpenDrawer = { scope.launch { drawerState.open() } },
            onCreateMemory = { createIsFolder = false },
            onCreateFolder = { createIsFolder = true },
            onRename = { renameTarget = it },
            onDelete = { deleteTarget = it },
        )
    }

    if (expanded) {
        PermanentNavigationDrawer(
            drawerContent = {
                PermanentDrawerSheet(Modifier.width(280.dp)) {
                    SpacesRail(app.email, app.deviceTrusted, onSignOut, onUntrust, onOpenSettings)
                }
            },
        ) { browse() }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = { ModalDrawerSheet { SpacesRail(app.email, app.deviceTrusted, onSignOut, onUntrust, onOpenSettings) } },
        ) { browse() }
    }

    if (showSettings) {
        SettingsSheet(email = app.email, instance = app.apiBase, onDismiss = { showSettings = false })
    }

    // Dialogs (the Compose analog of Apple's create/rename alerts + delete confirmationDialog).
    createIsFolder?.let { isFolder ->
        NameDialog(
            title = if (isFolder) l.t("home.newFolder") else l.t("home.newMemory"),
            confirm = l.t("common.create"),
            onConfirm = { name ->
                createIsFolder = null
                scope.launch { if (isFolder) model.createFolder(name) else model.createMemory(name) }
            },
            onDismiss = { createIsFolder = null },
        )
    }
    renameTarget?.let { target ->
        NameDialog(
            title = l.t("common.rename"),
            confirm = l.t("common.rename"),
            initial = target.name,
            onConfirm = { name -> renameTarget = null; scope.launch { model.rename(target, name) } },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(l.t("home.deleteTitle", target.name)) },
            text = { Text(l.t("home.deleteBody")) },
            confirmButton = {
                TextButton(onClick = { deleteTarget = null; scope.launch { model.delete(target) } }) {
                    Text(l.t("common.delete"), color = veyqColors.danger)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(l.t("common.cancel")) } },
        )
    }
}

/** The spaces rail (drawer sheet): a thin static "Personal" entry + a profile bar (device-trust status +
 *  untrust + a gear to Settings + sign-out) — the Compose analog of Apple's `sidebar` + `profileBar`. The
 *  gear opens the theme + language Settings sheet (cr-0043); the untrust control lives here too. */
@Composable
private fun SpacesRail(
    email: String,
    deviceTrusted: Boolean,
    onSignOut: () -> Unit,
    onUntrust: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val c = veyqColors
    val l = LocalL10n.current
    Column(Modifier.fillMaxHeight().background(c.bg)) {
        Text("veyq", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.text, modifier = Modifier.padding(16.dp))
        Text(
            l.t("home.spaces"),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = c.muted,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        )
        NavigationDrawerItem(
            label = { Text(l.t("home.personal")) },
            selected = true,
            icon = { Icon(Icons.Filled.Person, contentDescription = null) },
            onClick = {},
            modifier = Modifier.padding(horizontal = 12.dp).testTag("space.personal"),
        )
        Spacer(Modifier.weight(1f))
        if (deviceTrusted) {
            HorizontalDivider(color = c.line)
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(l.t("home.deviceTrusted"), fontSize = 12.sp, color = c.muted)
                TextButton(onClick = onUntrust, modifier = Modifier.testTag("Stop trusting this device")) {
                    Text(l.t("home.stopTrusting"), fontSize = 13.sp, color = c.danger)
                }
            }
        }
        HorizontalDivider(color = c.line)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.Person, contentDescription = null, tint = c.muted, modifier = Modifier.size(20.dp))
            Text(
                email,
                fontSize = 13.sp,
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("Settings")) {
                Icon(Icons.Filled.Settings, contentDescription = l.t("settings.title"), tint = c.muted, modifier = Modifier.size(20.dp))
            }
            TextButton(onClick = onSignOut, modifier = Modifier.testTag("Sign out")) {
                Text(l.t("home.signOut"), fontSize = 13.sp, color = c.danger)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowsePane(
    model: HomeModel,
    showMenuButton: Boolean,
    onOpenDrawer: () -> Unit,
    onCreateMemory: () -> Unit,
    onCreateFolder: () -> Unit,
    onRename: (VeyqClient.TreeNode) -> Unit,
    onDelete: (VeyqClient.TreeNode) -> Unit,
) {
    val c = veyqColors
    val l = LocalL10n.current
    var createMenu by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = c.bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        model.path.lastOrNull()?.name ?: l.t("home.personal"),
                        color = c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    when {
                        model.path.isNotEmpty() -> IconButton(onClick = { model.goUp() }, modifier = Modifier.testTag("Back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = l.t("home.back"), tint = c.text)
                        }
                        showMenuButton -> IconButton(onClick = onOpenDrawer, modifier = Modifier.testTag("Spaces")) {
                            Icon(Icons.Filled.Menu, contentDescription = l.t("home.spacesNav"), tint = c.text)
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { createMenu = true }, modifier = Modifier.testTag("Create")) {
                            Icon(Icons.Filled.Add, contentDescription = l.t("home.create"), tint = c.text)
                        }
                        DropdownMenu(expanded = createMenu, onDismissRequest = { createMenu = false }) {
                            DropdownMenuItem(text = { Text(l.t("home.newMemory")) }, onClick = { createMenu = false; onCreateMemory() })
                            DropdownMenuItem(text = { Text(l.t("home.newFolder")) }, onClick = { createMenu = false; onCreateFolder() })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bg, scrolledContainerColor = c.bg),
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            model.error?.let { e -> item(span = { GridItemSpan(maxLineSpan) }) { MessageBox(e) } }
            if (model.loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            if (model.crumbs.size > 1) item(span = { GridItemSpan(maxLineSpan) }) { Breadcrumb(model) }

            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(l.t("home.memories"), model.level.memories.size) }
            if (model.level.memories.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyText(l.t("home.noMemories")) }
            } else {
                items(model.level.memories, key = { it.id }) { node ->
                    MemoryCard(node, onRename = { onRename(node) }, onDelete = { onDelete(node) })
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(l.t("home.folders"), model.level.folders.size) }
            if (model.level.folders.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyText(l.t("home.noFolders")) }
            } else {
                items(model.level.folders, key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { node ->
                    FolderRow(node, onOpen = { model.openFolder(node) }, onRename = { onRename(node) }, onDelete = { onDelete(node) })
                }
            }
        }
    }
}

@Composable
private fun Breadcrumb(model: HomeModel) {
    val c = veyqColors
    val l = LocalL10n.current
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        model.crumbs.forEachIndexed { idx, crumb ->
            if (idx > 0) Text("/", fontSize = 14.sp, color = c.muted)
            val last = idx == model.crumbs.lastIndex
            Text(
                if (crumb.nodeId == null) l.t("home.personal") else crumb.name,
                fontSize = 14.sp,
                fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                color = if (last) c.text else c.muted,
                modifier = Modifier.clickable(enabled = !last) { model.goto(crumb) },
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    val c = veyqColors
    Row(
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = c.text)
        Box(
            Modifier.clip(RoundedCornerShape(999.dp)).background(c.surfaceMuted).padding(horizontal = 9.dp, vertical = 2.dp),
        ) {
            Text("$count", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.muted)
        }
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(text, fontSize = 14.sp, color = veyqColors.muted)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemoryCard(node: VeyqClient.TreeNode, onRename: () -> Unit, onDelete: () -> Unit) {
    val c = veyqColors
    val shape = RoundedCornerShape(VeyqTokens.radius)
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .shadow(3.dp, shape, clip = false)
                .clip(shape)
                .background(c.surface)
                .border(1.dp, c.line, shape)
                .combinedClickable(onClick = {}, onLongClick = { menu = true })
                .testTag("memory.${node.name}"),
        ) {
            // The cover: an accent-tinted 16:9 panel with the Memory glyph (parity with the web/iOS cover,
            // until media thumbnails land). Top corners follow the card's clip.
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(c.accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Photo, contentDescription = null, tint = c.accent, modifier = Modifier.size(34.dp))
            }
            Text(
                node.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        NodeMenu(menu, onDismiss = { menu = false }, onRename = onRename, onDelete = onDelete)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(node: VeyqClient.TreeNode, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val c = veyqColors
    val shape = RoundedCornerShape(VeyqTokens.radius)
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(3.dp, shape, clip = false)
                .clip(shape)
                .background(c.surface)
                .border(1.dp, c.line, shape)
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(12.dp)
                .testTag("folder.${node.name}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // A rounded accent-tinted icon tile (the web/iOS folder language).
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(VeyqTokens.radiusSm)).background(c.accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null, tint = c.accent, modifier = Modifier.size(22.dp))
            }
            Text(
                node.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
        }
        NodeMenu(menu, onDismiss = { menu = false }, onRename = onRename, onDelete = onDelete)
    }
}

@Composable
private fun NodeMenu(expanded: Boolean, onDismiss: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val l = LocalL10n.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(l.t("common.rename")) }, onClick = { onDismiss(); onRename() })
        DropdownMenuItem(text = { Text(l.t("common.delete"), color = veyqColors.danger) }, onClick = { onDismiss(); onDelete() })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NameDialog(
    title: String,
    confirm: String,
    initial: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    val l = LocalL10n.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(l.t("common.name")) },
                modifier = Modifier.testTag("name.field"),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(l.t("common.cancel")) } },
    )
}
