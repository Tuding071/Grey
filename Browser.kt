package com.grey.browser

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import java.util.LinkedHashMap

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GreyBrowser()
        }
    }
}

// ── Tab State ───────────────────────────────────────────────────────
class TabState(val session: GeckoSession) {
    var title by mutableStateOf("New Tab")
    var url by mutableStateOf("about:blank")
    var progress by mutableIntStateOf(100)
    var lastUpdated by mutableLongStateOf(System.currentTimeMillis())
    var favicon by mutableStateOf<Bitmap?>(null)
}

// Favicon Cache (max 100, LRU)
val faviconCache = object : LinkedHashMap<String, Bitmap>(100, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
        return size > 100
    }
}

fun getDomainName(url: String): String {
    if (url == "about:blank" || url.isBlank()) return "Blank"
    return try {
        Uri.parse(url).host?.removePrefix("www.") ?: "Unknown"
    } catch (e: Exception) {
        "Unknown"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GreyBrowser() {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val clipboardManager = LocalClipboardManager.current
    val runtime = remember { GeckoRuntime.create(context) }
    val prefs = remember { context.getSharedPreferences("browser_settings", Context.MODE_PRIVATE) }

    val applySettingsToSession: (GeckoSession) -> Unit = { session ->
        session.settings.allowJavascript = prefs.getBoolean("javascript.enabled", true)
        session.settings.useTrackingProtection = prefs.getBoolean("privacy.trackingprotection.enabled", false)
        session.settings.suspendMediaWhenInactive = !prefs.getBoolean("media.autoplay.enabled", true)
    }

    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuUri by remember { mutableStateOf<String?>(null) }

    val setupDelegates: (TabState) -> Unit = { tabState ->
        tabState.session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                tabState.url = url
                tabState.progress = 5
                tabState.lastUpdated = System.currentTimeMillis()
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                tabState.progress = 100
                tabState.lastUpdated = System.currentTimeMillis()
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
                    showContextMenu = true
                }
            }
        }
    }

    // Tabs
    val tabs = remember { mutableStateListOf<TabState>() }

    LaunchedEffect(Unit) {
        if (tabs.isEmpty()) {
            val initialSession = GeckoSession().apply {
                applySettingsToSession(this)
                open(runtime)
                loadUri("about:blank")
            }
            val initialTab = TabState(initialSession)
            setupDelegates(initialTab)
            tabs.add(initialTab)
        }
    }

    var currentTabIndex by remember { mutableIntStateOf(0) }
    var showTabManager by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showScripting by remember { mutableStateOf(false) }

    val pinnedDomains = remember { mutableStateListOf<String>() }
    var selectedDomain by remember { mutableStateOf("Blank") }

    val currentTab = tabs.getOrNull(currentTabIndex) ?: tabs.firstOrNull() ?: return

    // Back Handler
    BackHandler {
        if (currentTab.url == "about:blank") {
            if (tabs.size > 1) {
                tabs.remove(currentTab)
                currentTab.session.close()
                currentTabIndex = (currentTabIndex - 1).coerceAtLeast(0)
            } else {
                activity?.finish()
            }
        } else {
            currentTab.session.goBack()
        }
    }

    @Composable
    fun GeckoViewBox() {
        AndroidView(
            factory = { ctx -> GeckoView(ctx).apply { setSession(currentTab.session) } },
            update = { it.setSession(currentTab.session) },
            modifier = Modifier.fillMaxSize()
        )
    }

    // Dialogs
    if (showSettings) {
        SettingsDialog(prefs = prefs, onDismiss = { showSettings = false }) {
            tabs.forEach { applySettingsToSession(it.session) }
        }
    }

    if (showScripting) {
        ScriptingDialog(
            initialScript = prefs.getString("user_script", "alert('Hello from Grey Browser!');") ?: "",
            onDismiss = { showScripting = false },
            onSaveAndRun = { script ->
                prefs.edit().putString("user_script", script).apply()
                currentTab.session.loadUri("javascript:" + Uri.encode("(function(){\n$script\n})();"))
                showScripting = false
            }
        )
    }

    // Context Menu
    if (showContextMenu && contextMenuUri != null) {
        Popup(
            alignment = Alignment.Center,
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

    // Fullscreen Tab Manager
    if (showTabManager) {
        val domainGroups = tabs.groupBy { getDomainName(it.url) }
        val sortedDomains = domainGroups.keys.sortedWith(
            compareByDescending<String> { pinnedDomains.contains(it) }
                .thenByDescending { domain -> domainGroups[domain]?.maxOfOrNull { it.lastUpdated } ?: 0L }
        )

        LaunchedEffect(Unit) {
            selectedDomain = getDomainName(currentTab.url)
        }

        ModalBottomSheet(
            onDismissRequest = { showTabManager = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color(0xFF121212),
            contentColor = Color.White,
            shape = RectangleShape,
            dragHandle = null
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showTabManager = false }) {
                        Icon(Icons.Default.Close, "Close", tint = Color.White)
                    }
                    Text("Tab Manager", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Spacer(Modifier.weight(1f))
                }

                Divider(color = Color.DarkGray)

                LazyRow(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    items(sortedDomains) { domain ->
                        val isSelected = domain == selectedDomain
                        val isPinned = pinnedDomains.contains(domain)
                        val tabCount = domainGroups[domain]?.size ?: 0

                        Surface(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .clickable { selectedDomain = domain },
                            color = if (isSelected) Color(0xFF2A2A2A) else Color.Transparent,
                            border = if (isSelected) BorderStroke(1.dp, Color.White) else null,
                            shape = RectangleShape
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isPinned) Text("📌 ", fontSize = 14.sp)
                                Text(domain, color = Color.White, fontSize = 15.sp)
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier.background(Color.DarkGray, RectangleShape)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(tabCount.toString(), fontSize = 11.sp, color = Color.White)
                                }
                            }
                        }
                    }
                }

                Divider(color = Color.DarkGray)

                val tabsToShow = domainGroups[selectedDomain] ?: emptyList()
                val listState = rememberLazyListState()

                LaunchedEffect(selectedDomain) {
                    val index = tabsToShow.indexOf(currentTab)
                    if (index >= 0) listState.scrollToItem(index)
                }

                LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                    items(tabsToShow) { tab ->
                        val isCurrent = tab == currentTab
                        val domain = getDomainName(tab.url)
                        val favicon = tab.favicon ?: faviconCache[domain]

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentTabIndex = tabs.indexOf(tab)
                                    showTabManager = false
                                },
                            color = if (isCurrent) Color(0xFF2A2A2A) else Color.Transparent
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (favicon != null) {
                                    Image(
                                        bitmap = favicon.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.size(24.dp).background(Color.DarkGray, RectangleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("🌐", fontSize = 14.sp)
                                    }
                                }

                                Spacer(Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = tab.title,
                                        color = if (isCurrent) Color.White else Color.Gray,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = tab.url,
                                        color = Color.Gray,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                IconButton(onClick = {
                                    tab.session.close()
                                    tabs.remove(tab)

                                    if (tabs.isEmpty()) {
                                        val s = GeckoSession().apply {
                                            applySettingsToSession(this)
                                            open(runtime)
                                            loadUri("about:blank")
                                        }
                                        val newTab = TabState(s).also { setupDelegates(it) }
                                        tabs.add(newTab)
                                        currentTabIndex = 0
                                    } else if (currentTabIndex >= tabs.size) {
                                        currentTabIndex = tabs.lastIndex
                                    }
                                }) {
                                    Icon(Icons.Default.Close, "Close tab", tint = Color.White)
                                }
                            }
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    OutlinedButton(
                        onClick = {
                            val s = GeckoSession().apply {
                                applySettingsToSession(this)
                                open(runtime)
                                loadUri("about:blank")
                            }
                            val newTab = TabState(s).also { setupDelegates(it) }
                            tabs.add(newTab)
                            currentTabIndex = tabs.lastIndex
                            showTabManager = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RectangleShape,
                        border = BorderStroke(1.dp, Color.White)
                    ) {
                        Icon(Icons.Default.Add, null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("New Tab", color = Color.White)
                    }

                    Spacer(Modifier.height(8.dp))

                    Row {
                        val isPinned = pinnedDomains.contains(selectedDomain)
                        OutlinedButton(
                            onClick = {
                                if (isPinned) pinnedDomains.remove(selectedDomain) else pinnedDomains.add(selectedDomain)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RectangleShape,
                            border = BorderStroke(1.dp, Color.White)
                        ) {
                            Text(if (isPinned) "Unpin Group" else "Pin Group")
                        }

                        Spacer(Modifier.width(8.dp))

                        OutlinedButton(
                            onClick = {
                                val tabsToRemove = tabs.filter { getDomainName(it.url) == selectedDomain }
                                tabsToRemove.forEach { it.session.close() }
                                tabs.removeAll(tabsToRemove)

                                if (tabs.isEmpty()) {
                                    val s = GeckoSession().apply {
                                        applySettingsToSession(this)
                                        open(runtime)
                                        loadUri("about:blank")
                                    }
                                    tabs.add(TabState(s).also { setupDelegates(it) })
                                    currentTabIndex = 0
                                } else {
                                    currentTabIndex = currentTabIndex.coerceAtMost(tabs.lastIndex)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RectangleShape,
                            border = BorderStroke(1.dp, Color.White)
                        ) {
                            Text("Delete Group")
                        }
                    }
                }
            }
        }
    }

    // URL Bar
    var urlInput by remember { mutableStateOf(TextFieldValue(currentTab.url)) }
    var isUrlFocused by remember { mutableStateOf(false) }

    LaunchedEffect(currentTab.url) {
        if (!isUrlFocused) {
            urlInput = TextFieldValue(currentTab.url)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showTabManager = true }) {
                    Icon(Icons.Default.Tab, contentDescription = "Open tabs", tint = Color.White)
                }

                val isLoading = currentTab.progress in 1..99

                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    singleLine = true,
                    placeholder = { Text("Enter URL", color = Color.White.copy(alpha = 0.5f)) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp)
                        .onFocusChanged { focusState ->
                            isUrlFocused = focusState.isFocused
                            if (focusState.isFocused) {
                                urlInput = urlInput.copy(selection = TextRange(0, urlInput.text.length))
                            }
                        }
                        .drawBehind {
                            if (isLoading) {
                                val fraction = currentTab.progress / 100f
                                drawRect(color = Color.White, size = size.copy(width = size.width * fraction))
                            }
                        },
                    textStyle = TextStyle(color = if (isLoading) Color.Gray else Color.White, fontSize = 16.sp),
                    shape = RectangleShape,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            val input = urlInput.text.trim()
                            val uri = if (input.contains("://")) input else "https://$input"
                            currentTab.session.loadUri(uri)
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF1E1E1E),
                        unfocusedContainerColor = Color(0xFF1E1E1E),
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color.White,
                        cursorColor = Color.White
                    )
                )

                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Color.White)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
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

