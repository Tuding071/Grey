// ═══════════════════════════════════════════════════════════════════
// Grey Browser - V1.2 (Fix: Tab manager always defaults to "All" on open.
// Fix: Current tab highlight, + button, adjust version on every change)
// ═══════════════════════════════════════════════════════════════════

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
//
// ═══════════════════════════════════════════════════════════════════
// VERSION HISTORY:
// V1   - Initial release: Tab manager, favicons, grouping, undo delete,
//        foreground-only sessions, Google search default, confirmation dialogs
// V1.1 - Fix: Current tab always highlighted in tab manager (persists across
//        sessions). Added + button in top bar for quick new tab.
//        On app start: always home page, last session's tab highlighted.
// V1.2 - Fix: Tab manager always defaults to "All" on open regardless of
//        previously selected group.
// ═══════════════════════════════════════════════════════════════════
// DESIGN SYSTEM: Consistent Thickness Values
// Use these as basis for all borders, dividers, and strokes:
// - Border.thin  = 0.5.dp  (subtle separators, unselected chip borders)
// - Border.normal = 1.dp   (selected chips, standard dividers)
// - Border.thick  = 2.dp   (section dividers like pinned/unpinned)
// All chips: 52.dp width
// Favicon sizes: 20.dp (horizontal), 24.dp (vertical sidebar)
// Tab list favicon: 16.dp
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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.filled.Undo
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
import kotlinx.coroutines.delay
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
        setContent { GreyBrowser() }
    }
    override fun onPause() { super.onPause(); saveTabs(this) }
    override fun onDestroy() { super.onDestroy(); saveTabs(this) }
}

// ── Favicon Cache Manager ────────────────────────────────────────────
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
            val json = file.readText(); val array = JSONArray(json)
            mutableListOf<FaviconMeta>().apply {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(FaviconMeta(obj.getString("domain"), obj.getLong("lastAccessed")))
                }
            }
        } catch (e: Exception) { mutableListOf() }
    }
    
    private fun saveMeta(context: Context, meta: List<FaviconMeta>) {
        val array = JSONArray()
        for (item in meta) { val obj = JSONObject(); obj.put("domain", item.domain); obj.put("lastAccessed", item.lastAccessed); array.put(obj) }
        getMetaFile(context).writeText(array.toString())
    }
    
    fun getFaviconFile(context: Context, domain: String) = File(getFaviconDir(context), domain.replace(".", "_").replace("/", "_") + ".png")
    
    fun getFaviconBitmap(context: Context, domain: String): Bitmap? {
        val file = getFaviconFile(context, domain); if (!file.exists()) return null
        val meta = loadMeta(context); val existing = meta.find { it.domain == domain }
        if (existing != null) { meta.remove(existing); meta.add(existing.copy(lastAccessed = System.currentTimeMillis())) }
        else { meta.add(FaviconMeta(domain, System.currentTimeMillis())) }
        saveMeta(context, meta); return BitmapFactory.decodeFile(file.absolutePath)
    }
    
    private fun tryDownload(urlStr: String): Bitmap? {
        return try {
            val url = URL(urlStr); val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000; conn.readTimeout = 4000; conn.connect()
            if (conn.responseCode == 200) { val b = BitmapFactory.decodeStream(conn.inputStream); conn.inputStream.close(); conn.disconnect(); b }
            else { conn.disconnect(); null }
        } catch (e: Exception) { null }
    }
    
    suspend fun downloadAndCacheFavicon(context: Context, domain: String): Bitmap? = withContext(Dispatchers.IO) {
        val sources = listOf("https://www.google.com/s2/favicons?domain=$domain&sz=64", "https://$domain/favicon.ico", "https://$domain/favicon.png")
        var bitmap: Bitmap? = null; for (src in sources) { bitmap = tryDownload(src); if (bitmap != null) break }
        if (bitmap != null) {
            FileOutputStream(getFaviconFile(context, domain)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val meta = loadMeta(context); meta.removeAll { it.domain == domain }; meta.add(FaviconMeta(domain, System.currentTimeMillis()))
            if (meta.size > MAX_FAVICONS) { val oldest = meta.minByOrNull { it.lastAccessed }; if (oldest != null) { meta.remove(oldest); getFaviconFile(context, oldest.domain).delete() } }
            saveMeta(context, meta)
        }
        bitmap
    }
}

// ── Tab Persistence ─────────────────────────────────────────────────
private const val PREFS_NAME = "browser_tabs"; private const val KEY_TABS = "saved_tabs"; private const val KEY_PINNED = "pinned_domains"
private const val KEY_LAST_ACTIVE_URL = "last_active_url"

