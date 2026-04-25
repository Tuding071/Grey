package com.grey.browser

// ═══════════════════════════════════════════════════════════════════
// GECKOVIEW API NOTE FOR AI:
// This browser uses Mozilla GeckoView (org.mozilla.geckoview) as the 
// rendering engine, NOT Android WebView. GeckoView has different APIs:
//
// - GeckoSession (not WebView) for browsing sessions
// - GeckoRuntime for shared browser runtime
// - GeckoView for rendering web content
// - ProgressDelegate for page load progress (0-100)
// - onPageStart is called when page starts loading, gives URL
// - ContentDelegate for title changes, context menus
// - NavigationDelegate for URL/location changes
// - session.loadUri() to load URLs
// - session.goBack() / session.goForward() for navigation
// - session.stop() to cancel loading
// - session.setActive(false) to freeze session completely (no network/JS/rendering)
// - session.setActive(true) to resume session
// - session.settings for browser settings
// - session.close() to destroy session and free memory
// - No "currentUrl" property, use NavigationDelegate.onLocationChange
// - GeckoSession must be opened with runtime before use
// - GeckoSession.open() requires GeckoRuntime
// - suspendMediaWhenInactive = true pauses media when session inactive
//
// API Reference: https://mozilla.github.io/geckoview/javadoc/mozilla-central/
// ═══════════════════════════════════════════════════════════════════

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GreyBrowser()
        }
    }
    
    override fun onPause() {
        super.onPause()
        saveTabs(this)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        saveTabs(this)
    }
}

// ── Favicon Cache Manager ────────────────────────────────────────────
object FaviconCache {
    private const val MAX_FAVICONS = 50
    private const val FAVICON_DIR = "favicons"
    private const val META_FILE = "favicon_meta.json"
    
    data class FaviconMeta(
        val domain: String,
        val lastAccessed: Long = System.currentTimeMillis()
    )
    
    private fun getFaviconDir(context: Context): File {
        val dir = File(context.filesDir, FAVICON_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
    
    private fun getMetaFile(context: Context): File {
        return File(context.filesDir, META_FILE)
    }
    
    private fun loadMeta(context: Context): MutableList<FaviconMeta> {
        val file = getMetaFile(context)
        if (!file.exists()) return mutableListOf()
        return try {
            val json = file.readText()
            val array = JSONArray(json)
            val list = mutableListOf<FaviconMeta>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(FaviconMeta(
                    domain = obj.getString("domain"),
                    lastAccessed = obj.getLong("lastAccessed")
                ))
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
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
    
    fun getFaviconFile(context: Context, domain: String): File {
        val safeName = domain.replace(".", "_").replace("/", "_") + ".png"
        return File(getFaviconDir(context), safeName)
    }
    
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
    
    suspend fun downloadAndCacheFavicon(context: Context, domain: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://$domain/favicon.ico")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.connect()
                
                if (connection.responseCode == 200) {
                    val inputStream = connection.inputStream
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream.close()
                    
                    if (bitmap != null) {
                        val file = getFaviconFile(context, domain)
                        FileOutputStream(file).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        
                        val meta = loadMeta(context)
                        val existing = meta.find { it.domain == domain }
                        if (existing != null) meta.remove(existing)
                        meta.add(FaviconMeta(domain, System.currentTimeMillis()))
                        
                        if (meta.size > MAX_FAVICONS) {
                            val oldest = meta.minByOrNull { it.lastAccessed }
                            if (oldest != null) {
                                meta.remove(oldest)
                                getFaviconFile(context, oldest.domain).delete()
                            }
                        }
                        
                        saveMeta(context, meta)
                        return@withContext bitmap
                    }
                }
                connection.disconnect()
            } catch (e: Exception) { }
            null
        }
    }
}

// ── Tab Persistence ─────────────────────────────────────────────────
private const val PREFS_NAME = "browser_tabs"
private const val KEY_TABS = "saved_tabs"
private const val KEY_PINNED = "pinned_domains"

fun saveTabs(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply()
}

fun saveTabsData(context: Context, tabs: List<TabState>, pinnedDomains: List<String>) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val editor = prefs.edit()
    val tabsArray = JSONArray()
    for (tab in tabs) {
        if (!tab.isBlankTab) {
            val tabObj = JSONObject()
            tabObj.put("url", tab.url)
            tabObj.put("title", tab.title)
            tabsArray.put(tabObj)
        }
    }
    editor.putString(KEY_TABS, tabsArray.toString())
    val pinnedArray = JSONArray()
    for (domain in pinnedDomains) pinnedArray.put(domain)
    editor.putString(KEY_PINNED, pinnedArray.toString())
    editor.apply()
}

fun loadTabsData(context: Context): Pair<List<Pair<String, String>>, List<String>> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val tabsList = mutableListOf<Pair<String, String>>()
    val tabsJson = prefs.getString(KEY_TABS, null)
    if (tabsJson != null) {
        try {
            val tabsArray = JSONArray(tabsJson)
            for (i in 0 until tabsArray.length()) {
                val tabObj = tabsArray.getJSONObject(i)
                tabsList.add(Pair(tabObj.getString("url"), tabObj.optString("title", tabObj.getString("url"))))
            }
        } catch (e: Exception) { }
    }
    val pinnedList = mutableListOf<String>()
    val pinnedJson = prefs.getString(KEY_PINNED, null)
    if (pinnedJson != null) {
        try {
            val pinnedArray = JSONArray(pinnedJson)
            for (i in 0 until pinnedArray.length()) pinnedList.add(pinnedArray.getString(i))
        } catch (e: Exception) { }
    }
    return Pair(tabsList, pinnedList)
}

