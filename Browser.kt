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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
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
    var lastUpdated by mutableLongStateOf(System.currentTimeMillis())
    var isBlankTab by mutableStateOf(true)
}

// Helper to extract the main domain for grouping (ignores subdomains)
fun getDomainName(url: String): String {
    if (url == "about:blank" || url.isBlank()) return ""
    return try {
        val host = Uri.parse(url).host?.removePrefix("www.") ?: return ""
        // Extract main domain (second-level + top-level)
        val parts = host.split(".")
        if (parts.size >= 2) {
            // Get last two parts (e.g., "google.com" from "mail.google.com")
            "${parts[parts.size - 2]}.${parts[parts.size - 1]}"
        } else {
            host
        }
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

    val applySettingsToSession = { session: GeckoSession ->
        session.settings.allowJavascript = prefs.getBoolean("javascript.enabled", true)
        session.settings.useTrackingProtection = prefs.getBoolean("privacy.trackingprotection.enabled", false)
        session.settings.suspendMediaWhenInactive = !prefs.getBoolean("media.autoplay.enabled", true)
    }

    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuUri by remember { mutableStateOf<String?>(null) }

    val setupDelegates = { tabState: TabState ->
        tabState.session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                tabState.url = url
                tabState.progress = 5
                tabState.lastUpdated = System.currentTimeMillis()
                if (url != "about:blank") {
                    tabState.isBlankTab = false
                }
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
                if (!tabState.isBlankTab) {
                    tabState.title = title ?: "New Tab"
                }
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
        tabState.session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                url?.let { newUrl ->
                    tabState.url = newUrl
                    if (newUrl != "about:blank") {
                        tabState.isBlankTab = false
                    }
                }
            }
        }
    }

    // Home blank session (always exists, not shown in tabs)
    val homeSession = remember {
        GeckoSession().apply {
            applySettingsToSession(this)
            open(runtime)
            loadUri("about:blank")
        }
    }
    val homeTab = remember {
        TabState(homeSession).apply {
            isBlankTab = true
            title = "Home"
            setupDelegates(this)
        }
    }

    // Tabs list - only real websites, no blank tabs
    val tabs = remember { mutableStateListOf<TabState>() }
    
    var currentTabIndex by remember { mutableIntStateOf(-1) } // -1 means home tab
    var showTabManager by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showScripting by remember { mutableStateOf(false) }

    // Grouping State
    val pinnedDomains = remember { mutableStateListOf<String>() }
    var selectedDomain by remember { mutableStateOf("") }

    // Get current tab (home tab or website tab)
    val currentTab = if (currentTabIndex == -1) homeTab else tabs.getOrNull(currentTabIndex) ?: homeTab

    // Helper to remove a tab properly
    fun removeTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        val tab = tabs[index]
        tab.session.close()
        tabs.removeAt(index)
        if (tabs.isEmpty()) {
            currentTabIndex = -1 // Back to home
        } else {
            currentTabIndex = minOf(index, tabs.lastIndex)
        }
    }

    // ── Navigation / Back Button Handling ─────────────────────────
    BackHandler {
        if (currentTab.isBlankTab) {
            if (tabs.isNotEmpty()) {
                // Go back to last website tab
                currentTabIndex = tabs.lastIndex
            } else {
                activity?.finish()
            }
        } else if (currentTab.url == "about:blank") {
            // Shouldn't happen now, but handle just in case
            val index = tabs.indexOf(currentTab)
            if (index >= 0) removeTab(index)
        } else {
            currentTab.session.goBack()
        }
    }

    @Composable
    fun GeckoViewBox() {
        AndroidView(
            factory = { ctx -> GeckoView(ctx).apply { setSession(currentTab.session) } },
            update = { geckoView -> geckoView.setSession(currentTab.session) },
            modifier = Modifier.fillMaxSize()
        )
    }

    // ── Dialogs ───────────────────────────────────────────────────
    if (showSettings) {
        SettingsDialog(prefs = prefs, onDismiss = { showSettings = false }, onSettingsApplied = { 
            applySettingsToSession(homeSession)
            tabs.forEach { applySettingsToSession(it.session) }
        })
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

    // ── Long-Press Context Menu (Centered) ────────────────────────
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
                    tabs.add(TabState(s).also { 
                        setupDelegates(it)
                        it.isBlankTab = false
                    })
                    currentTabIndex = tabs.lastIndex
                    showContextMenu = false
                }
                ContextMenuItem("Open in Background") {
                    val s = GeckoSession().apply { applySettingsToSession(this); open(runtime); loadUri(contextMenuUri!!) }
                    tabs.add(TabState(s).also { 
                        setupDelegates(it)
                        it.isBlankTab = false
                    })
                    showContextMenu = false
                }
                ContextMenuItem("Copy link") {
                    clipboardManager.setText(AnnotatedString(contextMenuUri!!))
                    showContextMenu = false
                }
            }
        }
    }

    // ── Tab Manager Full Screen ───────────────────────────────────
    if (showTabManager) {
        // Only group non-blank tabs
        val domainGroups = tabs.groupBy { getDomainName(it.url) }
            .filter { it.key.isNotBlank() }
        
        val sortedDomains = domainGroups.keys.sortedWith(
            compareByDescending<String> { pinnedDomains.contains(it) }
            .thenByDescending { domain -> domainGroups[domain]?.maxOfOrNull { it.lastUpdated } ?: 0L }
        )

        // Auto-select current tab's domain when opening
        LaunchedEffect(Unit) {
            selectedDomain = if (currentTab.isBlankTab) "" else getDomainName(currentTab.url)
        }

        Popup(
            alignment = Alignment.TopStart,
            onDismissRequest = { showTabManager = false },
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .background(Color(0xFF1E1E1E)),
                color = Color(0xFF1E1E1E)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { showTabManager = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                        Text("Tabs", style = TextStyle(color = Color.White, fontSize = 18.sp))
                        if (tabs.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "(${tabs.size})",
                                color = Color.Gray,
                                fontSize = 14.sp
                            )
                        }
                    }

                    // Horizontal Group Bar
                    if (sortedDomains.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            items(sortedDomains) { domain ->
                                val isSelected = domain == selectedDomain
                                val isPinned = pinnedDomains.contains(domain)
                                val tabCount = domainGroups[domain]?.size ?: 0

                                Surface(
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .clickable { selectedDomain = domain }
                                        .border(
                                            if (isSelected) 1.dp else 0.dp,
                                            Color.White,
                                            RectangleShape
                                        ),
                                    color = if (isSelected) Color(0xFF2A2A2A) else Color.Transparent
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (isPinned) Text("📌 ", fontSize = 12.sp)
                                        Text(domain, color = Color.White, fontSize = 14.sp)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .background(Color.DarkGray)
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                tabCount.toString(),
                                                color = Color.White,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Divider(
                            color = Color.DarkGray,
                            thickness = 1.dp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    // Tab List
                    val tabsToShow = if (selectedDomain.isBlank()) {
                        tabs.toList()
                    } else {
                        domainGroups[selectedDomain] ?: emptyList()
                    }
                    
                    val listState = rememberLazyListState()

                    // Auto-locate current tab in list
                    LaunchedEffect(selectedDomain) {
                        val index = tabsToShow.indexOf(currentTab)
                        if (index >= 0) {
                            listState.scrollToItem(index)
                        }
                    }

                    if (tabs.isEmpty()) {
                        // Show empty state
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "No open tabs",
                                    color = Color.Gray,
                                    fontSize = 16.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Tap 'New Tab' to start browsing",
                                    color = Color.Gray.copy(alpha = 0.7f),
                                    fontSize = 14.sp
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            if (tabsToShow.isEmpty()) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(32.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "No tabs in this group",
                                            color = Color.Gray,
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                            } else {
                                items(tabsToShow) { tab ->
                                    val isCurrent = tab == currentTab
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
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = tab.title,
                                                    color = if (isCurrent) Color.White else Color.Gray,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = tab.url,
                                                    color = Color.Gray.copy(alpha = 0.7f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    fontSize = 12.sp
                                                )
                                            }
                                            IconButton(onClick = {
                                                val index = tabs.indexOf(tab)
                                                removeTab(index)
                                                selectedDomain = if (currentTabIndex == -1) "" else getDomainName(tabs[currentTabIndex].url)
                                            }) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = "Close tab",
                                                    tint = Color.White
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Bottom Actions
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .navigationBarsPadding()
                    ) {
                        OutlinedButton(
                            onClick = {
                                // Create new blank tab
                                val session = GeckoSession().apply {
                                    applySettingsToSession(this)
                                    open(runtime)
                                    loadUri("about:blank")
                                }
                                val newTab = TabState(session).apply {
                                    setupDelegates(this)
                                    isBlankTab = true
                                }
                                tabs.add(newTab)
                                currentTabIndex = tabs.lastIndex
                                showTabManager = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RectangleShape,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = BorderStroke(1.dp, Color.White)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("New Tab", color = Color.White)
                        }

                        if (selectedDomain.isNotBlank() && tabs.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(modifier = Modifier.fillMaxWidth()) {
                                val isPinned = pinnedDomains.contains(selectedDomain)
                                OutlinedButton(
                                    onClick = {
                                        if (isPinned) {
                                            pinnedDomains.remove(selectedDomain)
                                        } else {
                                            pinnedDomains.add(selectedDomain)
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RectangleShape,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                    border = BorderStroke(1.dp, Color.White)
                                ) {
                                    Text(if (isPinned) "Unpin Group" else "Pin Group")
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                OutlinedButton(
                                    onClick = {
                                        val tabsToRemove = domainGroups[selectedDomain] ?: emptyList()
                                        tabsToRemove.forEach { it.session.close() }
                                        tabs.removeAll(tabsToRemove)
                                        
                                        if (tabs.isEmpty()) {
                                            currentTabIndex = -1 // Back to home
                                        } else {
                                            currentTabIndex = minOf(currentTabIndex, tabs.lastIndex)
                                        }
                                        selectedDomain = if (currentTabIndex == -1) "" else getDomainName(tabs[currentTabIndex].url)
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RectangleShape,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                    border = BorderStroke(1.dp, Color.White)
                                ) {
                                    Text("Delete Group")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // URL bar state
    var urlInput by remember { mutableStateOf(TextFieldValue(currentTab.url)) }
    var isUrlFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // Update URL input when tab changes or URL updates
    LaunchedEffect(currentTab, currentTab.url) {
        if (!isUrlFocused) {
            urlInput = TextFieldValue(currentTab.url)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, end = 4.dp),
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
                    placeholder = { 
                        Text(
                            if (currentTab.isBlankTab) "Search or enter URL" else currentTab.url,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp)
                        .background(Color(0xFF1E1E1E))
                        .focusRequester(focusRequester)
                        .onFocusChanged { focusState ->
                            isUrlFocused = focusState.isFocused
                        }
                        .drawBehind {
                            if (isLoading) {
                                val fraction = currentTab.progress / 100f
                                drawRect(
                                    color = Color.White,
                                    size = size.copy(width = size.width * fraction)
                                )
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
                            val input = urlInput.text
                            if (input.isNotBlank()) {
                                val uri = if (input.contains("://")) input else "https://$input"
                                if (currentTab.isBlankTab) {
                                    // Convert blank tab to website tab
                                    currentTab.session.loadUri(uri)
                                } else {
                                    currentTab.session.loadUri(uri)
                                }
                            }
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color.White,
                        cursorColor = if (isLoading) Color.Gray else Color.White
                    ),
                    trailingIcon = {
                        if (isLoading) {
                            // Show X button to cancel loading
                            IconButton(onClick = {
                                currentTab.session.stop()
                            }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Stop loading",
                                    tint = Color.White
                                )
                            }
                        } else {
                            // Show Select All button
                            IconButton(onClick = {
                                urlInput = urlInput.copy(
                                    selection = TextRange(0, urlInput.text.length)
                                )
                                focusRequester.requestFocus()
                            }) {
                                Icon(
                                    Icons.Default.SelectAll,
                                    contentDescription = "Select all",
                                    tint = Color.White
                                )
                            }
                        }
                    }
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
                        DropdownMenuItem(
                            text = { Text("Scripting", color = Color.White) },
                            onClick = { showMenu = false; showScripting = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Settings", color = Color.White) },
                            onClick = { showMenu = false; showSettings = true }
                        )
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

// ── Scripting & Settings Dialogs ──────────────────────────────────
@Composable
fun ScriptingDialog(initialScript: String, onDismiss: () -> Unit, onSaveAndRun: (String) -> Unit) {
    var script by remember { mutableStateOf(initialScript) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Inject JavaScript", color = Color.White, fontSize = 20.sp) },
        text = {
            Column {
                Text("Write or paste JS below. It will execute immediately on the current tab.", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(
                    value = script, onValueChange = { script = it }, modifier = Modifier.fillMaxWidth().height(200.dp),
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Color.White, cursorColor = Color.White, focusedContainerColor = Color(0xFF1E1E1E), unfocusedContainerColor = Color(0xFF1E1E1E)),
                    shape = RectangleShape
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSaveAndRun(script) }) { Text("Run", color = Color.White) } }, 
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = Color.White) } },
        containerColor = Color(0xFF1E1E1E), titleContentColor = Color.White, textContentColor = Color.White, shape = RectangleShape, tonalElevation = 0.dp
    )
}

@Composable
fun SettingsDialog(prefs: SharedPreferences, onDismiss: () -> Unit, onSettingsApplied: () -> Unit) {
    var jsEnabled by remember { mutableStateOf(prefs.getBoolean("javascript.enabled", true)) }
    var trackingProtection by remember { mutableStateOf(prefs.getBoolean("privacy.trackingprotection.enabled", false)) }
    var cookieBehavior by remember { mutableIntStateOf(prefs.getInt("network.cookie.cookieBehavior", 0)) }
    var autoplayMedia by remember { mutableStateOf(prefs.getBoolean("media.autoplay.enabled", true)) }

    val applyChanges = {
        prefs.edit().putBoolean("javascript.enabled", jsEnabled).putBoolean("privacy.trackingprotection.enabled", trackingProtection).putInt("network.cookie.cookieBehavior", cookieBehavior).putBoolean("media.autoplay.enabled", autoplayMedia).apply()
        onSettingsApplied(); onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Settings", color = Color.White, fontSize = 20.sp) },
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
        containerColor = Color(0xFF1E1E1E), titleContentColor = Color.White, textContentColor = Color.White, shape = RectangleShape, tonalElevation = 0.dp
    )
}

@Composable
fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF444444), uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF444444)))
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
            value = selectedText, onValueChange = {}, readOnly = true, trailingIcon = { Icon(Icons.Default.Close, contentDescription = null, tint = Color.White) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), textStyle = TextStyle(color = Color.White, fontSize = 14.sp), shape = RectangleShape, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Color.White, focusedContainerColor = Color(0xFF1E1E1E), unfocusedContainerColor = Color(0xFF1E1E1E)), enabled = true
        )
        Box(modifier = Modifier.matchParentSize().clickable { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.border(1.dp, Color.White, RectangleShape), containerColor = Color(0xFF1E1E1E), shape = RectangleShape) {
            options.forEach { (label, value) -> DropdownMenuItem(text = { Text(label, color = Color.White) }, onClick = { onChange(value); expanded = false }) }
        }
    }
}