fun saveTabs(context: Context) { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply() }

fun saveTabsData(context: Context, tabs: List<TabState>, pinnedDomains: List<String>, lastActiveUrl: String) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val tabsArray = JSONArray()
    for (tab in tabs) { if (!tab.isBlankTab) { val obj = JSONObject(); obj.put("url", tab.url); obj.put("title", tab.title); tabsArray.put(obj) } }
    prefs.edit().putString(KEY_TABS, tabsArray.toString()).putString(KEY_LAST_ACTIVE_URL, lastActiveUrl).apply()
    val pinnedArray = JSONArray(); for (d in pinnedDomains) pinnedArray.put(d)
    prefs.edit().putString(KEY_PINNED, pinnedArray.toString()).apply()
}

fun loadTabsData(context: Context): Triple<List<Pair<String, String>>, List<String>, String> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val tabsList = mutableListOf<Pair<String, String>>()
    prefs.getString(KEY_TABS, null)?.let { json -> try { val arr = JSONArray(json); for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); tabsList.add(Pair(o.getString("url"), o.optString("title", o.getString("url")))) } } catch (e: Exception) { } }
    val pinnedList = mutableListOf<String>(); prefs.getString(KEY_PINNED, null)?.let { json -> try { val arr = JSONArray(json); for (i in 0 until arr.length()) pinnedList.add(arr.getString(i)) } catch (e: Exception) { } }
    val lastActiveUrl = prefs.getString(KEY_LAST_ACTIVE_URL, "") ?: ""
    return Triple(tabsList, pinnedList, lastActiveUrl)
}

class TabState {
    var session by mutableStateOf<GeckoSession?>(null); var title by mutableStateOf("New Tab"); var url by mutableStateOf("about:blank")
    var progress by mutableIntStateOf(100); var lastUpdated by mutableLongStateOf(System.currentTimeMillis())
    var isBlankTab by mutableStateOf(true); var isDiscarded by mutableStateOf(false)
}

fun getDomainName(url: String): String {
    if (url == "about:blank" || url.isBlank()) return ""
    return try { val host = Uri.parse(url).host?.removePrefix("www.") ?: return ""; val parts = host.split("."); if (parts.size >= 2) "${parts[parts.size - 2]}.${parts[parts.size - 1]}" else host } catch (e: Exception) { "Unknown" }
}

const val MAX_LOADED_TABS = 10
const val UNDO_DELAY_MS = 3000L

