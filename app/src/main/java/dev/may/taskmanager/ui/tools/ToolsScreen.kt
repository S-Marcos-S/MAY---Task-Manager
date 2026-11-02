// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.ui.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.may.taskmanager.R
import dev.may.taskmanager.installer.PackageInstallDialog
import dev.may.taskmanager.root.AppLogCaptureService
import dev.may.taskmanager.service.MayAccessibilityService
import dev.may.taskmanager.ui.root.AppLogViewerDialog
import dev.may.taskmanager.ui.theme.dynamicBorderColor
import dev.may.taskmanager.ui.theme.glass.FrostedCard

@Composable
fun ToolsScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme

    val isRecordingActive by AppLogCaptureService.isServiceRunning.collectAsState()
    val isServiceConnected by MayAccessibilityService.isServiceActive.collectAsState()

    val commandsPrefs = remember {
        context.getSharedPreferences(MayAccessibilityService.PREFS_COMMANDS, Context.MODE_PRIVATE)
    }

    var isVolumeShortcutEnabled by remember {
        mutableStateOf(commandsPrefs.getBoolean(MayAccessibilityService.KEY_VOLUME_SHORTCUT_ENABLED, false))
    }

    var showLogViewerDialog by remember { mutableStateOf(false) }
    var selectedApkUri by remember { mutableStateOf<Uri?>(null) }

    val apkPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedApkUri = uri
        }
    }

    fun checkAccessibilityEnabled(): Boolean {
        return try {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            enabledServices.contains(context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    var isAccessibilityGloballyEnabled by remember {
        mutableStateOf(checkAccessibilityEnabled())
    }

    DisposableEffect(Unit) {
        isAccessibilityGloballyEnabled = checkAccessibilityEnabled()
        onDispose {}
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Section 1: Gravação de Logs do Sistema
        item {
            FrostedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FiberManualRecord,
                                contentDescription = null,
                                tint = if (isRecordingActive) colorScheme.error else colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(R.string.tools_log_recording_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.onSurface
                            )
                        }

                        // Badge de Status
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isRecordingActive) colorScheme.errorContainer else colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, if (isRecordingActive) colorScheme.error else colorScheme.outline.copy(alpha = 0.2f))
                        ) {
                            Text(
                                text = if (isRecordingActive) {
                                    stringResource(R.string.tools_log_recording_status_active)
                                } else {
                                    stringResource(R.string.tools_log_recording_status_idle)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = if (isRecordingActive) colorScheme.onErrorContainer else colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.tools_log_recording_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(
                        color = dynamicBorderColor(alpha = 0.20f),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!isRecordingActive) {
                            Button(
                                onClick = {
                                    val sysLabel = context.getString(R.string.tools_system_logs_label)
                                    AppLogCaptureService.startCapture(
                                        context = context,
                                        packageName = null,
                                        appName = sysLabel
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Filled.FiberManualRecord, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.tools_start_recording),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        } else {
                            Button(
                                onClick = {
                                    AppLogCaptureService.saveLog(context)
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = colorScheme.error),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.tools_stop_recording),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    val intent = Intent(context, AppLogCaptureService::class.java).apply {
                                        action = AppLogCaptureService.ACTION_CANCEL_CAPTURE
                                    }
                                    context.startService(intent)
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.tools_discard_recording),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                showLogViewerDialog = true
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.tools_view_recordings),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }

        // Section 2: Instalador de APK (Root)
        item {
            FrostedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Android,
                                contentDescription = null,
                                tint = colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(R.string.tools_installer_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.onSurface
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.tools_installer_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(
                        color = dynamicBorderColor(alpha = 0.20f),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )

                    Button(
                        onClick = {
                            apkPickerLauncher.launch("application/vnd.android.package-archive")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.tools_installer_select_btn),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        // Section 3: Criação de Comandos e Atalhos
        item {
            FrostedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Build,
                                contentDescription = null,
                                tint = colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(R.string.tools_commands_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.onSurface
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.tools_commands_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(
                        color = dynamicBorderColor(alpha = 0.20f),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )

                    // Card do comando de abrir o MAY com botões de volume
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colorScheme.surface.copy(alpha = 0.35f))
                            .border(1.dp, colorScheme.outline.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.tools_cmd_open_may_title),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.tools_cmd_open_may_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = isVolumeShortcutEnabled,
                                onCheckedChange = { checked ->
                                    isVolumeShortcutEnabled = checked
                                    commandsPrefs.edit()
                                        .putBoolean(MayAccessibilityService.KEY_VOLUME_SHORTCUT_ENABLED, checked)
                                        .apply()
                                },
                                modifier = Modifier.scale(0.80f)
                            )
                        }

                        // Status do serviço e link para ativação
                        val serviceActive = isServiceConnected || isAccessibilityGloballyEnabled
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (serviceActive) Color(0xFF10B981) else colorScheme.error)
                                )
                                Text(
                                    text = if (serviceActive) {
                                        stringResource(R.string.tools_accessibility_active)
                                    } else {
                                        stringResource(R.string.tools_accessibility_inactive)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (serviceActive) Color(0xFF10B981) else colorScheme.error
                                )
                            }

                            if (!serviceActive) {
                                OutlinedButton(
                                    onClick = {
                                        try {
                                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(intent)
                                        } catch (_: Exception) {}
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.tools_enable_accessibility),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }

                        // Ação configurada
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.tools_action_label),
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = colorScheme.primary.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, colorScheme.primary.copy(alpha = 0.3f))
                            ) {
                                Text(
                                    text = stringResource(R.string.tools_action_open_may),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLogViewerDialog) {
        AppLogViewerDialog(
            initialTab = "recordings",
            onDismissRequest = { showLogViewerDialog = false }
        )
    }

    selectedApkUri?.let { uri ->
        PackageInstallDialog(
            apkUri = uri,
            onDismiss = { selectedApkUri = null }
        )
    }
}
