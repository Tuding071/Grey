package com.grey.browser

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GreyBrowser()
        }
    }
}

// ── State Wrapper for Tabs ──────────────────────────────────────────
class TabState(val session: GeckoSession) {
    var title by mutableStateOf("New Tab")
    var url by mutableStateOf("about:blank")
    var progress by mutableIntStateOf(100)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GreyBrowser() {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val clipboardManager = LocalClipboardManager.current
    val runtime = remember { GeckoRuntime.create(context) }
    val prefs = remember { context.getSharedPreferences("browser_settings", Context.MODE_PRIVATE) }

    val applySettingsToSession = { session: GeckoSession ->
        session.settings.allowJavascript = prefs.getBoolean("javascript.enabled", true)
        session.settings.useTrackingProtection = prefs.getBoolean("privacy.trackingprotection.enabled", false)
        session.settings.suspendMediaWhenInactive = !prefs.getBoolean("media.autoplay.enabled", true)
    }

    // Context Menu State
    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuUri by remember { mutableStateOf<String?>(null) }
    var contextMenuScreenX by remember { mutableIntStateOf(0) }
    var contextMenuScreenY by remember { mutableIntStateOf(0) }

    // Helper to wire up all GeckoView delegates to our Compose TabState
    val setupDelegates = { tabState: TabState ->
        tabState.session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                tabState.url = url
                tabState.progress = 5 // Initial progress jump
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                tabState.progress = 100
            }
            override fun onProgressChange(session: GeckoSession, progress: Int) {
                tabState.progress = progress
            }
        }
        tabState.session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                tabState.title = title ?: "New Tab"
            }
            override fun onContextMenu(
                session: GeckoSession,
                screenX: Int,
                screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement
            ) {
                if (element.linkUri != null) {
                    contextMenuUri = element.linkUri
                    contextMenuScreenX = screenX
                    contextMenuScreenY = screenY
                    showContextMenu = true
                }
            }
        }
    }

    // Tabs Manager
    val tabs = remember {
        mutableStateListOf<TabState>().apply {
            val initialSession = GeckoSession().apply {
                applySettingsToSession(this)
                open(runtime)
                loadUri("about:blank")
            }
            val initialTab = TabState(initialSession)
            setupDelegates(initialTab)
            add(initialTab)
        }
    }

    var currentTabIndex by remember { mutableIntStateOf(0) }
    var showTabManager by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showScripting by remember { mutableStateOf(false) }

    val currentTab = tabs[currentTabIndex]

    // ── Navigation / Back Button Handling ─────────────────────────
    BackHandler {
        if (currentTab.url == "about:blank") {
            if (tabs.size > 1) {
                tabs.removeAt(currentTabIndex)
                currentTabIndex = minOf(currentTabIndex, tabs.lastIndex)
            } else {
                activity?.finish() // Exit app if it's the last blank tab
            }
        } else {
            currentTab.session.goBack()
        }
    }

    // GeckoView for the current tab
    @Composable
    fun GeckoViewBox() {
        AndroidView(
            factory = { ctx ->
                GeckoView(ctx).apply {
                    setSession(currentTab.session)
                }
            },
            update = { geckoView ->
                geckoView.setSession(currentTab.session)
            },
            modifier = Modifier.fillMaxSize()
        )
    }

    // ── Dialogs ───────────────────────────────────────────────────
    if (showSettings) {
        SettingsDialog(
            prefs = prefs,
            onDismiss = { showSettings = false },
            onSettingsApplied = { tabs.forEach { applySettingsToSession(it.session) } }
        )
    }

    if (showScripting) {
        ScriptingDialog(
            initialScript = prefs.getString("user_script", "alert('Hello from Grey Browser!');") ?: "",
            onDismiss = { showScripting = false },
            onSaveAndRun = { script ->
                prefs.edit().putString("user_script", script).apply()
                val jsUri = "javascript:" + Uri.encode("(function(){\n$script\n})();")
                currentTab.session.loadUri(jsUri)
                showScripting = false
            }
        )
    }

    // ── Long-Press Context Menu Popup ─────────────────────────────
    if (showContextMenu && contextMenuUri != null) {
        Popup(
            alignment = Alignment.TopStart,
            offset = IntOffset(contextMenuScreenX, contextMenuScreenY),
            onDismissRequest = { showContextMenu = false },
            properties = PopupProperties(focusable = true)
        ) {
            Column(
                modifier = Modifier
                    .border(1.dp, Color.White, RectangleShape)
                    .background(Color(0xFF1E1E1E))
                    .width(IntrinsicSize.Max)
            ) {
                ContextMenuItem("New Tab") {
                    val s = GeckoSession().apply { applySettingsToSession(this); open(runtime); loadUri(contextMenuUri!!) }
                    val newTab = TabState(s).also { setupDelegates(it) }
                    tabs.add(newTab)
                    currentTabIndex = tabs.lastIndex
                    showContextMenu = false
                }
                ContextMenuItem("Open in Background") {
                    val s = GeckoSession().apply { applySettingsToSession(this); open(runtime); loadUri(contextMenuUri!!) }
                    val newTab = TabState(s).also { setupDelegates(it) }
                    tabs.add(newTab)
                    showContextMenu = false
                }
                ContextMenuItem("Copy link") {
                    clipboardManager.setText(AnnotatedString(contextMenuUri!!))
                    showContextMenu = false
                }
            }
        }
    }

    // ── Tab Manager Sheet ─────────────────────────────────────────
    if (showTabManager) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showTabManager = false },
            sheetState = sheetState,
            containerColor = Color(0xFF1E1E1E),
            contentColor = Color.White,
            shape = RectangleShape
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showTabManager = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                    Text("Tabs", style = TextStyle(color = Color.White, fontSize = 18.sp))
                }

                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(tabs) { index, tab ->
                        val isCurrent = index == currentTabIndex
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentTabIndex = index
                                    showTabManager = false
                                },
                            color = if (isCurrent) Color(0xFF2A2A2A) else Color.Transparent
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = tab.title, // Actually uses the website title now!
                                    color = if (isCurrent) Color.White else Color.Gray,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = {
                                    if (tabs.size > 1) {
                                        tabs.removeAt(index).session.close()
                                        if (currentTabIndex >= tabs.size)
                                            currentTabIndex = tabs.lastIndex
                                    }
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Close tab", tint = Color.White)
                                }
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        val s = GeckoSession().apply { applySettingsToSession(this); open(runtime); loadUri("about:blank") }
                        val newTab = TabState(s).also { setupDelegates(it) }
                        tabs.add(newTab)
                        currentTabIndex = tabs.lastIndex
                        showTabManager = false
                    },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    shape = RectangleShape,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = BorderStroke(1.dp, Color.White)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("New Tab", color = Color.White)
                }
            }
        }
    }

    // URL bar state syncing
    var urlInput by remember { mutableStateOf(currentTab.url) }
    LaunchedEffect(currentTab, currentTab.url) {
        urlInput = currentTab.url
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Top row: Tab button | URL bar | Menu button ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showTabManager = true }) {
                    Icon(Icons.Default.Tab, contentDescription = "Open tabs", tint = Color.White)
                }

                // URL / Progress Bar
                val isLoading = currentTab.progress in 1..99
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    placeholder = { Text("Enter URL", color = Color.White.copy(alpha = 0.5f)) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp)
                        .background(Color(0xFF1E1E1E))
                        .drawBehind {
                            if (isLoading) {
                                val fraction = currentTab.progress / 100f
                                drawRect(color = Color.White, size = size.copy(width = size.width * fraction))
                            }
                        },
                    textStyle = TextStyle(
                        color = if (isLoading) Color.Gray else Color.White,
                        fontSize = 16.sp
                    ),
                    shape = RectangleShape,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            val uri = if (urlInput.contains("://")) urlInput else "https://$urlInput"
                            currentTab.session.loadUri(uri)
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color.White,
                        cursorColor = if (isLoading) Color.Gray else Color.White
                    )
                )

                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Color.White)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.border(1.dp, Color.White, RectangleShape),
                        containerColor = Color(0xFF1E1E1E),
                        shape = RectangleShape
                    ) {
                        DropdownMenuItem(text = { Text("Scripting", color = Color.White) }, onClick = { showMenu = false; showScripting = true })
                        DropdownMenuItem(text = { Text("Settings", color = Color.White) }, onClick = { showMenu = false; showSettings = true })
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                GeckoViewBox()
            }
        }
    }
}

