package com.arrestme.benchmark.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arrestme.benchmark.core.backend.ActiveBackendConfig
import com.arrestme.benchmark.core.backend.AuthCredentials
import com.arrestme.benchmark.core.backend.BackendAuthState
import com.arrestme.benchmark.core.backend.BackendMode
import com.arrestme.benchmark.core.backend.ClientInputValidator
import com.arrestme.benchmark.core.backend.HealthCheckResult
import com.arrestme.benchmark.core.backend.UrlValidationResult
import com.arrestme.benchmark.ui.navigation.formatWallClockTime

private enum class AuthFormTab {
    LOGIN,
    REGISTER
}

@Composable
fun AccountScreen(
    config: ActiveBackendConfig,
    authState: BackendAuthState,
    healthCheck: HealthCheckResult?,
    isTestingConnection: Boolean,
    isAuthBusy: Boolean,
    isSyncingAccount: Boolean,
    authErrorMessage: String?,
    onDismissAuthError: () -> Unit,
    onLogin: (email: String, password: String) -> Unit,
    onRegister: (username: String, email: String, password: String, displayName: String?) -> Unit,
    onLogout: () -> Unit,
    onSyncAccount: () -> Unit,
    onUpdateDisplayName: (String) -> Unit,
    onSetMode: (BackendMode) -> Unit,
    onSaveCustomUrl: (String, (UrlValidationResult) -> Unit) -> Unit,
    onTestConnection: (String?) -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Account & Cloud Server",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Manage your ArrestMe cloud account, registered Android installation, and backend server. Self Arrest remains 100% functional offline.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 1. Account & Authentication Overview Summary Card
        AccountOverviewSummaryCard(
            config = config,
            authState = authState,
            isSyncing = isSyncingAccount,
            isAuthBusy = isAuthBusy,
            onSyncAccount = onSyncAccount,
            onLogout = onLogout
        )

        // 2. Conditional Auth Form OR Authenticated Profile & Registered Device Cards
        when (authState) {
            is BackendAuthState.Offline -> {
                OfflineModeInfoCard()
            }
            is BackendAuthState.Unauthenticated -> {
                AuthenticationFormCard(
                    serverUrl = config.effectiveBaseUrl ?: config.backendIdentity,
                    sessionExpiredReason = authState.sessionExpiredReason,
                    authErrorMessage = authErrorMessage,
                    isBusy = isAuthBusy,
                    onDismissError = onDismissAuthError,
                    onLogin = onLogin,
                    onRegister = onRegister
                )
            }
            is BackendAuthState.Authenticated -> {
                AuthenticatedProfileAndDeviceSection(
                    credentials = authState.credentials,
                    authErrorMessage = authErrorMessage,
                    isSyncing = isSyncingAccount,
                    isBusy = isAuthBusy,
                    onDismissError = onDismissAuthError,
                    onSyncAccount = onSyncAccount,
                    onUpdateDisplayName = onUpdateDisplayName,
                    onLogout = onLogout
                )
            }
        }

        // 3. Server Configuration & Connection Diagnostics Section
        ServerConfigurationCard(
            config = config,
            healthCheck = healthCheck,
            isTestingConnection = isTestingConnection,
            onSetMode = onSetMode,
            onSaveCustomUrl = onSaveCustomUrl,
            onTestConnection = onTestConnection
        )

        // 4. Security & Offline Guarantee Footer
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Security, Privacy & Offline Guarantee",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "• Access & refresh tokens are encrypted at rest with Android Keystore (AES-256-GCM) and isolated per server.\n" +
                        "• Device registration uses a random app-scoped installation UUID — zero hardware IDs (no IMEI, serial, or ANDROID_ID).\n" +
                        "• Remote endpoints strictly require HTTPS.\n" +
                        "• Local Self Arrest sessions run independently on-device and never depend on cloud connectivity.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AccountOverviewSummaryCard(
    config: ActiveBackendConfig,
    authState: BackendAuthState,
    isSyncing: Boolean,
    isAuthBusy: Boolean,
    onSyncAccount: () -> Unit,
    onLogout: () -> Unit
) {
    val isAuthed = authState is BackendAuthState.Authenticated
    val creds = (authState as? BackendAuthState.Authenticated)?.credentials

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (authState) {
                is BackendAuthState.Authenticated -> Color(0xFF064E3B)
                is BackendAuthState.Unauthenticated -> MaterialTheme.colorScheme.surfaceVariant
                is BackendAuthState.Offline -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isAuthed) Icons.Default.CheckCircle else Icons.Default.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = if (isAuthed) Color(0xFF6EE7B7) else MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (authState) {
                            is BackendAuthState.Authenticated ->
                                creds?.displayName?.ifBlank { creds.username ?: "Signed In" } ?: "Signed In"
                            is BackendAuthState.Unauthenticated ->
                                if (authState.sessionExpiredReason != null) "Session Expired" else "Not Signed In"
                            is BackendAuthState.Offline -> "Local-Only Mode (Offline)"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (creds != null && !creds.username.isNullOrBlank()) {
                        Text(
                            text = "@${creds.username}" + (creds.email?.let { " • $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFD1FAE5)
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))

            AccountDetailRow(
                label = "Authentication Status",
                value = when (authState) {
                    is BackendAuthState.Authenticated -> "Authenticated (${creds?.accountStatus ?: "ACTIVE"})"
                    is BackendAuthState.Unauthenticated ->
                        if (authState.sessionExpiredReason != null) "Expired — Sign-in required" else "Unauthenticated"
                    is BackendAuthState.Offline -> "Offline Mode (No cloud account needed)"
                }
            )
            AccountDetailRow(
                label = "Current Server",
                value = config.effectiveBaseUrl ?: "Local-Only (On-Device)"
            )
            AccountDetailRow(
                label = "Registered Device",
                value = when {
                    creds?.registeredDevice != null -> {
                        val dev = creds.registeredDevice
                        "${dev.deviceName} (${dev.deviceStatus})"
                    }
                    isAuthed -> "Pending sync"
                    else -> "Not registered"
                }
            )
            AccountDetailRow(
                label = "Last Synchronization",
                value = if (creds != null && creds.lastSyncAtMs > 0L) {
                    formatWallClockTime(creds.lastSyncAtMs)
                } else {
                    "Never"
                }
            )

            if (isAuthed) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onSyncAccount,
                        enabled = !isSyncing && !isAuthBusy,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Syncing...")
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sync Now")
                        }
                    }

                    Button(
                        onClick = onLogout,
                        enabled = !isAuthBusy,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isAuthBusy) "Signing out..." else "Logout", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun OfflineModeInfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Operating in Local-Only Mode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "Cloud features are currently disabled. Select Official Server or Custom Self-Hosted Backend below to sign in or register an account.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AuthenticationFormCard(
    serverUrl: String,
    sessionExpiredReason: String?,
    authErrorMessage: String?,
    isBusy: Boolean,
    onDismissError: () -> Unit,
    onLogin: (email: String, password: String) -> Unit,
    onRegister: (username: String, email: String, password: String, displayName: String?) -> Unit
) {
    var selectedTab by rememberSaveable { mutableStateOf(AuthFormTab.LOGIN) }
    var username by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var localValidationError by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (selectedTab == AuthFormTab.LOGIN) "Sign In to ArrestMe Cloud" else "Create ArrestMe Account",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Target Server: $serverUrl",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedTab == AuthFormTab.LOGIN,
                    onClick = {
                        selectedTab = AuthFormTab.LOGIN
                        localValidationError = null
                        onDismissError()
                    },
                    label = { Text("Sign In") }
                )
                FilterChip(
                    selected = selectedTab == AuthFormTab.REGISTER,
                    onClick = {
                        selectedTab = AuthFormTab.REGISTER
                        localValidationError = null
                        onDismissError()
                    },
                    label = { Text("Register") }
                )
            }

            if (!sessionExpiredReason.isNullOrBlank()) {
                StatusBannerBox(
                    message = sessionExpiredReason,
                    isError = true
                )
            }

            val combinedError = localValidationError ?: authErrorMessage
            if (!combinedError.isNullOrBlank()) {
                StatusBannerBox(
                    message = combinedError,
                    isError = true
                )
            }

            if (selectedTab == AuthFormTab.REGISTER) {
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        localValidationError = null
                        onDismissError()
                    },
                    label = { Text("Username") },
                    placeholder = { Text("e.g. alex_focus") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    singleLine = true,
                    enabled = !isBusy,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Next
                    ),
                    supportingText = {
                        Text("3–32 lowercase letters, numbers, or underscores.")
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = displayName,
                    onValueChange = {
                        displayName = it
                        localValidationError = null
                    },
                    label = { Text("Display Name (Optional)") },
                    placeholder = { Text("Alex") },
                    leadingIcon = { Icon(Icons.Default.AccountCircle, contentDescription = null) },
                    singleLine = true,
                    enabled = !isBusy,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            OutlinedTextField(
                value = email,
                onValueChange = {
                    email = it
                    localValidationError = null
                    onDismissError()
                },
                label = { Text("Email Address") },
                placeholder = { Text("you@example.com") },
                leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                singleLine = true,
                enabled = !isBusy,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    localValidationError = null
                    onDismissError()
                },
                label = { Text("Password") },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(if (showPassword) "Hide" else "Show", fontSize = 12.sp)
                    }
                },
                visualTransformation = if (showPassword) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                singleLine = true,
                enabled = !isBusy,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done
                ),
                supportingText = if (selectedTab == AuthFormTab.REGISTER) {
                    { Text("Minimum 10 characters with at least 1 letter and 1 number or symbol.") }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    localValidationError = null
                    if (selectedTab == AuthFormTab.LOGIN) {
                        val err = ClientInputValidator.validateEmail(email)
                            ?: ClientInputValidator.validateLoginPassword(password)
                        if (err != null) {
                            localValidationError = err
                        } else {
                            onLogin(email, password)
                        }
                    } else {
                        val err = ClientInputValidator.validateUsername(username)
                            ?: ClientInputValidator.validateEmail(email)
                            ?: ClientInputValidator.validateRegistrationPassword(password)
                            ?: ClientInputValidator.validateDisplayName(displayName)
                        if (err != null) {
                            localValidationError = err
                        } else {
                            onRegister(
                                username,
                                email,
                                password,
                                displayName.trim().takeIf { it.isNotEmpty() }
                            )
                        }
                    }
                },
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (selectedTab == AuthFormTab.LOGIN) "Signing In..." else "Creating Account...")
                } else {
                    Text(if (selectedTab == AuthFormTab.LOGIN) "Sign In" else "Create Account & Register Device")
                }
            }
        }
    }
}

