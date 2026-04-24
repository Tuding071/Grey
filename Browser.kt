package com.grey.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

// ── Media Management (LRU Cache) ──────────────────────────────────
object GeckoMediaManager {
    fun saveMedia(context: Context, id: String, bitmap: Bitmap, folder: String, limit: Int) {
        val dir = File(context.filesDir, folder).apply { if (!exists()) mkdirs() }
        val file = File(dir, "$id.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
        }
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size > limit) {
            files.take(files.size - limit).forEach { it.delete() }
        }
    }

    fun loadMedia(context: Context, id: String, folder: String): Bitmap? {
        val file = File(File(context.filesDir, folder), "$id.jpg")
        return if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }
}

// ── State Wrapper ──────────────────────────────────────────────────
class TabState(val session: GeckoSession, val id: String = UUID.randomUUID().toString()) {
    var title by mutableStateOf("New Tab")
    var url by mutableStateOf("about:blank")
    var lastUpdated by mutableLongStateOf(System.currentTimeMillis())
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GreyBrowser() }
    }
}

@Composable
fun GreyBrowser() {
    val context = LocalContext.current
    val runtime = remember { GeckoRuntime.create(context) }
    val tabs = remember { mutableStateListOf<TabState>() }
    var currentTabIndex by remember { mutableIntStateOf(0) }
    var showTabManager by remember { mutableStateOf(false) }
    var previewTab by remember { mutableStateOf<TabState?>(null) }

    // Helper to add tabs
    fun addNewTab(url: String = "about:blank") {
        val session = GeckoSession().apply { open(runtime); loadUri(url) }
        val newTab = TabState(session)
        
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onLocationChange(session: GeckoSession, url: String?) {
                newTab.url = url ?: "about:blank"
                newTab.lastUpdated = System.currentTimeMillis()
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                newTab.title = title ?: "New Tab"
            }
        }
        tabs.add(newTab)
        currentTabIndex = tabs.lastIndex
    }

    // Initialize first tab
    if (tabs.isEmpty()) { LaunchedEffect(Unit) { addNewTab() } }

    val currentTab = tabs.getOrNull(currentTabIndex) ?: return

    // URL Field State
    var urlInput by remember { mutableStateOf(TextFieldValue(currentTab.url)) }
    LaunchedEffect(currentTab.url) { urlInput = TextFieldValue(currentTab.url) }

    BackHandler {
        if (showTabManager) showTabManager = false
        else if (currentTab.session.canGoBack()) currentTab.session.goBack()
        else if (tabs.size > 1) {
            tabs.removeAt(currentTabIndex)
            currentTabIndex = tabs.lastIndex
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Top Bar ──
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { 
                    // Capture thumbnail before opening manager
                    currentTab.session.screenshot().accept { bm ->
                        if (bm != null) GeckoMediaManager.saveMedia(context, currentTab.id, bm, "thumbs", 50)
                    }
                    showTabManager = true 
                }) {
                    Icon(Icons.Default.Tab, null, tint = Color.White)
                }

                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    modifier = Modifier.weight(1f).onFocusChanged {
                        if (it.isFocused) {
                            urlInput = urlInput.copy(selection = TextRange(0, urlInput.text.length))
                        }
                    },
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                    singleLine = true,
                    shape = RectangleShape,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { currentTab.session.loadUri(urlInput.text) }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.White, unfocusedBorderColor = Color.Gray,
                        focusedContainerColor = Color.Black, unfocusedContainerColor = Color.Black
                    )
                )
            }

            // ── GeckoView Content ──
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { GeckoView(it).apply { setSession(currentTab.session) } },
                    update = { it.setSession(currentTab.session) },
                    modifier = Modifier.fillMaxSize()
                )

                // ── Full Screen Tab Manager ──
                if (showTabManager) {
                    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                        Column(modifier = Modifier.systemBarsPadding()) {
                            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { showTabManager = false }) { Icon(Icons.Default.Close, null, tint = Color.White) }
                                Text("Tabs", color = Color.White, fontSize = 20.sp)
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { addNewTab(); showTabManager = false }) { Icon(Icons.Default.Add, null, tint = Color.White) }
                            }

                            LazyColumn(modifier = Modifier.weight(1f)) {
                                items(tabs) { tab ->
                                    val isCurrent = tab == currentTab
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .background(if (isCurrent) Color(0xFF1A1A1A) else Color.Transparent)
                                        .clickable { previewTab = tab }
                                        .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(tab.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        IconButton(onClick = { 
                                            val idx = tabs.indexOf(tab)
                                            tabs.remove(tab)
                                            if (tabs.isEmpty()) addNewTab()
                                            currentTabIndex = tabs.lastIndex
                                        }) { Icon(Icons.Default.Close, null, tint = Color.Gray, modifier = Modifier.size(18.dp)) }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Center Preview Popup ──
                if (previewTab != null) {
                    Popup(alignment = Alignment.Center, onDismissRequest = { previewTab = null }) {
                        Card(
                            modifier = Modifier.fillMaxWidth(0.8f).aspectRatio(0.7f),
                            border = BorderStroke(1.dp, Color.White),
                            colors = CardDefaults.cardColors(containerColor = Color.Black),
                            shape = RectangleShape
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                val thumb = GeckoMediaManager.loadMedia(context, previewTab!!.id, "thumbs")
                                Box(modifier = Modifier.weight(1f).fillMaxWidth().clickable {
                                    currentTabIndex = tabs.indexOf(previewTab)
                                    previewTab = null
                                    showTabManager = false
                                }) {
                                    if (thumb != null) {
                                        Image(thumb.asImageBitmap(), null, modifier = Modifier.fillMaxSize())
                                    } else {
                                        Text("No Preview Available", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
                                    }
                                }
                                Text(previewTab!!.title, color = Color.White, modifier = Modifier.padding(8.dp), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
