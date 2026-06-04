//PART 0 START
// GREY BROWSER — CORE DIRECTIVE
// This browser uses Mozilla GeckoView as its rendering engine.
// All browsing logic must be built on GeckoView APIs.
// Never use Android WebView. Never suggest WebView-based solutions.

// COLORS
// Warm Grey   #1A1817  — main background
// Dark Grey   #292625  — surfaces
// White       #FFFFFF  — text

// SHAPES
// No round corners. All UI elements use sharp corners.

// FULL-SCREEN OVERLAY PATTERN
// All secondary screens use Popup + Surface + Column structure.
// PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false)

// BACKUP SYSTEM
// Single JSON file on external storage. Import on start, export on state change.
// Atomic write: temp file then rename. Handles all data types added in future parts.
//PART 0 END

//PART 1 START
package com.grey.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.parseColor("#1A1817")
        window.navigationBarColor = android.graphics.Color.parseColor("#1A1817")
        setContent { GreyBrowser() }
    }
}
//PART 1 END

//PART 2 START
class TabState {
    var session by mutableStateOf<GeckoSession?>(null)
    var title by mutableStateOf("New Tab")
    var url by mutableStateOf("")
    var isBlank by mutableStateOf(true)
    var isDiscarded by mutableStateOf(false)
    var lastUpdated by mutableLongStateOf(System.currentTimeMillis())
    var canGoBack by mutableStateOf(false)
    var progress by mutableIntStateOf(100)
}

data class HistoryItem(val url: String, val title: String, val timestamp: Long = System.currentTimeMillis())

const val MAX_WARM_TABS = 15
const val MAX_HISTORY_ITEMS = 250

fun stripHttps(url: String): String {
    return url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
}

fun getDomainName(url: String): String {
    if (url.isBlank()) return ""
    return try {
        val host = android.net.Uri.parse(url).host?.removePrefix("www.") ?: return ""
        host
    } catch (e: Exception) { "" }
}