fun resolveUrl(input: String): String {
    if (input.isBlank()) return "about:blank"
    if (input.contains("://") || (input.contains(".") && !input.contains(" "))) return if (input.contains("://")) input else "https://$input"
    return "https://www.google.com/search?q=${Uri.encode(input)}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GreyBrowser() {
    val context = LocalContext.current; val activity = context as? ComponentActivity
    val clipboardManager = LocalClipboardManager.current; val focusManager = LocalFocusManager.current
    val runtime = remember { GeckoRuntime.create(context) }
    val prefs = remember { context.getSharedPreferences("browser_settings", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    val applySettingsToSession: (GeckoSession) -> Unit = { session ->
        session.settings.allowJavascript = prefs.getBoolean("javascript.enabled", true)
        session.settings.useTrackingProtection = prefs.getBoolean("privacy.trackingprotection.enabled", false)
        session.settings.suspendMediaWhenInactive = true
    }

    var showContextMenu by remember { mutableStateOf(false) }; var contextMenuUri by remember { mutableStateOf<String?>(null) }
    var showConfirmDialog by remember { mutableStateOf(false) }; var confirmAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmTitle by remember { mutableStateOf("") }; var confirmMessage by remember { mutableStateOf("") }

    val setupDelegates = fun(tabState: TabState) {
        val session = tabState.session ?: return
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(s: GeckoSession, url: String) { tabState.url = url; tabState.progress = 5; tabState.lastUpdated = System.currentTimeMillis(); if (url != "about:blank") tabState.isBlankTab = false }
            override fun onPageStop(s: GeckoSession, success: Boolean) { tabState.progress = 100; tabState.lastUpdated = System.currentTimeMillis() }
            override fun onProgressChange(s: GeckoSession, progress: Int) { tabState.progress = progress }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(s: GeckoSession, title: String?) { if (!tabState.isBlankTab) tabState.title = title ?: tabState.url }
            override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: GeckoSession.ContentDelegate.ContextElement) { if (element.linkUri != null) { contextMenuUri = element.linkUri; showContextMenu = true } }
        }
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(session: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) { url?.let { newUrl -> tabState.url = newUrl; if (newUrl != "about:blank") tabState.isBlankTab = false } }
        }
    }

    val homeSession = remember { GeckoSession().apply { applySettingsToSession(this); open(runtime); loadUri("about:blank") } }
    val homeTab = remember { TabState().apply { session = homeSession; isBlankTab = true; title = "Home"; setupDelegates(this) } }

    val (savedTabs, savedPinned, savedLastActiveUrl) = remember { loadTabsData(context) }
    val tabs = remember { mutableStateListOf<TabState>().apply { for ((url, title) in savedTabs) add(TabState().apply { this.url = url; this.title = title; isBlankTab = false; isDiscarded = true; session = null }) } }
    
    var currentTabIndex by remember { mutableIntStateOf(-1) }
    var highlightedTabIndex by remember { mutableIntStateOf(tabs.indexOfFirst { it.url == savedLastActiveUrl }.let { if (it >= 0) it else -1 }) }
    var lastActiveUrl by remember { mutableStateOf(savedLastActiveUrl) }
    
    var showTabManager by remember { mutableStateOf(false) }; var showMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }; var showScripting by remember { mutableStateOf(false) }
    val pinnedDomains = remember { mutableStateListOf<String>().apply { addAll(savedPinned) } }
    var selectedDomain by remember { mutableStateOf("") }
    val faviconBitmaps = remember { mutableStateMapOf<String, Bitmap?>() }; val faviconLoading = remember { mutableStateMapOf<String, Boolean>() }
    val tabFavicons = remember { mutableStateMapOf<String, Bitmap?>() }; val tabFaviconLoading = remember { mutableStateMapOf<String, Boolean>() }
    val currentTab = if (currentTabIndex == -1) homeTab else tabs.getOrNull(currentTabIndex) ?: homeTab

    val pendingDeletions = remember { mutableStateMapOf<Int, Long>() }
    var showBlink by remember { mutableStateOf(false) }; val blinkTargetDomain = remember { mutableStateOf("") }

    LaunchedEffect(tabs.toList(), pinnedDomains.toList(), lastActiveUrl) { saveTabsData(context, tabs, pinnedDomains, lastActiveUrl) }
    LaunchedEffect(tabs.map { "${it.url}|${it.title}" }.joinToString()) { saveTabsData(context, tabs, pinnedDomains, lastActiveUrl) }

    LaunchedEffect(currentTabIndex) {
        if (currentTabIndex >= 0 && currentTabIndex < tabs.size) {
            lastActiveUrl = tabs[currentTabIndex].url; highlightedTabIndex = currentTabIndex
        }
    }

    LaunchedEffect(pendingDeletions.toMap()) {
        while (pendingDeletions.isNotEmpty()) {
            delay(1000)
            val now = System.currentTimeMillis()
            val toRemove = pendingDeletions.filter { now - it.value >= UNDO_DELAY_MS }.keys.toList()
            for (index in toRemove.sortedDescending()) {
                pendingDeletions.remove(index)
                val tab = tabs.getOrNull(index) ?: continue
                tab.session?.setActive(false); tab.session?.close(); tabs.removeAt(index)
                if (tabs.isEmpty()) currentTabIndex = -1
                else if (currentTabIndex > index) currentTabIndex--
                else if (currentTabIndex == index && tabs.isNotEmpty()) currentTabIndex = minOf(currentTabIndex, tabs.lastIndex)
                if (highlightedTabIndex == index) highlightedTabIndex = -1
                else if (highlightedTabIndex > index) highlightedTabIndex--
                if (selectedDomain.isNotBlank()) {
                    val dg = tabs.groupBy { getDomainName(it.url) }.filter { it.key.isNotBlank() }
                    if (!dg.containsKey(selectedDomain)) selectedDomain = ""
                }
            }
        }
    }

    fun enforceTabLimit() { val loaded = tabs.filter { !it.isDiscarded && !it.isBlankTab && it.session != null }; if (loaded.size > MAX_LOADED_TABS) { val toDiscard = loaded.filter { tabs.indexOf(it) != currentTabIndex }.minByOrNull { it.lastUpdated }; toDiscard?.let { tab -> tab.session?.setActive(false); tab.session?.close(); tab.session = null; tab.isDiscarded = true; tab.progress = 100 } } }
    fun restoreTab(tab: TabState) { if (tab.isDiscarded && tab.session == null) { val s = GeckoSession().apply { applySettingsToSession(this); open(runtime); setActive(false); loadUri(tab.url) }; tab.session = s; tab.isDiscarded = false; setupDelegates(tab); tab.lastUpdated = System.currentTimeMillis(); enforceTabLimit() } }

    fun loadFavicon(domain: String) { if (!faviconBitmaps.containsKey(domain) && faviconLoading[domain] != true) { faviconLoading[domain] = true; scope.launch { faviconBitmaps[domain] = FaviconCache.getFaviconBitmap(context, domain) ?: FaviconCache.downloadAndCacheFavicon(context, domain); faviconLoading[domain] = false } } }
    fun loadTabFavicon(domain: String) { if (domain.isNotBlank() && !tabFavicons.containsKey(domain) && tabFaviconLoading[domain] != true) { tabFaviconLoading[domain] = true; scope.launch { tabFavicons[domain] = FaviconCache.getFaviconBitmap(context, domain) ?: FaviconCache.downloadAndCacheFavicon(context, domain); tabFaviconLoading[domain] = false } } }

    LaunchedEffect(showTabManager, currentTabIndex) { if (showTabManager) { homeSession.setActive(false); tabs.forEach { it.session?.setActive(false) } } else { homeSession.setActive(currentTabIndex == -1); tabs.forEachIndexed { i, ts -> val active = i == currentTabIndex; if (active && ts.isDiscarded) restoreTab(ts); ts.session?.setActive(active) } } }

    fun createBackgroundTab(url: String) { val s = GeckoSession().apply { applySettingsToSession(this); open(runtime); loadUri(url); setActive(false) }; tabs.add(TabState().apply { session = s; this.url = url; isBlankTab = false; isDiscarded = false; lastUpdated = System.currentTimeMillis(); setupDelegates(this) }); enforceTabLimit() }
    fun createForegroundTab(url: String) { val s = GeckoSession().apply { applySettingsToSession(this); open(runtime); loadUri(url) }; tabs.add(TabState().apply { session = s; isBlankTab = false; isDiscarded = false; lastUpdated = System.currentTimeMillis(); setupDelegates(this) }); currentTabIndex = tabs.lastIndex; enforceTabLimit() }

    fun requestDeleteTab(index: Int) { if (index >= 0 && index < tabs.size) pendingDeletions[index] = System.currentTimeMillis() }
    fun undoDeleteTab(index: Int) { pendingDeletions.remove(index) }

    BackHandler { if (currentTab.isBlankTab) { if (tabs.isNotEmpty()) currentTabIndex = tabs.lastIndex else activity?.finish() } else currentTab.session?.goBack() }

    @Composable
    fun GeckoViewBox() { val s = currentTab.session; if (s != null) AndroidView(factory = { ctx -> GeckoView(ctx).apply { setSession(s) } }, update = { gv -> gv.setSession(s) }, modifier = Modifier.fillMaxSize()) else Box(Modifier.fillMaxSize().background(Color(0xFF121212)), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) } }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false; confirmAction = null },
            title = { Text(confirmTitle, color = Color.White, fontSize = 18.sp) },
            text = { Text(confirmMessage, color = Color.Gray, fontSize = 14.sp) },
            confirmButton = { TextButton({ val a = confirmAction; showConfirmDialog = false; confirmAction = null; a?.invoke() }) { Text("Confirm", color = Color.White) } },
            dismissButton = { TextButton({ showConfirmDialog = false; confirmAction = null }) { Text("Cancel", color = Color.White) } },
            containerColor = Color(0xFF1E1E1E), titleContentColor = Color.White, textContentColor = Color.White, shape = RectangleShape, tonalElevation = 0.dp
        )
    }

    if (showSettings) SettingsDialog(prefs, { showSettings = false }) { applySettingsToSession(homeSession); tabs.forEach { it.session?.let { s -> applySettingsToSession(s) } } }
    if (showScripting) ScriptingDialog(prefs.getString("user_script", "alert('Hello from Grey Browser!');") ?: "", { showScripting = false }) { script -> prefs.edit().putString("user_script", script).apply(); currentTab.session?.loadUri("javascript:" + Uri.encode("(function(){\n$script\n})();")); showScripting = false }
    if (showContextMenu && contextMenuUri != null) { Popup(alignment = Alignment.Center, onDismissRequest = { showContextMenu = false }, properties = PopupProperties(focusable = true)) { Column(Modifier.border(1.dp, Color.White, RectangleShape).background(Color(0xFF1E1E1E)).width(IntrinsicSize.Max)) { ContextMenuItem("New Tab") { createForegroundTab(contextMenuUri!!); showContextMenu = false }; ContextMenuItem("Open in Background") { createBackgroundTab(contextMenuUri!!); showContextMenu = false }; ContextMenuItem("Copy link") { clipboardManager.setText(AnnotatedString(contextMenuUri!!)); showContextMenu = false } } } }

    // ═══════════════════════════════════════════════════════════════
    // ── TAB MANAGER ───────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════
    if (showTabManager) {
        val domainGroups = tabs.groupBy { getDomainName(it.url) }.filter { it.key.isNotBlank() }
        val sortedDomains = domainGroups.keys.sortedWith(compareByDescending<String> { pinnedDomains.contains(it) }.thenBy { d: String -> domainGroups[d]?.firstOrNull()?.let { t -> tabs.indexOf(t) } ?: Int.MAX_VALUE })
        val pinnedSorted = sortedDomains.filter { pinnedDomains.contains(it) }
        val unpinnedSorted = sortedDomains.filter { !pinnedDomains.contains(it) }
        val highlightDomain = if (currentTab.isBlankTab && highlightedTabIndex >= 0) getDomainName(tabs[highlightedTabIndex].url) else if (!currentTab.isBlankTab) getDomainName(currentTab.url) else ""

        // ALWAYS reset to "All" when tab manager opens
        LaunchedEffect(Unit) {
            selectedDomain = ""
            if (highlightDomain.isNotBlank()) { blinkTargetDomain.value = highlightDomain; showBlink = true; delay(1500); showBlink = false; blinkTargetDomain.value = "" }
        }

        Popup(alignment = Alignment.TopStart, onDismissRequest = { showTabManager = false }, properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false)) {
            Surface(Modifier.fillMaxSize().statusBarsPadding().background(Color(0xFF1E1E1E)), color = Color(0xFF1E1E1E)) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ showTabManager = false }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                        Spacer(Modifier.width(4.dp)); Text("Tabs", color = Color.White, fontSize = 18.sp)
                        if (tabs.isNotEmpty()) { Spacer(Modifier.width(8.dp)); Text("(${tabs.size})", color = Color.Gray, fontSize = 14.sp) }
                    }

                    Row(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp)) {
                        val groupListState = rememberLazyListState()
                        val allSidebarItems = listOf("__ALL__") + pinnedSorted + unpinnedSorted
                        LaunchedEffect(allSidebarItems, selectedDomain) { val idx = if (selectedDomain.isBlank()) 0 else allSidebarItems.indexOf(selectedDomain); if (idx >= 0) groupListState.scrollToItem(idx) }
                        
                        LazyColumn(state = groupListState, modifier = Modifier.width(56.dp).fillMaxHeight().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            item { AllGroupChip(isSelected = selectedDomain.isBlank(), tabCount = tabs.size, onClick = { selectedDomain = "" }) }
                            items(pinnedSorted) { domain: String -> SidebarGroupChip(domain, domain == selectedDomain, domainGroups[domain]?.size ?: 0, { selectedDomain = domain }, faviconBitmaps[domain], { loadFavicon(domain) }, showBlink && blinkTargetDomain.value == domain, true) }
                            items(unpinnedSorted) { domain: String -> SidebarGroupChip(domain, domain == selectedDomain, domainGroups[domain]?.size ?: 0, { selectedDomain = domain }, faviconBitmaps[domain], { loadFavicon(domain) }, showBlink && blinkTargetDomain.value == domain, false) }
                        }
                        
                        VerticalDivider(color = Color.DarkGray, modifier = Modifier.fillMaxHeight().width(1.dp))

                        val tabsToShow = if (selectedDomain.isBlank()) tabs.toList() else domainGroups[selectedDomain] ?: emptyList()
                        val tabListState = rememberLazyListState()
                        val scrollTarget = if (currentTabIndex >= 0) currentTab else if (highlightedTabIndex >= 0) tabs.getOrNull(highlightedTabIndex) else null
                        LaunchedEffect(selectedDomain) { val idx = tabsToShow.indexOf(scrollTarget); if (idx >= 0) tabListState.scrollToItem(idx) }

                        if (tabs.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("No open tabs", color = Color.Gray, fontSize = 16.sp); Spacer(Modifier.height(8.dp)); Text("Tap 'New Tab' to start browsing", color = Color.Gray.copy(alpha = 0.7f), fontSize = 14.sp) } }
                        } else {
                            LazyColumn(state = tabListState, modifier = Modifier.weight(1f).fillMaxHeight()) {
                                if (tabsToShow.isEmpty()) { item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text("No tabs in this group", color = Color.Gray, fontSize = 14.sp) } } }
                                else {
                                    items(tabsToShow) { tab: TabState ->
                                        val tabIndex = tabs.indexOf(tab)
                                        val isHighlighted = tabIndex == currentTabIndex || (currentTabIndex == -1 && tabIndex == highlightedTabIndex)
                                        val isPending = pendingDeletions.containsKey(tabIndex)
                                        val tabDomain = getDomainName(tab.url)
                                        LaunchedEffect(tab.url) { loadTabFavicon(tabDomain) }
                                        val tabFav = tabFavicons[tabDomain]
                                        
                                        Surface(
                                            Modifier.fillMaxWidth().clickable(enabled = !isPending) { currentTabIndex = tabIndex; showTabManager = false }.border(0.5.dp, Color.DarkGray, RectangleShape),
                                            color = if (isPending) Color.Red.copy(alpha = 0.3f) else if (isHighlighted) Color.White else Color.Transparent
                                        ) {
                                            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                if (tabFav != null) Image(tabFav.asImageBitmap(), tabDomain, Modifier.size(16.dp).clip(CircleShape), contentScale = ContentScale.Fit)
                                                else Box(Modifier.size(16.dp).clip(CircleShape).background(Color.DarkGray), contentAlignment = Alignment.Center) { Text(tabDomain.take(1).uppercase(), color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold) }
                                                Spacer(Modifier.width(8.dp))
                                                Text(if (tab.title == "New Tab" || tab.title.isBlank()) tab.url else tab.title, color = if (isPending) Color.White else if (isHighlighted) Color.Black else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                                if (isPending) IconButton({ undoDeleteTab(tabIndex) }) { Icon(Icons.Default.Undo, "Undo", tint = Color.White, modifier = Modifier.size(18.dp)) }
                                                else IconButton({ requestDeleteTab(tabIndex) }) { Icon(Icons.Default.Close, "Close", tint = if (isHighlighted) Color.Black else Color.White, modifier = Modifier.size(18.dp)) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Column(Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding()) {
                        OutlinedButton(onClick = { currentTabIndex = -1; showTabManager = false }, modifier = Modifier.fillMaxWidth(), shape = RectangleShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), border = BorderStroke(1.dp, Color.White)) { Icon(Icons.Default.Add, null, tint = Color.White); Spacer(Modifier.width(8.dp)); Text("New Tab", color = Color.White) }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth()) {
                            val hasSelection = selectedDomain.isNotBlank(); val isPinned = pinnedDomains.contains(selectedDomain)
                            val faintColor = Color.White.copy(alpha = 0.3f); val activeColor = Color.White
                            OutlinedButton(onClick = { if (hasSelection) { confirmTitle = if (isPinned) "Unpin Group?" else "Pin Group?"; confirmMessage = "Are you sure?"; confirmAction = { if (isPinned) pinnedDomains.remove(selectedDomain) else pinnedDomains.add(selectedDomain) }; showConfirmDialog = true } }, modifier = Modifier.weight(1f), shape = RectangleShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = if (hasSelection) activeColor else faintColor), border = BorderStroke(1.dp, if (hasSelection) activeColor else faintColor)) { Text(if (isPinned) "Unpin Group" else "Pin Group", fontSize = 13.sp, color = if (hasSelection) activeColor else faintColor) }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { if (hasSelection) { confirmTitle = "Delete Group?"; confirmMessage = "All tabs in this group will be lost."; confirmAction = { val toRemove = domainGroups[selectedDomain] ?: emptyList(); toRemove.forEach { it.session?.setActive(false); it.session?.close() }; tabs.removeAll(toRemove); if (tabs.isEmpty()) currentTabIndex = -1 else currentTabIndex = minOf(currentTabIndex, tabs.lastIndex); selectedDomain = "" }; showConfirmDialog = true } }, modifier = Modifier.weight(1f), shape = RectangleShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = if (hasSelection) activeColor else faintColor), border = BorderStroke(1.dp, if (hasSelection) activeColor else faintColor)) { Text("Delete Group", fontSize = 13.sp, color = if (hasSelection) activeColor else faintColor) }
                        }
                    }
                }
            }
        }
    }

    var urlInput by remember { mutableStateOf(TextFieldValue(if (currentTab.url == "about:blank") "" else currentTab.url)) }
    var isUrlFocused by remember { mutableStateOf(false) }; val focusRequester = remember { FocusRequester() }
    LaunchedEffect(currentTab, currentTab.url) { if (!isUrlFocused) urlInput = TextFieldValue(if (currentTab.url == "about:blank") "" else currentTab.url) }

    Surface(Modifier.fillMaxSize(), color = Color(0xFF121212)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ showTabManager = true }) { Icon(Icons.Default.Tab, "Tabs", tint = Color.White) }
                val isLoading = currentTab.progress in 1..99
                OutlinedTextField(value = urlInput, onValueChange = { urlInput = it }, singleLine = true, placeholder = { Text(if (currentTab.isBlankTab) "Search or enter URL" else currentTab.url.take(50), color = Color.White.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier = Modifier.weight(1f).padding(vertical = 8.dp).background(Color(0xFF1E1E1E)).focusRequester(focusRequester).onFocusChanged { isUrlFocused = it.isFocused }.drawBehind { if (isLoading) drawRect(Color.White, size = size.copy(width = size.width * currentTab.progress / 100f)) }, textStyle = TextStyle(if (isLoading) Color.Gray else Color.White, 16.sp), shape = RectangleShape, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { val input = urlInput.text; if (input.isNotBlank()) { focusManager.clearFocus(); val uri = resolveUrl(input); if (currentTab.isBlankTab) createForegroundTab(uri) else currentTab.session?.loadUri(uri) } }), colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedBorderColor = Color.White, unfocusedBorderColor = Color.White, cursorColor = if (isLoading) Color.Gray else Color.White), trailingIcon = { if (isLoading) IconButton({ currentTab.session?.stop() }) { Icon(Icons.Default.Close, "Stop", tint = Color.White) } else IconButton({ urlInput = urlInput.copy(selection = TextRange(0, urlInput.text.length)); focusRequester.requestFocus() }) { Icon(Icons.Default.SelectAll, "Select all", tint = Color.White) } })
                IconButton({ currentTabIndex = -1 }) { Icon(Icons.Default.Add, "New Tab", tint = Color.White) }
                Box { IconButton({ showMenu = true }) { Icon(Icons.Default.MoreVert, "Menu", tint = Color.White) }; DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.border(1.dp, Color.White, RectangleShape), containerColor = Color(0xFF1E1E1E), shape = RectangleShape) { DropdownMenuItem(text = { Text("Scripting", color = Color.White) }, onClick = { showMenu = false; showScripting = true }); DropdownMenuItem(text = { Text("Settings", color = Color.White) }, onClick = { showMenu = false; showSettings = true }) } }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { GeckoViewBox() }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// ── Composable Helpers ────────────────────────────────────────────
