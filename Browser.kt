// ═══════════════════════════════════════════════════════════════════
// Grey Browser - V3.0 (Clean + Import/Export)
// ═══════════════════════════════════════════════════════════════════
// === PART 1/10 — Package, GeckoView API Note, Imports, MainActivity ===
// ═══════════════════════════════════════════════════════════════════

package com.grey.browser

// ═══════════════════════════════════════════════════════════════════
// GECKOVIEW API NOTE:
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
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResponse
import org.mozilla.geckoview.GeckoResult
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GreyBrowser() }
    }
    override fun onPause() { super.onPause(); saveTabs(this) }
    override fun onDestroy() { super.onDestroy(); saveTabs(this) }
}

// END OF PART 1/10
// ═══════════════════════════════════════════════════════════════════
// === PART 2/10 — FaviconCache, Constants ===
// ═══════════════════════════════════════════════════════════════════

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
private const val KEY_HISTORY = "saved_history"
private const val KEY_BOOKMARKS = "saved_bookmarks"

const val MAX_LOADED_TABS = 10
const val MAX_WARM_TABS = 9
const val UNDO_DELAY_MS = 3000L
const val MAX_HISTORY_ITEMS = 500

// END OF PART 2/10



// ═══════════════════════════════════════════════════════════════════
// === PART 3/10 — Data Classes, Save/Load Functions ===
// ═══════════════════════════════════════════════════════════════════

data class HistoryItem(
    val url: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class Bookmark(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    val folder: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

class TabState {
    var session by mutableStateOf<GeckoSession?>(null)
    var title by mutableStateOf("New Tab")
    var url by mutableStateOf("about:blank")
    var progress by mutableIntStateOf(100)
    var lastUpdated by mutableLongStateOf(System.currentTimeMillis())
    var isBlankTab by mutableStateOf(true)
    var isDiscarded by mutableStateOf(false)
}

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

fun saveHistory(context: Context, history: List<HistoryItem>) {
    val arr = JSONArray()
    for (h in history) {
        val obj = JSONObject()
        obj.put("url", h.url); obj.put("title", h.title); obj.put("timestamp", h.timestamp)
        arr.put(obj)
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_HISTORY, arr.toString()).apply()
}

fun loadHistory(context: Context): List<HistoryItem> {
    val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_HISTORY, null) ?: return emptyList()
    return try {
        val arr = JSONArray(json)
        mutableListOf<HistoryItem>().apply {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(HistoryItem(o.getString("url"), o.getString("title"), o.getLong("timestamp")))
            }
        }
    } catch (e: Exception) { emptyList() }
}

fun saveBookmarks(context: Context, bookmarks: List<Bookmark>) {
    val arr = JSONArray()
    for (b in bookmarks) {
        val obj = JSONObject()
        obj.put("id", b.id); obj.put("url", b.url)
        obj.put("title", b.title); obj.put("folder", b.folder)
        obj.put("timestamp", b.timestamp)
        arr.put(obj)
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_BOOKMARKS, arr.toString()).apply()
}

fun loadBookmarks(context: Context): List<Bookmark> {
    val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_BOOKMARKS, null) ?: return emptyList()
    return try {
        val arr = JSONArray(json)
        mutableListOf<Bookmark>().apply {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(Bookmark(o.getString("id"), o.getString("url"), o.getString("title"),
                    o.optString("folder", ""), o.getLong("timestamp")))
            }
        }
    } catch (e: Exception) { emptyList() }
}

// END OF PART 3/10



// ═══════════════════════════════════════════════════════════════════
// === PART 4/10 — Utility Functions, Import/Export Logic ===
// ═══════════════════════════════════════════════════════════════════

fun getDomainName(url: String): String {
    if (url == "about:blank" || url.isBlank()) return ""
    return try {
        val host = Uri.parse(url).host?.removePrefix("www.") ?: return ""
        val parts = host.split(".")
        if (parts.size >= 2) "${parts[parts.size - 2]}.${parts[parts.size - 1]}" else host
    } catch (e: Exception) { "Unknown" }
}

fun resolveUrl(input: String): String {
    if (input.isBlank()) return "about:blank"
    if (input.contains("://") || (input.contains(".") && !input.contains(" "))) {
        return if (input.contains("://")) input else "https://$input"
    }
    return "https://www.google.com/search?q=${Uri.encode(input)}"
}

// ── Import/Export Functions ────────────────────────────────────────