@Composable
private fun AuthenticatedProfileAndDeviceSection(
    credentials: AuthCredentials,
    authErrorMessage: String?,
    isSyncing: Boolean,
    isBusy: Boolean,
    onDismissError: () -> Unit,
    onSyncAccount: () -> Unit,
    onUpdateDisplayName: (String) -> Unit,
    onLogout: () -> Unit
) {
    var editingDisplayName by remember { mutableStateOf(false) }
    var displayNameDraft by remember(credentials.displayName) {
        mutableStateOf(credentials.displayName.orEmpty())
    }

    if (!authErrorMessage.isNullOrBlank()) {
        StatusBannerBox(message = authErrorMessage, isError = true)
    }

    // Profile Card
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Account Profile",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(
                    onClick = {
                        editingDisplayName = !editingDisplayName
                        displayNameDraft = credentials.displayName.orEmpty()
                        onDismissError()
                    }
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (editingDisplayName) "Cancel" else "Edit Name")
                }
            }

            AccountDetailRow(label = "Username", value = "@${credentials.username ?: "-"}")
            AccountDetailRow(label = "Display Name", value = credentials.displayName ?: "-")
            AccountDetailRow(label = "Email", value = credentials.email ?: "-")
            AccountDetailRow(label = "Account ID", value = credentials.userId?.take(18)?.plus("…") ?: "-")

            if (editingDisplayName) {
                OutlinedTextField(
                    value = displayNameDraft,
                    onValueChange = { displayNameDraft = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        onUpdateDisplayName(displayNameDraft)
                        editingDisplayName = false
                    },
                    enabled = !isBusy && displayNameDraft.trim().isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Display Name")
                }
            }
        }
    }

    // Registered Device Card
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Phone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Registered Android Installation",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            val dev = credentials.registeredDevice
            if (dev != null) {
                AccountDetailRow(label = "Device Name", value = dev.deviceName)
                AccountDetailRow(label = "Status", value = dev.deviceStatus)
                AccountDetailRow(label = "Android / SDK", value = "Android ${dev.androidVersion} (API ${dev.sdkInt ?: "-"})")
                AccountDetailRow(label = "App Version", value = dev.appVersion)
                AccountDetailRow(label = "Cloud Device ID", value = dev.id.take(18) + "…")
                AccountDetailRow(label = "Installation UUID", value = dev.installationId.take(18) + "…")
            } else {
                Text(
                    text = "This installation has not finished registering with the server yet. Tap Sync Device Metadata when online.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedButton(
                onClick = onSyncAccount,
                enabled = !isSyncing && !isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isSyncing) "Synchronizing..." else "Sync Profile & Device Metadata")
            }
        }
    }
}

