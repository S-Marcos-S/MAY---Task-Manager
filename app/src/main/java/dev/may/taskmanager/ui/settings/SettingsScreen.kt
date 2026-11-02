// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.may.taskmanager.BuildConfig
import dev.may.taskmanager.R
import dev.may.taskmanager.ui.theme.glass.GlassCard
import dev.may.taskmanager.update.DownloadStatus
import dev.may.taskmanager.update.RootInstaller
import dev.may.taskmanager.update.UpdateInfo
import dev.may.taskmanager.update.UpdateManager
import dev.may.taskmanager.util.HapticUtil

data class ThemeColorDotOption(
    val id: String,
    val nameRes: Int,
    val color: Color,
    val borderColor: Color,
    val checkmarkColor: Color,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    activeThemeId: String,
    onSelectTheme: (String) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val colorScheme = MaterialTheme.colorScheme
    val isDark = isSystemInDarkTheme()

    val updateInfo by UpdateManager.updateAvailable.collectAsState()
    val isCheckingUpdates by UpdateManager.isCheckingUpdates.collectAsState()
    val downloadStatus by UpdateManager.downloadStatus.collectAsState()
    var isBetaTester by remember { mutableStateOf(UpdateManager.isBetaTesterEnabled(context)) }

    // Automatically check for updates on entering SettingsScreen
    LaunchedEffect(Unit) {
        UpdateManager.checkForUpdates(context, force = true)
    }

    val isRoot = remember { RootInstaller.isRootAvailable() }

    val themeOptions = remember(colorScheme, isDark) {
        listOf(
            ThemeColorDotOption(
                id = "system_dynamic",
                nameRes = R.string.theme_system_dynamic_title,
                color = colorScheme.primary,
                borderColor = colorScheme.outlineVariant.copy(alpha = 0.5f),
                checkmarkColor = colorScheme.onPrimary,
            ),
            ThemeColorDotOption(
                id = "glass",
                nameRes = R.string.theme_glass_title,
                color = if (isDark) Color(0x667FD1E0) else Color(0x88A0D0E8),
                borderColor = Color.White.copy(alpha = 0.85f),
                checkmarkColor = Color.White,
            ),
            ThemeColorDotOption(
                id = "oled_black",
                nameRes = R.string.theme_oled_black_title,
                color = Color.Black,
                borderColor = Color(0xFF333333),
                checkmarkColor = Color(0xFFEDEDED),
            ),
            ThemeColorDotOption(
                id = "dark_modern",
                nameRes = R.string.theme_dark_modern_title,
                color = Color(0xFF1B2733),
                borderColor = Color(0xFF384755),
                checkmarkColor = Color(0xFF7FD1E0),
            ),
            ThemeColorDotOption(
                id = "light_clean",
                nameRes = R.string.theme_light_clean_title,
                color = Color(0xFFF3F6F8),
                borderColor = Color(0xFFB0BCC7),
                checkmarkColor = Color(0xFF10707F),
            ),
        )
    }

    val activeOption = themeOptions.firstOrNull { it.id == activeThemeId } ?: themeOptions.first()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
        containerColor = Color.Transparent,
        modifier = Modifier.fillMaxSize(),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Section: Appearance & Themes
            item {
                Text(
                    text = stringResource(R.string.settings_section_appearance),
                    style = MaterialTheme.typography.labelMedium,
                    color = colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }

            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadiusDp = 14.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(activeOption.nameRes),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .padding(end = 8.dp),
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            themeOptions.forEach { option ->
                                val isSelected = activeThemeId == option.id
                                ThemeColorDot(
                                    option = option,
                                    isSelected = isSelected,
                                    onClick = {
                                        if (!isSelected) {
                                            HapticUtil.tick(view)
                                            onSelectTheme(option.id)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // Section: Updates
            item {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_section_updates),
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )

                    if (isCheckingUpdates) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = colorScheme.primary,
                        )
                    }
                }
            }

            item {
                UpdateStatusCard(
                    updateInfo = updateInfo,
                    isChecking = isCheckingUpdates,
                    downloadStatus = downloadStatus,
                    isRoot = isRoot,
                    onCheckAgain = {
                        UpdateManager.checkForUpdates(context, force = true)
                    },
                    onDownload = { info ->
                        UpdateManager.downloadUpdate(context, info)
                    },
                    onInstall = { uri, useRoot ->
                        UpdateManager.installUpdate(context, uri, useRoot)
                    },
                )
            }

            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadiusDp = 14.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_beta_tester_title),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.settings_beta_tester_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant,
                            )
                        }

                        Switch(
                            checked = isBetaTester,
                            onCheckedChange = { checked ->
                                isBetaTester = checked
                                UpdateManager.setBetaTesterEnabled(context, checked)
                                HapticUtil.tick(view)
                                UpdateManager.checkForUpdates(context, force = true)
                            },
                        )
                    }
                }
            }

            // Section: System & App Info
            item {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_section_about),
                    style = MaterialTheme.typography.labelMedium,
                    color = colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }

            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadiusDp = 14.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "v${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.labelMedium,
                                color = colorScheme.onSurfaceVariant,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.settings_root_access_label),
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant,
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isRoot) colorScheme.primaryContainer else colorScheme.surfaceVariant,
                            ) {
                                Text(
                                    text = if (isRoot) stringResource(R.string.settings_root_granted) else stringResource(R.string.settings_root_not_detected),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isRoot) colorScheme.onPrimaryContainer else colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeColorDot(
    option: ThemeColorDotOption,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.05f else 0.85f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "dotScale",
    )
    val ringSize by animateDpAsState(
        targetValue = if (isSelected) 34.dp else 24.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "dotRingSize",
    )
    val ringAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "dotRingAlpha",
    )

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (ringAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .size(ringSize)
                    .graphicsLayer { alpha = ringAlpha }
                    .border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = ringAlpha),
                        shape = CircleShape,
                    ),
            )
        }

        Box(
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(option.color)
                .border(1.dp, option.borderColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = isSelected,
                enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(tween(150)),
                exit = scaleOut(tween(150)) + fadeOut(tween(150)),
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = option.checkmarkColor,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

@Composable
private fun UpdateStatusCard(
    updateInfo: UpdateInfo?,
    isChecking: Boolean,
    downloadStatus: DownloadStatus,
    isRoot: Boolean,
    onCheckAgain: () -> Unit,
    onDownload: (UpdateInfo) -> Unit,
    onInstall: (android.net.Uri, Boolean) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    var isChangelogExpanded by remember { mutableStateOf(false) }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadiusDp = 14.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (updateInfo != null && updateInfo.hasUpdate) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.update_available_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.primary,
                        )
                        Text(
                            text = "${stringResource(R.string.update_version_label)}: ${updateInfo.displayVersion}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }

                    Icon(
                        imageVector = Icons.Filled.SystemUpdate,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }

                if (updateInfo.changelog.isNotBlank()) {
                    val formattedChangelog = remember(updateInfo.changelog, colorScheme) {
                        formatMarkdownToAnnotated(updateInfo.changelog, colorScheme)
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(colorScheme.surfaceContainerHigh.copy(alpha = 0.5f))
                            .padding(8.dp)
                    ) {
                        if (isChangelogExpanded) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = formattedChangelog,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onSurface,
                                )
                            }
                        } else {
                            Text(
                                text = formattedChangelog,
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurface,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { isChangelogExpanded = !isChangelogExpanded }
                                .padding(vertical = 4.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (isChangelogExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                tint = colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = stringResource(if (isChangelogExpanded) R.string.action_show_less else R.string.action_show_more),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = colorScheme.primary,
                            )
                        }
                    }
                }

                when (val status = downloadStatus) {
                    is DownloadStatus.Idle -> {
                        Button(
                            onClick = { onDownload(updateInfo) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(vertical = 8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.action_download_update),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    is DownloadStatus.Downloading -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            LinearProgressIndicator(
                                progress = { status.progressPercent / 100f },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            )
                            Text(
                                text = "${status.progressPercent}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.primary,
                                modifier = Modifier.align(Alignment.End),
                            )
                        }
                    }
                    is DownloadStatus.Finished -> {
                        val uri = status.fileUri
                        if (uri != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (isRoot) {
                                    Button(
                                        onClick = { onInstall(uri, true) },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(vertical = 8.dp),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.action_install_root),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                        )
                                    }
                                }
                                Button(
                                    onClick = { onInstall(uri, false) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = if (isRoot) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors(),
                                    contentPadding = PaddingValues(vertical = 8.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.action_install),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                    is DownloadStatus.InstallingRoot -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                text = stringResource(R.string.update_installing_root),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    is DownloadStatus.Failed -> {
                        Text(
                            text = "${stringResource(R.string.update_download_failed)}: ${status.error}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.error,
                        )
                        Button(
                            onClick = { onDownload(updateInfo) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(vertical = 8.dp),
                        ) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isChecking) stringResource(R.string.update_checking) else stringResource(R.string.update_up_to_date),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "${stringResource(R.string.update_current_version)}: v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }

                    IconButton(
                        onClick = onCheckAgain,
                        enabled = !isChecking,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.action_check_updates),
                        )
                    }
                }
            }
        }
    }
}

