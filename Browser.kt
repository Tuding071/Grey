// ═══════════════════════════════════════════════════════════════════
// Grey Browser - V1.6 (Downloader + Video Downloader)
// ═══════════════════════════════════════════════════════════════════
// === PART 1/2 ===

package com.grey.browser

// ═══════════════════════════════════════════════════════════════════
// GECKOVIEW API NOTE:
// - ACTIVE: session.setActive(true). Fully rendered, network active.
// - WARM:   session.setActive(false). Paused in RAM, instant resume.
// - COLD:   session = null. Discarded from RAM, restores on click.
// - Network sniffing via onLoadRequest for video detection (no JS injection).
// ═══════════════════════════════════════════════════════════════════
// VERSION HISTORY:
// V1.4 - Scripts auto-inject on page load. Removed Quick Script, play button,
//        Select All, Copy buttons. Added delete confirmation for scripts.
// V1.5 - Tab lifecycle: 1 ACTIVE + max 9 WARM + unlimited COLD
// V1.6 - Downloader core: queue with manual start, one-at-a-time.
//        Video detection via network sniffing (onLoadRequest), no URL blink.
//        M3U8: download segments, merge to .ts file.
//        Copy link restored. Group delete fix.
// ═══════════════════════════════════════════════════════════════════

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GreyBrowser() }
    }
    override fun onPause() { super.onPause(); saveTabs(this) }
    override fun onDestroy() { super.onDestroy(); saveTabs(this) }
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
            conn.connectTimeout = 4000; conn.readTimeout = 4000; conn.connect()
            if (conn.responseCode == 200) {
                val b = BitmapFactory.decodeStream(conn.inputStream)
                conn.inputStream.close(); conn.disconnect()
                b
            } else { conn.disconnect(); null }
        } catch (e: Exception) { null }
    }
    
    suspend fun downloadAndCacheFavicon(context: Context, domain: String): Bitmap? = withContext(Dispatchers.IO) {
        val sources = listOf(
            "https://www.google.com/s2/favicons?domain=$domain&sz=64",
            "https://$domain/favicon.ico",
            "https://$domain/favicon.png"
        )
        var bitmap: Bitmap? = null
        for (src in sources) { bitmap = tryDownload(src); if (bitmap != null) break }
        if (bitmap != null) {
            FileOutputStream(getFaviconFile(context, domain)).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            val meta = loadMeta(context)
            meta.removeAll { it.domain == domain }
            meta.add(FaviconMeta(domain, System.currentTimeMillis()))
            if (meta.size > MAX_FAVICONS) {
                val oldest = meta.minByOrNull { it.lastAccessed }
                if (oldest != null) { meta.remove(oldest); getFaviconFile(context, oldest.domain).delete() }
            }
            saveMeta(context, meta)
        }
        bitmap
    }
}

private const val PREFS_NAME = "browser_tabs"
private const val KEY_TABS = "saved_tabs"
private const val KEY_PINNED = "pinned_domains"
private const val KEY_LAST_ACTIVE_URL = "last_active_url"
private const val KEY_SCRIPTS = "saved_scripts"

fun saveTabs(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply()
}

fun saveTabsData(context: Context, tabs: List<TabState>, pinnedDomains: List<String>, lastActiveUrl: String) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val tabsArray = JSONArray()
    for (tab in tabs) {
        if (!tab.isBlankTab) {
            val obj = JSONObject()
            obj.put("url", tab.url)
            obj.put("title", tab.title)
            tabsArray.put(obj)
        }
    }
    prefs.edit().putString(KEY_TABS, tabsArray.toString()).putString(KEY_LAST_ACTIVE_URL, lastActiveUrl).apply()
    val pinnedArray = JSONArray()
    for (d in pinnedDomains) pinnedArray.put(d)
    prefs.edit().putString(KEY_PINNED, pinnedArray.toString()).apply()
}

fun loadTabsData(context: Context): Triple<List<Pair<String, String>>, List<String>, String> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val tabsList = mutableListOf<Pair<String, String>>()
    prefs.getString(KEY_TABS, null)?.let { json ->
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                tabsList.add(Pair(o.getString("url"), o.optString("title", o.getString("url"))))
            }
        } catch (e: Exception) { }
    }
    val pinnedList = mutableListOf<String>()
    prefs.getString(KEY_PINNED, null)?.let { json ->
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) pinnedList.add(arr.getString(i))
        } catch (e: Exception) { }
    }
    val lastActiveUrl = prefs.getString(KEY_LAST_ACTIVE_URL, "") ?: ""
    return Triple(tabsList, pinnedList, lastActiveUrl)
}

data class ScriptItem(val id: String, val name: String, val code: String, val enabled: Boolean)

// ── Download System ───────────────────────────────────────────────────
enum class DownloadState { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED }

data class DownloadItem(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val fileName: String,
    val fileSize: Long = 0L,
    var downloadedBytes: Long = 0L,
    var state: DownloadState = DownloadState.QUEUED,
    var progress: Int = 0,
    var speed: String = "",
    val referer: String = "",
    val cookies: String = "",
    var isVideo: Boolean = false,
    var tempFile: File? = null
)

fun saveScripts(context: Context, scripts: List<ScriptItem>) {
    val arr = JSONArray()
    for (s in scripts) {
        val obj = JSONObject()
        obj.put("id", s.id); obj.put("name", s.name)
        obj.put("code", s.code); obj.put("enabled", s.enabled)
        arr.put(obj)
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_SCRIPTS, arr.toString()).apply()
}