// ── Helper Composables (unchanged) ─────────────────────────────────
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

@Composable
fun ScriptingDialog(
    initialScript: String,
    onDismiss: () -> Unit,
    onSaveAndRun: (String) -> Unit
) {
    var script by remember { mutableStateOf(initialScript) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Inject JavaScript", color = Color.White, fontSize = 20.sp) },
        text = {
            Column {
                Text(
                    "Write or paste JS below. It will execute immediately on the current tab.",
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
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color.White,
                        cursorColor = Color.White,
                        focusedContainerColor = Color(0xFF1E1E1E),
                        unfocusedContainerColor = Color(0xFF1E1E1E)
                    ),
                    shape = RectangleShape
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSaveAndRun(script) }) { Text("Run", color = Color.White) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = Color.White) } },
        containerColor = Color(0xFF1E1E1E),
        shape = RectangleShape
    )
}

@Composable
fun SettingsDialog(
    prefs: SharedPreferences,
    onDismiss: () -> Unit,
    onSettingsApplied: () -> Unit
) {
    var jsEnabled by remember { mutableStateOf(prefs.getBoolean("javascript.enabled", true)) }
    var trackingProtection by remember { mutableStateOf(prefs.getBoolean("privacy.trackingprotection.enabled", false)) }
    var cookieBehavior by remember { mutableIntStateOf(prefs.getInt("network.cookie.cookieBehavior", 0)) }
    var autoplayMedia by remember { mutableStateOf(prefs.getBoolean("media.autoplay.enabled", true)) }

    val applyChanges = {
        prefs.edit()
            .putBoolean("javascript.enabled", jsEnabled)
            .putBoolean("privacy.trackingprotection.enabled", trackingProtection)
            .putInt("network.cookie.cookieBehavior", cookieBehavior)
            .putBoolean("media.autoplay.enabled", autoplayMedia)
            .apply()
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
                Text("Cookie Behavior", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                CookieBehaviorSelector(current = cookieBehavior, onChange = { cookieBehavior = it })
                SettingSwitch(label = "Autoplay Media", checked = autoplayMedia, onCheckedChange = { autoplayMedia = it })
            }
        },
        confirmButton = { TextButton(onClick = applyChanges) { Text("OK", color = Color.White) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) } },
        containerColor = Color(0xFF1E1E1E),
        shape = RectangleShape
    )
}

@Composable
fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 14.sp)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF444444),
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFF444444)
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
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { Icon(Icons.Default.Close, contentDescription = null, tint = Color.White) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
            shape = RectangleShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.White,
                unfocusedBorderColor = Color.White,
                focusedContainerColor = Color(0xFF1E1E1E),
                unfocusedContainerColor = Color(0xFF1E1E1E)
            )
        )
        Box(modifier = Modifier.matchParentSize().clickable { expanded = true })
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.border(1.dp, Color.White, RectangleShape),
            containerColor = Color(0xFF1E1E1E),
            shape = RectangleShape
        ) {
            options.forEach { (label, value) ->
                DropdownMenuItem(
                    text = { Text(label, color = Color.White) },
                    onClick = { onChange(value); expanded = false }
                )
            }
        }
    }
}
