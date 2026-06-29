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
 * device; the server computes no navigation. Theme / language settings + the real space switcher are later
 * slices (the rail is a thin static "Personal" + a sign-out for now).
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun HomeScreen(app: AppModel) {
    val activity = LocalContext.current as Activity
    val expanded = calculateWindowSizeClass(activity).widthSizeClass != WindowWidthSizeClass.Compact

    val model = remember(app) { HomeModel(app.activeClient) }
    LaunchedEffect(Unit) { model.start() }

    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // create kind: true = folder, false = memory, null = hidden
    var createIsFolder by remember { mutableStateOf<Boolean?>(null) }
    var renameTarget by remember { mutableStateOf<VeyqClient.TreeNode?>(null) }
    var deleteTarget by remember { mutableStateOf<VeyqClient.TreeNode?>(null) }

    // Back: close the drawer first (compact), otherwise step one level up the drill path.
    BackHandler(enabled = (!expanded && drawerState.isOpen) || model.path.isNotEmpty()) {
        if (!expanded && drawerState.isOpen) scope.launch { drawerState.close() } else model.goUp()
    }

    val onSignOut: () -> Unit = { scope.launch { app.signOut() } }
    val onUntrust: () -> Unit = { scope.launch { app.untrust() } }
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
                    SpacesRail(app.email, app.deviceTrusted, onSignOut, onUntrust)
                }
            },
        ) { browse() }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = { ModalDrawerSheet { SpacesRail(app.email, app.deviceTrusted, onSignOut, onUntrust) } },
        ) { browse() }
    }

    // Dialogs (the Compose analog of Apple's create/rename alerts + delete confirmationDialog).
    createIsFolder?.let { isFolder ->
        NameDialog(
            title = if (isFolder) "New folder" else "New memory",
            confirm = "Create",
            onConfirm = { name ->
                createIsFolder = null
                scope.launch { if (isFolder) model.createFolder(name) else model.createMemory(name) }
            },
            onDismiss = { createIsFolder = null },
        )
    }
    renameTarget?.let { target ->
        NameDialog(
            title = "Rename",
            confirm = "Rename",
            initial = target.name,
            onConfirm = { name -> renameTarget = null; scope.launch { model.rename(target, name) } },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete “${target.name}”?") },
            text = { Text("This removes it and everything inside it.") },
            confirmButton = {
                TextButton(onClick = { deleteTarget = null; scope.launch { model.delete(target) } }) {
                    Text("Delete", color = veyqColors.danger)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

/** The spaces rail (drawer sheet): a thin static "Personal" entry + a profile bar (device-trust status +
 *  untrust + sign-out) — the Compose analog of Apple's `sidebar` + `profileBar`. The untrust control lives
 *  here (not the old landing) until a device-management/settings slice lands. The real switcher / theme +
 *  language settings are later slices. */
@Composable
private fun SpacesRail(email: String, deviceTrusted: Boolean, onSignOut: () -> Unit, onUntrust: () -> Unit) {
    val c = veyqColors
    Column(Modifier.fillMaxHeight().background(c.bg)) {
        Text("veyq", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.text, modifier = Modifier.padding(16.dp))
        Text(
            "SPACES",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = c.muted,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        )
        NavigationDrawerItem(
            label = { Text("Personal") },
            selected = true,
            icon = { Icon(Icons.Filled.Person, contentDescription = null) },
            onClick = {},
            modifier = Modifier.padding(horizontal = 12.dp).testTag("space.personal"),
        )
        Spacer(Modifier.weight(1f))
        if (deviceTrusted) {
            HorizontalDivider(color = c.line)
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text("This device is trusted — next time you’ll unlock with just your password.", fontSize = 12.sp, color = c.muted)
                TextButton(onClick = onUntrust, modifier = Modifier.testTag("Stop trusting this device")) {
                    Text("Stop trusting this device", fontSize = 13.sp, color = c.danger)
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
            TextButton(onClick = onSignOut, modifier = Modifier.testTag("Sign out")) {
                Text("Sign out", fontSize = 13.sp, color = c.danger)
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
    var createMenu by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = c.bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        model.path.lastOrNull()?.name ?: "Personal",
                        color = c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    when {
                        model.path.isNotEmpty() -> IconButton(onClick = { model.goUp() }, modifier = Modifier.testTag("Back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.text)
                        }
                        showMenuButton -> IconButton(onClick = onOpenDrawer, modifier = Modifier.testTag("Spaces")) {
                            Icon(Icons.Filled.Menu, contentDescription = "Spaces", tint = c.text)
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { createMenu = true }, modifier = Modifier.testTag("Create")) {
                            Icon(Icons.Filled.Add, contentDescription = "Create", tint = c.text)
                        }
                        DropdownMenu(expanded = createMenu, onDismissRequest = { createMenu = false }) {
                            DropdownMenuItem(text = { Text("New memory") }, onClick = { createMenu = false; onCreateMemory() })
                            DropdownMenuItem(text = { Text("New folder") }, onClick = { createMenu = false; onCreateFolder() })
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

            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Memories", model.level.memories.size) }
            if (model.level.memories.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyText("No memories yet") }
            } else {
                items(model.level.memories, key = { it.id }) { node ->
                    MemoryCard(node, onRename = { onRename(node) }, onDelete = { onDelete(node) })
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Folders", model.level.folders.size) }
            if (model.level.folders.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyText("No folders yet") }
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
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        model.crumbs.forEachIndexed { idx, crumb ->
            if (idx > 0) Text("/", fontSize = 14.sp, color = c.muted)
            val last = idx == model.crumbs.lastIndex
            Text(
                if (crumb.nodeId == null) "Personal" else crumb.name,
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
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Rename") }, onClick = { onDismiss(); onRename() })
        DropdownMenuItem(text = { Text("Delete", color = veyqColors.danger) }, onClick = { onDismiss(); onDelete() })
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.testTag("name.field"),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