fun loadScripts(context: Context): List<ScriptItem> {
    val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_SCRIPTS, null) ?: return emptyList()
    return try {
        val arr = JSONArray(json)
        mutableListOf<ScriptItem>().apply {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(ScriptItem(o.getString("id"), o.getString("name"), o.getString("code"), o.getBoolean("enabled")))
            }
        }
    } catch (e: Exception) { emptyList() }
}

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
const val MAX_WARM_TABS = 9
const val UNDO_DELAY_MS = 3000L

fun resolveUrl(input: String): String {
    if (input.isBlank()) return "about:blank"
    if (input.contains("://") || (input.contains(".") && !input.contains(" "))) {
        return if (input.contains("://")) input else "https://$input"
    }
    return "https://www.google.com/search?q=${Uri.encode(input)}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GreyBrowser() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val activity = context as? ComponentActivity
    val focusManager = LocalFocusManager.current
    val runtime = remember { GeckoRuntime.create(context) }
    val prefs = remember {
        context.getSharedPreferences("browser_settings", Context.MODE_PRIVATE)
    }
    val scope = rememberCoroutineScope()

    val scripts = remember {
        mutableStateListOf<ScriptItem>().apply { addAll(loadScripts(context)) }
    }

    // ── Download State ────────────────────────────────────────────────
    val activeDownloads = remember { mutableStateListOf<DownloadItem>() }
    var currentDownloadId by remember { mutableStateOf<String?>(null) }
    var showDownloadManager by remember { mutableStateOf(false) }
    val detectedVideoUrls = remember { mutableStateListOf<Pair<String, String>>() }

    val applySettingsToSession: (GeckoSession) -> Unit = { session ->
        session.settings.allowJavascript = prefs.getBoolean("javascript.enabled", true)
        session.settings.useTrackingProtection = prefs.getBoolean("privacy.trackingprotection.enabled", false)
        session.settings.suspendMediaWhenInactive = true
    }

    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuUri by remember { mutableStateOf<String?>(null) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var confirmAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmTitle by remember { mutableStateOf("") }
    var confirmMessage by remember { mutableStateOf("") }
    var showScriptManager by remember { mutableStateOf(false) }

    val injectScripts: (GeckoSession) -> Unit = { s ->
        for (script in scripts) { 
            if (script.enabled && script.code.isNotBlank()) {
                s.loadUri("javascript:" + Uri.encode("(function(){\n${script.code}\n})();")) 
            }
        }
    }

    val setupDelegates = fun(tabState: TabState) {
        val session = tabState.session ?: return
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(s: GeckoSession, url: String) {
                tabState.url = url; tabState.progress = 5
                tabState.lastUpdated = System.currentTimeMillis()
                if (url != "about:blank") {
                    tabState.isBlankTab = false
                    injectScripts(s)
                }
            }
            override fun onPageStop(s: GeckoSession, success: Boolean) {
                tabState.progress = 100; tabState.lastUpdated = System.currentTimeMillis()
            }
            override fun onProgressChange(s: GeckoSession, progress: Int) {
                tabState.progress = progress
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(s: GeckoSession, title: String?) {
                if (!tabState.isBlankTab) tabState.title = title ?: tabState.url
            }
            override fun onContextMenu(
                session: GeckoSession, screenX: Int, screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement
            ) {
                if (element.linkUri != null) {
                    contextMenuUri = element.linkUri; showContextMenu = true
                }
            }
        }
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<GeckoSession.NavigationDelegate.AllowOrDeny>? {
                val url = request.uri
                if (url != null && (url.contains(".mp4") || url.contains(".webm") || 
                    url.contains(".m3u8") || url.contains(".ts") || url.contains("video") || 
                    url.contains("stream"))) {
                    val fileName = url.substringAfterLast("/").substringBefore("?")
                    if (fileName.isNotBlank() && !detectedVideoUrls.any { it.first == url }) {
                        detectedVideoUrls.add(Pair(url, tabState.url))
                    }
                }
                val allow: GeckoSession.NavigationDelegate.AllowOrDeny = GeckoSession.NavigationDelegate.AllowOrDeny.ALLOW
                return GeckoResult.fromValue(allow)
            }
            
            override fun onLocationChange(
                session: GeckoSession, url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                url?.let { newUrl ->
                    tabState.url = newUrl
                    if (newUrl != "about:blank") tabState.isBlankTab = false
                }
            }
        }
    }

    val homeSession = remember {
        GeckoSession().apply {
            applySettingsToSession(this); open(runtime); loadUri("about:blank")
        }
    }
    val homeTab = remember {
        TabState().apply {
            session = homeSession; isBlankTab = true; title = "Home"; setupDelegates(this)
        }
    }

    val (savedTabs, savedPinned, savedLastActiveUrl) = remember { loadTabsData(context) }
    val tabs = remember {
        mutableStateListOf<TabState>().apply {
            for ((url, title) in savedTabs) {
                add(TabState().apply {
                    this.url = url; this.title = title; isBlankTab = false
                    isDiscarded = true; session = null
                })
            }
        }
    }

    var currentTabIndex by remember { mutableIntStateOf(-1) }
    var highlightedTabIndex by remember {
        mutableIntStateOf(
            tabs.indexOfFirst { it.url == savedLastActiveUrl }.let { if (it >= 0) it else -1 }
        )
    }
    var lastActiveUrl by remember { mutableStateOf(savedLastActiveUrl) }

    var showTabManager by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val pinnedDomains = remember {
        mutableStateListOf<String>().apply { addAll(savedPinned) }
    }
    var selectedDomain by remember { mutableStateOf("") }
    val faviconBitmaps = remember { mutableStateMapOf<String, Bitmap?>() }
    val faviconLoading = remember { mutableStateMapOf<String, Boolean>() }
    val tabFavicons = remember { mutableStateMapOf<String, Bitmap?>() }
    val tabFaviconLoading = remember { mutableStateMapOf<String, Boolean>() }
    val currentTab =
        if (currentTabIndex == -1) homeTab else tabs.getOrNull(currentTabIndex) ?: homeTab

    val pendingDeletions = remember { mutableStateMapOf<Int, Long>() }
    var showBlink by remember { mutableStateOf(false) }
    val blinkTargetDomain = remember { mutableStateOf("") }

    LaunchedEffect(tabs.toList(), pinnedDomains.toList(), lastActiveUrl) {
        saveTabsData(context, tabs, pinnedDomains, lastActiveUrl)
    }
    LaunchedEffect(tabs.map { "${it.url}|${it.title}" }.joinToString()) {
        saveTabsData(context, tabs, pinnedDomains, lastActiveUrl)
    }
    LaunchedEffect(scripts.toList()) { saveScripts(context, scripts) }

    LaunchedEffect(currentTabIndex) {
        if (currentTabIndex >= 0 && currentTabIndex < tabs.size) {
            lastActiveUrl = tabs[currentTabIndex].url
            highlightedTabIndex = currentTabIndex
        }
    }

    LaunchedEffect(currentTabIndex) {
        detectedVideoUrls.clear()
    }

    fun manageTabLifecycle(activeIndex: Int) {
        tabs.forEachIndexed { i, ts ->
            if (i == activeIndex && ts.session == null && ts.isDiscarded) {
                val s = GeckoSession().apply {
                    applySettingsToSession(this); open(runtime); loadUri(ts.url)
                }
                ts.session = s; ts.isDiscarded = false; setupDelegates(ts)
                ts.lastUpdated = System.currentTimeMillis()
            }
        }
        tabs.forEachIndexed { i, ts -> ts.session?.setActive(i == activeIndex) }
        val warmTabs = tabs.filter { 
            val idx = tabs.indexOf(it)
            !it.isDiscarded && !it.isBlankTab && it.session != null && idx != activeIndex 
        }
        if (warmTabs.size > MAX_WARM_TABS) {
            val toMakeCold = warmTabs.sortedBy { it.lastUpdated }.take(warmTabs.size - MAX_WARM_TABS)
            for (tab in toMakeCold) {
                tab.session?.setActive(false); tab.session?.close()
                tab.session = null; tab.isDiscarded = true; tab.progress = 100
            }
        }
    }

    fun restoreTab(tab: TabState) {
        if (tab.isDiscarded && tab.session == null) {
            val s = GeckoSession().apply {
                applySettingsToSession(this); open(runtime); setActive(false); loadUri(tab.url)
            }
            tab.session = s; tab.isDiscarded = false; setupDelegates(tab)
            tab.lastUpdated = System.currentTimeMillis()
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
                tab.session?.setActive(false); tab.session?.close()
                tabs.removeAt(index)
                if (tabs.isEmpty()) currentTabIndex = -1
                else if (currentTabIndex > index) currentTabIndex--
                else if (currentTabIndex == index && tabs.isNotEmpty())
                    currentTabIndex = minOf(currentTabIndex, tabs.lastIndex)
                if (highlightedTabIndex == index) highlightedTabIndex = -1
                else if (highlightedTabIndex > index) highlightedTabIndex--
                if (selectedDomain.isNotBlank()) {
                    val dg = tabs.groupBy { getDomainName(it.url) }.filter { it.key.isNotBlank() }
                    if (!dg.containsKey(selectedDomain)) selectedDomain = ""
                }
            }
        }
    }

    fun loadFavicon(domain: String) {
        if (!faviconBitmaps.containsKey(domain) && faviconLoading[domain] != true) {
            faviconLoading[domain] = true
            scope.launch {
                faviconBitmaps[domain] = FaviconCache.getFaviconBitmap(context, domain)
                    ?: FaviconCache.downloadAndCacheFavicon(context, domain)
                faviconLoading[domain] = false
            }
        }
    }

    fun loadTabFavicon(domain: String) {
        if (domain.isNotBlank() && !tabFavicons.containsKey(domain) && tabFaviconLoading[domain] != true) {
            tabFaviconLoading[domain] = true
            scope.launch {
                tabFavicons[domain] = FaviconCache.getFaviconBitmap(context, domain)
                    ?: FaviconCache.downloadAndCacheFavicon(context, domain)
                tabFaviconLoading[domain] = false
            }
        }
    }

    LaunchedEffect(showTabManager, currentTabIndex) {
        if (showTabManager) {
            homeSession.setActive(false); tabs.forEach { it.session?.setActive(false) }
        } else {
            homeSession.setActive(currentTabIndex == -1)
            if (currentTabIndex >= 0) manageTabLifecycle(currentTabIndex)
        }
    }

    fun createBackgroundTab(url: String) {
        val s = GeckoSession().apply {
            applySettingsToSession(this); open(runtime); loadUri(url); setActive(false)
        }
        tabs.add(TabState().apply {
            session = s; this.url = url; isBlankTab = false; isDiscarded = false
            lastUpdated = System.currentTimeMillis(); setupDelegates(this)
        })
        manageTabLifecycle(currentTabIndex)
    }

    fun createForegroundTab(url: String) {
        val s = GeckoSession().apply {
            applySettingsToSession(this); open(runtime); loadUri(url)
        }
        tabs.add(TabState().apply {
            session = s; isBlankTab = false; isDiscarded = false
            lastUpdated = System.currentTimeMillis(); setupDelegates(this)
        })
        currentTabIndex = tabs.lastIndex; manageTabLifecycle(currentTabIndex)
    }

    fun requestDeleteTab(index: Int) {
        if (index >= 0 && index < tabs.size) pendingDeletions[index] = System.currentTimeMillis()
    }

    fun undoDeleteTab(index: Int) { pendingDeletions.remove(index) }

    // ═══════════════════════════════════════════════════════════════
    // ── DOWNLOAD HELPER FUNCTIONS ─────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    fun getDownloadDir(): File {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Grey")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun addDownload(item: DownloadItem) { activeDownloads.add(item) }

    fun downloadM3U8(item: DownloadItem) {
        item.state = DownloadState.DOWNLOADING
        currentDownloadId = item.id
        
        thread(name = "m3u8-${item.id}") {
            try {
                val url = URL(item.url)
                val conn = url.openConnection() as HttpURLConnection
                if (item.referer.isNotBlank()) conn.setRequestProperty("Referer", item.referer)
                conn.connect()
                val playlist = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                
                val baseUrl = item.url.substringBeforeLast("/")
                val segments = playlist.lines()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .map { seg -> if (seg.startsWith("http")) seg.trim() else "$baseUrl/${seg.trim()}" }
                
                if (segments.isEmpty()) {
                    item.state = DownloadState.FAILED
                    return@thread
                }
                
                val tempDir = File(context.cacheDir, "m3u8_${item.id}")
                tempDir.mkdirs()
                val segmentFiles = mutableListOf<File>()
                
                for ((i, segUrl) in segments.withIndex()) {
                    if (item.state == DownloadState.PAUSED) {
                        while (item.state == DownloadState.PAUSED) { Thread.sleep(500) }
                    }
                    val segFile = File(tempDir, "seg_${i.toString().padStart(5, '0')}.ts")
                    try {
                        val segConn = URL(segUrl).openConnection() as HttpURLConnection
                        segConn.connectTimeout = 10000; segConn.readTimeout = 10000
                        if (item.referer.isNotBlank()) segConn.setRequestProperty("Referer", item.referer)
                        segConn.inputStream.use { input ->
                            segFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        segmentFiles.add(segFile)
                        item.progress = ((i + 1) * 100) / segments.size
                    } catch (e: Exception) { }
                }
                
                if (segmentFiles.isEmpty()) {
                    item.state = DownloadState.FAILED
                    tempDir.deleteRecursively()
                    return@thread
                }
                
                val outputFile = File(getDownloadDir(), item.fileName.replace(".m3u8", ".ts"))
                FileOutputStream(outputFile).use { out ->
                    for (seg in segmentFiles) {
                        seg.inputStream().use { it.copyTo(out) }
                    }
                }
                
                item.tempFile = outputFile
                item.state = DownloadState.COMPLETED
                item.progress = 100
                tempDir.deleteRecursively()
            } catch (e: Exception) {
                if (item.state != DownloadState.PAUSED) item.state = DownloadState.FAILED
            } finally {
                if (currentDownloadId == item.id) currentDownloadId = null
            }
        }
    }

    fun startDownload(id: String) {
        val item = activeDownloads.find { it.id == id } ?: return
        if (currentDownloadId != null && currentDownloadId != id) return
        
        if (item.url.contains(".m3u8")) {
            downloadM3U8(item)
            return
        }
        
        item.state = DownloadState.DOWNLOADING
        currentDownloadId = id
        
        thread(name = "download-$id") {
            try {
                val url = URL(item.url)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000; conn.readTimeout = 30000
                if (item.referer.isNotBlank()) conn.setRequestProperty("Referer", item.referer)
                if (item.cookies.isNotBlank()) conn.setRequestProperty("Cookie", item.cookies)
                conn.connect()
                
                val totalSize = conn.contentLength.toLong()
                val input = BufferedInputStream(conn.inputStream)
                val outputFile = File(getDownloadDir(), item.fileName)
                item.tempFile = outputFile
                val output = FileOutputStream(outputFile)
                
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead = 0L
                val startTime = System.currentTimeMillis()
                
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (item.state == DownloadState.PAUSED) {
                        while (item.state == DownloadState.PAUSED) { Thread.sleep(500) }
                        if (item.state == DownloadState.FAILED) break
                    }
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                    val speedBps = if (elapsed > 0) totalRead / elapsed else 0f
                    item.downloadedBytes = totalRead
                    item.progress = if (totalSize > 0) ((totalRead * 100) / totalSize).toInt() else 0
                    item.speed = when {
                        speedBps > 1_000_000 -> "%.1f MB/s".format(speedBps / 1_000_000)
                        speedBps > 1_000 -> "%.0f KB/s".format(speedBps / 1_000)
                        else -> "%.0f B/s".format(speedBps)
                    }
                }
                input.close(); output.close(); conn.disconnect()
                if (item.state != DownloadState.PAUSED) { item.state = DownloadState.COMPLETED; item.progress = 100 }
            } catch (e: Exception) { if (item.state != DownloadState.PAUSED) item.state = DownloadState.FAILED
            } finally { if (currentDownloadId == id) currentDownloadId = null }
        }
    }

    fun pauseDownload(id: String) {
        activeDownloads.find { it.id == id }?.state = DownloadState.PAUSED
    }

    fun resumeDownload(id: String) {
        val item = activeDownloads.find { it.id == id } ?: return
        item.state = DownloadState.DOWNLOADING
    }

    fun deleteDownload(id: String) {
        val item = activeDownloads.find { it.id == id } ?: return
        item.tempFile?.delete(); activeDownloads.remove(item)
        if (currentDownloadId == id) currentDownloadId = null
    }

    BackHandler {
        if (currentTab.isBlankTab) { if (tabs.isNotEmpty()) currentTabIndex = tabs.lastIndex else activity?.finish() } else currentTab.session?.goBack()
    }

    @Composable
    fun GeckoViewBox() {
        val s = currentTab.session
        if (s != null) {
            AndroidView(factory = { ctx -> GeckoView(ctx).apply { setSession(s) } }, update = { gv -> gv.setSession(s) }, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(Color(0xFF121212)), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
        }
    }

    if (showConfirmDialog) {
        AlertDialog(onDismissRequest = { showConfirmDialog = false; confirmAction = null }, title = { Text(confirmTitle, color = Color.White, fontSize = 18.sp) }, text = { Text(confirmMessage, color = Color.Gray, fontSize = 14.sp) }, confirmButton = { TextButton({ val a = confirmAction; showConfirmDialog = false; confirmAction = null; a?.invoke() }) { Text("Confirm", color = Color.White) } }, dismissButton = { TextButton({ showConfirmDialog = false; confirmAction = null }) { Text("Cancel", color = Color.White) } }, containerColor = Color(0xFF1E1E1E), titleContentColor = Color.White, textContentColor = Color.White, shape = RectangleShape, tonalElevation = 0.dp)
    }

    if (showSettings) {
        SettingsDialog(prefs, { showSettings = false }) {
            applySettingsToSession(homeSession); tabs.forEach { it.session?.let { s -> applySettingsToSession(s) } }
        }
    }

    if (showScriptManager) {
        ScriptManager(scripts = scripts, onDismiss = { showScriptManager = false })
    }

    if (showDownloadManager) {
        DownloadManagerUI(
            downloads = activeDownloads,
            onDismiss = { showDownloadManager = false },
            onStart = { startDownload(it) },
            onPause = { pauseDownload(it) },
            onResume = { resumeDownload(it) },
            onDelete = { deleteDownload(it) }
        )
    }

    if (showContextMenu && contextMenuUri != null) {
        val clipMgr = clipboardManager
        val ctxUri = contextMenuUri
        Popup(alignment = Alignment.Center, onDismissRequest = { showContextMenu = false }, properties = PopupProperties(focusable = true)) {
            Column(Modifier.border(1.dp, Color.White, RectangleShape).background(Color(0xFF1E1E1E)).width(IntrinsicSize.Max)) {
                ContextMenuItem("New Tab") { createForegroundTab(ctxUri!!); showContextMenu = false }
                ContextMenuItem("Open in Background") { createBackgroundTab(ctxUri!!); showContextMenu = false }
                ContextMenuItem("Copy link") { clipMgr.setText(AnnotatedString(ctxUri!!)); showContextMenu = false }
                val uri = ctxUri ?: ""
                if (uri.contains(".mp4") || uri.contains(".webm") || uri.contains(".m3u8") || uri.contains("video") || uri.contains("stream")) {
                    ContextMenuItem("Download Video") {
                        val fileName = uri.substringAfterLast("/").substringBefore("?")
                        addDownload(DownloadItem(url = uri, fileName = if (fileName.contains(".")) fileName else "video_${System.currentTimeMillis()}.mp4", isVideo = true, referer = currentTab.url))
                        showContextMenu = false
                    }
                }
                val pageVideos = detectedVideoUrls.filter { it.second == currentTab.url }
                for ((videoUrl, _) in pageVideos.take(3)) {
                    val shortName = videoUrl.substringAfterLast("/").substringBefore("?").take(40)
                    ContextMenuItem("Sniffed: $shortName") {
                        addDownload(DownloadItem(url = videoUrl, fileName = shortName.ifBlank { "video_${System.currentTimeMillis()}.mp4" }, isVideo = true, referer = currentTab.url))
                        showContextMenu = false
                    }
                }
            }
        }
    }

    if (showTabManager) {
        val domainGroups = tabs.groupBy { getDomainName(it.url) }.filter { it.key.isNotBlank() }
        val sortedDomains = domainGroups.keys.sortedWith(compareByDescending<String> { pinnedDomains.contains(it) }.thenBy { d: String -> domainGroups[d]?.firstOrNull()?.let { t -> tabs.indexOf(t) } ?: Int.MAX_VALUE })
        val pinnedSorted = sortedDomains.filter { pinnedDomains.contains(it) }
        val unpinnedSorted = sortedDomains.filter { !pinnedDomains.contains(it) }
        val highlightDomain = if (currentTab.isBlankTab && highlightedTabIndex >= 0) getDomainName(tabs[highlightedTabIndex].url) else if (!currentTab.isBlankTab) getDomainName(currentTab.url) else ""

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

                                        Surface(Modifier.fillMaxWidth().clickable(enabled = !isPending) { currentTabIndex = tabIndex; showTabManager = false }.border(0.5.dp, Color.DarkGray, RectangleShape), color = if (isPending) Color.Red.copy(alpha = 0.3f) else if (isHighlighted) Color.White else Color.Transparent) {
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
                            val hasSelection = selectedDomain.isNotBlank()
                            val isPinned = pinnedDomains.contains(selectedDomain)
                            val faintColor = Color.White.copy(alpha = 0.3f); val activeColor = Color.White
                            OutlinedButton(onClick = { if (hasSelection) { confirmTitle = if (isPinned) "Unpin Group?" else "Pin Group?"; confirmMessage = "Are you sure?"; confirmAction = { if (isPinned) pinnedDomains.remove(selectedDomain) else pinnedDomains.add(selectedDomain) }; showConfirmDialog = true } }, modifier = Modifier.weight(1f), shape = RectangleShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = if (hasSelection) activeColor else faintColor), border = BorderStroke(1.dp, if (hasSelection) activeColor else faintColor)) {
                                Text(if (isPinned) "Unpin Group" else "Pin Group", fontSize = 13.sp, color = if (hasSelection) activeColor else faintColor)
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { if (hasSelection) { confirmTitle = "Delete Group?"; confirmMessage = "All tabs in this group will be lost."; confirmAction = { 
                                val toRemove = (domainGroups[selectedDomain] ?: emptyList()).toList()
                                toRemove.forEach { it.session?.setActive(false); it.session?.close() }
                                tabs.removeAll(toRemove)
                                if (tabs.isEmpty()) { currentTabIndex = -1; selectedDomain = "" }
                                else { currentTabIndex = currentTabIndex.coerceIn(0, tabs.lastIndex); selectedDomain = "" }
                            }; showConfirmDialog = true } }, modifier = Modifier.weight(1f), shape = RectangleShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = if (hasSelection) activeColor else faintColor), border = BorderStroke(1.dp, if (hasSelection) activeColor else faintColor)) {
                                Text("Delete Group", fontSize = 13.sp, color = if (hasSelection) activeColor else faintColor)
                            }
                        }
                    }
                }
            }
        }
    }

    var urlInput by remember { mutableStateOf(TextFieldValue(if (currentTab.url == "about:blank") "" else currentTab.url)) }
    var isUrlFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(currentTab, currentTab.url) { if (!isUrlFocused) { urlInput = TextFieldValue(if (currentTab.url == "about:blank") "" else currentTab.url) } }

    Surface(Modifier.fillMaxSize(), color = Color(0xFF121212)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ showTabManager = true }) { Icon(Icons.Default.Tab, "Tabs", tint = Color.White) }
                val isLoading = currentTab.progress in 1..99
                OutlinedTextField(
                    value = urlInput, onValueChange = { urlInput = it }, singleLine = true,
                    placeholder = { Text(if (currentTab.isBlankTab) "Search or enter URL" else currentTab.url.take(50), color = Color.White.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp).background(Color(0xFF1E1E1E)).focusRequester(focusRequester).onFocusChanged { isUrlFocused = it.isFocused }.drawBehind { if (isLoading) drawRect(Color.White, size = size.copy(width = size.width * currentTab.progress / 100f)) },
                    textStyle = TextStyle(if (isLoading) Color.Gray else Color.White, 16.sp), shape = RectangleShape,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { val input = urlInput.text; if (input.isNotBlank()) { focusManager.clearFocus(); val uri = resolveUrl(input); if (currentTab.isBlankTab) createForegroundTab(uri) else currentTab.session?.loadUri(uri) } }),
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedBorderColor = Color.White, unfocusedBorderColor = Color.White, cursorColor = if (isLoading) Color.Gray else Color.White),
                    trailingIcon = { if (isLoading) IconButton({ currentTab.session?.stop() }) { Icon(Icons.Default.Close, "Stop", tint = Color.White) } else IconButton({ urlInput = urlInput.copy(selection = TextRange(0, urlInput.text.length)); focusRequester.requestFocus() }) { Icon(Icons.Default.SelectAll, "Select all", tint = Color.White) } }
                )
                IconButton({ currentTabIndex = -1 }) { Icon(Icons.Default.Add, "New Tab", tint = Color.White) }
                Box {
                    IconButton({ showMenu = true }) { Icon(Icons.Default.MoreVert, "Menu", tint = Color.White) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.border(1.dp, Color.White, RectangleShape), containerColor = Color(0xFF1E1E1E), shape = RectangleShape) {
                        DropdownMenuItem(text = { Text("Downloads", color = Color.White) }, onClick = { showMenu = false; showDownloadManager = true })
                        DropdownMenuItem(text = { Text("Script Manager", color = Color.White) }, onClick = { showMenu = false; showScriptManager = true })
                        DropdownMenuItem(text = { Text("Settings", color = Color.White) }, onClick = { showMenu = false; showSettings = true })
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { GeckoViewBox() }
        }
    }
}