fun exportData(
    context: Context,
    exportTabs: Boolean,
    exportHistory: Boolean,
    exportBookmarks: Boolean,
    tabs: List<TabState>,
    pinnedDomains: List<String>,
    currentTabIndex: Int,
    history: List<HistoryItem>,
    bookmarks: List<Bookmark>,
    exportAll: Boolean,
    onResult: (Boolean, String) -> Unit
) {
    thread(name = "export-data") {
        try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Grey")
            if (!dir.exists()) dir.mkdirs()
            
            if (exportAll) {
                // Single combined file
                val root = JSONObject()
                root.put("version", 1)
                root.put("exportDate", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.getDefault()).format(java.util.Date()))
                
                val tabsArr = JSONArray()
                for (i in tabs.indices) {
                    val t = tabs[i]
                    if (t.isBlankTab) continue
                    val obj = JSONObject()
                    obj.put("url", t.url)
                    obj.put("title", t.title)
                    obj.put("isPinned", pinnedDomains.contains(getDomainName(t.url)))
                    obj.put("order", i)
                    obj.put("group", getDomainName(t.url))
                    tabsArr.put(obj)
                }
                root.put("tabs", tabsArr)
                val pinnedArr = JSONArray()
                for (d in pinnedDomains) pinnedArr.put(d)
                root.put("pinnedDomains", pinnedArr)
                root.put("activeTabIndex", currentTabIndex)
                
                val histArr = JSONArray()
                for (h in history) {
                    val obj = JSONObject()
                    obj.put("url", h.url); obj.put("title", h.title); obj.put("timestamp", h.timestamp)
                    histArr.put(obj)
                }
                root.put("history", histArr)
                
                val bookArr = JSONArray()
                for (b in bookmarks) {
                    val obj = JSONObject()
                    obj.put("url", b.url); obj.put("title", b.title)
                    obj.put("folder", b.folder); obj.put("timestamp", b.timestamp)
                    bookArr.put(obj)
                }
                root.put("bookmarks", bookArr)
                
                val fileName = "grey_backup_${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())}.json"
                val file = File(dir, fileName)
                file.writeText(root.toString(2))
                onResult(true, file.absolutePath)
            } else {
                // Separate files per category
                val files = mutableListOf<String>()
                
                if (exportTabs) {
                    val root = JSONObject()
                    root.put("type", "tabs")
                    val tabsArr = JSONArray()
                    for (i in tabs.indices) {
                        val t = tabs[i]
                        if (t.isBlankTab) continue
                        val obj = JSONObject()
                        obj.put("url", t.url)
                        obj.put("title", t.title)
                        obj.put("isPinned", pinnedDomains.contains(getDomainName(t.url)))
                        obj.put("order", i)
                        obj.put("group", getDomainName(t.url))
                        tabsArr.put(obj)
                    }
                    root.put("tabs", tabsArr)
                    val file = File(dir, "grey_tabs.json")
                    file.writeText(root.toString(2))
                    files.add(file.absolutePath)
                }
                
                if (exportHistory) {
                    val root = JSONObject()
                    root.put("type", "history")
                    val histArr = JSONArray()
                    for (h in history) {
                        val obj = JSONObject()
                        obj.put("url", h.url); obj.put("title", h.title); obj.put("timestamp", h.timestamp)
                        histArr.put(obj)
                    }
                    root.put("history", histArr)
                    val file = File(dir, "grey_history.json")
                    file.writeText(root.toString(2))
                    files.add(file.absolutePath)
                }
                
                if (exportBookmarks) {
                    val root = JSONObject()
                    root.put("type", "bookmarks")
                    val bookArr = JSONArray()
                    for (b in bookmarks) {
                        val obj = JSONObject()
                        obj.put("url", b.url); obj.put("title", b.title)
                        obj.put("folder", b.folder); obj.put("timestamp", b.timestamp)
                        bookArr.put(obj)
                    }
                    root.put("bookmarks", bookArr)
                    val file = File(dir, "grey_bookmarks.json")
                    file.writeText(root.toString(2))
                    files.add(file.absolutePath)
                }
                
                onResult(true, files.joinToString("\n"))
            }
        } catch (e: Exception) {
            onResult(false, "Export failed: ${e.message}")
        }
    }
}

fun importData(
    context: Context,
    uri: Uri,
    tabs: MutableList<TabState>,
    pinnedDomains: MutableList<String>,
    history: MutableList<HistoryItem>,
    bookmarks: MutableList<Bookmark>,
    createForegroundTab: (String) -> Unit,
    onResult: (Boolean, String) -> Unit
) {
    thread(name = "import-data") {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val json = inputStream?.bufferedReader()?.readText() ?: ""
            inputStream?.close()
            
            val root = JSONObject(json)
            var importedTabs = 0
            var importedHistory = 0
            var importedBookmarks = 0
            
            // Import tabs if present
            val tabsArr = root.optJSONArray("tabs")
            if (tabsArr != null) {
                for (i in 0 until tabsArr.length()) {
                    val t = tabsArr.getJSONObject(i)
                    val url = t.getString("url")
                    createForegroundTab(url)
                    importedTabs++
                }
            }
            
            // Import pinned domains
            val pinnedArr = root.optJSONArray("pinnedDomains")
            if (pinnedArr != null) {
                for (i in 0 until pinnedArr.length()) {
                    val domain = pinnedArr.getString(i)
                    if (domain !in pinnedDomains) pinnedDomains.add(domain)
                }
            }
            
            // Import history if present
            val histArr = root.optJSONArray("history")
            if (histArr != null) {
                for (i in 0 until histArr.length()) {
                    val h = histArr.getJSONObject(i)
                    val url = h.getString("url")
                    if (history.none { it.url == url }) {
                        history.add(HistoryItem(
                            url = url,
                            title = h.optString("title", url),
                            timestamp = h.optLong("timestamp", System.currentTimeMillis())
                        ))
                        importedHistory++
                    }
                }
                saveHistory(context, history)
            }
            
            // Import bookmarks if present
            val bookArr = root.optJSONArray("bookmarks")
            if (bookArr != null) {
                for (i in 0 until bookArr.length()) {
                    val b = bookArr.getJSONObject(i)
                    val url = b.getString("url")
                    if (bookmarks.none { it.url == url }) {
                        bookmarks.add(Bookmark(
                            url = url,
                            title = b.optString("title", url),
                            folder = b.optString("folder", ""),
                            timestamp = b.optLong("timestamp", System.currentTimeMillis())
                        ))
                        importedBookmarks++
                    }
                }
                saveBookmarks(context, bookmarks)
            }
            
            onResult(true, "Imported: $importedTabs tabs, $importedHistory history, $importedBookmarks bookmarks")
        } catch (e: Exception) {
            onResult(false, "Import failed: ${e.message}")
        }
    }
}

// END OF PART 4/10