// ── Custom Menu Item Helper ───────────────────────────────────────
@Composable
fun ContextMenuItem(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(text, color = Color.White, fontSize = 16.sp)
    }
}

// ── Scripting Dialog ──────────────────────────────────────────────
@Composable
fun ScriptingDialog(initialScript: String, onDismiss: () -> Unit, onSaveAndRun: (String) -> Unit) {
    var script by remember { mutableStateOf(initialScript) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Inject JavaScript", color = Color.White, fontSize = 20.sp) },
        text = {
            Column {
                Text(
                    text = "Write or paste JS below. It will execute immediately on the current tab.",
                    color = Color.Gray,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = script,
                    onValueChange = { script = it },
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.White, unfocusedBorderColor = Color.White,
                        cursorColor = Color.White, focusedContainerColor = Color(0xFF1E1E1E),
                        unfocusedContainerColor = Color(0xFF1E1E1E)
                    ),
                    shape = RectangleShape
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSaveAndRun(script) }) { Text("Run", color = Color.White) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = Color.White) } },
        containerColor = Color(0xFF1E1E1E), titleContentColor = Color.White, textContentColor = Color.White,
        shape = RectangleShape, tonalElevation = 0.dp
    )
}

// ── Settings Dialog ───────────────────────────────────────────────
@Composable
fun SettingsDialog(prefs: SharedPreferences, onDismiss: () -> Unit, onSettingsApplied: () -> Unit) {
    var jsEnabled by remember { mutableStateOf(prefs.getBoolean("javascript.enabled", true)) }
    var trackingProtection by remember { mutableStateOf(prefs.getBoolean("privacy.trackingprotection.enabled", false)) }
    var cookieBehavior by remember { mutableIntStateOf(prefs.getInt("network.cookie.cookieBehavior", 0)) }
    var autoplayMedia by remember { mutableStateOf(prefs.getBoolean("media.autoplay.enabled", true)) }

    val applyChanges = {
        prefs.edit()
            .putBoolean("javascript.enabled", jsEnabled)
            .putBoolean("privacy.trackingprotection.enabled", trackingProtection)
            .putInt("network.cookie.cookieBehavior", cookieBehavior)
            .putBoolean("media.autoplay.enabled", autoplayMedia).apply()
        onSettingsApplied()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings", color = Color.White, fontSize = 20.sp) },
        text = {
            Column {
                SettingSwitch(label = "JavaScript", checked = jsEnabled, onCheckedChange = { jsEnabled = it })
                SettingSwitch(label = "Tracking Protection", checked = trackingProtection, onCheckedChange = { trackingProtection = it })
                Text("Cookie Behavior", color = Color.White, fontSize = 14.sp)
                CookieBehaviorSelector(current = cookieBehavior, onChange = { cookieBehavior = it })
                SettingSwitch(label = "Autoplay Media", checked = autoplayMedia, onCheckedChange = { autoplayMedia = it })
            }
        },
        confirmButton = { TextButton(onClick = applyChanges) { Text("OK", color = Color.White) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) } },
        containerColor = Color(0xFF1E1E1E), titleContentColor = Color.White, textContentColor = Color.White,
        shape = RectangleShape, tonalElevation = 0.dp
    )
}

@Composable
fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 14.sp)
        Switch(
            checked = checked, onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF444444),
                uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF444444)
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookieBehaviorSelector(current: Int, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("Accept all" to 0, "Reject all" to 1, "Only from visited" to 2)
    val selectedText = options.firstOrNull { it.second == current }?.first ?: "Accept all"

    Box {
        OutlinedTextField(
            value = selectedText, onValueChange = {}, readOnly = true,
            trailingIcon = { Icon(Icons.Default.Close, contentDescription = null, tint = Color.White) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp), shape = RectangleShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.White, unfocusedBorderColor = Color.White,
                focusedContainerColor = Color(0xFF1E1E1E), unfocusedContainerColor = Color(0xFF1E1E1E)
            ),
            enabled = true
        )
        Box(modifier = Modifier.matchParentSize().clickable { expanded = true })
        DropdownMenu(
            expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.border(1.dp, Color.White, RectangleShape),
            containerColor = Color(0xFF1E1E1E), shape = RectangleShape
        ) {
            options.forEach { (label, value) ->
                DropdownMenuItem(text = { Text(label, color = Color.White) }, onClick = { onChange(value); expanded = false })
            }
        }
    }
}