// END OF PART 1/2


// ═══════════════════════════════════════════════════════════════════
// Grey Browser - V1.6 (Downloader + Video Downloader)
// ═══════════════════════════════════════════════════════════════════
// === PART 2/2 ===

// ═══════════════════════════════════════════════════════════════════
// ── DOWNLOAD MANAGER UI ──────────────────────────────────────────
// ═══════════════════════════════════════════════════════════════════

@Composable
fun DownloadManagerUI(
    downloads: List<DownloadItem>,
    onDismiss: () -> Unit,
    onStart: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    Popup(
        alignment = Alignment.TopStart,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false)
    ) {
        Surface(
            Modifier.fillMaxSize().statusBarsPadding().background(Color(0xFF1E1E1E)),
            color = Color(0xFF1E1E1E)
        ) {
            Column(Modifier.fillMaxSize()) {
                // Header
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton({ onDismiss() }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, "Close", tint = Color.White)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("Downloads", color = Color.White, fontSize = 18.sp)
                    if (downloads.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text("(${downloads.size})", color = Color.Gray, fontSize = 14.sp)
                    }
                }

                // Content
                if (downloads.isEmpty()) {
                    Box(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No downloads", color = Color.Gray, fontSize = 16.sp)
                    }
                } else {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)
                    ) {
                        items(downloads) { item ->
                            Surface(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                    .border(0.5.dp, Color.DarkGray, RectangleShape),
                                color = Color.Transparent
                            ) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    // File name
                                    Text(
                                        item.fileName,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(4.dp))

                                    // State-specific UI
                                    when (item.state) {
                                        DownloadState.QUEUED -> {
                                            Text("Queued", color = Color.Gray, fontSize = 12.sp)
                                        }
                                        DownloadState.DOWNLOADING -> {
                                            LinearProgressIndicator(
                                                progress = { item.progress / 100f },
                                                modifier = Modifier.fillMaxWidth(),
                                                color = Color.White,
                                                trackColor = Color.DarkGray
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                "${item.progress}% · ${item.speed}",
                                                color = Color.White,
                                                fontSize = 12.sp
                                            )
                                        }
                                        DownloadState.PAUSED -> {
                                            Text(
                                                "Paused · ${item.progress}%",
                                                color = Color(0xFFFFA500),
                                                fontSize = 12.sp
                                            )
                                        }
                                        DownloadState.COMPLETED -> {
                                            Text(
                                                "Completed",
                                                color = Color(0xFF4CAF50),
                                                fontSize = 12.sp
                                            )
                                        }
                                        DownloadState.FAILED -> {
                                            Text("Failed", color = Color.Red, fontSize = 12.sp)
                                        }
                                    }

                                    Spacer(Modifier.height(4.dp))

                                    // Action buttons
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        when (item.state) {
                                            DownloadState.QUEUED -> {
                                                TextButton({ onStart(item.id) }) {
                                                    Text("Start", color = Color.White, fontSize = 13.sp)
                                                }
                                            }
                                            DownloadState.DOWNLOADING -> {
                                                TextButton({ onPause(item.id) }) {
                                                    Text("Pause", color = Color.White, fontSize = 13.sp)
                                                }
                                            }
                                            DownloadState.PAUSED -> {
                                                TextButton({ onResume(item.id) }) {
                                                    Text("Resume", color = Color.White, fontSize = 13.sp)
                                                }
                                            }
                                            else -> {}
                                        }
                                        Spacer(Modifier.width(4.dp))
                                        TextButton({ onDelete(item.id) }) {
                                            Text("Delete", color = Color.Red, fontSize = 13.sp)
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
}

// ═══════════════════════════════════════════════════════════════════
// ── SCRIPT MANAGER ────────────────────────────────────────────────
// ═══════════════════════════════════════════════════════════════════

@Composable
fun ScriptManager(scripts: MutableList<ScriptItem>, onDismiss: () -> Unit) {
    var editingScript by remember { mutableStateOf<ScriptItem?>(null) }
    var isCreating by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var scriptToDelete by remember { mutableStateOf<ScriptItem?>(null) }

    if (showDeleteConfirm && scriptToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false; scriptToDelete = null },
            title = { Text("Delete Script?", color = Color.White, fontSize = 18.sp) },
            text = { Text("This cannot be undone.", color = Color.Gray, fontSize = 14.sp) },
            confirmButton = {
                TextButton({
                    scripts.remove(scriptToDelete!!)
                    showDeleteConfirm = false
                    scriptToDelete = null
                }) { Text("Delete", color = Color.White) }
            },
            dismissButton = {
                TextButton({ showDeleteConfirm = false; scriptToDelete = null }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF1E1E1E),
            titleContentColor = Color.White,
            textContentColor = Color.White,
            shape = RectangleShape,
            tonalElevation = 0.dp
        )
    }

    if (editingScript != null || isCreating) {
        ScriptEditor(
            script = editingScript,
            onSave = { name, code ->
                if (editingScript != null) {
                    val idx = scripts.indexOfFirst { it.id == editingScript!!.id }
                    if (idx >= 0) scripts[idx] = editingScript!!.copy(name = name, code = code)
                } else {
                    scripts.add(
                        ScriptItem(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            code = code,
                            enabled = true
                        )
                    )
                }
                editingScript = null
                isCreating = false
            },
            onCancel = { editingScript = null; isCreating = false }
        )
    } else {
        ScriptList(
            scripts = scripts,
            onDismiss = onDismiss,
            onAdd = { isCreating = true },
            onEdit = { editingScript = it },
            onDelete = { scriptToDelete = it; showDeleteConfirm = true },
            onToggle = { script ->
                val idx = scripts.indexOf(script)
                if (idx >= 0) scripts[idx] = script.copy(enabled = !script.enabled)
            }
        )
    }
}

@Composable
fun ScriptList(
    scripts: List<ScriptItem>,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (ScriptItem) -> Unit,
    onDelete: (ScriptItem) -> Unit,
    onToggle: (ScriptItem) -> Unit
) {
    Popup(
        alignment = Alignment.TopStart,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false)
    ) {
        Surface(
            Modifier.fillMaxSize().statusBarsPadding().background(Color(0xFF1E1E1E)),
            color = Color(0xFF1E1E1E)
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton({ onDismiss() }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, "Close", tint = Color.White)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("Scripts", color = Color.White, fontSize = 18.sp)
                    if (scripts.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text("(${scripts.size})", color = Color.Gray, fontSize = 14.sp)
                    }
                }

                if (scripts.isEmpty()) {
                    Box(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No scripts saved", color = Color.Gray, fontSize = 16.sp)
                    }
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                        items(scripts) { script ->
                            Surface(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                    .border(0.5.dp, Color.DarkGray, RectangleShape),
                                color = Color.Transparent
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            script.name,
                                            color = if (script.enabled) Color.White else Color.Gray,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            script.code.take(80),
                                            color = Color.Gray.copy(alpha = 0.7f),
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Switch(
                                        checked = script.enabled,
                                        onCheckedChange = { onToggle(script) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = Color(0xFF444444),
                                            uncheckedThumbColor = Color.White,
                                            uncheckedTrackColor = Color(0xFF444444)
                                        ),
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                    IconButton({ onEdit(script) }) {
                                        Icon(
                                            Icons.Default.Edit, "Edit",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    IconButton({ onDelete(script) }) {
                                        Icon(
                                            Icons.Default.Delete, "Delete",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Column(Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding()) {
                    OutlinedButton(
                        onClick = onAdd,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RectangleShape,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = BorderStroke(1.dp, Color.White)
                    ) {
                        Icon(Icons.Default.Add, null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Add Script", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun ScriptEditor(script: ScriptItem?, onSave: (String, String) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(script?.name ?: "") }
    var code by remember { mutableStateOf(script?.code ?: "") }

    Popup(
        alignment = Alignment.TopStart,
        onDismissRequest = onCancel,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false)
    ) {
        Surface(
            Modifier.fillMaxSize().statusBarsPadding().background(Color(0xFF1E1E1E)),
            color = Color(0xFF1E1E1E)
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton({ onCancel() }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (script != null) "Edit Script" else "New Script",
                        color = Color.White,
                        fontSize = 18.sp
                    )
                }

                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                    Text(
                        "Name:",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
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
                    Text(
                        "Script:",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        modifier = Modifier.fillMaxWidth().weight(1f),
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

                Row(Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding()) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                        shape = RectangleShape,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = BorderStroke(1.dp, Color.White)
                    ) { Text("Cancel", color = Color.White) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { if (name.isNotBlank()) onSave(name, code) },
                        modifier = Modifier.weight(1f),
                        shape = RectangleShape,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = BorderStroke(1.dp, Color.White)
                    ) { Text("Save Script", color = Color.White) }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// ── Composable Helpers ────────────────────────────────────────────
// ═══════════════════════════════════════════════════════════════════

@Composable
fun AllGroupChip(isSelected: Boolean, tabCount: Int, onClick: () -> Unit) {
    val bg = if (isSelected) Color.White else Color.Transparent
    val fg = if (isSelected) Color.Black else Color.White
    val borderColor = if (isSelected) Color.White else Color.White.copy(alpha = 0.2f)
    Surface(
        Modifier.padding(vertical = 4.dp).width(52.dp).clickable { onClick() }
            .border(0.5.dp, borderColor, RectangleShape),
        color = bg
    ) {
        Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("All", color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier.background(if (isSelected) Color.LightGray else Color.DarkGray)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) { Text(tabCount.toString(), color = fg, fontSize = 9.sp) }
        }
    }
}

@Composable
fun SidebarGroupChip(
    domain: String,
    isSelected: Boolean,
    tabCount: Int,
    onClick: () -> Unit,
    favicon: Bitmap?,
    onAppear: () -> Unit,
    isBlinking: Boolean,
    isPinned: Boolean
) {
    LaunchedEffect(domain) { onAppear() }
    val blinkAlpha = if (isBlinking) {
        val t = rememberInfiniteTransition(label = "blink_$domain")
        t.animateFloat(
            0.2f, 0.5f,
            infiniteRepeatable(tween(400), RepeatMode.Reverse),
            label = "blinkV"
        )
    } else null
    val bgAlpha =
        if (isBlinking && blinkAlpha != null) blinkAlpha.value else if (isSelected) 1f else 0f
    val bg = Color.White.copy(alpha = bgAlpha)
    val fg = if (isSelected) Color.Black else Color.White
    val borderColor = if (isSelected) Color.White else Color.White.copy(alpha = 0.2f)
    Surface(
        Modifier.padding(vertical = 4.dp).width(52.dp).clickable { onClick() }
            .border(0.5.dp, borderColor, RectangleShape),
        color = bg
    ) {
        Box(Modifier.padding(6.dp)) {
            if (isPinned) Icon(
                Icons.Default.PushPin, "Pinned", tint = fg,
                modifier = Modifier.size(12.dp).align(Alignment.TopStart)
            )
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(4.dp))
                if (favicon != null) Image(
                    favicon.asImageBitmap(), domain,
                    Modifier.size(24.dp).clip(CircleShape),
                    contentScale = ContentScale.Fit
                )
                else Box(
                    Modifier.size(24.dp).clip(CircleShape)
                        .background(if (isSelected) Color.LightGray else Color.DarkGray),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        domain.take(1).uppercase(), color = fg,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .background(if (isSelected) Color.LightGray else Color.DarkGray)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) { Text(tabCount.toString(), color = fg, fontSize = 9.sp) }
        }
    }
}

@Composable
fun ContextMenuItem(text: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) { Text(text, color = Color.White, fontSize = 16.sp) }
}

@Composable
fun SettingsDialog(prefs: SharedPreferences, onDismiss: () -> Unit, onSettingsApplied: () -> Unit) {
    var js by remember { mutableStateOf(prefs.getBoolean("javascript.enabled", true)) }
    var tp by remember {
        mutableStateOf(prefs.getBoolean("privacy.trackingprotection.enabled", false))
    }
    var cb by remember { mutableIntStateOf(prefs.getInt("network.cookie.cookieBehavior", 0)) }
    var am by remember { mutableStateOf(prefs.getBoolean("media.autoplay.enabled", true)) }
    val apply = {
        prefs.edit()
            .putBoolean("javascript.enabled", js)
            .putBoolean("privacy.trackingprotection.enabled", tp)
            .putInt("network.cookie.cookieBehavior", cb)
            .putBoolean("media.autoplay.enabled", am)
            .apply()
        onSettingsApplied()
        onDismiss()
    }
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
        containerColor = Color(0xFF1E1E1E),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        shape = RectangleShape,
        tonalElevation = 0.dp
    )
}

@Composable
fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        Arrangement.SpaceBetween,
        Alignment.CenterVertically
    ) {
        Text(text = label, color = Color.White, fontSize = 14.sp)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
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
    val st = options.firstOrNull { it.second == current }?.first ?: "Accept all"
    Box {
        OutlinedTextField(
            value = st,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { Icon(Icons.Default.Close, null, tint = Color.White) },
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
        Box(Modifier.matchParentSize().clickable { expanded = true })
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.border(1.dp, Color.White, RectangleShape),
            containerColor = Color(0xFF1E1E1E),
            shape = RectangleShape
        ) {
            options.forEach { (label, value) ->
                DropdownMenuItem(
                    text = { Text(text = label, color = Color.White) },
                    onClick = { onChange(value); expanded = false }
                )
            }
        }
    }
}

// END OF PART 2/2
// ═══════════════════════════════════════════════════════════════════
// END OF FILE - Grey Browser V1.6
// ═══════════════════════════════════════════════════════════════════