// ═══════════════════════════════════════════════════════════════════

@Composable
fun AllGroupChip(isSelected: Boolean, tabCount: Int, onClick: () -> Unit) {
    val bg = if (isSelected) Color.White else Color.Transparent; val fg = if (isSelected) Color.Black else Color.White
    val borderColor = if (isSelected) Color.White else Color.White.copy(alpha = 0.2f)
    Surface(Modifier.padding(vertical = 4.dp).width(52.dp).clickable { onClick() }.border(0.5.dp, borderColor, RectangleShape), color = bg) {
        Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("All", color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(2.dp)); Box(Modifier.background(if (isSelected) Color.LightGray else Color.DarkGray).padding(horizontal = 4.dp, vertical = 1.dp)) { Text(tabCount.toString(), color = fg, fontSize = 9.sp) } }
    }
}

@Composable
fun SidebarGroupChip(domain: String, isSelected: Boolean, tabCount: Int, onClick: () -> Unit, favicon: Bitmap?, onAppear: () -> Unit, isBlinking: Boolean, isPinned: Boolean) {
    LaunchedEffect(domain) { onAppear() }
    val blinkAlpha = if (isBlinking) { val t = rememberInfiniteTransition(label = "blink_$domain"); t.animateFloat(0.2f, 0.5f, infiniteRepeatable(tween(400), RepeatMode.Reverse), label = "blinkV") } else null
    val bgAlpha = if (isBlinking && blinkAlpha != null) blinkAlpha.value else if (isSelected) 1f else 0f
    val bg = Color.White.copy(alpha = bgAlpha); val fg = if (isSelected) Color.Black else Color.White
    val borderColor = if (isSelected) Color.White else Color.White.copy(alpha = 0.2f)
    Surface(Modifier.padding(vertical = 4.dp).width(52.dp).clickable { onClick() }.border(0.5.dp, borderColor, RectangleShape), color = bg) {
        Box(Modifier.padding(6.dp)) {
            if (isPinned) Icon(Icons.Default.PushPin, "Pinned", tint = fg, modifier = Modifier.size(12.dp).align(Alignment.TopStart))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { Spacer(Modifier.height(4.dp)); if (favicon != null) Image(favicon.asImageBitmap(), domain, Modifier.size(24.dp).clip(CircleShape), contentScale = ContentScale.Fit) else Box(Modifier.size(24.dp).clip(CircleShape).background(if (isSelected) Color.LightGray else Color.DarkGray), contentAlignment = Alignment.Center) { Text(domain.take(1).uppercase(), color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold) } }
            Box(Modifier.align(Alignment.BottomEnd).background(if (isSelected) Color.LightGray else Color.DarkGray).padding(horizontal = 4.dp, vertical = 1.dp)) { Text(tabCount.toString(), color = fg, fontSize = 9.sp) }
        }
    }
}

