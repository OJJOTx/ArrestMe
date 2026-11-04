package com.arrestme.benchmark.ui.partners

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.arrestme.benchmark.data.local.AppRuleEntity
import com.arrestme.benchmark.data.local.AppRuleType
import com.arrestme.benchmark.data.remote.dto.BlockingConfigLimitsDto
import com.arrestme.benchmark.data.remote.dto.BlockingConfigurationDto
import com.arrestme.benchmark.data.remote.dto.CreateProposalRequestDto
import com.arrestme.benchmark.data.remote.dto.PartnerProfileDto
import com.arrestme.benchmark.data.remote.dto.ProposalDto
import com.arrestme.benchmark.data.remote.dto.ProposalScheduleDto
import com.arrestme.benchmark.data.remote.dto.ProposalStandingAuthorizationDto
import com.arrestme.benchmark.data.remote.dto.RecurrenceRuleDto
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArrestProposalBuilderScreen(
    partnershipId: String,
    profile: PartnerProfileDto?,
    localAppRules: List<AppRuleEntity>,
    isLoading: Boolean,
    errorMessage: String?,
    onLoadProfile: (String) -> Unit,
    onSubmitProposal: (CreateProposalRequestDto, (ProposalDto) -> Unit) -> Unit,
    onProposalCreated: (proposalId: String) -> Unit,
    onBack: () -> Unit
) {
    LaunchedEffect(partnershipId) {
        if (profile == null || profile.partnership.id != partnershipId) {
            onLoadProfile(partnershipId)
        }
    }

    val scrollState = rememberScrollState()

    val partner = profile?.partner
    val myDevices = profile?.myDevices.orEmpty()
    val partnerDevices = profile?.partnerDevices.orEmpty()

    // Direction: true = Partner arrests MY device; false = I arrest PARTNER's device
    var targetIsMyDevice by rememberSaveable {
        mutableStateOf(partnerDevices.isEmpty())
    }

    val availableDevices = if (targetIsMyDevice) myDevices else partnerDevices
    var selectedDeviceId by remember(targetIsMyDevice, myDevices, partnerDevices) {
        mutableStateOf(availableDevices.firstOrNull()?.id.orEmpty())
    }

    var requestType by rememberSaveable { mutableStateOf("IMMEDIATE") }
    var goal by rememberSaveable { mutableStateOf("Deep Work & Focus Session") }
    var durationMinutesText by rememberSaveable { mutableStateOf("45") }
    var ruleMode by rememberSaveable { mutableStateOf("BLOCK_SELECTED") }
    var overlayStatusBar by rememberSaveable { mutableStateOf(false) }
    var disableEmergencyExit by rememberSaveable { mutableStateOf(false) }
    var requireApprovalBeforeArrest by rememberSaveable { mutableStateOf(true) }

    val defaultBlockedCsv = remember(localAppRules) {
        localAppRules
            .filter { it.ruleType == AppRuleType.BLOCKED.name }
            .joinToString(", ") { it.packageName }
            .ifBlank { "com.instagram.android, com.zhiliaoapp.musically" }
    }
    val defaultAllowedCsv = remember(localAppRules) {
        localAppRules
            .filter { it.ruleType == AppRuleType.ALLOWED_STUDY.name }
            .joinToString(", ") { it.packageName }
    }

    var blockedPackagesText by rememberSaveable { mutableStateOf(defaultBlockedCsv) }
    var allowedPackagesText by rememberSaveable { mutableStateOf(defaultAllowedCsv) }

    // Schedule / Recurrence fields
    val defaultTz = remember { ZoneId.systemDefault().id.ifBlank { "UTC" } }
    val tomorrowDate = remember { LocalDate.now().plusDays(1).toString() }
    val nextMonthDate = remember { LocalDate.now().plusDays(30).toString() }

    var scheduleDate by rememberSaveable { mutableStateOf(tomorrowDate) }
    var scheduleStartTime by rememberSaveable { mutableStateOf("09:00") }
    var scheduleEndTime by rememberSaveable { mutableStateOf("10:00") }
    var timezone by rememberSaveable { mutableStateOf(defaultTz) }

    var selectedDaysOfWeek by remember { mutableStateOf(setOf(1, 2, 3, 4, 5)) }
    var recurrenceEndDate by rememberSaveable { mutableStateOf(nextMonthDate) }
    var maxOccurrencesText by rememberSaveable { mutableStateOf("10") }

    // Standing authorization fields
    var standingValidDaysText by rememberSaveable { mutableStateOf("14") }
    var standingMaxDurationText by rememberSaveable { mutableStateOf("90") }

    var reasonMessage by rememberSaveable { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(4.dp))
            Column {
                Text(
                    text = "Build Arrest Proposal",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = partner?.let { "Negotiate an Arrest session or schedule with @${it.username}" }
                        ?: "Create a versioned Arrest proposal",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!errorMessage.isNullOrBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D))
            ) {
                Text(
                    text = errorMessage,
                    modifier = Modifier.padding(12.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (profile == null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (isLoading) "Loading partner devices..." else "Partner profile unavailable.",
                    modifier = Modifier.padding(16.dp)
                )
            }
            return@Column
        }

        // 1. Direction & Target Device Selection
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "1. Authorization Direction & Target Device",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !targetIsMyDevice,
                        onClick = {
                            targetIsMyDevice = false
                            selectedDeviceId = partnerDevices.firstOrNull()?.id.orEmpty()
                            validationError = null
                        },
                        label = { Text("Arrest @${partner?.username}'s Device") }
                    )
                    FilterChip(
                        selected = targetIsMyDevice,
                        onClick = {
                            targetIsMyDevice = true
                            selectedDeviceId = myDevices.firstOrNull()?.id.orEmpty()
                            validationError = null
                        },
                        label = { Text("@${partner?.username} Arrests MY Device") }
                    )
                }

                if (availableDevices.isEmpty()) {
                    Text(
                        text = if (targetIsMyDevice) {
                            "You have no active registered devices. Register this device in Account settings."
                        } else {
                            "@${partner?.username} has no active registered Android devices yet."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        text = "Select Target Device:",
                        style = MaterialTheme.typography.labelMedium
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        availableDevices.forEach { dev ->
                            FilterChip(
                                selected = selectedDeviceId == dev.id,
                                onClick = {
                                    selectedDeviceId = dev.id
                                    validationError = null
                                },
                                label = { Text("${dev.deviceName} (${dev.platform})") }
                            )
                        }
                    }
                }
            }
        }

        // 2. Request Type Selection
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "2. Arrest Request Type",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "IMMEDIATE" to "Immediate",
                        "SCHEDULED" to "Scheduled",
                        "RECURRING" to "Recurring",
                        "STANDING_AUTHORIZATION" to "Standing Auth"
                    ).forEach { (typeKey, label) ->
                        FilterChip(
                            selected = requestType == typeKey,
                            onClick = {
                                requestType = typeKey
                                validationError = null
                            },
                            label = { Text(label) }
                        )
                    }
                }

                when (requestType) {
                    "IMMEDIATE" -> {
                        Text(
                            text = "Immediate: Starts as soon as the proposal is approved and delivered to the target device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    "SCHEDULED" -> {
                        Text(
                            text = "Scheduled: Specify local date, start time, optional end time, and IANA timezone. Stored as UTC on the server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = scheduleDate,
                                onValueChange = { scheduleDate = it },
                                label = { Text("Local Date (YYYY-MM-DD)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = scheduleStartTime,
                                onValueChange = { scheduleStartTime = it },
                                label = { Text("Start Time (HH:mm)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = scheduleEndTime,
                                onValueChange = { scheduleEndTime = it },
                                label = { Text("End Time (HH:mm, Optional)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = timezone,
                                onValueChange = { timezone = it },
                                label = { Text("Time Zone (IANA)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    "RECURRING" -> {
                        Text(
                            text = "Recurring: Deterministic DST-aware recurring schedule with bounded end date and max occurrences.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val dayNames = listOf(
                            0 to "Sun", 1 to "Mon", 2 to "Tue", 3 to "Wed",
                            4 to "Thu", 5 to "Fri", 6 to "Sat"
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            dayNames.forEach { (dayIdx, name) ->
                                val isSelected = selectedDaysOfWeek.contains(dayIdx)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        selectedDaysOfWeek = if (isSelected) {
                                            selectedDaysOfWeek - dayIdx
                                        } else {
                                            selectedDaysOfWeek + dayIdx
                                        }
                                    },
                                    label = { Text(name) }
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = scheduleStartTime,
                                onValueChange = { scheduleStartTime = it },
                                label = { Text("Local Start (HH:mm)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = timezone,
                                onValueChange = { timezone = it },
                                label = { Text("Time Zone") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = recurrenceEndDate,
                                onValueChange = { recurrenceEndDate = it },
                                label = { Text("End Date (YYYY-MM-DD)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = maxOccurrencesText,
                                onValueChange = { maxOccurrencesText = it },
                                label = { Text("Max Occurrences") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    "STANDING_AUTHORIZATION" -> {
                        Text(
                            text = "Standing Authorization: Propose a bounded pre-authorization with a definite expiration and per-session maximum duration.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = standingMaxDurationText,
                                onValueChange = { standingMaxDurationText = it },
                                label = { Text("Max Session (min)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = standingValidDaysText,
                                onValueChange = { standingValidDaysText = it },
                                label = { Text("Expires In (days)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // 3. Session Configuration & Blocking Engine Rules
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "3. Session Goal, Duration & Blocking Mode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                OutlinedTextField(
                    value = goal,
                    onValueChange = {
                        goal = it
                        validationError = null
                    },
                    label = { Text("Session Goal") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = durationMinutesText,
                    onValueChange = {
                        durationMinutesText = it
                        validationError = null
                    },
                    label = { Text("Duration (5–720 minutes)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Android Arrest Engine Blocking Mode:",
                    style = MaterialTheme.typography.labelMedium
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("BLOCK_SELECTED", "STRICT_ALLOWLIST", "WHITELIST_ONLY").forEach { mode ->
                        FilterChip(
                            selected = ruleMode == mode,
                            onClick = { ruleMode = mode },
                            label = { Text(mode) }
                        )
                    }
                }

                if (ruleMode == "BLOCK_SELECTED") {
                    OutlinedTextField(
                        value = blockedPackagesText,
                        onValueChange = { blockedPackagesText = it },
                        label = { Text("Blocked Applications (comma-separated packages)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = allowedPackagesText,
                    onValueChange = { allowedPackagesText = it },
                    label = { Text("Allowed Study Applications (comma-separated packages)") },
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider()

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = overlayStatusBar, onCheckedChange = { overlayStatusBar = it })
                    Text("overlayStatusBar (Restrict notification shade pull-down)", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = disableEmergencyExit, onCheckedChange = { disableEmergencyExit = it })
                    Text("disableEmergencyExit (Require partner release approval)", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = requireApprovalBeforeArrest,
                        onCheckedChange = { requireApprovalBeforeArrest = it }
                    )
                    Text("Require explicit approval before execution", style = MaterialTheme.typography.bodySmall)
                }

                OutlinedTextField(
                    value = reasonMessage,
                    onValueChange = { reasonMessage = it },
                    label = { Text("Explanatory Message / Proposal Note (Optional)") },
                    placeholder = { Text("e.g. Exam prep block from 9am to 10am") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (validationError != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D))
            ) {
                Text(
                    text = validationError!!,
                    modifier = Modifier.padding(12.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Button(
            onClick = {
                val durationMin = durationMinutesText.toIntOrNull()
                if (selectedDeviceId.isBlank()) {
                    validationError = "Please select a valid target device."
                    return@Button
                }
                if (goal.trim().isEmpty()) {
                    validationError = "Please enter a session goal."
                    return@Button
                }
                if (durationMin == null || durationMin !in 5..720) {
                    validationError = "Duration must be between 5 and 720 minutes."
                    return@Button
                }

                val blockedList = blockedPackagesText
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                val allowedList = allowedPackagesText
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()

                val blockingConfig = BlockingConfigurationDto(
                    schemaVersion = 1,
                    ruleMode = ruleMode,
                    allowedCategories = listOf("CALLS", "STUDY_APPS"),
                    overlayStatusBar = overlayStatusBar,
                    disableEmergencyExit = disableEmergencyExit,
                    blockedPackages = blockedList.takeIf { it.isNotEmpty() },
                    allowedStudyPackages = allowedList.takeIf { it.isNotEmpty() }
                )

                val scheduleDto = if (requestType == "SCHEDULED") {
                    if (!Regex("""^\d{4}-\d{2}-\d{2}$""").matches(scheduleDate.trim())) {
                        validationError = "Scheduled date must be YYYY-MM-DD."
                        return@Button
                    }
                    if (!Regex("""^\d{2}:\d{2}$""").matches(scheduleStartTime.trim())) {
                        validationError = "Scheduled start time must be HH:mm."
                        return@Button
                    }
                    ProposalScheduleDto(
                        localDate = scheduleDate.trim(),
                        localStartTime = scheduleStartTime.trim(),
                        localEndTime = scheduleEndTime.trim().takeIf { it.isNotEmpty() },
                        timezone = timezone.trim().ifBlank { "UTC" }
                    )
                } else null

                val recurrenceDto = if (requestType == "RECURRING") {
                    if (selectedDaysOfWeek.isEmpty()) {
                        validationError = "Select at least one day of the week for recurring schedules."
                        return@Button
                    }
                    val maxOcc = maxOccurrencesText.toIntOrNull()
                    if (maxOcc == null || maxOcc !in 1..365) {
                        validationError = "Max occurrences must be between 1 and 365."
                        return@Button
                    }
                    RecurrenceRuleDto(
                        daysOfWeek = selectedDaysOfWeek.sorted(),
                        localStartTime = scheduleStartTime.trim(),
                        durationMinutes = durationMin,
                        timezone = timezone.trim().ifBlank { "UTC" },
                        endDate = recurrenceEndDate.trim(),
                        maxOccurrences = maxOcc
                    )
                } else null

                val standingDto = if (requestType == "STANDING_AUTHORIZATION") {
                    val validDays = standingValidDaysText.toLongOrNull()
                    val maxStandingMin = standingMaxDurationText.toIntOrNull()
                    if (validDays == null || validDays !in 1..90) {
                        validationError = "Standing authorization expiration must be 1–90 days."
                        return@Button
                    }
                    if (maxStandingMin == null || maxStandingMin !in 5..720) {
                        validationError = "Standing max session duration must be 5–720 minutes."
                        return@Button
                    }
                    val expUtc = Instant.now().plus(validDays, ChronoUnit.DAYS).toString()
                    ProposalStandingAuthorizationDto(
                        expiresAtUtc = expUtc,
                        maximumSessionDuration = maxStandingMin,
                        blockingConfigLimits = BlockingConfigLimitsDto(
                            allowedRuleModes = listOf(ruleMode),
                            allowOverlayStatusBar = overlayStatusBar,
                            allowDisableEmergencyExit = disableEmergencyExit,
                            requiredAllowedCategories = listOf("CALLS"),
                            allowCustomPackageLists = true
                        )
                    )
                } else null

                validationError = null
                val request = CreateProposalRequestDto(
                    targetDeviceId = selectedDeviceId,
                    partnerUserId = partner?.id,
                    requestType = requestType,
                    sessionType = "PARTNER_ARREST",
                    goal = goal.trim(),
                    durationMinutes = durationMin,
                    blockingConfiguration = blockingConfig,
                    requireApprovalBeforeArrest = requireApprovalBeforeArrest,
                    schedule = scheduleDto,
                    recurrence = recurrenceDto,
                    standingAuthorization = standingDto,
                    reason = reasonMessage.trim().takeIf { it.isNotEmpty() }
                )
                onSubmitProposal(request) { created ->
                    onProposalCreated(created.proposalId)
                }
            },
            enabled = !isLoading && availableDevices.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Submitting Proposal...")
            } else {
                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Send Arrest Proposal")
            }
        }
    }
}
