package com.pmcl.ui.page
import com.pmcl.ui.widget.pmclVerticalScroll

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.theme.glassSurfaceVariantColor
import com.pmcl.ui.viewmodel.LauncherViewModel

/**
 * 首次打开时的协议弹窗。点外面不会关掉；拒绝则退出。
 * 全文在另一层对话框里阅读。
 */
@Composable
fun AgreementGateDialog(vm: LauncherViewModel) {
    var agreed by remember { mutableStateOf(false) }
    var viewingDoc by remember { mutableStateOf<Pair<String, String>?>(null) }

    viewingDoc?.let { (title, resourceName) ->
        AgreementDocumentDialog(
            title = title,
            resourceName = resourceName,
            onDismiss = { viewingDoc = null }
        )
        return
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text(I18n.t("agreement.title"), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(
                    I18n.t("agreement.subtitle"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    I18n.t("agreement.docs_heading"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                AgreementLink(
                    title = I18n.t("agreement.user_agreement_title"),
                    onClick = {
                        viewingDoc = I18n.t("agreement.user_agreement_title") to "USER_AGREEMENT.txt"
                    }
                )
                AgreementLink(
                    title = I18n.t("agreement.disclaimer_title"),
                    onClick = {
                        viewingDoc = I18n.t("agreement.disclaimer_title") to "DISCLAIMER.txt"
                    }
                )
                AgreementLink(
                    title = I18n.t("agreement.license_title"),
                    onClick = {
                        viewingDoc = I18n.t("agreement.license_title") to "LICENSE.zh.txt"
                    }
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = agreed, onCheckedChange = { agreed = it })
                    Text(
                        I18n.t("agreement.accept_all"),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (!agreed) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        I18n.t("agreement.warning"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { kotlin.system.exitProcess(0) }) {
                Text(I18n.t("agreement.decline"))
            }
        },
        confirmButton = {
            Button(onClick = { vm.acceptAgreements() }, enabled = agreed) {
                Text(I18n.t("agreement.continue"))
            }
        }
    )
}

@Composable
private fun AgreementLink(
    title: String,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(
                textDecoration = TextDecoration.Underline
            ),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start
        )
    }
}

@Composable
private fun AgreementDocumentDialog(
    title: String,
    resourceName: String,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val docText by produceState(I18n.t("common.loading"), resourceName) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                Thread.currentThread().contextClassLoader
                    ?.getResourceAsStream(resourceName)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: I18n.t("agreement.doc_not_found", resourceName)
            }.getOrElse { I18n.t("agreement.load_failed", it.message ?: "") }
        }
    }
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Description, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        clipboardManager.setText(AnnotatedString(docText))
                    }) {
                        Icon(Icons.Filled.ContentCopy, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(I18n.t("common.copy_all"))
                    }
                }
                Surface(
                    color = glassSurfaceVariantColor(),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 280.dp, max = 440.dp)
                ) {
                    Text(
                        text = docText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .pmclVerticalScroll(scrollState)
                            .padding(12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(I18n.t("common.close"))
            }
        }
    )
}