// ═══════════════════════════════════════════════════════════════════
// === PART 5/10 — GreyBrowser() State Declarations ===
// ═══════════════════════════════════════════════════════════════════

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

    // ── History State ──────────────────────────────────────────────────
    val history = remember { mutableStateListOf<HistoryItem>().apply { addAll(loadHistory(context)) } }
    var showHistory by remember { mutableStateOf(false) }

    // ── Bookmark State ─────────────────────────────────────────────────
    val bookmarks = remember { mutableStateListOf<Bookmark>().apply { addAll(loadBookmarks(context)) } }
    var showBookmarks by remember { mutableStateOf(false) }

    // ── Import/Export State ────────────────────────────────────────────
    var showImportExport by remember { mutableStateOf(false) }

    // ── Logcat State ───────────────────────────────────────────────────
    val logLines = remember { mutableStateListOf<String>() }
    var showLogcat by remember { mutableStateOf(false) }

    // ── Toast State ────────────────────────────────────────────────────
    var toastMessage by remember { mutableStateOf("") }
    var showToast by remember { mutableStateOf(false) }

    // ── Logging helper ────────────────────────────────────────────────
    fun log(msg: String) {
        android.util.Log.d("GreyBrowser", msg)
        logLines.add("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} $msg")
        if (logLines.size > 500) logLines.removeAt(0)
    }

    fun showToast(msg: String) {
        toastMessage = msg
        showToast = true
    }

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

    fun addToHistory(url: String, title: String) {
        if (url == "about:blank" || url.isBlank()) return
        history.removeAll { it.url == url }
        history.add(HistoryItem(url = url, title = title.ifBlank { url }, timestamp = System.currentTimeMillis()))
        if (history.size > MAX_HISTORY_ITEMS) {
            history.removeAt(0)
        }
        saveHistory(context, history)
    }

    // ── No more homeSession — currentTabIndex = -1 means homepage ──

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
    val currentTab = tabs.getOrNull(currentTabIndex)

    val pendingDeletions = remember { mutableStateMapOf<Int, Long>() }
    var showBlink by remember { mutableStateOf(false) }
    val blinkTargetDomain = remember { mutableStateOf("") }

    LaunchedEffect(tabs.toList(), pinnedDomains.toList(), lastActiveUrl) {
        saveTabsData(context, tabs, pinnedDomains, lastActiveUrl)
    }
    LaunchedEffect(tabs.map { "${it.url}|${it.title}" }.joinToString()) {
        saveTabsData(context, tabs, pinnedDomains, lastActiveUrl)
    }
    LaunchedEffect(bookmarks.toList()) { saveBookmarks(context, bookmarks) }

    LaunchedEffect(currentTabIndex) {
        if (currentTabIndex >= 0 && currentTabIndex < tabs.size) {
            lastActiveUrl = tabs[currentTabIndex].url
            highlightedTabIndex = currentTabIndex
        }
    }

    // LaunchedEffect for toast auto-hide
    if (showToast) {
        LaunchedEffect(showToast) {
            delay(2000)
            showToast = false
        }
    }

// END OF PART 5/10