// ── State Wrapper for Tabs ──────────────────────────────────────────
class TabState {
    var session by mutableStateOf<GeckoSession?>(null)
    var title by mutableStateOf("New Tab")
    var url by mutableStateOf("about:blank")
    var progress by mutableIntStateOf(100)
    var lastUpdated by mutableLongStateOf(System.currentTimeMillis())
    var isBlankTab by mutableStateOf(true)
    var isDiscarded by mutableStateOf(false)
}

fun getDomainName(url: String): String {
    if (url == "about:blank" || url.isBlank()) return ""
    return try {
        val host = Uri.parse(url).host?.removePrefix("www.") ?: return ""
        val parts = host.split(".")
        if (parts.size >= 2) "${parts[parts.size - 2]}.${parts[parts.size - 1]}" else host
    } catch (e: Exception) { "Unknown" }
}

const val MAX_LOADED_TABS = 10

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GreyBrowser() {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val clipboardManager = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val runtime = remember { GeckoRuntime.create(context) }
    val prefs = remember { context.getSharedPreferences("browser_settings", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    val applySettingsToSession = { session: GeckoSession ->
        session.settings.allowJavascript = prefs.getBoolean("javascript.enabled", true)
        session.settings.useTrackingProtection = prefs.getBoolean("privacy.trackingprotection.enabled", false)
        session.settings.suspendMediaWhenInactive = true
    }

    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuUri by remember { mutableStateOf<String?>(null) }

    val setupDelegates = fun(tabState: TabState) {
        val session = tabState.session ?: return
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                tabState.url = url; tabState.progress = 5
                tabState.lastUpdated = System.currentTimeMillis()
                if (url != "about:blank") tabState.isBlankTab = false
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                tabState.progress = 100; tabState.lastUpdated = System.currentTimeMillis()
            }
            override fun onProgressChange(session: GeckoSession, progress: Int) { tabState.progress = progress }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                if (!tabState.isBlankTab) tabState.title = title ?: "New Tab"
            }
            override fun onContextMenu(s: GeckoSession, sx: Int, sy: Int, el: GeckoSession.ContentDelegate.ContextElement) {
                if (el.linkUri != null) { contextMenuUri = el.linkUri; showContextMenu = true }
            }
        }
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(s: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                url?.let { tabState.url = it; if (it != "about:blank") tabState.isBlankTab = false }
            }
        }
    }

    val homeSession = remember { GeckoSession().apply { applySettingsToSession(this); open(runtime); loadUri("about:blank") } }
    val homeTab = remember { TabState().apply { session = homeSession; isBlankTab = true; title = "Home"; setupDelegates(this) } }

    val tabs = remember {
        val (savedTabs, _) = loadTabsData(context)
        mutableStateListOf<TabState>().apply {
            for ((url, title) in savedTabs) {
                add(TabState().apply { this.url = url; this.title = title; isBlankTab = false; isDiscarded = true; session = null })
            }
        }
    }
    
    var currentTabIndex by remember { mutableIntStateOf(-1) }
    var showTabManager by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showScripting by remember { mutableStateOf(false) }

    val pinnedDomains = remember {
        val (_, savedPinned) = loadTabsData(context)
        mutableStateListOf<String>().apply { addAll(savedPinned) }
    }
    var selectedDomain by remember { mutableStateOf("") }

    val faviconBitmaps = remember { mutableStateMapOf<String, Bitmap?>() }
    val faviconLoading = remember { mutableStateMapOf<String, Boolean>() }

    val currentTab = if (currentTabIndex == -1) homeTab else tabs.getOrNull(currentTabIndex) ?: homeTab

    LaunchedEffect(tabs.toList(), pinnedDomains.toList()) { saveTabsData(context, tabs, pinnedDomains) }
    val tabsSnapshot = tabs.map { "${it.url}|${it.title}" }.joinToString()
    LaunchedEffect(tabsSnapshot) { saveTabsData(context, tabs, pinnedDomains) }

    fun enforceTabLimit() {
        val loadedTabs = tabs.filter { !it.isDiscarded && !it.isBlankTab && it.session != null }
        if (loadedTabs.size > MAX_LOADED_TABS) {
            val toDiscard = loadedTabs.filter { tabs.indexOf(it) != currentTabIndex }.minByOrNull { it.lastUpdated }
            toDiscard?.let {
                it.session?.setActive(false); it.session?.close()
                it.session = null; it.isDiscarded = true; it.progress = 100
            }
        }
    }

    fun restoreTab(tab: TabState) {
        if (tab.isDiscarded && tab.session == null) {
            val s = GeckoSession().apply { applySettingsToSession(this); open(runtime); setActive(false); loadUri(tab.url) }
            tab.session = s; tab.isDiscarded = false; setupDelegates(tab)
            tab.lastUpdated = System.currentTimeMillis(); enforceTabLimit()
        }
    }

    fun loadFavicon(domain: String) {
        if (!faviconBitmaps.containsKey(domain) && faviconLoading[domain] != true) {
            faviconLoading[domain] = true
            scope.launch {
                val cached = FaviconCache.getFaviconBitmap(context, domain)
                faviconBitmaps[domain] = cached ?: FaviconCache.downloadAndCacheFavicon(context, domain)
                faviconLoading[domain] = false
            }
        }
    }

    LaunchedEffect(showTabManager, currentTabIndex) {
        if (showTabManager) {
            homeSession.setActive(false); tabs.forEach { it.session?.setActive(false) }
        } else {
            homeSession.setActive(currentTabIndex == -1)
            tabs.forEachIndexed { i, ts ->
                val active = i == currentTabIndex
                if (active && ts.isDiscarded) restoreTab(ts)
                ts.session?.setActive(active)
            }
        }
    }

    fun createBackgroundTab(url: String) {
        val s = GeckoSession().apply { applySettingsToSession(this); open(runtime); loadUri(url); setActive(false) }
        tabs.add(TabState().apply { session = s; this.url = url; isBlankTab = false; isDiscarded = false; lastUpdated = System.currentTimeMillis(); setupDelegates(this) })
        enforceTabLimit()
    }
    
    fun createForegroundTab(url: String) {
        val s = GeckoSession().apply { applySettingsToSession(this); open(runtime); loadUri(url) }
        tabs.add(TabState().apply { session = s; isBlankTab = false; isDiscarded = false; lastUpdated = System.currentTimeMillis(); setupDelegates(this) })
        currentTabIndex = tabs.lastIndex; enforceTabLimit()
    }

    fun removeTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        val tab = tabs[index]; tab.session?.setActive(false); tab.session?.close(); tabs.removeAt(index)
        if (tabs.isEmpty()) currentTabIndex = -1
        else if (currentTabIndex > index) currentTabIndex--
        else if (currentTabIndex == index && tabs.isNotEmpty()) currentTabIndex = minOf(currentTabIndex, tabs.lastIndex)
    }

    BackHandler {
        if (currentTab.isBlankTab) { if (tabs.isNotEmpty()) currentTabIndex = tabs.lastIndex else activity?.finish() }
        else currentTab.session?.goBack()
    }

    @Composable
    fun GeckoViewBox() {
        val session = currentTab.session
        if (session != null) AndroidView(factory = { ctx -> GeckoView(ctx).apply { setSession(session) } }, update = { it.setSession(session) }, modifier = Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize().background(Color(0xFF121212)), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
    }

    if (showSettings) SettingsDialog(prefs, { showSettings = false }) { applySettingsToSession(homeSession); tabs.forEach { it.session?.let { s -> applySettingsToSession(s) } } }
    if (showScripting) ScriptingDialog(prefs.getString("user_script", "alert('Hello from Grey Browser!');") ?: "", { showScripting = false }) { script ->
        prefs.edit().putString("user_script", script).apply()
        currentTab.session?.loadUri("javascript:" + Uri.encode("(function(){\n$script\n})();"))
        showScripting = false
    }

    if (showContextMenu && contextMenuUri != null) {
        Popup(alignment = Alignment.Center, onDismissRequest = { showContextMenu = false }, properties = PopupProperties(focusable = true)) {
            Column(Modifier.border(1.dp, Color.White, RectangleShape).background(Color(0xFF1E1E1E)).width(IntrinsicSize.Max)) {
                ContextMenuItem("New Tab") { createForegroundTab(contextMenuUri!!); showContextMenu = false }
                ContextMenuItem("Open in Background") { createBackgroundTab(contextMenuUri!!); showContextMenu = false }
                ContextMenuItem("Copy link") { clipboardManager.setText(AnnotatedString(contextMenuUri!!)); showContextMenu = false }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── TAB MANAGER - FULL SCREEN ─────────────────────────────────
    // ═══════════════════════════════════════════════════════════════
    if (showTabManager) {
        val domainGroups = tabs.groupBy { getDomainName(it.url) }.filter { it.key.isNotBlank() }
        
        val sortedDomains = domainGroups.keys.sortedWith(
            compareByDescending<String> { pinnedDomains.contains(it) }
            .thenByDescending { d -> domainGroups[d]?.maxOfOrNull { it.lastUpdated } ?: 0L }
        )
        
        val pinnedSortedDomains = sortedDomains.filter { pinnedDomains.contains(it) }
        val unpinnedSortedDomains = sortedDomains.filter { !pinnedDomains.contains(it) }

        LaunchedEffect(Unit) { selectedDomain = if (currentTab.isBlankTab) "" else getDomainName(currentTab.url) }

        Popup(alignment = Alignment.TopStart, onDismissRequest = { showTabManager = false }, properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false)) {
            Surface(Modifier.fillMaxSize().statusBarsPadding().background(Color(0xFF1E1E1E)), color = Color(0xFF1E1E1E)) {
                Column(Modifier.fillMaxSize()) {
                    // Header
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ showTabManager = false }) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                        Text("Tabs", style = TextStyle(color = Color.White, fontSize = 18.sp))
                        if (tabs.isNotEmpty()) { Spacer(Modifier.width(8.dp)); Text("(${tabs.size})", color = Color.Gray, fontSize = 14.sp) }
                    }

                    // ── Pinned Row (Horizontal at top) ──
                    if (pinnedSortedDomains.isNotEmpty()) {
                        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            items(pinnedSortedDomains) { domain ->
                                GroupChip(domain, domain == selectedDomain, true, domainGroups[domain]?.size ?: 0, { selectedDomain = domain }, faviconBitmaps[domain], { loadFavicon(domain) })
                            }
                        }
                        Divider(Color.White.copy(alpha = 0.3f), 2.dp, Modifier.padding(horizontal = 8.dp))
                    }

                    // ── Main Content: Left Groups | Right Tabs ──
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        // Left: Vertical Group Bar (unpinned)
                        if (unpinnedSortedDomains.isNotEmpty()) {
                            val groupListState = rememberLazyListState()
                            
                            LaunchedEffect(unpinnedSortedDomains, selectedDomain) {
                                val idx = unpinnedSortedDomains.indexOf(selectedDomain)
                                if (idx >= 0) groupListState.animateScrollToItem(idx)
                            }
                            
                            LazyColumn(
                                state = groupListState,
                                modifier = Modifier.width(56.dp).fillMaxHeight().padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                items(unpinnedSortedDomains) { domain ->
                                    VerticalGroupChip(domain, domain == selectedDomain, domainGroups[domain]?.size ?: 0, { selectedDomain = domain }, faviconBitmaps[domain], { loadFavicon(domain) })
                                }
                            }
                            
                            Divider(color = Color.DarkGray, modifier = Modifier.fillMaxHeight().width(1.dp))
                        }

                        // Right: Tab List
                        val tabsToShow = if (selectedDomain.isBlank()) tabs.toList() else domainGroups[selectedDomain] ?: emptyList()
                        val tabListState = rememberLazyListState()

                        LaunchedEffect(selectedDomain) {
                            val idx = tabsToShow.indexOf(currentTab)
                            if (idx >= 0) tabListState.scrollToItem(idx)
                        }

                        if (tabs.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("No open tabs", color = Color.Gray, fontSize = 16.sp)
                                    Spacer(Modifier.height(8.dp))
                                    Text("Tap 'New Tab' to start browsing", color = Color.Gray.copy(alpha = 0.7f), fontSize = 14.sp)
                                }
                            }
                        } else {
                            LazyColumn(state = tabListState, modifier = Modifier.weight(1f).fillMaxHeight()) {
                                if (tabsToShow.isEmpty()) {
                                    item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text("No tabs in this group", color = Color.Gray, fontSize = 14.sp) } }
                                } else {
                                    items(tabsToShow) { tab ->
                                        val isCurrent = tab == currentTab
                                        Surface(Modifier.fillMaxWidth().clickable { currentTabIndex = tabs.indexOf(tab); showTabManager = false }, color = if (isCurrent) Color.White else Color.Transparent) {
                                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(tab.title, color = if (isCurrent) Color.Black else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                                    Text(tab.url, color = if (isCurrent) Color.DarkGray else Color.Gray.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
                                                }
                                                IconButton({
                                                    val idx = tabs.indexOf(tab); removeTab(idx)
                                                    selectedDomain = if (currentTabIndex == -1) "" else getDomainName(tabs[currentTabIndex].url)
                                                }) { Icon(Icons.Default.Close, "Close tab", tint = if (isCurrent) Color.Black else Color.White, modifier = Modifier.size(18.dp)) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ── Bottom Actions ──
                    Column(Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding()) {
                        OutlinedButton({
                            currentTabIndex = -1; showTabManager = false
                        }, Modifier.fillMaxWidth(), RectangleShape, ButtonDefaults.outlinedButtonColors(contentColor = Color.White), BorderStroke(1.dp, Color.White)) {
                            Icon(Icons.Default.Add, null, tint = Color.White); Spacer(Modifier.width(8.dp)); Text("New Tab", color = Color.White)
                        }

                        if (selectedDomain.isNotBlank() && tabs.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth()) {
                                val isPinned = pinnedDomains.contains(selectedDomain)
                                OutlinedButton({
                                    if (isPinned) pinnedDomains.remove(selectedDomain) else pinnedDomains.add(selectedDomain)
                                }, Modifier.weight(1f), RectangleShape, ButtonDefaults.outlinedButtonColors(contentColor = Color.White), BorderStroke(1.dp, Color.White)) {
                                    Text(if (isPinned) "Unpin Group" else "Pin Group", fontSize = 13.sp)
                                }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton({
                                    val toRemove = domainGroups[selectedDomain] ?: emptyList()
                                    toRemove.forEach { it.session?.setActive(false); it.session?.close() }
                                    tabs.removeAll(toRemove)
                                    if (tabs.isEmpty()) currentTabIndex = -1 else currentTabIndex = minOf(currentTabIndex, tabs.lastIndex)
                                    selectedDomain = if (currentTabIndex == -1) "" else getDomainName(tabs[currentTabIndex].url)
                                }, Modifier.weight(1f), RectangleShape, ButtonDefaults.outlinedButtonColors(contentColor = Color.White), BorderStroke(1.dp, Color.White)) {
                                    Text("Delete Group", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── URL Bar ──────────────────────────────────────────────────
    var urlInput by remember { mutableStateOf(TextFieldValue(currentTab.url)) }
    var isUrlFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(currentTab, currentTab.url) { if (!isUrlFocused) urlInput = TextFieldValue(currentTab.url) }

    // ── Main UI ───────────────────────────────────────────────────
    Surface(Modifier.fillMaxSize(), color = Color(0xFF121212)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ showTabManager = true }) { Icon(Icons.Default.Tab, "Open tabs", tint = Color.White) }
                val isLoading = currentTab.progress in 1..99
                OutlinedTextField(
                    value = urlInput, onValueChange = { urlInput = it }, singleLine = true,
                    placeholder = { Text(if (currentTab.isBlankTab) "Search or enter URL" else currentTab.url.take(50), color = Color.White.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp).background(Color(0xFF1E1E1E)).focusRequester(focusRequester).onFocusChanged { isUrlFocused = it.isFocused }
                        .drawBehind { if (isLoading) drawRect(Color.White, size = size.copy(width = size.width * currentTab.progress / 100f)) },
                    textStyle = TextStyle(if (isLoading) Color.Gray else Color.White, 16.sp), shape = RectangleShape,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        val input = urlInput.text
                        if (input.isNotBlank()) {
                            val uri = if (input.contains("://")) input else "https://$input"
                            focusManager.clearFocus()
                            if (currentTab.isBlankTab) createForegroundTab(uri) else currentTab.session?.loadUri(uri)
                        }
                    }),
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedBorderColor = Color.White, unfocusedBorderColor = Color.White, cursorColor = if (isLoading) Color.Gray else Color.White),
                    trailingIcon = {
                        if (isLoading) IconButton({ currentTab.session?.stop() }) { Icon(Icons.Default.Close, "Stop", tint = Color.White) }
                        else IconButton({ urlInput = urlInput.copy(selection = TextRange(0, urlInput.text.length)); focusRequester.requestFocus() }) { Icon(Icons.Default.SelectAll, "Select all", tint = Color.White) }
                    }
                )
                Box {
                    IconButton({ showMenu = true }) { Icon(Icons.Default.MoreVert, "Menu", tint = Color.White) }
                    DropdownMenu(showMenu, { showMenu = false }, Modifier.border(1.dp, Color.White, RectangleShape), containerColor = Color(0xFF1E1E1E), shape = RectangleShape) {
                        DropdownMenuItem({ Text("Scripting", color = Color.White) }, { showMenu = false; showScripting = true })
                        DropdownMenuItem({ Text("Settings", color = Color.White) }, { showMenu = false; showSettings = true })
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { GeckoViewBox() }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// ── GROUP CHIP (Horizontal - for pinned row) ──────────────────────
// ═══════════════════════════════════════════════════════════════════
@Composable
fun GroupChip(domain: String, isSelected: Boolean, isPinned: Boolean, tabCount: Int, selected: () -> Unit, faviconBitmap: Bitmap?, onAppear: () -> Unit) {
    LaunchedEffect(domain) { onAppear() }
    Surface(
        Modifier.padding(end = 8.dp).clickable { selected() }.border(if (isSelected) 1.dp else 0.dp, if (isSelected) Color.White else Color.Transparent, RectangleShape),
        color = if (isSelected) Color.White else Color.Transparent
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isPinned) { Icon(Icons.Default.PushPin, "Pinned", tint = if (isSelected) Color.Black else Color.White, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
            if (faviconBitmap != null) androidx.compose.foundation.Image(faviconBitmap.asImageBitmap(), domain, Modifier.size(20.dp).clip(CircleShape), contentScale = ContentScale.Fit)
            else Box(Modifier.size(20.dp).clip(CircleShape).background(if (isSelected) Color.LightGray else Color.DarkGray), contentAlignment = Alignment.Center) { Text(domain.take(1).uppercase(), if (isSelected) Color.Black else Color.White, 11.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(6.dp))
            Box(Modifier.background(if (isSelected) Color.LightGray else Color.DarkGray).padding(horizontal = 4.dp, vertical = 2.dp)) { Text(tabCount.toString(), if (isSelected) Color.Black else Color.White, 10.sp) }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// ── VERTICAL GROUP CHIP (for left sidebar) ────────────────────────
// ═══════════════════════════════════════════════════════════════════
@Composable
fun VerticalGroupChip(domain: String, isSelected: Boolean, tabCount: Int, selected: () -> Unit, faviconBitmap: Bitmap?, onAppear: () -> Unit) {
    LaunchedEffect(domain) { onAppear() }
    Surface(
        Modifier.padding(vertical = 4.dp).clickable { selected() }.border(if (isSelected) 1.dp else 0.dp, if (isSelected) Color.White else Color.Transparent, RectangleShape),
        color = if (isSelected) Color.White else Color.Transparent
    ) {
        Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (faviconBitmap != null) androidx.compose.foundation.Image(faviconBitmap.asImageBitmap(), domain, Modifier.size(24.dp).clip(CircleShape), contentScale = ContentScale.Fit)
            else Box(Modifier.size(24.dp).clip(CircleShape).background(if (isSelected) Color.LightGray else Color.DarkGray), contentAlignment = Alignment.Center) { Text(domain.take(1).uppercase(), if (isSelected) Color.Black else Color.White, 12.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(2.dp))
            Box(Modifier.background(if (isSelected) Color.LightGray else Color.DarkGray).padding(horizontal = 4.dp, vertical = 1.dp)) { Text(tabCount.toString(), if (isSelected) Color.Black else Color.White, 9.sp) }
        }
    }
}

// ── Custom Menu Item ─────────────────────────────────────────────
@Composable
fun ContextMenuItem(text: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) { Text(text, Color.White, 16.sp) }
}

// ── Scripting Dialog ─────────────────────────────────────────────
@Composable
fun ScriptingDialog(initialScript: String, onDismiss: () -> Unit, onSaveAndRun: (String) -> Unit) {
    var script by remember { mutableStateOf(initialScript) }
    AlertDialog(onDismiss, { Text("Inject JavaScript", color = Color.White, fontSize = 20.sp) }, confirmButton = { TextButton({ onSaveAndRun(script) }) { Text("Run", Color.White) } }, dismissButton = { TextButton(onDismiss) { Text("Close", Color.White) } }, containerColor = Color(0xFF1E1E1E), titleContentColor = Color.White, textContentColor = Color.White, shape = RectangleShape, tonalElevation = 0.dp, text = {
        Column {
            Text("Write or paste JS below. It will execute immediately on the current tab.", Color.Gray, 14.sp, modifier = Modifier.padding(bottom = 8.dp))
            OutlinedTextField(script, { script = it }, Modifier.fillMaxWidth().height(200.dp), TextStyle(color = Color.White, fontSize = 14.sp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Color.White, cursorColor = Color.White, focusedContainerColor = Color(0xFF1E1E1E), unfocusedContainerColor = Color(0xFF1E1E1E)), shape = RectangleShape)
        }
    })
}

// ── Settings Dialog ──────────────────────────────────────────────
@Composable
fun SettingsDialog(prefs: SharedPreferences, onDismiss: () -> Unit, onSettingsApplied: () -> Unit) {
    var jsEnabled by remember { mutableStateOf(prefs.getBoolean("javascript.enabled", true)) }
    var trackingProtection by remember { mutableStateOf(prefs.getBoolean("privacy.trackingprotection.enabled", false)) }
    var cookieBehavior by remember { mutableIntStateOf(prefs.getInt("network.cookie.cookieBehavior", 0)) }
    var autoplayMedia by remember { mutableStateOf(prefs.getBoolean("media.autoplay.enabled", true)) }
    AlertDialog(onDismiss, { Text("Settings", color = Color.White, fontSize = 20.sp) }, confirmButton = { TextButton({ prefs.edit().putBoolean("javascript.enabled", jsEnabled).putBoolean("privacy.trackingprotection.enabled", trackingProtection).putInt("network.cookie.cookieBehavior", cookieBehavior).putBoolean("media.autoplay.enabled", autoplayMedia).apply(); onSettingsApplied(); onDismiss() }) { Text("OK", Color.White) } }, dismissButton = { TextButton(onDismiss) { Text("Cancel", Color.White) } }, containerColor = Color(0xFF1E1E1E), titleContentColor = Color.White, textContentColor = Color.White, shape = RectangleShape, tonalElevation = 0.dp, text = {
        Column {
            SettingSwitch("JavaScript", jsEnabled) { jsEnabled = it }
            SettingSwitch("Tracking Protection", trackingProtection) { trackingProtection = it }
            Text("Cookie Behavior", Color.White, 14.sp)
            CookieBehaviorSelector(cookieBehavior) { cookieBehavior = it }
            SettingSwitch("Autoplay Media", autoplayMedia) { autoplayMedia = it }
        }
    })
}

@Composable
fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text(label, Color.White, 14.sp)
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF444444), uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF444444)))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookieBehaviorSelector(current: Int, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("Accept all" to 0, "Reject all" to 1, "Only from visited" to 2)
    val selectedText = options.firstOrNull { it.second == current }?.first ?: "Accept all"
    Box {
        OutlinedTextField(selectedText, {}, true, trailingIcon = { Icon(Icons.Default.Close, null, tint = Color.White) }, Modifier.fillMaxWidth().padding(vertical = 4.dp), TextStyle(Color.White, 14.sp), shape = RectangleShape, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Color.White, focusedContainerColor = Color(0xFF1E1E1E), unfocusedContainerColor = Color(0xFF1E1E1E)))
        Box(Modifier.matchParentSize().clickable { expanded = true })
        DropdownMenu(expanded, { expanded = false }, Modifier.border(1.dp, Color.White, RectangleShape), containerColor = Color(0xFF1E1E1E), shape = RectangleShape) {
            options.forEach { (label, value) -> DropdownMenuItem({ Text(label, Color.White) }, { onChange(value); expanded = false }) }
        }
    }
}