@Composable
private fun ServerConfigurationCard(
    config: ActiveBackendConfig,
    healthCheck: HealthCheckResult?,
    isTestingConnection: Boolean,
    onSetMode: (BackendMode) -> Unit,
    onSaveCustomUrl: (String, (UrlValidationResult) -> Unit) -> Unit,
    onTestConnection: (String?) -> Unit
) {
    var inputCustomUrl by remember(config.customUrl) { mutableStateOf(config.customUrl) }
    var validationError by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Server Configuration",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "Switch between the official server, a custom self-hosted HTTPS server, or local-only mode. Each server keeps an isolated authentication session.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Option 1: Official Server (BuildConfig / GitHub Actions)
            ServerModeOptionRow(
                selected = config.mode == BackendMode.BUILD_DEFAULT,
                title = "Official Server (GitHub Actions BuildConfig)",
                subtitle = if (config.buildConfigUrl.isNotBlank()) {
                    "Configured URL: ${config.buildConfigUrl}"
                } else {
                    "No official URL injected in this build — defaults safely to Local-Only mode."
                },
                onSelect = { onSetMode(BackendMode.BUILD_DEFAULT) }
            )

            // Option 2: Custom Self-Hosted Backend
            ServerModeOptionRow(
                selected = config.mode == BackendMode.CUSTOM_SELF_HOSTED,
                title = "Custom Self-Hosted HTTPS Backend",
                subtitle = "Connect to your own Fastify server (e.g. Hostinger or custom domain).",
                onSelect = { onSetMode(BackendMode.CUSTOM_SELF_HOSTED) }
            )

            if (config.mode == BackendMode.CUSTOM_SELF_HOSTED) {
                OutlinedTextField(
                    value = inputCustomUrl,
                    onValueChange = {
                        inputCustomUrl = it
                        validationError = null
                    },
                    label = { Text("Server Base URL") },
                    placeholder = { Text("https://api.yourdomain.com") },
                    singleLine = true,
                    isError = validationError != null,
                    supportingText = {
                        if (validationError != null) {
                            Text(text = validationError!!, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("HTTPS required for remote domains. Trailing /api/v1/ is normalized automatically.")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            onSaveCustomUrl(inputCustomUrl) { res ->
                                validationError = (res as? UrlValidationResult.Invalid)?.reason
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save Server URL")
                    }

                    OutlinedButton(
                        onClick = { onTestConnection(inputCustomUrl) },
                        enabled = !isTestingConnection && inputCustomUrl.isNotBlank()
                    ) {
                        Text(if (isTestingConnection) "Testing..." else "Test URL")
                    }
                }
            }

            // Option 3: Local-Only Offline Mode
            ServerModeOptionRow(
                selected = config.mode == BackendMode.OFFLINE_LOCAL,
                title = "Local-Only Mode (Offline)",
                subtitle = "Zero network traffic. Self Arrest runs 100% locally on-device.",
                onSelect = { onSetMode(BackendMode.OFFLINE_LOCAL) }
            )

            HorizontalDivider()

            Button(
                onClick = { onTestConnection(null) },
                enabled = !isTestingConnection && !config.isLocalOnly,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isTestingConnection) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Testing Server Health...")
                } else {
                    Text("Test Active Server Connection")
                }
            }

            healthCheck?.let { res ->
                StatusBannerBox(
                    message = when (res) {
                        is HealthCheckResult.Success -> res.message
                        is HealthCheckResult.Error -> res.error
                    },
                    isError = res is HealthCheckResult.Error
                )
            }
        }
    }
}

@Composable
private fun ServerModeOptionRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(text = title, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusBannerBox(
    message: String,
    isError: Boolean
) {
    val bgColor = if (isError) Color(0xFF450A0A) else Color(0xFF064E3B)
    val fgColor = if (isError) Color(0xFFFCA5A5) else Color(0xFF6EE7B7)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isError) Icons.Default.Warning else Icons.Default.Check,
                contentDescription = null,
                tint = fgColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                color = fgColor,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
