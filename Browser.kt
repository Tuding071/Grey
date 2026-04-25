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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
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
            val json = file.readText(); val array = JSONArray(json); val list = mutableListOf<FaviconMeta>()
            for (i in 0 until array.length()) { val obj = array.getJSONObject(i); list.add(FaviconMeta(obj.getString("domain"), obj.getLong("lastAccessed"))) }
            list
        } catch (e: Exception) { mutableListOf() }
    }
    
    private fun saveMeta(context: Context, meta: List<FaviconMeta>) {
        val array = JSONArray(); for (item in meta) { val obj = JSONObject(); obj.put("domain", item.domain); obj.put("lastAccessed", item.lastAccessed); array.put(obj) }
        getMetaFile(context).writeText(array.toString())
    }
    
    fun getFaviconFile(context: Context, domain: String): File = File(getFaviconDir(context), domain.replace(".", "_").replace("/", "_") + ".png")
    
    fun getFaviconBitmap(context: Context, domain: String): Bitmap? {
        val file = getFaviconFile(context, domain); if (!file.exists()) return null
        val meta = loadMeta(context); val existing = meta.find { it.domain == domain }
        if (existing != null) { meta.remove(existing); meta.add(existing.copy(lastAccessed = System.currentTimeMillis())) }
        else { meta.add(FaviconMeta(domain, System.currentTimeMillis())) }
        saveMeta(context, meta); return BitmapFactory.decodeFile(file.absolutePath)
    }
    
    suspend fun downloadAndCacheFavicon(context: Context, domain: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://$domain/favicon.ico"); val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000; connection.readTimeout = 5000; connection.connect()
            if (connection.responseCode == 200) {
                val bitmap = BitmapFactory.decodeStream(connection.inputStream); connection.inputStream.close()
                if (bitmap != null) {
                    val file = getFaviconFile(context, domain); FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val meta = loadMeta(context); meta.removeAll { it.domain == domain }; meta.add(FaviconMeta(domain, System.currentTimeMillis()))
                    if (meta.size > MAX_FAVICONS) { val oldest = meta.minByOrNull { it.lastAccessed }; if (oldest != null) { meta.remove(oldest); getFaviconFile(context, oldest.domain).delete() } }
                    saveMeta(context, meta); return@withContext bitmap
                }
            }
            connection.disconnect()
        } catch (e: Exception) { }
        null
    }
}

// ── Tab Persistence ─────────────────────────────────────────────────
private const val PREFS_NAME = "browser_tabs"; private const val KEY_TABS = "saved_tabs"; private const val KEY_PINNED = "pinned_domains"
fun saveTabs(context: Context) { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply() }

fun saveTabsData(context: Context, tabs: List<TabState>, pinnedDomains: List<String>) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val tabsArray = JSONArray(); for (tab in tabs) { if (!tab.isBlankTab) { val obj = JSONObject(); obj.put("url", tab.url); obj.put("title", tab.title); tabsArray.put(obj) } }
    prefs.edit().putString(KEY_TABS, tabsArray.toString()).apply()
    val pinnedArray = JSONArray(); for (d in pinnedDomains) pinnedArray.put(d)
    prefs.edit().putString(KEY_PINNED, pinnedArray.toString()).apply()
}

fun loadTabsData(context: Context): Pair<List<Pair<String, String>>, List<String>> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val tabsList = mutableListOf<Pair<String, String>>()
    prefs.getString(KEY_TABS, null)?.let { json: String -> try { val arr = JSONArray(json); for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); tabsList.add(Pair(o.getString("url"), o.optString("title", o.getString("url")))) } } catch (e: Exception) { } }
    val pinnedList = mutableListOf<String>(); prefs.getString(KEY_PINNED, null)?.let { json: String -> try { val arr = JSONArray(json); for (i in 0 until arr.length()) pinnedList.add(arr.getString(i)) } catch (e: Exception) { } }
    return Pair(tabsList, pinnedList)
}

