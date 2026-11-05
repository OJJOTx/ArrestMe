package com.arrestme.benchmark.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arrestme.benchmark.data.remote.dto.ConversationDto
import com.arrestme.benchmark.data.remote.dto.MessageDto
import com.arrestme.benchmark.data.remote.dto.StructuredRequestDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartnerChatScreen(
    conversation: ConversationDto?,
    messages: List<MessageDto>,
    currentUserId: String?,
    isLoading: Boolean,
    errorMessage: String?,
    activeSessionId: String? = null,
    onRefresh: () -> Unit,
    onSendMessage: (text: String) -> Unit,
    onSendStructuredRequest: (requestType: String, sessionId: String?, targetPackage: String?, durationMinutes: Int?, messageText: String?) -> Unit,
    onRespondToRequest: (requestId: String, decision: String, reason: String?) -> Unit,
    onCancelRequest: (requestId: String) -> Unit,
    onMarkRead: (lastReadMessageId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var messageText by rememberSaveable { mutableStateOf("") }
    var showComposerDialog by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val partner = conversation?.partner
        ?: conversation?.members?.firstOrNull { it.userId != currentUserId }?.let {
            com.arrestme.benchmark.data.remote.dto.UserSummaryDto(it.userId, it.username, it.displayName)
        }
    val title = partner?.displayName ?: partner?.username ?: "Partner Chat"
    val partnerUsername = partner?.username

    // Mark as read when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
            val latest = messages.lastOrNull { it.sender.id != currentUserId }
            if (latest != null) {
                onMarkRead(latest.id)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1)
                        if (partnerUsername != null) {
                            Text(
                                text = "@$partnerUsername",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { showComposerDialog = true },
                        modifier = Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Structured Request",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        placeholder = { Text("Message...") },
                        maxLines = 4,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(20.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            val trimmed = messageText.trim()
                            if (trimmed.isNotEmpty()) {
                                onSendMessage(trimmed)
                                messageText = ""
                            }
                        },
                        enabled = messageText.isNotBlank(),
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                if (messageText.isNotBlank()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                                CircleShape
                            )
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (messageText.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        modifier = modifier
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (messages.isEmpty() && !isLoading) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Say hello to your partner!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "You can send chat messages or structured requests (early release, temporary app access, extensions) anytime.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        when (msg.messageType) {
                            "SYSTEM_EVENT" -> SystemEventBubble(message = msg)
                            "STRUCTURED_REQUEST" -> StructuredRequestBubble(
                                message = msg,
                                currentUserId = currentUserId,
                                onRespond = onRespondToRequest,
                                onCancel = onCancelRequest
                            )
                            else -> TextMessageBubble(
                                message = msg,
                                isFromMe = msg.sender.id == currentUserId
                            )
                        }
                    }
                }
            }

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(28.dp)
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }

    if (showComposerDialog) {
        StructuredRequestComposerDialog(
            activeSessionId = activeSessionId,
            onDismiss = { showComposerDialog = false },
            onSubmit = { type, sessId, pkg, dur, msg ->
                onSendStructuredRequest(type, sessId, pkg, dur, msg)
                showComposerDialog = false
            }
        )
    }
}