// ═══════════════════════════════════════════════════════════════════
// === PART 6/10 — GreyBrowser() Delegates, Lifecycle, Tab Functions ===
// ═══════════════════════════════════════════════════════════════════

    val setupDelegates = fun(tabState: TabState) {
        val session = tabState.session ?: return
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(s: GeckoSession, url: String) {
                tabState.url = url; tabState.progress = 5
                tabState.lastUpdated = System.currentTimeMillis()
                log("onPageStart: $url")
                if (url != "about:blank") {
                    tabState.isBlankTab = false
                    lastActiveUrl = url
                    if (currentTabIndex >= 0 && currentTabIndex < tabs.size) {
                        highlightedTabIndex = currentTabIndex
                    }
                }
            }
            override fun onPageStop(s: GeckoSession, success: Boolean) {
                tabState.progress = 100; tabState.lastUpdated = System.currentTimeMillis()
                log("onPageStop: success=$success url=${tabState.url}")
            }
            override fun onProgressChange(s: GeckoSession, progress: Int) {
                tabState.progress = progress
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(s: GeckoSession, title: String?) {
                if (!tabState.isBlankTab) {
                    tabState.title = title ?: tabState.url
                    log("onTitleChange: ${tabState.title}")
                }
            }
            override fun onContextMenu(
                session: GeckoSession, screenX: Int, screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement
            ) {
                if (element.linkUri != null) {
                    log("onContextMenu: ${element.linkUri}")
                    contextMenuUri = element.linkUri; showContextMenu = true
                }
            }
            override fun onExternalResponse(
                session: GeckoSession,
                response: WebResponse
            ) {
                val url = response.uri
                log("onExternalResponse: $url")
            }
        }
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession, url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                url?.let { newUrl ->
                    log("onLocationChange: $newUrl")
                    tabState.url = newUrl
                    if (newUrl != "about:blank") {
                        tabState.isBlankTab = false
                    }
                }
            }
        }
        session.historyDelegate = object : GeckoSession.HistoryDelegate {
            override fun onVisited(
                s: GeckoSession,
                url: String,
                lastVisitedURL: String?,
                flags: Int
            ): GeckoResult<Boolean>? {
                if (url != "about:blank" && url.isNotBlank()) {
                    // ── FIX: Don't save "New Tab" as title ──
                    val title = if (tabState.title == "New Tab" || tabState.title.isBlank()) {
                        getDomainName(url).ifBlank { url }
                    } else {
                        tabState.title
                    }
                    log("HistoryDelegate.onVisited: $url title=$title")
                    addToHistory(url, title)
                }
                return GeckoResult.fromValue(true)
            }
        }
    }

    fun manageTabLifecycle(activeIndex: Int) {
        tabs.forEachIndexed { i, ts ->
            if (i == activeIndex && ts.session == null && ts.isDiscarded) {
                val s = GeckoSession().apply {
                    applySettingsToSession(this); open(runtime); loadUri(ts.url)
                }
                ts.session = s; ts.isDiscarded = false; setupDelegates(ts)
                ts.lastUpdated = System.currentTimeMillis()
                log("Tab restored from cold: ${ts.url}")
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
                val tab = tabs.getOrNull(index)
                if (tab == null) continue
                log("Tab deleted: ${tab.url}")
                tab.session?.setActive(false); tab.session?.close()
                tabs.removeAt(index)
                val updated = mutableMapOf<Int, Long>()
                for ((oldIdx, time) in pendingDeletions) {
                    updated[if (oldIdx > index) oldIdx - 1 else oldIdx] = time
                }
                pendingDeletions.clear()
                pendingDeletions.putAll(updated)
                
                if (tabs.isEmpty()) {
                    currentTabIndex = -1
                    selectedDomain = ""
                } else if (currentTabIndex > index) {
                    currentTabIndex--
                } else if (currentTabIndex == index && tabs.isNotEmpty()) {
                    currentTabIndex = minOf(currentTabIndex, tabs.lastIndex)
                }
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
            tabs.forEach { it.session?.setActive(false) }
        } else {
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
        log("Background tab created: $url")
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
        log("Foreground tab created: $url")
        currentTabIndex = tabs.lastIndex; manageTabLifecycle(currentTabIndex)
    }

    fun requestDeleteTab(index: Int) {
        if (index >= 0 && index < tabs.size) {
            pendingDeletions[index] = System.currentTimeMillis()
            log("Tab delete requested: ${tabs[index].url}")
        }
    }

    fun undoDeleteTab(index: Int) { 
        pendingDeletions.remove(index)
        log("Tab delete undone")
    }

// END OF PART 6/10



// ═══════════════════════════════════════════════════════════════════
// === PART 7/10 — GreyBrowser() BackHandler, GeckoViewBox, Homepage ===
// ═══════════════════════════════════════════════════════════════════

    BackHandler {
        if (currentTabIndex == -1) { if (tabs.isNotEmpty()) currentTabIndex = tabs.lastIndex else activity?.finish() }
        else currentTab?.session?.goBack()
    }

    @Composable
    fun GeckoViewBox() {
        if (currentTabIndex == -1) {
            // ── Homepage (no session, pure Compose) ──
            Box(
                Modifier.fillMaxSize().background(Color(0xFF121212)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Grey",
                    color = Color.White.copy(alpha = 0.15f),
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            val s = currentTab?.session
            if (s != null) {
                AndroidView(
                    factory = { ctx -> GeckoView(ctx).apply { setSession(s) } },
                    update = { gv -> gv.setSession(s) },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize().background(Color(0xFF121212)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }

// END OF PART 7/10



// ═══════════════════════════════════════════════════════════════════
// === PART 8/10 — GreyBrowser() Dialogs, Context Menu, Tab Manager, URL Bar, Menu, Toast ===
// ═══════════════════════════════════════════════════════════════════

    if (showConfirmDialog) {
        AlertDialog(onDismissRequest = { showConfirmDialog = false; confirmAction = null }, title = { Text(confirmTitle, color = Color.White, fontSize = 18.sp) }, text = { Text(confirmMessage, color = Color.Gray, fontSize = 14.sp) }, confirmButton = { TextButton({ val a = confirmAction; showConfirmDialog = false; confirmAction = null; a?.invoke() }) { Text("Confirm", color = Color.White) } }, dismissButton = { TextButton({ showConfirmDialog = false; confirmAction = null }) { Text("Cancel", color = Color.White) } }, containerColor = Color(0xFF1E1E1E), titleContentColor = Color.White, textContentColor = Color.White, shape = RectangleShape, tonalElevation = 0.dp)
    }

    if (showSettings) { SettingsDialog(prefs, { showSettings = false }) { tabs.forEach { it.session?.let { s -> applySettingsToSession(s) } } } }
    if (showImportExport) { ImportExportUI(context = context, tabs = tabs, pinnedDomains = pinnedDomains, currentTabIndex = currentTabIndex, history = history, bookmarks = bookmarks, createForegroundTab = { url -> createForegroundTab(url) }, onShowToast = { msg -> showToast(msg) }, onDismiss = { showImportExport = false }) }
    if (showLogcat) { LogcatUI(logLines = logLines, onClear = { logLines.clear() }, onDismiss = { showLogcat = false }) }

    if (showHistory) {
        HistoryUI(history = history, onDismiss = { showHistory = false }, onOpenUrl = { url -> createForegroundTab(url) }, faviconBitmaps = faviconBitmaps, loadFavicon = { loadFavicon(it) })
    }

    if (showBookmarks) {
        BookmarksUI(bookmarks = bookmarks, onDismiss = { showBookmarks = false }, onOpenUrl = { url -> createForegroundTab(url) }, onDelete = { id -> bookmarks.removeAll { it.id == id }; showToast("Bookmark deleted") }, faviconBitmaps = faviconBitmaps, loadFavicon = { loadFavicon(it) })
    }

    if (showContextMenu && contextMenuUri != null) {
        val clipMgr = clipboardManager; val ctxUri = contextMenuUri
        Popup(alignment = Alignment.Center, onDismissRequest = { showContextMenu = false }, properties = PopupProperties(focusable = true)) {
            Column(Modifier.border(1.dp, Color.White, RectangleShape).background(Color(0xFF1E1E1E)).width(IntrinsicSize.Max)) {
                ContextMenuItem("New Tab") { createForegroundTab(ctxUri!!); showContextMenu = false }
                ContextMenuItem("Open in Background") { createBackgroundTab(ctxUri!!); showContextMenu = false }
                ContextMenuItem("Copy link") { clipMgr.setText(AnnotatedString(ctxUri!!)); showContextMenu = false; showToast("Link copied") }
            }
        }
    }

    if (showTabManager) {
        val domainGroups = tabs.groupBy { getDomainName(it.url) }.filter { it.key.isNotBlank() }
        val sortedDomains = domainGroups.keys.sortedWith(compareByDescending<String> { pinnedDomains.contains(it) }.thenBy { d: String -> domainGroups[d]?.firstOrNull()?.let { t -> tabs.indexOf(t) } ?: Int.MAX_VALUE })
        val pinnedSorted = sortedDomains.filter { pinnedDomains.contains(it) }; val unpinnedSorted = sortedDomains.filter { !pinnedDomains.contains(it) }
        val highlightDomain = if (currentTabIndex == -1 && highlightedTabIndex >= 0) getDomainName(tabs[highlightedTabIndex].url) else if (currentTabIndex >= 0) getDomainName(currentTab?.url ?: "") else ""

        LaunchedEffect(Unit) { selectedDomain = ""; if (highlightDomain.isNotBlank()) { blinkTargetDomain.value = highlightDomain; showBlink = true; delay(1500); showBlink = false; blinkTargetDomain.value = "" } }

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
                                        val isHighlighted = tabIndex == currentTabIndex
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
                            val hasSelection = selectedDomain.isNotBlank(); val isPinned = pinnedDomains.contains(selectedDomain)
                            val faintColor = Color.White.copy(alpha = 0.3f); val activeColor = Color.White
                            OutlinedButton(onClick = { if (hasSelection) { confirmTitle = if (isPinned) "Unpin Group?" else "Pin Group?"; confirmMessage = "Are you sure?"; confirmAction = { if (isPinned) pinnedDomains.remove(selectedDomain) else pinnedDomains.add(selectedDomain) }; showConfirmDialog = true } }, modifier = Modifier.weight(1f), shape = RectangleShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = if (hasSelection) activeColor else faintColor), border = BorderStroke(1.dp, if (hasSelection) activeColor else faintColor)) {
                                Text(if (isPinned) "Unpin Group" else "Pin Group", fontSize = 13.sp, color = if (hasSelection) activeColor else faintColor)
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { if (hasSelection) { confirmTitle = "Delete Group?"; confirmMessage = "All tabs in this group will be lost."; confirmAction = { 
                                val toRemove = (domainGroups[selectedDomain] ?: emptyList()).toList()
                                toRemove.forEach { tab ->
                                    val idx = tabs.indexOf(tab)
                                    if (idx >= 0) pendingDeletions.remove(idx)
                                    tab.session?.setActive(false); tab.session?.close()
                                }
                                tabs.removeAll(toRemove)
                                if (tabs.isEmpty()) { currentTabIndex = -1; highlightedTabIndex = -1; selectedDomain = "" }
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

    var urlInput by remember { mutableStateOf(TextFieldValue(if (currentTabIndex == -1) "" else currentTab?.url?.let { if (it == "about:blank") "" else it } ?: "")) }
    var isUrlFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(currentTabIndex, currentTab?.url) {
        if (!isUrlFocused) {
            urlInput = TextFieldValue(if (currentTabIndex == -1) "" else currentTab?.url?.let { if (it == "about:blank") "" else it } ?: "")
        }
    }

    Surface(Modifier.fillMaxSize(), color = Color(0xFF121212)) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ showTabManager = true; showToast("Tab manager opened") }) { Icon(Icons.Default.Tab, "Tabs", tint = Color.White) }
                    val isLoading = (currentTab?.progress ?: 100) in 1..99
                    OutlinedTextField(
                        value = urlInput, onValueChange = { urlInput = it }, singleLine = true,
                        placeholder = { Text(if (currentTabIndex == -1 || currentTab?.isBlankTab == true) "Search or enter URL" else currentTab?.url?.take(50) ?: "", color = Color.White.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp).background(Color(0xFF1E1E1E)).focusRequester(focusRequester).onFocusChanged { isUrlFocused = it.isFocused }.drawBehind { if (isLoading) drawRect(Color.White, size = size.copy(width = size.width * (currentTab?.progress ?: 100) / 100f)) },
                        textStyle = TextStyle(if (isLoading) Color.Gray else Color.White, 16.sp), shape = RectangleShape,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { val input = urlInput.text; if (input.isNotBlank()) { focusManager.clearFocus(); val uri = resolveUrl(input); if (currentTabIndex == -1 || currentTab?.isBlankTab == true) createForegroundTab(uri) else currentTab?.session?.loadUri(uri) } }),
                        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedBorderColor = Color.White, unfocusedBorderColor = Color.White, cursorColor = if (isLoading) Color.Gray else Color.White),
                        trailingIcon = { if (isLoading) IconButton({ currentTab?.session?.stop() }) { Icon(Icons.Default.Close, "Stop", tint = Color.White) } else IconButton({ urlInput = urlInput.copy(selection = TextRange(0, urlInput.text.length)); focusRequester.requestFocus() }) { Icon(Icons.Default.SelectAll, "Select all", tint = Color.White) } }
                    )
                    IconButton({ currentTabIndex = -1 }) { Icon(Icons.Default.Add, "New Tab", tint = Color.White) }
                    Box {
                        IconButton({ showMenu = true }) { Icon(Icons.Default.MoreVert, "Menu", tint = Color.White) }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.border(1.dp, Color.White, RectangleShape), containerColor = Color(0xFF1E1E1E), shape = RectangleShape) {
                            if (currentTabIndex >= 0) {
                                DropdownMenuItem(text = { Text("Add to Bookmark", color = Color.White) }, onClick = {
                                    showMenu = false
                                    val url = currentTab?.url ?: ""
                                    if (url != "about:blank" && url.isNotBlank()) {
                                        bookmarks.removeAll { it.url == url }
                                        bookmarks.add(Bookmark(url = url, title = currentTab?.title?.ifBlank { url } ?: url))
                                        log("Bookmark added: $url")
                                        showToast("Added to bookmarks")
                                    }
                                })
                            }
                            DropdownMenuItem(text = { Text("Bookmarks", color = Color.White) }, onClick = { showMenu = false; showBookmarks = true })
                            DropdownMenuItem(text = { Text("History", color = Color.White) }, onClick = { showMenu = false; showHistory = true })
                            DropdownMenuItem(text = { Text("Import/Export", color = Color.White) }, onClick = { showMenu = false; showImportExport = true })
                            DropdownMenuItem(text = { Text("Settings", color = Color.White) }, onClick = { showMenu = false; showSettings = true })
                            DropdownMenuItem(text = { Text("Logcat", color = Color.White) }, onClick = { showMenu = false; showLogcat = true })
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { GeckoViewBox() }
            }

            // ── Toast Overlay ──────────────────────────────────
            if (showToast) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Surface(
                        modifier = Modifier.padding(bottom = 80.dp),
                        color = Color.White.copy(alpha = 0.9f),
                        shape = RectangleShape
                    ) {
                        Text(
                            toastMessage,
                            color = Color.Black,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

// END OF PART 8/10





// ═══════════════════════════════════════════════════════════════════
// === PART 9/10 — ImportExportUI Composable ===
// ═══════════════════════════════════════════════════════════════════

@Composable
fun ImportExportUI(
    context: Context,
    tabs: List<TabState>,
    pinnedDomains: List<String>,
    currentTabIndex: Int,
    history: List<HistoryItem>,
    bookmarks: List<Bookmark>,
    createForegroundTab: (String) -> Unit,
    onShowToast: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var exportTabs by remember { mutableStateOf(true) }
    var exportHistory by remember { mutableStateOf(true) }
    var exportBookmarks by remember { mutableStateOf(true) }

    val importFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            onShowToast("Importing...")
            importData(
                context = context,
                uri = uri,
                tabs = tabs as MutableList<TabState>,
                pinnedDomains = pinnedDomains as MutableList<String>,
                history = history as MutableList<HistoryItem>,
                bookmarks = bookmarks as MutableList<Bookmark>,
                createForegroundTab = createForegroundTab
            ) { success, msg ->
                if (success) {
                    onShowToast("Import complete: $msg")
                } else {
                    onShowToast("Import failed: $msg")
                }
            }
        }
    }

    Popup(
        alignment = Alignment.TopStart,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false)
    ) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().background(Color(0xFF1E1E1E)), color = Color(0xFF1E1E1E)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ onDismiss() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                    Spacer(Modifier.width(4.dp))
                    Text("Import/Export", color = Color.White, fontSize = 18.sp)
                }

                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    // ── Export Section ──────────────────────
                    Text("Export:", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(12.dp))

                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = exportTabs,
                            onCheckedChange = { exportTabs = it },
                            colors = CheckboxDefaults.colors(checkedColor = Color.White, uncheckedColor = Color.Gray, checkmarkColor = Color.Black)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Tabs (groups, pins, position)", color = Color.White, fontSize = 14.sp)
                    }

                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = exportHistory,
                            onCheckedChange = { exportHistory = it },
                            colors = CheckboxDefaults.colors(checkedColor = Color.White, uncheckedColor = Color.Gray, checkmarkColor = Color.Black)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("History (${history.size} entries)", color = Color.White, fontSize = 14.sp)
                    }

                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = exportBookmarks,
                            onCheckedChange = { exportBookmarks = it },
                            colors = CheckboxDefaults.colors(checkedColor = Color.White, uncheckedColor = Color.Gray, checkmarkColor = Color.Black)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Bookmarks (${bookmarks.size} entries)", color = Color.White, fontSize = 14.sp)
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                if (!exportTabs && !exportHistory && !exportBookmarks) {
                                    onShowToast("Select at least one category")
                                    return@OutlinedButton
                                }
                                onShowToast("Exporting...")
                                exportData(
                                    context = context,
                                    exportTabs = exportTabs,
                                    exportHistory = exportHistory,
                                    exportBookmarks = exportBookmarks,
                                    tabs = tabs,
                                    pinnedDomains = pinnedDomains,
                                    currentTabIndex = currentTabIndex,
                                    history = history,
                                    bookmarks = bookmarks,
                                    exportAll = false,
                                    onResult = { success, msg ->
                                        if (success) {
                                            onShowToast("Exported to /Download/Grey/")
                                        } else {
                                            onShowToast(msg)
                                        }
                                    }
                                )
                            },
                            modifier = Modifier.weight(1f),
                            shape = RectangleShape,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = BorderStroke(1.dp, Color.White)
                        ) { Text("Export Selected", color = Color.White, fontSize = 13.sp) }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                onShowToast("Exporting all...")
                                exportData(
                                    context = context,
                                    exportTabs = true,
                                    exportHistory = true,
                                    exportBookmarks = true,
                                    tabs = tabs,
                                    pinnedDomains = pinnedDomains,
                                    currentTabIndex = currentTabIndex,
                                    history = history,
                                    bookmarks = bookmarks,
                                    exportAll = true,
                                    onResult = { success, msg ->
                                        if (success) {
                                            onShowToast("Exported to /Download/Grey/")
                                        } else {
                                            onShowToast(msg)
                                        }
                                    }
                                )
                            },
                            modifier = Modifier.weight(1f),
                            shape = RectangleShape,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = BorderStroke(1.dp, Color.White)
                        ) { Text("Export All", color = Color.White, fontSize = 13.sp) }
                    }

                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider(color = Color.DarkGray)
                    Spacer(Modifier.height(24.dp))

                    // ── Import Section ──────────────────────
                    Text("Import:", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { importFilePicker.launch("application/json") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RectangleShape,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = BorderStroke(1.dp, Color.White)
                    ) {
                        Text("Import File", color = Color.White)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Select a .json file — merges with existing data", color = Color.Gray.copy(alpha = 0.7f), fontSize = 12.sp)
                }
            }
        }
    }
}