@Composable
fun GreyBrowser() {
    val context = LocalContext.current
    val runtime = remember {
        val settings = GeckoRuntimeSettings.Builder()
            .aboutConfigEnabled(true)
            .build()
        GeckoRuntime.create(context, settings)
    }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    val tabs = remember { mutableStateListOf<TabState>() }
    var currentTabIndex by remember { mutableIntStateOf(-1) }
    var urlInput by remember { mutableStateOf("") }
    var isUrlFocused by remember { mutableStateOf(false) }
    var showTabManager by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<HistoryItem>() }
    var lastActiveUrl by remember { mutableStateOf("") }
    val filterRules = remember { mutableStateListOf<String>() }
    val faviconBitmaps = remember { mutableStateMapOf<String, Bitmap?>() }
    val faviconLoading = remember { mutableStateMapOf<String, Boolean>() }
    val tabFavicons = remember { mutableStateMapOf<String, Bitmap?>() }
    val tabFaviconLoading = remember { mutableStateMapOf<String, Boolean>() }
    var backupLoaded by remember { mutableStateOf(false) }

    val currentTab = if (currentTabIndex >= 0) tabs.getOrNull(currentTabIndex) else null
    val isLoading = currentTab != null && currentTab.progress in 1..99

    LaunchedEffect(Unit) {
        var permissionRequested = false
        while (!backupLoaded) {
            val hasPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                android.os.Environment.isExternalStorageManager()
            } else { true }

            if (!hasPermission) {
                if (!permissionRequested) {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                    permissionRequested = true
                }
                delay(500)
                continue
            }

            withContext(Dispatchers.IO) {
                val rules = loadFilterRules()
                val data = importBackup()
                withContext(Dispatchers.Main) {
                    filterRules.addAll(rules)
                    history.addAll(data.history)
                    lastActiveUrl = data.lastActiveUrl
                    for ((url, title) in data.tabs) {
                        tabs.add(TabState().apply {
                            this.url = url
                            this.title = title
                            this.isBlank = false
                            this.isDiscarded = true
                        })
                    }
                }
            }
            backupLoaded = true
        }
    }

    LaunchedEffect(backupLoaded, history.toList(), tabs.map { "${it.url}|${it.title}" }.joinToString(), lastActiveUrl) {
        if (!backupLoaded) return@LaunchedEffect
        scope.launch(Dispatchers.IO) {
            exportBackup(tabs.toList(), history.toList(), lastActiveUrl)
        }
    }

    fun loadFavicon(domain: String) {
        if (domain.isBlank()) return
        if (faviconBitmaps.containsKey(domain) || faviconLoading[domain] == true) return
        faviconLoading[domain] = true
        scope.launch {
            val cached = FaviconMemoryCache.get(domain)
            if (cached != null) {
                faviconBitmaps[domain] = cached
                faviconLoading[domain] = false
                return@launch
            }
            val bitmap = FaviconCache.getFaviconBitmap(context, domain)
                ?: FaviconCache.downloadAndCacheFavicon(context, domain)
            if (bitmap != null) FaviconMemoryCache.put(domain, bitmap)
            faviconBitmaps[domain] = bitmap
            faviconLoading[domain] = false
        }
    }

    fun loadTabFavicon(domain: String) {
        if (domain.isBlank()) return
        if (tabFavicons.containsKey(domain) || tabFaviconLoading[domain] == true) return
        tabFaviconLoading[domain] = true
        scope.launch {
            val cached = FaviconMemoryCache.get(domain)
            if (cached != null) {
                tabFavicons[domain] = cached
                tabFaviconLoading[domain] = false
                return@launch
            }
            val bitmap = FaviconCache.getFaviconBitmap(context, domain)
                ?: FaviconCache.downloadAndCacheFavicon(context, domain)
            if (bitmap != null) FaviconMemoryCache.put(domain, bitmap)
            tabFavicons[domain] = bitmap
            tabFaviconLoading[domain] = false
        }
    }

    fun resolveUrl(input: String): String {
        if (input.isBlank()) return input
        return if (input.contains("://") || (input.contains(".") && !input.contains(" "))) {
            if (input.contains("://")) input else "https://$input"
        } else {
            "https://www.google.com/search?q=${input.replace(" ", "+")}"
        }
    }

    fun isUrlBlocked(url: String): Boolean {
        if (filterRules.isEmpty()) return false
        val host = try {
            android.net.Uri.parse(url).host?.lowercase() ?: return false
        } catch (e: Exception) { return false }
        return filterRules.any { rule ->
            host == rule || host.endsWith(".$rule") || url.lowercase().contains(rule)
        }
    }

    fun addToHistory(url: String, title: String) {
        if (url.isBlank() || url == "about:blank") return
        val baseUrl = url.substringBefore("#")
        history.removeAll { it.url.substringBefore("#") == baseUrl }
        history.add(HistoryItem(url = url, title = title.ifBlank { url }))
        if (history.size > MAX_HISTORY_ITEMS) {
            history.removeAt(0)
        }
    }

    fun setupDelegates(tab: TabState) {
        val s = tab.session ?: return
        var historySaved = false

        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession, url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                url?.let { newUrl ->
                    tab.url = newUrl
                    tab.isBlank = false
                    if (tabs.indexOf(tab) == currentTabIndex) {
                        urlInput = if (isUrlFocused) newUrl else stripHttps(newUrl)
                        lastActiveUrl = newUrl
                    }
                }
            }
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                tab.canGoBack = canGoBack
            }
        }

        s.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                val newTitle = title ?: tab.url
                tab.title = newTitle
                if (!historySaved && newTitle != "New Tab" && newTitle != tab.url
                    && tab.url.isNotBlank() && tab.url != "about:blank") {
                    historySaved = true
                    addToHistory(tab.url, newTitle)
                }
            }
        }

        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                tab.url = url
                tab.isBlank = false
                tab.lastUpdated = System.currentTimeMillis()
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                tab.progress = 100
                tab.lastUpdated = System.currentTimeMillis()
                if (tabs.indexOf(tab) == currentTabIndex) {
                    urlInput = if (isUrlFocused) tab.url else stripHttps(tab.url)
                }
                if (success && !historySaved && tab.title != "New Tab"
                    && tab.url.isNotBlank() && tab.url != "about:blank") {
                    historySaved = true
                    addToHistory(tab.url, tab.title)
                }
            }
            override fun onProgressChange(session: GeckoSession, progress: Int) {
                tab.progress = progress
            }
        }
    }

    fun ensureSession(tab: TabState): GeckoSession {
        if (tab.session == null) {
            val s = GeckoSession()
            s.open(runtime)
            tab.session = s
            tab.isDiscarded = false
            setupDelegates(tab)
        }
        return tab.session!!
    }

    fun manageWarmTabs(activeIndex: Int) {
        tabs.forEachIndexed { i, ts ->
            if (i == activeIndex && ts.session == null && ts.isDiscarded) {
                val s = GeckoSession()
                s.open(runtime)
                ts.session = s
                ts.isDiscarded = false
                s.loadUri(ts.url)
                setupDelegates(ts)
                ts.lastUpdated = System.currentTimeMillis()
            }
        }
        tabs.forEachIndexed { i, ts -> ts.session?.setActive(i == activeIndex) }
        val warmTabs = tabs.filter {
            val idx = tabs.indexOf(it)
            !it.isDiscarded && !it.isBlank && it.session != null && idx != activeIndex
        }
        if (warmTabs.size > MAX_WARM_TABS) {
            val toMakeCold = warmTabs.sortedBy { it.lastUpdated }.take(warmTabs.size - MAX_WARM_TABS)
            for (tab in toMakeCold) {
                tab.session?.setActive(false)
                tab.session?.close()
                tab.session = null
                tab.isDiscarded = true
            }
        }
    }

    fun loadUrl(url: String) {
        val resolved = resolveUrl(url)
        if (isUrlBlocked(resolved)) return
        val existingIndex = tabs.indexOfFirst { it.url == resolved && !it.isBlank }
        if (existingIndex >= 0 && existingIndex != currentTabIndex) {
            tabs[existingIndex].session?.close()
            tabs.removeAt(existingIndex)
            if (currentTabIndex > existingIndex) currentTabIndex--
        }
        urlInput = resolved
        if (currentTabIndex == -1) {
            val newTab = TabState()
            tabs.add(newTab)
            currentTabIndex = tabs.lastIndex
        }
        val tab = tabs[currentTabIndex]
        val s = ensureSession(tab)
        s.loadUri(resolved)
        tab.url = resolved
        tab.isBlank = false
        lastActiveUrl = resolved
        focusManager.clearFocus()
        manageWarmTabs(currentTabIndex)
    }

    fun switchToTab(index: Int) {
        currentTabIndex = index
        val tab = tabs[index]
        urlInput = if (tab.isBlank) "" else stripHttps(tab.url)
        showTabManager = false
        manageWarmTabs(index)
    }

    fun closeTab(index: Int) {
        tabs[index].session?.close()
        tabs.removeAt(index)
        if (tabs.isEmpty()) {
            currentTabIndex = -1
            urlInput = ""
            lastActiveUrl = ""
        } else if (currentTabIndex == index) {
            currentTabIndex = if (index >= tabs.size) tabs.lastIndex else index
            val tab = tabs[currentTabIndex]
            urlInput = if (!tab.isBlank) stripHttps(tab.url) else ""
            lastActiveUrl = tab.url
        } else if (currentTabIndex > index) {
            currentTabIndex--
        }
        manageWarmTabs(currentTabIndex)
    }

    fun newTab() {
        currentTabIndex = -1
        urlInput = ""
        showTabManager = false
    }

    LaunchedEffect(currentTabIndex) {
        if (currentTabIndex >= 0) {
            val tab = tabs[currentTabIndex]
            urlInput = if (tab.isBlank) "" else stripHttps(tab.url)
            lastActiveUrl = tab.url
            manageWarmTabs(currentTabIndex)
        }
    }

    BackHandler {
        if (currentTabIndex >= 0) {
            val tab = tabs[currentTabIndex]
            if (tab.canGoBack) {
                tab.session?.goBack()
            } else {
                currentTabIndex = -1
                urlInput = ""
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF1A1817))) {
        Row(
            Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { showTabManager = true }) {
                Icon(Icons.Default.Tab, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }

            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .background(Color(0xFF292625))
            ) {
                if (isLoading) {
                    androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                        drawRect(
                            color = Color.White.copy(alpha = 0.15f),
                            size = androidx.compose.ui.geometry.Size(
                                size.width * currentTab!!.progress / 100f,
                                size.height
                            )
                        )
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 14.dp)
                ) {
                    if (urlInput.isEmpty() && !isUrlFocused) {
                        Text("Search or enter URL", color = Color.White.copy(alpha = 0.5f), fontSize = 16.sp)
                    }
                    BasicTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = if (isLoading) Color.Gray else Color.White,
                            fontSize = 16.sp
                        ),
                        cursorBrush = SolidColor(if (isLoading) Color.Gray else Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            focusManager.clearFocus()
                            if (urlInput.isNotBlank()) loadUrl(urlInput)
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focused ->
                                isUrlFocused = focused.isFocused
                                if (focused.isFocused && currentTabIndex >= 0) {
                                    urlInput = tabs[currentTabIndex].url
                                }
                                if (!focused.isFocused && currentTabIndex >= 0) {
                                    urlInput = stripHttps(tabs[currentTabIndex].url)
                                }
                            }
                    )
                }
            }

            IconButton(onClick = { newTab() }) {
                Icon(Icons.Default.Add, "New Tab", tint = Color.White)
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, "Menu", tint = Color.White)
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    containerColor = Color(0xFF292625)
                ) {
                    DropdownMenuItem(
                        text = { Text("History", color = Color.White) },
                        onClick = { showMenu = false; showHistory = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Filters", color = Color.White) },
                        onClick = { showMenu = false; showFilters = true }
                    )
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.2f)))

        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF1A1817))) {
            if (currentTab != null && !currentTab.isBlank && currentTab.session != null) {
                val activeSession = currentTab.session!!
                AndroidView(
                    factory = { ctx -> GeckoView(ctx) },
                    update = { gv -> gv.setSession(activeSession) },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable { focusManager.clearFocus() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("GREY", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // ── Tab Manager ──────────────────────────────────
    if (showTabManager) {
        Popup(
            alignment = Alignment.TopStart,
            onDismissRequest = { showTabManager = false },
            properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false)
        ) {
            Surface(
                Modifier.fillMaxSize().statusBarsPadding().background(Color(0xFF1A1817)),
                color = Color(0xFF1A1817)
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { showTabManager = false }) {
                            Icon(Icons.Default.Close, "Close", tint = Color.White)
                        }
                        Text("Tabs", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text("(${tabs.size})", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { newTab() }) {
                            Icon(Icons.Default.Add, "New Tab", tint = Color.White)
                        }
                    }

                    if (tabs.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No open tabs", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            items(tabs.size) { index ->
                                val t = tabs[index]
                                val domain = getDomainName(t.url)
                                LaunchedEffect(t.url) { loadTabFavicon(domain) }
                                val fav = tabFavicons[domain]

                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .background(Color(0xFF292625))
                                        .clickable { switchToTab(index) }
                                        .padding(12.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (fav != null) {
                                            Image(
                                                fav.asImageBitmap(), domain,
                                                Modifier.size(32.dp).clip(CircleShape),
                                                contentScale = ContentScale.Fit
                                            )
                                        } else {
                                            Box(
                                                Modifier.size(32.dp).clip(CircleShape).background(Color.DarkGray),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    domain.take(1).uppercase(),
                                                    color = Color.White,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                t.title,
                                                color = Color.White,
                                                fontSize = 14.sp,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (!t.isBlank) {
                                                Text(
                                                    domain.ifBlank { "..." },
                                                    color = Color.White.copy(alpha = 0.5f),
                                                    fontSize = 11.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                        IconButton(onClick = { closeTab(index) }) {
                                            Icon(Icons.Default.Close, "Close", tint = Color.White, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── History ──────────────────────────────────────
    if (showHistory) {
        Popup(
            alignment = Alignment.TopStart,
            onDismissRequest = { showHistory = false },
            properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false)
        ) {
            Surface(
                Modifier.fillMaxSize().statusBarsPadding().background(Color(0xFF1A1817)),
                color = Color(0xFF1A1817)
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { showHistory = false }) {
                            Icon(Icons.Default.Close, "Close", tint = Color.White)
                        }
                        Text("History", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text("(${history.size})", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
                    }

                    if (history.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No history", color = Color.White.copy(alpha = 0.5f), fontSize = 16.sp)
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            items(history.reversed()) { item ->
                                val domain = getDomainName(item.url)
                                LaunchedEffect(item.url) { loadFavicon(domain) }
                                val fav = faviconBitmaps[domain]

                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .background(Color(0xFF292625))
                                        .clickable {
                                            loadUrl(item.url)
                                            showHistory = false
                                        }
                                        .padding(12.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (fav != null) {
                                            Image(
                                                fav.asImageBitmap(), domain,
                                                Modifier.size(20.dp).clip(CircleShape),
                                                contentScale = ContentScale.Fit
                                            )
                                        } else {
                                            Box(
                                                Modifier.size(20.dp).clip(CircleShape).background(Color.DarkGray),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    domain.take(1).uppercase(),
                                                    color = Color.White,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                item.title,
                                                color = Color.White,
                                                fontSize = 14.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                stripHttps(item.url),
                                                color = Color.White.copy(alpha = 0.5f),
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Filters ──────────────────────────────────────
    if (showFilters) {
        FiltersUI(
            filterRules = filterRules,
            onReload = {
                scope.launch(Dispatchers.IO) {
                    filterRules.clear()
                    filterRules.addAll(loadFilterRules())
                }
            },
            onDismiss = { showFilters = false }
        )
    }
}
//PART 2 END

//PART 3 START
data class BackupData(
    val lastActiveUrl: String,
    val tabs: List<Pair<String, String>>,
    val history: List<HistoryItem>
)

private val BACKUP_DIR = File(Environment.getExternalStorageDirectory(), "Grey")
private val BACKUP_FILE = File(BACKUP_DIR, "Grey-backup.json")

fun importBackup(): BackupData {
    android.util.Log.d("GreyBrowser", "importBackup: checking ${BACKUP_FILE.absolutePath}")
    if (!BACKUP_FILE.exists()) {
        android.util.Log.d("GreyBrowser", "importBackup: file not found")
        return BackupData("", emptyList(), emptyList())
    }
    return try {
        val json = BACKUP_FILE.readText()
        android.util.Log.d("GreyBrowser", "importBackup: read ${json.length} chars")
        val root = JSONObject(json)
        val lastActiveUrl = root.optString("lastActiveUrl", "")
        val tabs = mutableListOf<Pair<String, String>>()
        val tabsArr = root.optJSONArray("tabs")
        if (tabsArr != null) {
            for (i in 0 until tabsArr.length()) {
                val t = tabsArr.getJSONObject(i)
                tabs.add(Pair(t.getString("url"), t.optString("title", "")))
            }
        }
        val history = mutableListOf<HistoryItem>()
        val histArr = root.optJSONArray("history")
        if (histArr != null) {
            for (i in 0 until histArr.length()) {
                val h = histArr.getJSONObject(i)
                history.add(HistoryItem(
                    url = h.getString("url"),
                    title = h.optString("title", ""),
                    timestamp = h.optLong("timestamp", System.currentTimeMillis())
                ))
            }
        }
        android.util.Log.d("GreyBrowser", "importBackup: ${tabs.size} tabs, ${history.size} history items")
        BackupData(lastActiveUrl, tabs, history)
    } catch (e: Exception) {
        android.util.Log.e("GreyBrowser", "importBackup failed", e)
        BackupData("", emptyList(), emptyList())
    }
}

fun exportBackup(
    tabs: List<TabState>,
    history: List<HistoryItem>,
    lastActiveUrl: String
) {
    try {
        if (!BACKUP_DIR.exists()) {
            val created = BACKUP_DIR.mkdirs()
            android.util.Log.d("GreyBrowser", "exportBackup: mkdirs result=$created, path=${BACKUP_DIR.absolutePath}")
        }
        val root = JSONObject()
        root.put("lastActiveUrl", lastActiveUrl)

        val tabsArr = JSONArray()
        for (tab in tabs) {
            if (!tab.isBlank) {
                val obj = JSONObject()
                obj.put("url", tab.url)
                obj.put("title", tab.title)
                tabsArr.put(obj)
            }
        }
        root.put("tabs", tabsArr)

        val histArr = JSONArray()
        for (h in history) {
            val obj = JSONObject()
            obj.put("url", h.url)
            obj.put("title", h.title)
            obj.put("timestamp", h.timestamp)
            histArr.put(obj)
        }
        root.put("history", histArr)

        val tempFile = File(BACKUP_DIR, "Grey-backup.tmp")
        tempFile.writeText(root.toString(2))
        val renamed = tempFile.renameTo(BACKUP_FILE)
        android.util.Log.d("GreyBrowser", "exportBackup: wrote ${tabsArr.length()} tabs, ${histArr.length()} history, rename=$renamed")
    } catch (e: Exception) {
        android.util.Log.e("GreyBrowser", "exportBackup failed", e)
    }
}
//PART 3 END

//PART 4 START
data class FilterFile(
    val name: String,
    val ruleCount: Int,
    val enabled: Boolean = true
)

private val FILTERS_DIR = File(Environment.getExternalStorageDirectory(), "Grey/Filters")

fun loadFilterRules(): List<String> {
    val rules = mutableListOf<String>()
    try {
        if (!FILTERS_DIR.exists()) {
            FILTERS_DIR.mkdirs()
            return rules
        }
        val txtFiles = FILTERS_DIR.listFiles { file -> file.extension == "txt" } ?: return rules
        for (file in txtFiles) {
            android.util.Log.d("GreyBrowser", "Loading filter: ${file.name}")
            file.useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("!") || trimmed.startsWith("[")) continue
                    if (trimmed.startsWith("||") && trimmed.endsWith("^")) {
                        val domain = trimmed.removePrefix("||").removeSuffix("^")
                        rules.add(domain)
                    }
                }
            }
        }
        android.util.Log.d("GreyBrowser", "Loaded ${rules.size} filter rules")
    } catch (e: Exception) {
        android.util.Log.e("GreyBrowser", "Failed to load filters", e)
    }
    return rules
}

fun getFilterFiles(): List<FilterFile> {
    val files = mutableListOf<FilterFile>()
    try {
        if (!FILTERS_DIR.exists()) return files
        val txtFiles = FILTERS_DIR.listFiles { file -> file.extension == "txt" } ?: return files
        for (file in txtFiles) {
            var count = 0
            file.useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("!") || trimmed.startsWith("[")) continue
                    if (trimmed.startsWith("||") && trimmed.endsWith("^")) count++
                }
            }
            files.add(FilterFile(name = file.name, ruleCount = count))
        }
    } catch (e: Exception) {
        android.util.Log.e("GreyBrowser", "Failed to list filter files", e)
    }
    return files
}
//PART 4 END

//PART 5 START
@Composable
fun FiltersUI(
    filterRules: List<String>,
    onReload: () -> Unit,
    onDismiss: () -> Unit
) {
    val filterFiles = remember { mutableStateListOf<FilterFile>() }
    val scope = rememberCoroutineScope()

    fun reloadFiles() {
        scope.launch(Dispatchers.IO) {
            val files = getFilterFiles()
            filterFiles.clear()
            filterFiles.addAll(files)
        }
    }

    LaunchedEffect(Unit) {
        reloadFiles()
    }

    Popup(
        alignment = Alignment.TopStart,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false)
    ) {
        Surface(
            Modifier.fillMaxSize().statusBarsPadding().background(Color(0xFF1A1817)),
            color = Color(0xFF1A1817)
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close", tint = Color.White)
                    }
                    Text("Filters", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${filterRules.size} rules",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 14.sp
                    )
                }

                Spacer(Modifier.height(8.dp))

                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .background(Color(0xFF292625))
                        .padding(12.dp)
                ) {
                    Column {
                        Text(
                            "Place .txt files in /Grey/Filters/",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Uses EasyList format (||domain.com^).\nReload after adding new files.",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (filterFiles.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No filter files found", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        items(filterFiles) { file ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .background(Color(0xFF292625))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            file.name,
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "${file.ruleCount} rules",
                                            color = Color.White.copy(alpha = 0.5f),
                                            fontSize = 11.sp
                                        )
                                    }
                                    Text(
                                        if (file.enabled) "ON" else "OFF",
                                        color = if (file.enabled) Color(0xFF4CAF50) else Color.White.copy(alpha = 0.3f),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        onReload()
                        reloadFiles()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .navigationBarsPadding(),
                    shape = RectangleShape,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = BorderStroke(1.dp, Color.White)
                ) {
                    Text("Reload Filters", color = Color.White)
                }
            }
        }
    }
}
//PART 5 END

//PART 6 START
object FaviconMemoryCache {
    private const val MAX_MEMORY_FAVICONS = 100
    private val cache = object : LinkedHashMap<String, Bitmap>(MAX_MEMORY_FAVICONS, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Bitmap>?): Boolean {
            return size > MAX_MEMORY_FAVICONS
        }
    }

    fun get(domain: String): Bitmap? = cache[domain]
    fun put(domain: String, bitmap: Bitmap) { cache[domain] = bitmap }
    fun clear() = cache.clear()
}

object FaviconCache {
    private const val MAX_FAVICONS = 50
    private const val FAVICON_DIR = "favicons"
    private const val META_FILE = "favicon_meta.json"

    data class FaviconMeta(val domain: String, val lastAccessed: Long = System.currentTimeMillis())

    private fun getFaviconDir(context: Context): File {
        val dir = File(context.filesDir, FAVICON_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getMetaFile(context: Context) = File(context.filesDir, META_FILE)

    private fun loadMeta(context: Context): MutableList<FaviconMeta> {
        val file = getMetaFile(context)
        if (!file.exists()) return mutableListOf()
        return try {
            val json = file.readText()
            val array = JSONArray(json)
            val list = mutableListOf<FaviconMeta>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(FaviconMeta(obj.getString("domain"), obj.getLong("lastAccessed")))
            }
            list
        } catch (e: Exception) { mutableListOf() }
    }

    private fun saveMeta(context: Context, meta: List<FaviconMeta>) {
        val array = JSONArray()
        for (item in meta) {
            val obj = JSONObject()
            obj.put("domain", item.domain)
            obj.put("lastAccessed", item.lastAccessed)
            array.put(obj)
        }
        getMetaFile(context).writeText(array.toString())
    }

    fun getFaviconFile(context: Context, domain: String) =
        File(getFaviconDir(context), domain.replace(".", "_").replace("/", "_") + ".png")

    fun getFaviconBitmap(context: Context, domain: String): Bitmap? {
        val file = getFaviconFile(context, domain)
        if (!file.exists()) return null
        val meta = loadMeta(context)
        val existing = meta.find { it.domain == domain }
        if (existing != null) {
            meta.remove(existing)
            meta.add(existing.copy(lastAccessed = System.currentTimeMillis()))
        } else {
            meta.add(FaviconMeta(domain, System.currentTimeMillis()))
        }
        saveMeta(context, meta)
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    private fun tryDownload(urlStr: String): Bitmap? {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.connect()
            if (conn.responseCode == 200) {
                val b = BitmapFactory.decodeStream(conn.inputStream)
                conn.inputStream.close()
                conn.disconnect()
                b
            } else {
                conn.disconnect()
                null
            }
        } catch (e: Exception) { null }
    }

    suspend fun downloadAndCacheFavicon(context: Context, domain: String): Bitmap? = withContext(Dispatchers.IO) {
        val sources = listOf(
            "https://www.google.com/s2/favicons?domain=$domain&sz=64",
            "https://$domain/favicon.ico",
            "https://$domain/favicon.png"
        )
        var bitmap: Bitmap? = null
        for (src in sources) {
            bitmap = tryDownload(src)
            if (bitmap != null) break
        }
        if (bitmap != null) {
            FileOutputStream(getFaviconFile(context, domain)).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            val meta = loadMeta(context)
            meta.removeAll { it.domain == domain }
            meta.add(FaviconMeta(domain, System.currentTimeMillis()))
            if (meta.size > MAX_FAVICONS) {
                val oldest = meta.minByOrNull { it.lastAccessed }
                if (oldest != null) {
                    meta.remove(oldest)
                    getFaviconFile(context, oldest.domain).delete()
                }
            }
            saveMeta(context, meta)
        }
        bitmap
    }
}
//PART 6 END