// ── State Wrapper ───────────────────────────────────────────────────
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GreyBrowser() {
    val context = LocalContext.current; val activity = context as? ComponentActivity
    val clipboardManager = LocalClipboardManager.current; val focusManager = LocalFocusManager.current
    val runtime = remember { GeckoRuntime.create(context) }
    val prefs = remember { context.getSharedPreferences("browser_settings", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    val applySettingsToSession: (GeckoSession) -> Unit = { session: GeckoSession ->
        session.settings.allowJavascript = prefs.getBoolean("javascript.enabled", true)
        session.settings.useTrackingProtection = prefs.getBoolean("privacy.trackingprotection.enabled", false)
        session.settings.suspendMediaWhenInactive = true
    }

    var showContextMenu by remember { mutableStateOf(false) }; var contextMenuUri by remember { mutableStateOf<String?>(null) }

    val setupDelegates = fun(tabState: TabState) {
        val session = tabState.session ?: return
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(s: GeckoSession, url: String) { tabState.url = url; tabState.progress = 5; tabState.lastUpdated = System.currentTimeMillis(); if (url != "about:blank") tabState.isBlankTab = false }
            override fun onPageStop(s: GeckoSession, success: Boolean) { tabState.progress = 100; tabState.lastUpdated = System.currentTimeMillis() }
            override fun onProgressChange(s: GeckoSession, progress: Int) { tabState.progress = progress }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(s: GeckoSession, title: String?) { if (!tabState.isBlankTab) tabState.title = title ?: "New Tab" }
            override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: GeckoSession.ContentDelegate.ContextElement) { if (element.linkUri != null) { contextMenuUri = element.linkUri; showContextMenu = true } }
        }
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(session: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) { url?.let { newUrl: String -> tabState.url = newUrl; if (newUrl != "about:blank") tabState.isBlankTab = false } }
        }
    }

    val homeSession = remember { GeckoSession().apply { applySettingsToSession(this); open(runtime); loadUri("about:blank") } }
    val homeTab = remember { TabState().apply { session = homeSession; isBlankTab = true; title = "Home"; setupDelegates(this) } }

    val tabs = remember { val (savedTabs, _) = loadTabsData(context); mutableStateListOf<TabState>().apply { for ((url, title) in savedTabs) add(TabState().apply { this.url = url; this.title = title; isBlankTab = false; isDiscarded = true; session = null }) } }
    var currentTabIndex by remember { mutableIntStateOf(-1) }
    var showTabManager by remember { mutableStateOf(false) }; var showMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }; var showScripting by remember { mutableStateOf(false) }
    val pinnedDomains = remember { val (_, p) = loadTabsData(context); mutableStateListOf<String>().apply { addAll(p) } }
    var selectedDomain by remember { mutableStateOf("") }
    val faviconBitmaps = remember { mutableStateMapOf<String, Bitmap?>() }; val faviconLoading = remember { mutableStateMapOf<String, Boolean>() }
    val currentTab = if (currentTabIndex == -1) homeTab else tabs.getOrNull(currentTabIndex) ?: homeTab

    // Blink state for highlighting current tab's group
    var showBlink by remember { mutableStateOf(false) }
    val blinkTargetDomain = remember { mutableStateOf("") }

    LaunchedEffect(tabs.toList(), pinnedDomains.toList()) { saveTabsData(context, tabs, pinnedDomains) }
    LaunchedEffect(tabs.map { "${it.url}|${it.title}" }.joinToString()) { saveTabsData(context, tabs, pinnedDomains) }

    fun enforceTabLimit() {
        val loaded = tabs.filter { !it.isDiscarded && !it.isBlankTab && it.session != null }
        if (loaded.size > MAX_LOADED_TABS) { val toDiscard = loaded.filter { tabs.indexOf(it) != currentTabIndex }.minByOrNull { it.lastUpdated }; toDiscard?.let { tab: TabState -> tab.session?.setActive(false); tab.session?.close(); tab.session = null; tab.isDiscarded = true; tab.progress = 100 } }
    }

    fun restoreTab(tab: TabState) {
        if (tab.isDiscarded && tab.session == null) { val s = GeckoSession().apply { applySettingsToSession(this); open(runtime); setActive(false); loadUri(tab.url) }; tab.session = s; tab.isDiscarded = false; setupDelegates(tab); tab.lastUpdated = System.currentTimeMillis(); enforceTabLimit() }
    }

    fun loadFavicon(domain: String) {
        if (!faviconBitmaps.containsKey(domain) && faviconLoading[domain] != true) { faviconLoading[domain] = true; scope.launch { faviconBitmaps[domain] = FaviconCache.getFaviconBitmap(context, domain) ?: FaviconCache.downloadAndCacheFavicon(context, domain); faviconLoading[domain] = false } }
    }

    LaunchedEffect(showTabManager, currentTabIndex) {
        if (showTabManager) { homeSession.setActive(false); tabs.forEach { it.session?.setActive(false) } }
        else { homeSession.setActive(currentTabIndex == -1); tabs.forEachIndexed { i: Int, ts: TabState -> val active = i == currentTabIndex; if (active && ts.isDiscarded) restoreTab(ts); ts.session?.setActive(active) } }
    }

    fun createBackgroundTab(url: String) { val s = GeckoSession().apply { applySettingsToSession(this); open(runtime); loadUri(url); setActive(false) }; val ts = TabState().apply { session = s; this.url = url; isBlankTab = false; isDiscarded = false; lastUpdated = System.currentTimeMillis(); setupDelegates(this) }; tabs.add(ts); enforceTabLimit() }
    fun createForegroundTab(url: String) { val s = GeckoSession().apply { applySettingsToSession(this); open(runtime); loadUri(url) }; val ts = TabState().apply { session = s; isBlankTab = false; isDiscarded = false; lastUpdated = System.currentTimeMillis(); setupDelegates(this) }; tabs.add(ts); currentTabIndex = tabs.lastIndex; enforceTabLimit() }

    fun removeTab(index: Int) {
        if (index < 0 || index >= tabs.size) return; val tab = tabs[index]; tab.session?.setActive(false); tab.session?.close(); tabs.removeAt(index)
        if (tabs.isEmpty()) currentTabIndex = -1 else if (currentTabIndex > index) currentTabIndex-- else if (currentTabIndex == index && tabs.isNotEmpty()) currentTabIndex = minOf(currentTabIndex, tabs.lastIndex)
    }

    BackHandler { if (currentTab.isBlankTab) { if (tabs.isNotEmpty()) currentTabIndex = tabs.lastIndex else activity?.finish() } else currentTab.session?.goBack() }

    @Composable
    fun GeckoViewBox() { val s = currentTab.session; if (s != null) AndroidView(factory = { ctx -> GeckoView(ctx).apply { setSession(s) } }, update = { gv -> gv.setSession(s) }, modifier = Modifier.fillMaxSize()) else Box(Modifier.fillMaxSize().background(Color(0xFF121212)), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) } }

    if (showSettings) SettingsDialog(prefs = prefs, onDismiss = { showSettings = false }, onSettingsApplied = { applySettingsToSession(homeSession); tabs.forEach { it.session?.let { s: GeckoSession -> applySettingsToSession(s) } } })
    if (showScripting) ScriptingDialog(initialScript = prefs.getString("user_script", "alert('Hello from Grey Browser!');") ?: "", onDismiss = { showScripting = false }, onSaveAndRun = { script: String -> prefs.edit().putString("user_script", script).apply(); currentTab.session?.loadUri("javascript:" + Uri.encode("(function(){\n$script\n})();")); showScripting = false })

    if (showContextMenu && contextMenuUri != null) { Popup(alignment = Alignment.Center, onDismissRequest = { showContextMenu = false }, properties = PopupProperties(focusable = true)) { Column(Modifier.border(1.dp, Color.White, RectangleShape).background(Color(0xFF1E1E1E)).width(IntrinsicSize.Max)) { ContextMenuItem("New Tab") { createForegroundTab(contextMenuUri!!); showContextMenu = false }; ContextMenuItem("Open in Background") { createBackgroundTab(contextMenuUri!!); showContextMenu = false }; ContextMenuItem("Copy link") { clipboardManager.setText(AnnotatedString(contextMenuUri!!)); showContextMenu = false } } } }

    // ═══════════════════════════════════════════════════════════════
    // ── TAB MANAGER ───────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════
    if (showTabManager) {
        val domainGroups: Map<String, List<TabState>> = tabs.groupBy { getDomainName(it.url) }.filter { it.key.isNotBlank() }
        // Stable sort: pinned first (by pin order), then by first tab index (creation order)
        val sortedDomains: List<String> = domainGroups.keys.sortedWith(
            compareByDescending<String> { pinnedDomains.contains(it) }
                .thenBy { d: String -> domainGroups[d]?.firstOrNull()?.let { t -> tabs.indexOf(t) } ?: Int.MAX_VALUE }
        )
        val pinnedSorted: List<String> = sortedDomains.filter { pinnedDomains.contains(it) }
        val unpinnedSorted: List<String> = sortedDomains.filter { !pinnedDomains.contains(it) }

        // Determine which group the current tab belongs to
        val currentTabDomain = if (currentTab.isBlankTab) "" else getDomainName(currentTab.url)

        // Set default to "All" when opening
        LaunchedEffect(Unit) {
            selectedDomain = ""
            // Trigger blink on the group containing current tab
            if (currentTabDomain.isNotBlank()) {
                blinkTargetDomain.value = currentTabDomain
                showBlink = true
                delay(1500) // Blink for 1.5 seconds
                showBlink = false
                blinkTargetDomain.value = ""
            }
        }

        Popup(alignment = Alignment.TopStart, onDismissRequest = { showTabManager = false }, properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false)) {
            Surface(Modifier.fillMaxSize().statusBarsPadding().background(Color(0xFF1E1E1E)), color = Color(0xFF1E1E1E)) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) { IconButton({ showTabManager = false }) { Icon(Icons.Default.Close, "Close", tint = Color.White) }; Text("Tabs", color = Color.White, fontSize = 18.sp); if (tabs.isNotEmpty()) { Spacer(Modifier.width(8.dp)); Text("(${tabs.size})", color = Color.Gray, fontSize = 14.sp) } }

                    if (pinnedSorted.isNotEmpty()) {
                        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            items(pinnedSorted) { domain: String -> GroupChip(domain, domain == selectedDomain, true, domainGroups[domain]?.size ?: 0, { selectedDomain = domain }, faviconBitmaps[domain]) { loadFavicon(domain) }, showBlink && blinkTargetDomain.value == domain) }
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.3f), thickness = 2.dp, modifier = Modifier.padding(horizontal = 8.dp))
                    }

                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        val groupListState = rememberLazyListState()
                        
                        LaunchedEffect(unpinnedSorted, selectedDomain) {
                            val idx = if (selectedDomain.isBlank()) 0 else unpinnedSorted.indexOf(selectedDomain) + 1
                            if (idx >= 0) groupListState.scrollToItem(idx)
                        }
                        
                        LazyColumn(state = groupListState, modifier = Modifier.width(56.dp).fillMaxHeight().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            item { AllGroupChip(isSelected = selectedDomain.isBlank(), tabCount = tabs.size, onClick = { selectedDomain = "" }) }
                            items(unpinnedSorted) { domain: String ->
                                SidebarGroupChip(domain = domain, isSelected = domain == selectedDomain, tabCount = domainGroups[domain]?.size ?: 0, onClick = { selectedDomain = domain }, favicon = faviconBitmaps[domain], onAppear = { loadFavicon(domain) }, isBlinking = showBlink && blinkTargetDomain.value == domain)
                            }
                        }
                        
                        VerticalDivider(color = Color.DarkGray, modifier = Modifier.fillMaxHeight().width(1.dp))

                        val tabsToShow: List<TabState> = if (selectedDomain.isBlank()) tabs.toList() else domainGroups[selectedDomain] ?: emptyList()
                        val tabListState = rememberLazyListState()
                        LaunchedEffect(selectedDomain) { val idx = tabsToShow.indexOf(currentTab); if (idx >= 0) tabListState.scrollToItem(idx) }

                        if (tabs.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("No open tabs", color = Color.Gray, fontSize = 16.sp); Spacer(Modifier.height(8.dp)); Text("Tap 'New Tab' to start browsing", color = Color.Gray.copy(alpha = 0.7f), fontSize = 14.sp) } }
                        } else {
                            LazyColumn(state = tabListState, modifier = Modifier.weight(1f).fillMaxHeight()) {
                                if (tabsToShow.isEmpty()) { item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text("No tabs in this group", color = Color.Gray, fontSize = 14.sp) } } }
                                else { items(tabsToShow) { tab: TabState -> val isCurrent = tab == currentTab; Surface(Modifier.fillMaxWidth().clickable { currentTabIndex = tabs.indexOf(tab); showTabManager = false }, color = if (isCurrent) Color.White else Color.Transparent) { Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(tab.title, color = if (isCurrent) Color.Black else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp); Text(tab.url, color = if (isCurrent) Color.DarkGray else Color.Gray.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp) }; IconButton({ val idx = tabs.indexOf(tab); removeTab(idx); selectedDomain = if (currentTabIndex == -1) "" else getDomainName(tabs[currentTabIndex].url) }) { Icon(Icons.Default.Close, "Close", tint = if (isCurrent) Color.Black else Color.White, modifier = Modifier.size(18.dp)) } } } } }
                            }
                        }
                    }

                    Column(Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding()) {
                        OutlinedButton(onClick = { currentTabIndex = -1; showTabManager = false }, modifier = Modifier.fillMaxWidth(), shape = RectangleShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), border = BorderStroke(1.dp, Color.White)) { Icon(Icons.Default.Add, null, tint = Color.White); Spacer(Modifier.width(8.dp)); Text("New Tab", color = Color.White) }
                        if (selectedDomain.isNotBlank() && tabs.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp)); Row(Modifier.fillMaxWidth()) {
                                val isPinned = pinnedDomains.contains(selectedDomain)
                                OutlinedButton(onClick = { if (isPinned) pinnedDomains.remove(selectedDomain) else pinnedDomains.add(selectedDomain) }, modifier = Modifier.weight(1f), shape = RectangleShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), border = BorderStroke(1.dp, Color.White)) { Text(if (isPinned) "Unpin Group" else "Pin Group", fontSize = 13.sp) }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(onClick = { val toRemove = domainGroups[selectedDomain] ?: emptyList(); toRemove.forEach { tab: TabState -> tab.session?.setActive(false); tab.session?.close() }; tabs.removeAll(toRemove); if (tabs.isEmpty()) currentTabIndex = -1 else currentTabIndex = minOf(currentTabIndex, tabs.lastIndex); selectedDomain = if (currentTabIndex == -1) "" else getDomainName(tabs[currentTabIndex].url) }, modifier = Modifier.weight(1f), shape = RectangleShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), border = BorderStroke(1.dp, Color.White)) { Text("Delete Group", fontSize = 13.sp) }
                            }
                        }
                    }
                }
            }
        }
    }

    var urlInput by remember { mutableStateOf(TextFieldValue(currentTab.url)) }; var isUrlFocused by remember { mutableStateOf(false) }; val focusRequester = remember { FocusRequester() }
    LaunchedEffect(currentTab, currentTab.url) { if (!isUrlFocused) urlInput = TextFieldValue(currentTab.url) }

    Surface(Modifier.fillMaxSize(), color = Color(0xFF121212)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ showTabManager = true }) { Icon(Icons.Default.Tab, "Tabs", tint = Color.White) }
                val isLoading = currentTab.progress in 1..99
                OutlinedTextField(value = urlInput, onValueChange = { urlInput = it }, singleLine = true, placeholder = { Text(if (currentTab.isBlankTab) "Search or enter URL" else currentTab.url.take(50), color = Color.White.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier = Modifier.weight(1f).padding(vertical = 8.dp).background(Color(0xFF1E1E1E)).focusRequester(focusRequester).onFocusChanged { isUrlFocused = it.isFocused }.drawBehind { if (isLoading) drawRect(Color.White, size = size.copy(width = size.width * currentTab.progress / 100f)) }, textStyle = TextStyle(if (isLoading) Color.Gray else Color.White, 16.sp), shape = RectangleShape, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { val input = urlInput.text; if (input.isNotBlank()) { focusManager.clearFocus(); val uri = if (input.contains("://")) input else "https://$input"; if (currentTab.isBlankTab) createForegroundTab(uri) else currentTab.session?.loadUri(uri) } }), colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedBorderColor = Color.White, unfocusedBorderColor = Color.White, cursorColor = if (isLoading) Color.Gray else Color.White), trailingIcon = { if (isLoading) IconButton({ currentTab.session?.stop() }) { Icon(Icons.Default.Close, "Stop", tint = Color.White) } else IconButton({ urlInput = urlInput.copy(selection = TextRange(0, urlInput.text.length)); focusRequester.requestFocus() }) { Icon(Icons.Default.SelectAll, "Select all", tint = Color.White) } })
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
fun GroupChip(domain: String, isSelected: Boolean, isPinned: Boolean, tabCount: Int, onClick: () -> Unit, favicon: Bitmap?, onAppear: () -> Unit, isBlinking: Boolean = false) {
    LaunchedEffect(domain) { onAppear() }
    val blinkAlpha = if (isBlinking) { val infiniteTransition = rememberInfiniteTransition(label = "blink"); infiniteTransition.animateFloat(initialValue = 0.2f, targetValue = 0.5f, animationSpec = infiniteRepeatable(tween(400), RepeatMode.Reverse), label = "blinkAlpha") } else null
    val bgAlpha = if (isBlinking && blinkAlpha != null) blinkAlpha.value else if (isSelected) 1f else 0f
    val bg = Color.White.copy(alpha = bgAlpha)
    val fg = if (isSelected) Color.Black else Color.White
    Surface(Modifier.padding(end = 8.dp).clickable { onClick() }.border(if (isSelected) 1.dp else 0.5.dp, if (isSelected) Color.White else Color.White.copy(alpha = 0.2f), RectangleShape), color = bg) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isPinned) { Icon(Icons.Default.PushPin, "Pinned", tint = fg, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
            if (favicon != null) Image(favicon.asImageBitmap(), domain, Modifier.size(20.dp).clip(CircleShape), contentScale = ContentScale.Fit)
            else Box(Modifier.size(20.dp).clip(CircleShape).background(if (isSelected) Color.LightGray else Color.DarkGray), contentAlignment = Alignment.Center) { Text(domain.take(1).uppercase(), color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(6.dp)); Box(Modifier.background(if (isSelected) Color.LightGray else Color.DarkGray).padding(horizontal = 4.dp, vertical = 2.dp)) { Text(tabCount.toString(), color = fg, fontSize = 10.sp) }
        }
    }
}

