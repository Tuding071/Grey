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
//PART 0 END

//PART 1 START
package com.grey.browser

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

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
}

data class HistoryItem(val url: String, val title: String)

const val MAX_WARM_TABS = 15

@Composable
fun GreyBrowser() {
    val context = LocalContext.current
    val runtime = remember { GeckoRuntime.create(context) }
    val focusManager = LocalFocusManager.current

    val tabs = remember { mutableStateListOf(TabState()) }
    var currentTabIndex by remember { mutableIntStateOf(-1) }
    var urlInput by remember { mutableStateOf("") }
    var showTabManager by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<HistoryItem>() }

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
        s.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                tab.title = title ?: tab.url
            }
        }
        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                tab.url = url
                tab.isBlank = false
                if (url.isNotBlank() && url != "about:blank") {
                    history.removeAll { it.url == url }
                    history.add(HistoryItem(url, tab.title))
                }
            }
        }
    }

    fun ensureSession(tab: TabState): GeckoSession {
        if (tab.session == null) {
            val s = GeckoSession()
            s.open(runtime)
            tab.session = s
            setupDelegates(tab)
        }
        return tab.session!!
    }

    fun manageWarmTabs(activeIndex: Int) {
        tabs.forEachIndexed { i, tab ->
            tab.session?.setActive(i == activeIndex)
        }
        val warmCount = tabs.count { it.session != null && tabs.indexOf(it) != activeIndex }
        if (warmCount > MAX_WARM_TABS) {
            val toMakeCold = tabs
                .filter { it.session != null && tabs.indexOf(it) != activeIndex }
                .take(warmCount - MAX_WARM_TABS)
            for (tab in toMakeCold) {
                tab.session?.close()
                tab.session = null
            }
        }
    }

    fun loadUrl(url: String) {
        val resolved = resolveUrl(url)
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
        manageWarmTabs(currentTabIndex)
    }

    fun switchToTab(index: Int) {
        currentTabIndex = index
        val tab = tabs[index]
        urlInput = if (tab.isBlank) "" else tab.url
        showTabManager = false
        ensureSession(tab)
        manageWarmTabs(index)
    }

    fun closeTab(index: Int) {
        if (tabs.size == 1) {
            tabs[0].session?.close()
            tabs[0] = TabState()
            currentTabIndex = -1
            urlInput = ""
        } else {
            tabs[index].session?.close()
            tabs.removeAt(index)
            if (currentTabIndex == index) {
                currentTabIndex = if (index >= tabs.size) tabs.lastIndex else index
                urlInput = if (currentTabIndex >= 0 && !tabs[currentTabIndex].isBlank) tabs[currentTabIndex].url else ""
            } else if (currentTabIndex > index) {
                currentTabIndex--
            }
        }
        manageWarmTabs(currentTabIndex)
    }

    fun newTab() {
        tabs.add(TabState())
        currentTabIndex = -1
        urlInput = ""
        showTabManager = false
    }

    LaunchedEffect(currentTabIndex) {
        if (currentTabIndex >= 0) {
            val tab = tabs[currentTabIndex]
            urlInput = if (tab.isBlank) "" else tab.url
            ensureSession(tab)
            manageWarmTabs(currentTabIndex)
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
                if (urlInput.isEmpty()) {
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
                    modifier = Modifier.fillMaxWidth()
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

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val tab = if (currentTabIndex >= 0) tabs.getOrNull(currentTabIndex) else null
            if (tab != null && !tab.isBlank && tab.session != null) {
                val s = tab.session!!
                AndroidView(
                    factory = { ctx -> GeckoView(ctx).apply { setSession(s) } },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("GREY", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showTabManager) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF1A1817))
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
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .background(Color(0xFF292625))
                        .clickable { newTab() }
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("New Tab", color = Color.White, fontSize = 14.sp)
                    }
                }

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
                                            t.url,
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

    if (showHistory) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF1A1817))
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
                                        item.url,
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
//PART 2 END