private fun formatMarkdownToAnnotated(text: String, colorScheme: ColorScheme): AnnotatedString {
    return buildAnnotatedString {
        val lines = text.lines()
        lines.forEachIndexed { index, rawLine ->
            val trimmed = rawLine.trim()
            when {
                trimmed.startsWith("---") || trimmed.startsWith("***") -> {
                    // Skip horizontal rules or render subtle spacing
                }
                trimmed.startsWith("###") -> {
                    val content = trimmed.removePrefix("###").trim()
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colorScheme.primary)) {
                        append(content)
                    }
                }
                trimmed.startsWith("##") -> {
                    val content = trimmed.removePrefix("##").trim()
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colorScheme.primary)) {
                        append(content)
                    }
                }
                trimmed.startsWith("#") -> {
                    val content = trimmed.removePrefix("#").trim()
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colorScheme.primary)) {
                        append(content)
                    }
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    append("• ")
                    parseInlineMarkdown(trimmed.substring(2).trim(), colorScheme)
                }
                else -> {
                    parseInlineMarkdown(trimmed, colorScheme)
                }
            }

            if (index < lines.lastIndex && trimmed.isNotBlank()) {
                append("\n")
            }
        }
    }
}

private fun AnnotatedString.Builder.parseInlineMarkdown(line: String, colorScheme: ColorScheme) {
    val tokenRegex = Regex("""(\*\*(.+?)\*\*|\*(.+?)\*|`(.+?)`)""")
    var lastIndex = 0
    for (match in tokenRegex.findAll(line)) {
        if (match.range.first > lastIndex) {
            append(line.substring(lastIndex, match.range.first))
        }
        val fullMatch = match.value
        when {
            fullMatch.startsWith("**") && fullMatch.endsWith("**") -> {
                val inner = fullMatch.substring(2, fullMatch.length - 2)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(inner)
                }
            }
            fullMatch.startsWith("*") && fullMatch.endsWith("*") -> {
                val inner = fullMatch.substring(1, fullMatch.length - 1)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(inner)
                }
            }
            fullMatch.startsWith("`") && fullMatch.endsWith("`") -> {
                val inner = fullMatch.substring(1, fullMatch.length - 1)
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colorScheme.surfaceContainerHighest)) {
                    append(inner)
                }
            }
            else -> append(fullMatch)
        }
        lastIndex = match.range.last + 1
    }
    if (lastIndex < line.length) {
        append(line.substring(lastIndex))
    }
}
