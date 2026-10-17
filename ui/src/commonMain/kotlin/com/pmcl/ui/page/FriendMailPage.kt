package com.pmcl.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.mail.MailAccount
import com.pmcl.core.mail.MailAccountStore
import com.pmcl.core.mail.MailClient
import com.pmcl.core.mail.MailCodec
import com.pmcl.core.mail.MailException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

private class MailHold {
    val mutex = Mutex()
    var session: MailClient.Session? = null
    var accountId: String? = null
}

@Composable
fun FriendMailPage(modifier: Modifier = Modifier) {
    val store = remember { MailAccountStore.open() }
    val hold = remember { MailHold() }
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf(store.accounts()) }
    var accountId by remember { mutableStateOf(accounts.firstOrNull()?.id) }
    var folders by remember { mutableStateOf<List<MailClient.Folder>>(emptyList()) }
    var folderName by remember { mutableStateOf<String?>(null) }
    var localDrafts by remember { mutableStateOf(false) }
    var headers by remember { mutableStateOf<List<MailClient.Header>>(emptyList()) }
    var drafts by remember { mutableStateOf<List<MailAccountStore.Draft>>(emptyList()) }
    var letter by remember { mutableStateOf<MailClient.Letter?>(null) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var composing by remember { mutableStateOf(false) }
    var to by remember { mutableStateOf("") }
    var cc by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var draftId by remember { mutableStateOf<String?>(null) }
    var inReplyTo by remember { mutableStateOf("") }
    var showAccount by remember { mutableStateOf(false) }
    var accountError by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MailAccount?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val account = accounts.firstOrNull { it.id == accountId }

    suspend fun <T> use(id: String, block: (MailClient.Session) -> T): T = withContext(Dispatchers.IO) {
        hold.mutex.withLock {
            val current = store.find(id) ?: throw MailException("friend.mail.err_account", "")
            if (hold.session == null || hold.accountId != current.id) {
                hold.session?.close()
                hold.session = MailClient.Session.open(current)
                hold.accountId = current.id
            }
            try {
                block(hold.session!!)
            } catch (e: MailException) {
                hold.session?.close()
                hold.session = null
                hold.accountId = null
                throw e
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            hold.session?.close()
            hold.session = null
        }
    }

    LaunchedEffect(accountId, generation) {
        val id = accountId ?: return@LaunchedEffect
        busy = true
        error = ""
        try {
            val loaded = use(id) { session -> session.folders() }
            if (!isActive) return@LaunchedEffect
            folders = loaded
            drafts = store.drafts(id)
            val chosen = folderName?.takeIf { name -> loaded.any { it.name == name } }
                ?: loaded.firstOrNull { it.role == "inbox" }?.name
                ?: "INBOX"
            if (folderName != chosen && !localDrafts) folderName = chosen
        } catch (e: MailException) {
            if (isActive) error = mailError(e)
        } finally {
            if (isActive) busy = false
        }
    }

    LaunchedEffect(accountId, folderName, generation, localDrafts) {
        val id = accountId ?: return@LaunchedEffect
        if (localDrafts) {
            drafts = store.drafts(id)
            return@LaunchedEffect
        }
        val name = folderName ?: return@LaunchedEffect
        busy = true
        try {
            val loaded = use(id) { session -> session.messages(name) }
            if (!isActive) return@LaunchedEffect
            headers = loaded
            letter = null
        } catch (e: MailException) {
            if (isActive) error = mailError(e)
        } finally {
            if (isActive) busy = false
        }
    }

    if (showAccount) {
        MailAccountDialog(
            initial = editing,
            busy = busy,
            error = accountError,
            onDismiss = { showAccount = false },
            onSave = { input ->
                scope.launch {
                    busy = true
                    error = ""
                    try {
                        val saved = withContext(Dispatchers.IO) {
                            val prepared = store.prepare(input)
                            MailClient.Session.open(prepared).use { it.folders() }
                            store.save(prepared)
                        }
                        accounts = store.accounts()
                        accountId = saved.id
                        folderName = null
                        localDrafts = false
                        showAccount = false
                        accountError = ""
                        generation++
                    } catch (e: MailException) {
                        accountError = mailError(e)
                    } finally {
                        busy = false
                    }
                }
            }
        )
    }
    if (confirmRemove && account != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(I18n.t("friend.mail.remove_account")) },
            text = { Text(account.address) },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(account.id)
                    hold.session?.close()
                    hold.session = null
                    accounts = store.accounts()
                    accountId = accounts.firstOrNull()?.id
                    folderName = null
                    letter = null
                    confirmRemove = false
                    generation++
                }) { Text(I18n.t("friend.mail.remove_account")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text(I18n.t("friend.mail.discard")) }
            }
        )
    }

    Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.width(168.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
            Button(onClick = {
                editing = null
                accountError = ""
                showAccount = true
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Edit, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(I18n.t("friend.mail.add_account"))
            }
            Spacer(Modifier.height(8.dp))
            accounts.forEach { item ->
                val active = item.id == accountId
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (accountId != item.id) {
                            accountId = item.id
                            folderName = null
                            localDrafts = false
                            letter = null
                            composing = false
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    else MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                ) {
                    Text(
                        item.address,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (account != null) {
                Row {
                    TextButton(onClick = { editing = account; accountError = ""; showAccount = true }) {
                        Text(I18n.t("friend.mail.edit"))
                    }
                    TextButton(onClick = { confirmRemove = true }) {
                        Text(I18n.t("friend.mail.remove_account"), maxLines = 1)
                    }
                }
                folders.forEach { folder ->
                    val active = !localDrafts && folder.name == folderName && !composing
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable {
                            folderName = folder.name
                            localDrafts = false
                            composing = false
                            letter = null
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                    ) {
                        Text(
                            folderLabel(folder),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                val draftCount = drafts.size
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable {
                        localDrafts = true
                        composing = false
                        letter = null
                        drafts = store.drafts(account.id)
                    },
                    shape = RoundedCornerShape(8.dp),
                    color = if (localDrafts) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    else MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                ) {
                    Text(
                        I18n.t("friend.mail.local_drafts") + if (draftCount > 0) "  $draftCount" else "",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                I18n.t("friend.mail.hint"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Surface(
            modifier = Modifier.width(280.dp).fillMaxHeight(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        I18n.t("friend.mail.recent", MailClient.LIST_LIMIT),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { generation++ }, enabled = accountId != null && !busy) {
                        Icon(Icons.Filled.Refresh, I18n.t("friend.mail.refresh"), modifier = Modifier.size(16.dp))
                    }
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }
                when {
                    account == null -> Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                        Text(I18n.t("friend.mail.no_account"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    localDrafts && drafts.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(I18n.t("friend.mail.empty"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    localDrafts -> LazyColumn(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(drafts, key = { it.id }) { draft ->
                            Column(
                                Modifier.fillMaxWidth().clickable {
                                    composing = true
                                    draftId = draft.id
                                    to = draft.to ?: ""
                                    cc = draft.cc ?: ""
                                    subject = draft.subject ?: ""
                                    body = draft.body ?: ""
                                    inReplyTo = ""
                                    letter = null
                                }.padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text(draft.subject?.ifBlank { I18n.t("friend.mail.no_subject") } ?: I18n.t("friend.mail.no_subject"),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                Text(draft.to ?: "", style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    headers.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(I18n.t("friend.mail.empty"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> LazyColumn(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(headers, key = { it.uid }) { header ->
                            val active = letter?.uid == header.uid && !composing
                            Column(
                                Modifier.fillMaxWidth()
                                    .background(
                                        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        val id = accountId ?: return@clickable
                                        val name = folderName ?: return@clickable
                                        composing = false
                                        scope.launch {
                                            busy = true
                                            try {
                                                val opened = use(id) { session -> session.read(name, header.uid) }
                                                letter = opened
                                                headers = headers.map {
                                                    if (it.uid == header.uid) MailClient.Header(it.uid, it.from, it.subject, it.date, false)
                                                    else it
                                                }
                                            } catch (e: MailException) {
                                                error = mailError(e)
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (header.unread) {
                                        Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text(
                                        header.subject.ifBlank { I18n.t("friend.mail.no_subject") },
                                        modifier = Modifier.weight(1f),
                                        fontWeight = if (header.unread) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(header.from, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (header.date.isNotBlank()) {
                                    Text(header.date, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        ) {
            when {
                account == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(I18n.t("friend.mail.no_account"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                composing -> MailComposer(
                    to = to,
                    cc = cc,
                    subject = subject,
                    body = body,
                    notice = notice,
                    hasDraft = draftId != null,
                    onTo = { to = it },
                    onCc = { cc = it },
                    onSubject = { subject = MailCodec.clip(it, MailCodec.SUBJECT_MAX) },
                    onBody = { body = MailCodec.clip(it, MailCodec.BODY_MAX) },
                    onSend = {
                        val id = accountId ?: return@MailComposer
                        scope.launch {
                            busy = true
                            notice = ""
                            try {
                                withContext(Dispatchers.IO) {
                                    val current = store.find(id) ?: throw MailException("friend.mail.err_account", "")
                                    MailClient.send(current, to, cc, subject, body, inReplyTo)
                                }
                                notice = I18n.t("friend.mail.sent_ok")
                                composing = false
                                draftId?.let { store.deleteDraft(it) }
                                draftId = null
                                to = ""
                                cc = ""
                                subject = ""
                                body = ""
                                inReplyTo = ""
                                generation++
                            } catch (e: MailException) {
                                notice = mailError(e)
                            } finally {
                                busy = false
                            }
                        }
                    },
                    onSave = {
                        val id = accountId ?: return@MailComposer
                        scope.launch {
                            busy = true
                            try {
                                use(id) { session -> session.saveDraft(to, cc, subject, body) }
                                notice = I18n.t("friend.mail.draft_saved")
                                draftId?.let { store.deleteDraft(it) }
                                draftId = null
                                generation++
                            } catch (e: MailException) {
                                val saved = try {
                                    store.saveDraft(draftId, id, to, cc, subject, body)
                                } catch (ignored: MailException) {
                                    null
                                }
                                if (saved != null) {
                                    draftId = saved.id
                                    drafts = store.drafts(id)
                                    notice = I18n.t("friend.mail.draft_kept")
                                } else {
                                    notice = mailError(e)
                                }
                            } finally {
                                busy = false
                            }
                        }
                    },
                    onDiscard = {
                        composing = false
                        notice = ""
                    },
                    onDelete = {
                        draftId?.let { store.deleteDraft(it) }
                        draftId = null
                        composing = false
                        drafts = accountId?.let { store.drafts(it) } ?: emptyList()
                    }
                )
                letter == null -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(I18n.t("friend.mail.reading_empty"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            composing = true
                            draftId = null
                            to = ""
                            cc = ""
                            subject = ""
                            body = ""
                            inReplyTo = ""
                            notice = ""
                        }, enabled = accountId != null) { Text(I18n.t("friend.mail.compose")) }
                        if (error.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text(
                        letter!!.subject.ifBlank { I18n.t("friend.mail.no_subject") },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(I18n.t("friend.mail.from") + "  " + letter!!.from)
                    if (letter!!.to.isNotBlank()) Text(I18n.t("friend.mail.to") + "  " + letter!!.to)
                    if (letter!!.cc.isNotBlank()) Text(I18n.t("friend.mail.cc") + "  " + letter!!.cc)
                    if (letter!!.date.isNotBlank()) {
                        Text(letter!!.date, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            composing = true
                            draftId = null
                            to = MailCodec.addressOnly(letter!!.from)
                            cc = ""
                            val title = letter!!.subject
                            subject = when {
                                title.isBlank() -> "Re:"
                                title.startsWith("Re:") -> title
                                else -> MailCodec.clip("Re: $title", MailCodec.SUBJECT_MAX)
                            }
                            body = MailCodec.clip("\n\n" + letter!!.text.lineSequence().joinToString("\n") { "> $it" },
                                MailCodec.BODY_MAX)
                            inReplyTo = letter!!.messageId
                            notice = ""
                        }) { Text(I18n.t("friend.mail.reply")) }
                        TextButton(onClick = {
                            val id = accountId ?: return@TextButton
                            val name = folderName ?: return@TextButton
                            val uid = letter!!.uid
                            scope.launch {
                                busy = true
                                try {
                                    use(id) { session -> session.moveToTrash(name, uid) }
                                    letter = null
                                    generation++
                                } catch (e: MailException) {
                                    error = mailError(e)
                                } finally {
                                    busy = false
                                }
                            }
                        }) { Text(I18n.t("friend.mail.delete")) }
                    }
                    if (letter!!.fromHtml) {
                        Text(I18n.t("friend.mail.html_note"), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (error.isNotBlank()) {
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    SelectionContainer {
                        Text(letter!!.text, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (letter!!.attachments.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(I18n.t("friend.mail.attachments"), fontWeight = FontWeight.Medium)
                        letter!!.attachments.forEach { attachment ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(attachment.name, modifier = Modifier.weight(1f), maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                if (attachment.data != null) {
                                    TextButton(onClick = { saveAttachment(attachment.name, attachment.data) }) {
                                        Text(I18n.t("friend.mail.save_attachment"))
                                    }
                                } else {
                                    Text(I18n.t("friend.mail.attachment_big"), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MailComposer(
    to: String,
    cc: String,
    subject: String,
    body: String,
    notice: String,
    hasDraft: Boolean,
    onTo: (String) -> Unit,
    onCc: (String) -> Unit,
    onSubject: (String) -> Unit,
    onBody: (String) -> Unit,
    onSend: () -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        OutlinedTextField(value = to, onValueChange = onTo, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(I18n.t("friend.mail.to")) })
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = cc, onValueChange = onCc, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(I18n.t("friend.mail.cc")) })
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = subject, onValueChange = onSubject, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(I18n.t("friend.mail.subject")) })
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = body, onValueChange = onBody, modifier = Modifier.fillMaxWidth().weight(1f),
            label = { Text(I18n.t("friend.mail.body")) })
        if (notice.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(notice, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSend) { Text(I18n.t("friend.mail.send")) }
            OutlinedButton(onClick = onSave) { Text(I18n.t("friend.mail.save_draft")) }
            TextButton(onClick = onDiscard) { Text(I18n.t("friend.mail.discard")) }
            if (hasDraft) {
                TextButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(I18n.t("friend.mail.delete"))
                }
            }
        }
    }
}

@Composable
private fun MailAccountDialog(
    initial: MailAccount?,
    busy: Boolean,
    error: String,
    onDismiss: () -> Unit,
    onSave: (MailAccount) -> Unit,
) {
    val starting = initial?.let { MailAccount.Preset.forAddress(it.address) } ?: MailAccount.Preset.CUSTOM
    var presetId by remember { mutableStateOf(starting.id) }
    var address by remember { mutableStateOf(initial?.address ?: "") }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf("") }
    var imapHost by remember { mutableStateOf(initial?.imapHost ?: "") }
    var imapPort by remember { mutableStateOf((initial?.imapPort ?: 993).toString()) }
    var imapSecurity by remember { mutableStateOf(initial?.imapSecurity ?: "ssl") }
    var smtpHost by remember { mutableStateOf(initial?.smtpHost ?: "") }
    var smtpPort by remember { mutableStateOf((initial?.smtpPort ?: 465).toString()) }
    var smtpSecurity by remember { mutableStateOf(initial?.smtpSecurity ?: "ssl") }
    var hostsEdited by remember { mutableStateOf(initial != null) }
    var menu by remember { mutableStateOf(false) }
    val preset = MailAccount.Preset.byId(presetId)

    fun apply(next: MailAccount.Preset) {
        presetId = next.id
        if (next.id != "custom") {
            imapHost = next.imapHost
            imapPort = next.imapPort.toString()
            imapSecurity = next.imapSecurity
            smtpHost = next.smtpHost
            smtpPort = next.smtpPort.toString()
            smtpSecurity = next.smtpSecurity
            hostsEdited = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (initial == null) I18n.t("friend.mail.add_account") else I18n.t("friend.mail.edit")) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Box {
                    OutlinedButton(onClick = { menu = true }) { Text(I18n.t(preset.labelKey)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        MailAccount.Preset.all().forEach { item ->
                            DropdownMenuItem(
                                text = { Text(I18n.t(item.labelKey)) },
                                onClick = { apply(item); menu = false }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = address,
                    onValueChange = {
                        address = it
                        if (!hostsEdited) {
                            val match = MailAccount.Preset.forAddress(it)
                            if (match != null) apply(match)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(I18n.t("friend.mail.address")) }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(I18n.t("friend.mail.username")) }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(I18n.t("friend.mail.password")) }
                )
                Text(I18n.t("friend.mail.password_hint"), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Text(I18n.t("friend.mail.imap"), style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = imapHost,
                    onValueChange = { imapHost = it; hostsEdited = true },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = imapPort,
                        onValueChange = { imapPort = it.filter { ch -> ch.isDigit() }.take(5); hostsEdited = true },
                        modifier = Modifier.width(100.dp),
                        singleLine = true,
                        label = { Text(I18n.t("friend.mail.port")) }
                    )
                    TextButton(onClick = { imapSecurity = "ssl"; hostsEdited = true }) {
                        Text(if (imapSecurity == "ssl") "● " + I18n.t("friend.mail.security_ssl") else I18n.t("friend.mail.security_ssl"))
                    }
                    TextButton(onClick = { imapSecurity = "starttls"; hostsEdited = true }) {
                        Text(if (imapSecurity == "starttls") "● " + I18n.t("friend.mail.security_starttls") else I18n.t("friend.mail.security_starttls"))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(I18n.t("friend.mail.smtp"), style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = smtpHost,
                    onValueChange = { smtpHost = it; hostsEdited = true },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = smtpPort,
                        onValueChange = { smtpPort = it.filter { ch -> ch.isDigit() }.take(5); hostsEdited = true },
                        modifier = Modifier.width(100.dp),
                        singleLine = true,
                        label = { Text(I18n.t("friend.mail.port")) }
                    )
                    TextButton(onClick = { smtpSecurity = "ssl"; hostsEdited = true }) {
                        Text(if (smtpSecurity == "ssl") "● " + I18n.t("friend.mail.security_ssl") else I18n.t("friend.mail.security_ssl"))
                    }
                    TextButton(onClick = { smtpSecurity = "starttls"; hostsEdited = true }) {
                        Text(if (smtpSecurity == "starttls") "● " + I18n.t("friend.mail.security_starttls") else I18n.t("friend.mail.security_starttls"))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val account = MailAccount()
                account.id = initial?.id ?: ""
                account.address = address.trim()
                account.username = username.trim()
                account.password = password
                account.imapHost = imapHost.trim()
                account.imapPort = imapPort.toIntOrNull() ?: 0
                account.imapSecurity = imapSecurity
                account.smtpHost = smtpHost.trim()
                account.smtpPort = smtpPort.toIntOrNull() ?: 0
                account.smtpSecurity = smtpSecurity
                onSave(account)
            }, enabled = !busy) { Text(I18n.t("friend.mail.save_connect")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(I18n.t("friend.mail.discard")) }
        }
    )
}

private fun folderLabel(folder: MailClient.Folder): String = when (folder.role) {
    "inbox" -> I18n.t("friend.mail.inbox")
    "sent" -> I18n.t("friend.mail.sent")
    "drafts" -> I18n.t("friend.mail.drafts")
    "trash" -> I18n.t("friend.mail.trash")
    "junk" -> I18n.t("friend.mail.junk")
    else -> folder.name
}

private fun mailError(error: MailException): String {
    val base = I18n.t(error.key)
    return if (error.detail.isBlank()) base else base + "\n" + error.detail
}

private fun saveAttachment(name: String, data: ByteArray) {
    try {
        SwingUtilities.invokeLater {
            val chooser = JFileChooser()
            chooser.selectedFile = File(name)
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile.writeBytes(data)
            }
        }
    } catch (e: Exception) {
        System.err.println("[Mail] 保存附件失败")
    }
}