// END OF PART 9/10




// ═══════════════════════════════════════════════════════════════════
// === PART 10/10 — BookmarksUI, LogcatUI, HistoryUI, Settings, Helpers ===
// ═══════════════════════════════════════════════════════════════════

// ═══════════════════════════════════════════════════════════════════
// ── BOOKMARKS UI ─────────────────────────────────────────────────
// ═══════════════════════════════════════════════════════════════════

@Composable
fun BookmarksUI(
    bookmarks: List<Bookmark>,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onDelete: (String) -> Unit,
    faviconBitmaps: Map<String, Bitmap?>,
    loadFavicon: (String) -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var bookmarkToDelete by remember { mutableStateOf<String?>(null) }

    if (showDeleteConfirm && bookmarkToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false; bookmarkToDelete = null },
            title = { Text("Delete Bookmark?", color = Color.White, fontSize = 18.sp) },
            text = { Text("This cannot be undone.", color = Color.Gray, fontSize = 14.sp) },
            confirmButton = {
                TextButton({
                    onDelete(bookmarkToDelete!!)
                    showDeleteConfirm = false
                    bookmarkToDelete = null
                }) { Text("Delete", color = Color.White) }
            },
            dismissButton = {
                TextButton({ showDeleteConfirm = false; bookmarkToDelete = null }) {
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

    Popup(
        alignment = Alignment.TopStart,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false)
    ) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().background(Color(0xFF1E1E1E)), color = Color(0xFF1E1E1E)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ onDismiss() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                    Spacer(Modifier.width(4.dp))
                    Text("Bookmarks", color = Color.White, fontSize = 18.sp)
                    if (bookmarks.isNotEmpty()) { Spacer(Modifier.width(8.dp)); Text("(${bookmarks.size})", color = Color.Gray, fontSize = 14.sp) }
                }

                if (bookmarks.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("No bookmarks", color = Color.Gray, fontSize = 16.sp) }
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                        items(bookmarks.reversed()) { item ->
                            val domain = getDomainName(item.url)
                            LaunchedEffect(item.url) { loadFavicon(domain) }
                            val fav = faviconBitmaps[domain]

                            Surface(Modifier.fillMaxWidth().padding(vertical = 2.dp).border(0.5.dp, Color.DarkGray, RectangleShape), color = Color.Transparent) {
                                Row(Modifier.fillMaxWidth().padding(12.dp).clickable { onOpenUrl(item.url); onDismiss() }, verticalAlignment = Alignment.CenterVertically) {
                                    if (fav != null) Image(fav.asImageBitmap(), domain, Modifier.size(20.dp).clip(CircleShape), contentScale = ContentScale.Fit)
                                    else Box(Modifier.size(20.dp).clip(CircleShape).background(Color.DarkGray), contentAlignment = Alignment.Center) { Text(domain.take(1).uppercase(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(item.title.ifBlank { item.url }, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(item.url, color = Color.Gray.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    IconButton({ bookmarkToDelete = item.id; showDeleteConfirm = true }) { Icon(Icons.Default.Close, "Delete", tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(18.dp)) }
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
// ── LOGCAT UI ────────────────────────────────────────────────────
// ═══════════════════════════════════════════════════════════════════

@Composable
fun LogcatUI(logLines: List<String>, onClear: () -> Unit, onDismiss: () -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(logLines.size) { if (logLines.isNotEmpty()) listState.animateScrollToItem(logLines.size - 1) }

    Popup(alignment = Alignment.TopStart, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().background(Color(0xFF1E1E1E)), color = Color(0xFF1E1E1E)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ onDismiss() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                    Spacer(Modifier.width(4.dp)); Text("Logcat", color = Color.White, fontSize = 18.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onClear() }) { Text("Clear", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp) }
                }
                if (logLines.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("No logs yet.", color = Color.Gray, fontSize = 14.sp) }
                } else {
                    LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                        items(logLines) { line ->
                            val lineColor = when {
                                line.contains("Error") || line.contains("FAILED") || line.contains("error") -> Color.Red
                                line.contains("Import complete") -> Color(0xFF4CAF50)
                                line.contains("Export") || line.contains("Import") -> Color(0xFFFFA500)
                                else -> Color.White
                            }
                            Text(line, color = lineColor.copy(alpha = 0.9f), fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// ── SETTINGS ─────────────────────────────────────────────────────
// ═══════════════════════════════════════════════════════════════════

@Composable
fun SettingsDialog(prefs: SharedPreferences, onDismiss: () -> Unit, onSettingsApplied: () -> Unit) {
    var js by remember { mutableStateOf(prefs.getBoolean("javascript.enabled", true)) }
    var tp by remember { mutableStateOf(prefs.getBoolean("privacy.trackingprotection.enabled", false)) }
    var cb by remember { mutableIntStateOf(prefs.getInt("network.cookie.cookieBehavior", 0)) }
    var am by remember { mutableStateOf(prefs.getBoolean("media.autoplay.enabled", true)) }
    val apply = {
        prefs.edit().putBoolean("javascript.enabled", js).putBoolean("privacy.trackingprotection.enabled", tp).putInt("network.cookie.cookieBehavior", cb).putBoolean("media.autoplay.enabled", am).apply()
        onSettingsApplied(); onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings", color = Color.White, fontSize = 20.sp) },
        text = {
            Column {
                // ── FIX: Settings with white border styling ──
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        .border(1.dp, Color.White.copy(alpha = 0.3f), RectangleShape)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("JavaScript", color = Color.White, fontSize = 14.sp)
                    Switch(checked = js, onCheckedChange = { js = it }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF444444), uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF444444)))
                }

                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        .border(1.dp, Color.White.copy(alpha = 0.3f), RectangleShape)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Tracking Protection", color = Color.White, fontSize = 14.sp)
                    Switch(checked = tp, onCheckedChange = { tp = it }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF444444), uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF444444)))
                }

                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        .border(1.dp, Color.White.copy(alpha = 0.3f), RectangleShape)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Autoplay Media", color = Color.White, fontSize = 14.sp)
                    Switch(checked = am, onCheckedChange = { am = it }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF444444), uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF444444)))
                }

                Spacer(Modifier.height(12.dp))
                Text("Cookie Behavior", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
                CookieBehaviorSelector(cb) { cb = it }
            }
        },
        confirmButton = { TextButton(apply) { Text("OK", color = Color.White) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = Color.White) } },
        containerColor = Color(0xFF1E1E1E), titleContentColor = Color.White, textContentColor = Color.White, shape = RectangleShape, tonalElevation = 0.dp
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookieBehaviorSelector(current: Int, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("Accept all" to 0, "Reject all" to 1, "Only from visited" to 2)
    val st = options.firstOrNull { it.second == current }?.first ?: "Accept all"
    Box {
        OutlinedTextField(
            value = st, onValueChange = {}, readOnly = true,
            trailingIcon = { Icon(Icons.Default.Close, null, tint = Color.White) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                .border(1.dp, Color.White.copy(alpha = 0.3f), RectangleShape),
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
            shape = RectangleShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.White,
                unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                focusedContainerColor = Color(0xFF1E1E1E),
                unfocusedContainerColor = Color(0xFF1E1E1E)
            )
        )
        Box(Modifier.matchParentSize().clickable { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.border(1.dp, Color.White, RectangleShape), containerColor = Color(0xFF1E1E1E), shape = RectangleShape) {
            options.forEach { (label, value) -> DropdownMenuItem(text = { Text(text = label, color = Color.White) }, onClick = { onChange(value); expanded = false }) }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// ── HISTORY UI ───────────────────────────────────────────────────
// ═══════════════════════════════════════════════════════════════════

@Composable
fun HistoryUI(
    history: List<HistoryItem>,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
    faviconBitmaps: Map<String, Bitmap?>,
    loadFavicon: (String) -> Unit
) {
    Popup(alignment = Alignment.TopStart, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().background(Color(0xFF1E1E1E)), color = Color(0xFF1E1E1E)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ onDismiss() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                    Spacer(Modifier.width(4.dp)); Text("History", color = Color.White, fontSize = 18.sp)
                    if (history.isNotEmpty()) { Spacer(Modifier.width(8.dp)); Text("(${history.size})", color = Color.Gray, fontSize = 14.sp) }
                }
                if (history.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("No history", color = Color.Gray, fontSize = 16.sp) }
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                        items(history.reversed()) { item ->
                            val domain = getDomainName(item.url)
                            LaunchedEffect(item.url) { loadFavicon(domain) }
                            val fav = faviconBitmaps[domain]
                            Surface(Modifier.fillMaxWidth().padding(vertical = 2.dp).border(0.5.dp, Color.DarkGray, RectangleShape), color = Color.Transparent) {
                                Row(Modifier.fillMaxWidth().padding(12.dp).clickable { onOpenUrl(item.url); onDismiss() }, verticalAlignment = Alignment.CenterVertically) {
                                    if (fav != null) Image(fav.asImageBitmap(), domain, Modifier.size(20.dp).clip(CircleShape), contentScale = ContentScale.Fit)
                                    else Box(Modifier.size(20.dp).clip(CircleShape).background(Color.DarkGray), contentAlignment = Alignment.Center) { Text(domain.take(1).uppercase(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(item.title.ifBlank { item.url }, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(item.url, color = Color.Gray.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
// ── COMPOSABLE HELPERS ────────────────────────────────────────────
// ═══════════════════════════════════════════════════════════════════

@Composable
fun AllGroupChip(isSelected: Boolean, tabCount: Int, onClick: () -> Unit) {
    val bg = if (isSelected) Color.White else Color.Transparent
    val fg = if (isSelected) Color.Black else Color.White
    val borderColor = if (isSelected) Color.White else Color.White.copy(alpha = 0.2f)
    Surface(Modifier.padding(vertical = 4.dp).width(52.dp).clickable { onClick() }.border(0.5.dp, borderColor, RectangleShape), color = bg) {
        Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("All", color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Box(Modifier.background(if (isSelected) Color.LightGray else Color.DarkGray).padding(horizontal = 4.dp, vertical = 1.dp)) { Text(tabCount.toString(), color = fg, fontSize = 9.sp) }
        }
    }
}

@Composable
fun SidebarGroupChip(domain: String, isSelected: Boolean, tabCount: Int, onClick: () -> Unit, favicon: Bitmap?, onAppear: () -> Unit, isBlinking: Boolean, isPinned: Boolean) {
    LaunchedEffect(domain) { onAppear() }
    val blinkAlpha = if (isBlinking) {
        val t = rememberInfiniteTransition(label = "blink_$domain")
        t.animateFloat(0.2f, 0.5f, infiniteRepeatable(tween(400), RepeatMode.Reverse), label = "blinkV")
    } else null
    val bgAlpha = if (isBlinking && blinkAlpha != null) blinkAlpha.value else if (isSelected) 1f else 0f
    val bg = Color.White.copy(alpha = bgAlpha)
    val fg = if (isSelected) Color.Black else Color.White
    val borderColor = if (isSelected) Color.White else Color.White.copy(alpha = 0.2f)
    Surface(Modifier.padding(vertical = 4.dp).width(52.dp).clickable { onClick() }.border(0.5.dp, borderColor, RectangleShape), color = bg) {
        Box(Modifier.padding(6.dp)) {
            if (isPinned) Icon(Icons.Default.PushPin, "Pinned", tint = fg, modifier = Modifier.size(12.dp).align(Alignment.TopStart))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(4.dp))
                if (favicon != null) Image(favicon.asImageBitmap(), domain, Modifier.size(24.dp).clip(CircleShape), contentScale = ContentScale.Fit)
                else Box(Modifier.size(24.dp).clip(CircleShape).background(if (isSelected) Color.LightGray else Color.DarkGray), contentAlignment = Alignment.Center) { Text(domain.take(1).uppercase(), color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
            Box(Modifier.align(Alignment.BottomEnd).background(if (isSelected) Color.LightGray else Color.DarkGray).padding(horizontal = 4.dp, vertical = 1.dp)) { Text(tabCount.toString(), color = fg, fontSize = 9.sp) }
        }
    }
}

@Composable
fun ContextMenuItem(text: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) { Text(text, color = Color.White, fontSize = 16.sp) }
}

// END OF PART 10/10
// END OF FILE - Grey Browser V3.0
// ═══════════════════════════════════════════════════════════════════

