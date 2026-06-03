```
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
// All secondary screens (Tab Manager, History, Settings, etc.) use:
// Popup(
//     alignment = Alignment.TopStart,
//     onDismissRequest = { ... },
//     properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false)
// ) {
//     Surface(Modifier.fillMaxSize().statusBarsPadding().background(WarmGrey), color = WarmGrey) {
//         Column(Modifier.fillMaxSize()) {
//             // Header: Close button + title + count
//             // Content: LazyColumn
//             // Optional bottom button
//         }
//     }
// }
//PART 0 END

//PART 1 START
package com.grey.browser

import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import java.io.File

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
}

data class HistoryItem(val url: String, val title: String, val timestamp: Long = System.currentTimeMillis())

const val MAX_WARM_TABS = 15

fun stripHttps(url: String): String {
    return url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
}

@Composable
fun GreyBrowser() {
    val context = LocalContext.current
    val runtime = remember { GeckoRuntime.create(context) }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    val tabs = remember { mutableStateListOf<TabState>() }
    var currentTabIndex by remember { mutableIntStateOf(-1) }
    var urlInput by remember { mutableStateOf("") }
    var isUrlFocused by remember { mutableStateOf(false) }
    var showTabManager by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<HistoryItem>() }
    var lastActiveUrl by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            }
        }
    }

    LaunchedEffect(Unit) {
        val data = importBackup()
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
        if (tabs.isNotEmpty() && lastActiveUrl.isNotBlank()) {
            val idx = tabs.indexOfFirst { it.url == lastActiveUrl }
            if (idx >= 0) currentTabIndex = idx
        }
    }

    LaunchedEffect(history.toList(), tabs.map { "${it.url}|${it.title}" }.joinToString(), lastActiveUrl) {
        exportBackup(tabs, history, lastActiveUrl)
    }

    fun resolveUrl(input: String): String {
        if (input.isBlank()) return input
        return if (input.contains("://") || (input.contains(".") && !input.contains(" "))) {
            if (input.contains("://")) input else "https://$input"
        } else {
            "https://www.google.com/search?q=${input.replace(" ", "+")}"
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
                    val baseUrl = tab.url.substringBefore("#")
                    history.removeAll { it.url.substringBefore("#") == baseUrl }
                    history.add(HistoryItem(tab.url, newTitle))
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
                tab.lastUpdated = System.currentTimeMillis()
                if (tabs.indexOf(tab) == currentTabIndex) {
                    urlInput = if (isUrlFocused) tab.url else stripHttps(tab.url)
                }
                if (success && !historySaved && tab.title != "New Tab"
                    && tab.url.isNotBlank() && tab.url != "about:blank") {
                    historySaved = true
                    val baseUrl = tab.url.substringBefore("#")
                    history.removeAll { it.url.substringBefore("#") == baseUrl }
                    history.add(HistoryItem(tab.url, tab.title))
                }
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
            Box(
                Modifier
                    .background(Color(0xFF292625))
                    .clickable { showTabManager = true }
                    .padding(horizontal = 10.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tab, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(tabs.size.toString(), color = Color.White, fontSize = 14.sp)
                }
            }

            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .background(Color(0xFF292625))
                    .padding(horizontal = 12.dp, vertical = 14.dp)
            ) {
                if (urlInput.isEmpty() && !isUrlFocused) {
                    Text("Search or enter URL", color = Color.White.copy(alpha = 0.5f), fontSize = 16.sp)
                }
                BasicTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    singleLine = true,
                    textStyle = TextStyle(Color.White, 16.sp),
                    cursorBrush = SolidColor(Color.White),
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
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.2f)))

        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF1A1817))) {
            val tab = if (currentTabIndex >= 0) tabs.getOrNull(currentTabIndex) else null
            val activeSession = tab?.session
            if (tab != null && !tab.isBlank && activeSession != null) {
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
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .background(Color(0xFF292625))
                                        .clickable { switchToTab(index) }
                                        .padding(12.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                t.title,
                                                color = Color.White,
                                                fontSize = 14.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (!t.isBlank) {
                                                Text(
                                                    stripHttps(t.url),
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
                                    Column {
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
    if (!BACKUP_FILE.exists()) return BackupData("", emptyList(), emptyList())
    return try {
        val json = BACKUP_FILE.readText()
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
        BackupData(lastActiveUrl, tabs, history)
    } catch (e: Exception) {
        BackupData("", emptyList(), emptyList())
    }
}

fun exportBackup(
    tabs: List<TabState>,
    history: List<HistoryItem>,
    lastActiveUrl: String
) {
    try {
        if (!BACKUP_DIR.exists()) BACKUP_DIR.mkdirs()
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
        tempFile.renameTo(BACKUP_FILE)
    } catch (e: Exception) { }
}
//PART 3 END
```
