package com.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Gavel
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
import com.pmcl.ui.theme.glassCardBorder
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.theme.glassCardElevation
import com.pmcl.ui.theme.glassSurfaceVariantColor
import com.pmcl.ui.viewmodel.LauncherViewModel

/**
 * 协议同意门控页：首次打开 PMCL 时显示。
 *
 * 一条总勾选 + 三个协议链接；全文在对话框中阅读。
 */
@Composable
fun AgreementGatePage(vm: LauncherViewModel) {
    var agreed by remember { mutableStateOf(false) }
    var viewingDoc by remember { mutableStateOf<Pair<String, String>?>(null) }
    val contentScroll = rememberScrollState()

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 96.dp)
                .verticalScroll(contentScroll)
                .padding(horizontal = 24.dp)
                .padding(top = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.Gavel,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    I18n.t("agreement.title"),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    I18n.t("agreement.subtitle"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(24.dp))

                Card(
                    modifier = Modifier.fillMaxWidth().glassCardBorder(),
                    shape = RoundedCornerShape(12.dp),
                    colors = glassCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    elevation = glassCardElevation()
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text(
                            I18n.t("agreement.docs_heading"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))

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

                        HorizontalDivider(Modifier.padding(vertical = 12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Checkbox(
                                checked = agreed,
                                onCheckedChange = { agreed = it },
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Text(
                                I18n.t("agreement.accept_all"),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .padding(top = 12.dp, start = 4.dp)
                                    .weight(1f)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 14.dp)
                ) {
                    if (!agreed) {
                        Text(
                            I18n.t("agreement.warning"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { kotlin.system.exitProcess(0) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(I18n.t("agreement.decline"))
                        }
                        Button(
                            onClick = { vm.acceptAgreements() },
                            enabled = agreed,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(I18n.t("agreement.continue"))
                        }
                    }
                }
            }
        }
    }

    viewingDoc?.let { (title, resourceName) ->
        AgreementDocumentDialog(
            title = title,
            resourceName = resourceName,
            onDismiss = { viewingDoc = null }
        )
    }
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
                            .verticalScroll(scrollState)
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
