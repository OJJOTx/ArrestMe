package com.arrestme.benchmark.ui.partners

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arrestme.benchmark.data.remote.dto.BlockingConfigLimitsDto
import com.arrestme.benchmark.data.remote.dto.CreatePartnerPermissionRequestDto
import com.arrestme.benchmark.data.remote.dto.PartnerProfileDto
import java.time.Instant
import java.time.temporal.ChronoUnit

@Composable
fun PartnerApprovalSettingsScreen(
    partnershipId: String,
    profile: PartnerProfileDto?,
    isLoading: Boolean,
    errorMessage: String?,
    onLoadProfile: (String) -> Unit,
    onUpdateApprovalPolicy: (partnershipId: String, requireApprovalBeforeArrest: Boolean, notes: String?) -> Unit,
    onGrantStandingPermission: (CreatePartnerPermissionRequestDto, String) -> Unit,
    onRevokePermission: (permissionId: String, partnershipId: String) -> Unit,
    onBack: () -> Unit
) {
    LaunchedEffect(partnershipId) {
        if (profile == null || profile.partnership.id != partnershipId) {
            onLoadProfile(partnershipId)
        }
    }

    val scrollState = rememberScrollState()
    val policy = profile?.myApprovalPolicyForPartner
    var requireApproval by remember(policy?.requireApprovalBeforeArrest) {
        mutableStateOf(policy?.requireApprovalBeforeArrest ?: true)
    }
    var policyNotes by remember(policy?.notes) {
        mutableStateOf(policy?.notes.orEmpty())
    }

    // Form state for creating an explicit bounded Standing Authorization
    val myDevices = profile?.myDevices.orEmpty()
    var selectedDeviceId by remember(myDevices) {
        mutableStateOf(myDevices.firstOrNull()?.id.orEmpty())
    }
    var maxSessionMinutesText by rememberSaveable { mutableStateOf("60") }
    var validDaysText by rememberSaveable { mutableStateOf("7") }
    var allowBlockSelected by rememberSaveable { mutableStateOf(true) }
    var allowStrictAllowlist by rememberSaveable { mutableStateOf(true) }
    var allowWhitelistOnly by rememberSaveable { mutableStateOf(false) }
    var allowOverlayStatusBar by rememberSaveable { mutableStateOf(false) }
    var allowDisableEmergencyExit by rememberSaveable { mutableStateOf(false) }
    var allowCustomPackages by rememberSaveable { mutableStateOf(true) }
    var localFormError by remember { mutableStateOf<String?>(null) }

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
                    text = "Partner Approval Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = profile?.partner?.let { "Directional policy for @${it.username} on YOUR devices" }
                        ?: "Configure directional approval requirements",
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
                    text = if (isLoading) "Loading approval policy..." else "Partner profile not loaded.",
                    modifier = Modifier.padding(16.dp)
                )
            }
            return@Column
        }

        val partner = profile.partner

        // 1. Require Approval Before Arrest Switch Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Require Approval Before Arrest",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "requireApprovalBeforeArrest = ${if (requireApproval) "TRUE (Default)" else "FALSE"}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (requireApproval) Color(0xFF10B981) else Color(0xFFF59E0B)
                        )
                    }
                    Switch(
                        checked = requireApproval,
                        onCheckedChange = { checked ->
                            requireApproval = checked
                            onUpdateApprovalPolicy(partnershipId, checked, policyNotes)
                        },
                        enabled = !isLoading
                    )
                }

                HorizontalDivider()

                if (requireApproval) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "TRUE (Strict Per-Session Consent):\n" +
                                "• @${partner.username} must submit an Arrest proposal first.\n" +
                                "• You must approve the exact configuration or schedule before any session can start.\n" +
                                "• Subsequent sessions require a new approval unless covered by an approved recurring schedule.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "FALSE (Standing Authorization Mode):\n" +
                                "• Turning off this switch NEVER creates unlimited authority on its own.\n" +
                                "• @${partner.username} may only initiate sessions directly if covered by an active Standing Authorization below with a definite expiration and per-session maximum duration.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                OutlinedTextField(
                    value = policyNotes,
                    onValueChange = { policyNotes = it },
                    label = { Text("Policy Notes (Optional)") },
                    placeholder = { Text("e.g. Weeknight study sessions only") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedButton(
                    onClick = { onUpdateApprovalPolicy(partnershipId, requireApproval, policyNotes) },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Approval Policy")
                }
            }
        }

        // 2. Active Standing Authorizations Card
        val standingList = policy?.activeStandingAuthorizations.orEmpty()
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Active Standing Authorizations (${standingList.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (standingList.isEmpty()) {
                    Text(
                        text = "No standing authorizations active. Even if requireApprovalBeforeArrest is FALSE, @${partner.username} cannot start a session without an active bounded authorization.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    standingList.forEach { perm ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Device: ${perm.targetDevice.deviceName}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Max Per-Session Duration: ${perm.maximumSessionDuration} min",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "Allowed Modes: ${perm.blockingConfigLimits.allowedRuleModes.joinToString(", ")}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "Flags: overlayStatusBar=${perm.blockingConfigLimits.allowOverlayStatusBar}, disableEmergencyExit=${perm.blockingConfigLimits.allowDisableEmergencyExit}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "Expires: ${perm.expiresAt.take(19).replace('T', ' ')} UTC",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                                OutlinedButton(
                                    onClick = { onRevokePermission(perm.permissionId, partnershipId) },
                                    enabled = !isLoading,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Revoke Standing Authorization", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Grant Bounded Standing Authorization Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Grant Bounded Standing Authorization",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Establish an explicit, scoped standing authorization with a mandatory expiration and per-session maximum duration.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (myDevices.isEmpty()) {
                    Text(
                        text = "Register your Android device in Account settings before granting device permissions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text("Select Your Target Device:", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        myDevices.forEach { dev ->
                            FilterChip(
                                selected = selectedDeviceId == dev.id,
                                onClick = { selectedDeviceId = dev.id },
                                label = { Text(dev.deviceName) }
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = maxSessionMinutesText,
                        onValueChange = {
                            maxSessionMinutesText = it
                            localFormError = null
                        },
                        label = { Text("Max Session (min)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = validDaysText,
                        onValueChange = {
                            validDaysText = it
                            localFormError = null
                        },
                        label = { Text("Expires In (days)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Text("Allowed Blocking Modes:", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = allowBlockSelected,
                        onClick = { allowBlockSelected = !allowBlockSelected },
                        label = { Text("BLOCK_SELECTED") }
                    )
                    FilterChip(
                        selected = allowStrictAllowlist,
                        onClick = { allowStrictAllowlist = !allowStrictAllowlist },
                        label = { Text("STRICT_ALLOWLIST") }
                    )
                    FilterChip(
                        selected = allowWhitelistOnly,
                        onClick = { allowWhitelistOnly = !allowWhitelistOnly },
                        label = { Text("WHITELIST_ONLY") }
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allowOverlayStatusBar, onCheckedChange = { allowOverlayStatusBar = it })
                    Text("Allow overlayStatusBar", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allowDisableEmergencyExit, onCheckedChange = { allowDisableEmergencyExit = it })
                    Text("Allow disableEmergencyExit", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allowCustomPackages, onCheckedChange = { allowCustomPackages = it })
                    Text("Allow custom blocked/allowed package lists", style = MaterialTheme.typography.bodySmall)
                }

                if (localFormError != null) {
                    Text(
                        text = localFormError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Button(
                    onClick = {
                        val maxMin = maxSessionMinutesText.toIntOrNull()
                        val validDays = validDaysText.toLongOrNull()
                        val modes = buildList {
                            if (allowBlockSelected) add("BLOCK_SELECTED")
                            if (allowStrictAllowlist) add("STRICT_ALLOWLIST")
                            if (allowWhitelistOnly) add("WHITELIST_ONLY")
                        }
                        when {
                            selectedDeviceId.isBlank() -> {
                                localFormError = "Select one of your active registered devices."
                            }
                            maxMin == null || maxMin !in 5..720 -> {
                                localFormError = "Max session duration must be between 5 and 720 minutes."
                            }
                            validDays == null || validDays !in 1..90 -> {
                                localFormError = "Expiration must be between 1 and 90 days."
                            }
                            modes.isEmpty() -> {
                                localFormError = "Select at least one allowed blocking mode."
                            }
                            else -> {
                                localFormError = null
                                val expiresAtIso = Instant.now()
                                    .plus(validDays, ChronoUnit.DAYS)
                                    .toString()
                                onGrantStandingPermission(
                                    CreatePartnerPermissionRequestDto(
                                        partnerUserId = partner.id,
                                        targetDeviceId = selectedDeviceId,
                                        maximumSessionDuration = maxMin,
                                        allowedSessionTypes = listOf("PARTNER_ARREST", "MUTUAL_ARREST"),
                                        blockingConfigLimits = BlockingConfigLimitsDto(
                                            allowedRuleModes = modes,
                                            allowOverlayStatusBar = allowOverlayStatusBar,
                                            allowDisableEmergencyExit = allowDisableEmergencyExit,
                                            requiredAllowedCategories = listOf("CALLS"),
                                            allowCustomPackageLists = allowCustomPackages
                                        ),
                                        oneTimeOnly = false,
                                        expiresAt = expiresAtIso
                                    ),
                                    partnershipId
                                )
                            }
                        }
                    },
                    enabled = !isLoading && myDevices.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant Bounded Standing Authorization")
                }
            }
        }
    }
}
