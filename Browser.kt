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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GreyBrowser() }
    }
}
//PART 1 END

//PART 2 START
@Composable
fun GreyBrowser() {
    val context = LocalContext.current
    val runtime = remember { GeckoRuntime.create(context) }
    var session by remember { mutableStateOf<GeckoSession?>(null) }
    var urlInput by remember { mutableStateOf("") }
    var isBrowsing by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    fun resolveUrl(input: String): String {
        if (input.isBlank()) return input
        return if (input.contains("://") || (input.contains(".") && !input.contains(" "))) {
            if (input.contains("://")) input else "https://$input"
        } else {
            "https://www.google.com/search?q=${input.replace(" ", "+")}"
        }
    }

    fun loadUrl(url: String) {
        val resolved = resolveUrl(url)
        urlInput = resolved
        val s = session ?: GeckoSession().also {
            it.open(runtime)
            session = it
        }
        s.loadUri(resolved)
        isBrowsing = true
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF1A1817))) {
        OutlinedTextField(
            value = urlInput,
            onValueChange = { urlInput = it },
            placeholder = { Text("Search or enter URL", color = Color.White.copy(alpha = 0.5f)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            textStyle = TextStyle(Color.White, 16.sp),
            shape = RectangleShape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = {
                focusManager.clearFocus()
                if (urlInput.isNotBlank()) loadUrl(urlInput)
            }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF292625),
                unfocusedContainerColor = Color(0xFF292625),
                focusedBorderColor = Color.White,
                unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                cursorColor = Color.White
            )
        )

        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.2f)))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val currentSession = session
            if (isBrowsing && currentSession != null) {
                AndroidView(
                    factory = { ctx -> GeckoView(ctx).apply { setSession(currentSession) } },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("GREY", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
//PART 2 END