@Composable
private fun TextMessageBubble(
    message: MessageDto,
    isFromMe: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isFromMe) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isFromMe) 16.dp else 2.dp,
                        bottomEnd = if (isFromMe) 2.dp else 16.dp
                    )
                )
                .background(
                    if (isFromMe) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column {
                if (!isFromMe) {
                    Text(
                        text = message.sender.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                Text(
                    text = message.textContent ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isFromMe) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SystemEventBubble(message: MessageDto) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ) {
            Text(
                text = message.textContent ?: "System notification",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun StructuredRequestBubble(
    message: MessageDto,
    currentUserId: String?,
    onRespond: (requestId: String, decision: String, reason: String?) -> Unit,
    onCancel: (requestId: String) -> Unit
) {
    val req = message.structuredRequest
    val isInitiator = message.sender.id == currentUserId
    val status = req?.status ?: "PENDING"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (status) {
                "APPROVED" -> Color(0xFF064E3B).copy(alpha = 0.15f)
                "REJECTED" -> Color(0xFF7F1D1D).copy(alpha = 0.15f)
                "CANCELLED", "EXPIRED" -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
            }
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header Row: Type and Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = formatRequestType(req?.requestType ?: "REQUEST"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = when (status) {
                        "APPROVED" -> Color(0xFF059669)
                        "REJECTED" -> Color(0xFFDC2626)
                        "CANCELLED", "EXPIRED" -> Color(0xFF6B7280)
                        else -> Color(0xFFD97706)
                    }
                ) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Body info
            if (!req?.targetPackage.isNullOrBlank()) {
                Text(
                    text = "App: ${req?.targetPackage}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (req?.durationMinutes != null && req.durationMinutes > 0) {
                Text(
                    text = "Duration: ${req.durationMinutes} minutes",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (!req?.messageText.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "\"${req?.messageText}\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!req?.decisionReason.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Reason: ${req?.decisionReason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status == "APPROVED") Color(0xFF059669) else Color(0xFFDC2626)
                )
            }

            // Interactive Actions
            if (status == "PENDING" && req != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isInitiator) {
                        OutlinedButton(
                            onClick = { onRespond(req.id, "REJECTED", null) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Reject", fontSize = 12.sp)
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = { onRespond(req.id, "APPROVED", null) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Approve", fontSize = 12.sp)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onCancel(req.id) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text("Cancel Request", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StructuredRequestComposerDialog(
    activeSessionId: String?,
    onDismiss: () -> Unit,
    onSubmit: (requestType: String, sessionId: String?, targetPackage: String?, durationMinutes: Int?, messageText: String?) -> Unit
) {
    var selectedType by rememberSaveable { mutableStateOf("REQUEST_EARLY_RELEASE") }
    var targetPackage by rememberSaveable { mutableStateOf("") }
    var durationMinutes by rememberSaveable { mutableIntStateOf(10) }
    var pleaMessage by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Structured Request") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Select request type:", style = MaterialTheme.typography.bodySmall)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = selectedType == "REQUEST_EARLY_RELEASE",
                        onClick = { selectedType = "REQUEST_EARLY_RELEASE" },
                        label = { Text("Early Release", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = selectedType == "REQUEST_TEMPORARY_APP_ACCESS",
                        onClick = { selectedType = "REQUEST_TEMPORARY_APP_ACCESS" },
                        label = { Text("Temp App", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = selectedType == "REQUEST_SESSION_EXTENSION",
                        onClick = { selectedType = "REQUEST_SESSION_EXTENSION" },
                        label = { Text("Extend", fontSize = 11.sp) }
                    )
                }

                if (selectedType == "REQUEST_TEMPORARY_APP_ACCESS") {
                    OutlinedTextField(
                        value = targetPackage,
                        onValueChange = { targetPackage = it },
                        label = { Text("Target Package Name") },
                        placeholder = { Text("e.g. com.google.android.gm") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Text("Duration:", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(5, 10, 15, 30).forEach { mins ->
                            FilterChip(
                                selected = durationMinutes == mins,
                                onClick = { durationMinutes = mins },
                                label = { Text("${mins}m") }
                            )
                        }
                    }
                }

                if (selectedType == "REQUEST_SESSION_EXTENSION") {
                    Text("Extension Duration:", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(5, 15, 30, 60).forEach { mins ->
                            FilterChip(
                                selected = durationMinutes == mins,
                                onClick = { durationMinutes = mins },
                                label = { Text("${mins}m") }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = pleaMessage,
                    onValueChange = { pleaMessage = it },
                    label = { Text("Plea / Explanation") },
                    placeholder = { Text("Explain why to your partner...") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val pkg = if (selectedType == "REQUEST_TEMPORARY_APP_ACCESS") targetPackage.trim().ifEmpty { null } else null
                    val dur = if (selectedType == "REQUEST_EARLY_RELEASE") null else durationMinutes
                    val msg = pleaMessage.trim().ifEmpty { null }
                    onSubmit(selectedType, activeSessionId, pkg, dur, msg)
                },
                enabled = selectedType != "REQUEST_TEMPORARY_APP_ACCESS" || targetPackage.isNotBlank()
            ) {
                Text("Send Request")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatRequestType(type: String): String {
    return when (type) {
        "REQUEST_EARLY_RELEASE" -> "Early Release"
        "REQUEST_TEMPORARY_APP_ACCESS" -> "Temporary App Access"
        "REQUEST_SESSION_EXTENSION" -> "Session Extension"
        "REQUEST_ADD_ALLOWED_APP" -> "Add Allowed App"
        "REQUEST_REMOVE_BLOCKED_APP" -> "Remove Blocked App"
        "REQUEST_CHANGE_BLOCKING_MODE" -> "Change Blocking Mode"
        else -> type.replace("REQUEST_", "").replace("_", " ")
    }
}