@Composable
fun AllGroupChip(isSelected: Boolean, tabCount: Int, onClick: () -> Unit) {
    val bg = if (isSelected) Color.White else Color.Transparent; val fg = if (isSelected) Color.Black else Color.White
    Surface(Modifier.padding(vertical = 4.dp).width(52.dp).clickable { onClick() }.border(0.5.dp, if (isSelected) Color.White else Color.White.copy(alpha = 0.2f), RectangleShape), color = bg) {
        Column(Modifier.padding(6.dp).width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("All", color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(2.dp)); Box(Modifier.background(if (isSelected) Color.LightGray else Color.DarkGray).padding(horizontal = 4.dp, vertical = 1.dp)) { Text(tabCount.toString(), color = fg, fontSize = 9.sp) } }
    }
}

@Composable
fun SidebarGroupChip(domain: String, isSelected: Boolean, tabCount: Int, onClick: () -> Unit, favicon: Bitmap?, onAppear: () -> Unit, isBlinking: Boolean = false) {
    LaunchedEffect(domain) { onAppear() }
    val blinkAlpha = if (isBlinking) { val infiniteTransition = rememberInfiniteTransition(label = "blink"); infiniteTransition.animateFloat(initialValue = 0.2f, targetValue = 0.5f, animationSpec = infiniteRepeatable(tween(400), RepeatMode.Reverse), label = "blinkAlpha") } else null
    val bgAlpha = if (isBlinking && blinkAlpha != null) blinkAlpha.value else if (isSelected) 1f else 0f
    val bg = Color.White.copy(alpha = bgAlpha)
    val fg = if (isSelected) Color.Black else Color.White
    Surface(Modifier.padding(vertical = 4.dp).width(52.dp).clickable { onClick() }.border(0.5.dp, if (isSelected) Color.White else Color.White.copy(alpha = 0.2f), RectangleShape), color = bg) {
        Column(Modifier.padding(6.dp).width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (favicon != null) Image(favicon.asImageBitmap(), domain, Modifier.size(24.dp).clip(CircleShape), contentScale = ContentScale.Fit)
            else Box(Modifier.size(24.dp).clip(CircleShape).background(if (isSelected) Color.LightGray else Color.DarkGray), contentAlignment = Alignment.Center) { Text(domain.take(1).uppercase(), color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(2.dp)); Box(Modifier.background(if (isSelected) Color.LightGray else Color.DarkGray).padding(horizontal = 4.dp, vertical = 1.dp)) { Text(tabCount.toString(), color = fg, fontSize = 9.sp) }
        }
    }
}

@Composable
fun ContextMenuItem(text: String, onClick: () -> Unit) { Box(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) { Text(text, color = Color.White, fontSize = 16.sp) } }

// ── Dialogs ──────────────────────────────────────────────────────
@Composable
fun ScriptingDialog(initialScript: String, onDismiss: () -> Unit, onSaveAndRun: (String) -> Unit) {
    var script by remember { mutableStateOf(initialScript) }
    AlertDialog(onDismiss, { TextButton({ onSaveAndRun(script) }) { Text("Run", Color.White) } }, { TextButton(onDismiss) { Text("Close", Color.White) } }, title = { Text("Inject JavaScript", color = Color.White, fontSize = 20.sp) }, text = { Column { Text("Write or paste JS below. It will execute immediately on the current tab.", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp)); OutlinedTextField(script, { script = it }, Modifier.fillMaxWidth().height(200.dp), TextStyle(Color.White, 14.sp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Color.White, cursorColor = Color.White, focusedContainerColor = Color(0xFF1E1E1E), unfocusedContainerColor = Color(0xFF1E1E1E)), shape = RectangleShape) } }, containerColor = Color(0xFF1E1E1E), titleContentColor = Color.White, textContentColor = Color.White, shape = RectangleShape, tonalElevation = 0.dp)
}