@Composable
fun ContextMenuItem(text: String, onClick: () -> Unit) { Box(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) { Text(text, color = Color.White, fontSize = 16.sp) } }

// ── Dialogs ──────────────────────────────────────────────────────
@Composable
fun ScriptingDialog(initialScript: String, onDismiss: () -> Unit, onSaveAndRun: (String) -> Unit) {
    var script by remember { mutableStateOf(initialScript) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Inject JavaScript", color = Color.White, fontSize = 20.sp) },
        text = {
            Column {
                Text("Write or paste JS below.", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(value = script, onValueChange = { script = it }, modifier = Modifier.fillMaxWidth().height(200.dp), textStyle = TextStyle(color = Color.White, fontSize = 14.sp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Color.White, cursorColor = Color.White, focusedContainerColor = Color(0xFF1E1E1E), unfocusedContainerColor = Color(0xFF1E1E1E)), shape = RectangleShape)
            }
        },
        confirmButton = { TextButton({ onSaveAndRun(script) }) { Text("Run", color = Color.White) } },
        dismissButton = { TextButton(onDismiss) { Text("Close", color = Color.White) } },
        containerColor = Color(0xFF1E1E1E), titleContentColor = Color.White, textContentColor = Color.White, shape = RectangleShape, tonalElevation = 0.dp
    )
}

@Composable
fun SettingsDialog(prefs: SharedPreferences, onDismiss: () -> Unit, onSettingsApplied: () -> Unit) {
    var js by remember { mutableStateOf(prefs.getBoolean("javascript.enabled", true)) }; var tp by remember { mutableStateOf(prefs.getBoolean("privacy.trackingprotection.enabled", false)) }
    var cb by remember { mutableIntStateOf(prefs.getInt("network.cookie.cookieBehavior", 0)) }; var am by remember { mutableStateOf(prefs.getBoolean("media.autoplay.enabled", true)) }
    val apply = { prefs.edit().putBoolean("javascript.enabled", js).putBoolean("privacy.trackingprotection.enabled", tp).putInt("network.cookie.cookieBehavior", cb).putBoolean("media.autoplay.enabled", am).apply(); onSettingsApplied(); onDismiss() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings", color = Color.White, fontSize = 20.sp) },
        text = {
            Column {
                SettingSwitch("JavaScript", js) { js = it }
                SettingSwitch("Tracking Protection", tp) { tp = it }
                Text("Cookie Behavior", color = Color.White, fontSize = 14.sp)
                CookieBehaviorSelector(cb) { cb = it }
                SettingSwitch("Autoplay Media", am) { am = it }
            }
        },
        confirmButton = { TextButton(apply) { Text("OK", color = Color.White) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = Color.White) } },
        containerColor = Color(0xFF1E1E1E), titleContentColor = Color.White, textContentColor = Color.White, shape = RectangleShape, tonalElevation = 0.dp
    )
}

@Composable
fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text(text = label, color = Color.White, fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF444444), uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF444444)))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookieBehaviorSelector(current: Int, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("Accept all" to 0, "Reject all" to 1, "Only from visited" to 2)
    val st = options.firstOrNull { it.second == current }?.first ?: "Accept all"
    Box {
        OutlinedTextField(value = st, onValueChange = {}, readOnly = true, trailingIcon = { Icon(Icons.Default.Close, null, tint = Color.White) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), textStyle = TextStyle(color = Color.White, fontSize = 14.sp), shape = RectangleShape, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Color.White, focusedContainerColor = Color(0xFF1E1E1E), unfocusedContainerColor = Color(0xFF1E1E1E)))
        Box(Modifier.matchParentSize().clickable { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.border(1.dp, Color.White, RectangleShape), containerColor = Color(0xFF1E1E1E), shape = RectangleShape) {
            options.forEach { (label, value) -> DropdownMenuItem(text = { Text(text = label, color = Color.White) }, onClick = { onChange(value); expanded = false }) }
        }
    }
}
