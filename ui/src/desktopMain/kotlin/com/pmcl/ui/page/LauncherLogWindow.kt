package com.pmcl.ui.page

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.pmcl.core.i18n.I18n
import com.pmcl.core.util.LauncherLogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** 认证开发者的 PMCL 终端日志窗口。只读，显示启动器自己的输出。 */
@Composable
fun LauncherLogWindow(onClose: () -> Unit) {
    val state = rememberWindowState(width = 860.dp, height = 520.dp)
    Window(
        onCloseRequest = onClose,
        icon = com.pmcl.ui.navigation.pmclWindowIcon(),
        title = I18n.t("settings.launcher_log_window_title"),
        state = state
    ) {
        val lines = remember { mutableStateListOf<String>() }
        LaunchedEffect(Unit) {
            var seen = -1
            while (isActive) {
                val rev = LauncherLogCollector.revision()
                if (rev != seen) {
                    val snap = withContext(Dispatchers.IO) { LauncherLogCollector.snapshot() }
                    lines.clear()
                    lines.addAll(snap)
                    seen = rev
                }
                delay(400)
            }
        }
        val scroll = rememberLazyListState()
        LaunchedEffect(lines.size) {
            if (lines.isNotEmpty()) scroll.scrollToItem(lines.lastIndex)
        }
        Surface(Modifier.fillMaxSize(), color = Color(0xFF1C1C1C)) {
            if (lines.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(16.dp)) {
                    Text(
                        I18n.t("settings.launcher_log_window_empty"),
                        color = Color(0xFF9A9A9A),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    state = scroll,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    itemsIndexed(lines) { _, line ->
                        Text(
                            line.ifEmpty { " " },
                            color = Color(0xFFE6E6E6),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}