@Composable
fun SettingsDialog(prefs: SharedPreferences, onDismiss: () -> Unit, onSettingsApplied: () -> Unit) {
    var jsEnabled by remember { mutableStateOf(prefs.getBoolean("javascript.enabled", true)) }; var trackingProtection by remember { mutableStateOf(prefs.getBoolean("privacy.trackingprotection.enabled", false)) }
    var cookieBehavior by remember { mutableIntStateOf(prefs.getInt("network.cookie.cookieBehavior", 0)) }; var autoplayMedia by remember { mutableStateOf(prefs.getBoolean("media.autoplay.enabled", true)) }
    AlertDialog(onDismiss, { TextButton({ prefs.edit().putBoolean("javascript.enabled", jsEnabled).putBoolean("privacy.trackingprotection.enabled", trackingProtection).putInt("network.cookie.cookieBehavior", cookieBehavior).putBoolean("media.autoplay.enabled", autoplayMedia).apply(); onSettingsApplied(); onDismiss() }) { Text("OK", Color.White) } }, { TextButton(onDismiss) { Text("Cancel", Color.White) } }, title = { Text("Settings", color = Color.White, fontSize = 20.sp) }, text = { Column { SettingSwitch("JavaScript", jsEnabled) { jsEnabled = it }; SettingSwitch("Tracking Protection", trackingProtection) { trackingProtection = it }; Text("Cookie Behavior", Color.White, 14.sp); CookieBehaviorSelector(cookieBehavior) { cookieBehavior = it }; SettingSwitch("Autoplay Media", autoplayMedia) { autoplayMedia = it } } }, containerColor = Color(0xFF1E1E1E), titleContentColor = Color.White, textContentColor = Color.White, shape = RectangleShape, tonalElevation = 0.dp)
}

@Composable
fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text(label, Color.White, 14.sp); Switch(checked, onChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF444444), uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF444444))) } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookieBehaviorSelector(current: Int, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }; val options = listOf("Accept all" to 0, "Reject all" to 1, "Only from visited" to 2); val selectedText = options.firstOrNull { it.second == current }?.first ?: "Accept all"
    Box { OutlinedTextField(selectedText, {}, true, trailingIcon = { Icon(Icons.Default.Close, null, tint = Color.White) }, Modifier.fillMaxWidth().padding(vertical = 4.dp), TextStyle(Color.White, 14.sp), shape = RectangleShape, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Color.White, focusedContainerColor = Color(0xFF1E1E1E), unfocusedContainerColor = Color(0xFF1E1E1E))); Box(Modifier.matchParentSize().clickable { expanded = true })
        DropdownMenu(expanded, { expanded = false }, Modifier.border(1.dp, Color.White, RectangleShape), containerColor = Color(0xFF1E1E1E), shape = RectangleShape) { options.forEach { (label, value) -> DropdownMenuItem({ Text(label, Color.White) }, { onChange(value); expanded = false }) } } }
}
