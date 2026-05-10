package com.example.emergency.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.emergency.ui.screen.common.GroupedListContainer
import com.example.emergency.ui.screen.common.GroupedListDivider
import com.example.emergency.ui.screen.common.ScreenSectionHeader
import com.example.emergency.ui.screen.common.SubScreenTopBar
import com.example.emergency.ui.state.SettingsUiState
import com.example.emergency.ui.theme.EmergencyTheme

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit = {},
    onLanguageClick: () -> Unit = {},
    onRegionClick: () -> Unit = {},
    onLockScreenToggle: (Boolean) -> Unit = {},
    onPersonalInfoClick: () -> Unit = {},
    onCrowdAlertsToggle: (Boolean) -> Unit = {},
    onHighDensityToggle: (Boolean) -> Unit = {},
    onUpdateModel: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
    onSendFeedback: () -> Unit = {},
) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding(),
    ) {
        SubScreenTopBar(title = state.title, onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            ScreenSectionHeader(text = "GENERAL")
            Spacer(modifier = Modifier.height(8.dp))
            GroupedListContainer {
                DisclosureRow(
                    label = "Language",
                    value = state.languageLabel,
                    onClick = onLanguageClick,
                )
                GroupedListDivider()
                DisclosureRow(
                    label = "Region",
                    value = state.regionLabel,
                    onClick = onRegionClick,
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            ScreenSectionHeader(text = "PRIVACY")
            Spacer(modifier = Modifier.height(8.dp))
            GroupedListContainer {
                ToggleRow(
                    label = "Show on lock screen",
                    checked = state.showOnLockScreen,
                    helper = "Responders can see allergies, blood type, and contacts.",
                    onCheckedChange = onLockScreenToggle,
                )
                GroupedListDivider()
                DisclosureRow(
                    label = "Personal information",
                    value = "Allergies \u00B7 meds \u00B7 contacts",
                    onClick = onPersonalInfoClick,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Everything stays on your device. No data leaves it.",
                style = typography.helper.copy(fontSize = 12.sp),
                color = colors.textFaint,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            Spacer(modifier = Modifier.height(20.dp))
            ScreenSectionHeader(text = "NOTIFICATIONS")
            Spacer(modifier = Modifier.height(8.dp))
            GroupedListContainer {
                ToggleRow(
                    label = "Crowd alerts",
                    checked = state.crowdAlerts,
                    helper = "Notify when density rises near you.",
                    onCheckedChange = onCrowdAlertsToggle,
                )
                GroupedListDivider()
                ToggleRow(
                    label = "High-density warnings",
                    checked = state.highDensityWarnings,
                    helper = "Stronger alert when crowd risk is high.",
                    onCheckedChange = onHighDensityToggle,
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            ScreenSectionHeader(text = "MODEL")
            Spacer(modifier = Modifier.height(8.dp))
            GroupedListContainer {
                InfoRow(label = "Active model", value = state.modelName)
                GroupedListDivider()
                InfoRow(label = "Storage", value = state.modelStorageLabel)
                GroupedListDivider()
                InfoRow(label = "Last sync", value = state.modelLastSyncLabel)
                GroupedListDivider()
                ActionRow(label = "Update model", onClick = onUpdateModel)
            }

            Spacer(modifier = Modifier.height(20.dp))
            ScreenSectionHeader(text = "ABOUT")
            Spacer(modifier = Modifier.height(8.dp))
            GroupedListContainer {
                InfoRow(label = "Version", value = state.versionLabel)
                GroupedListDivider()
                InfoRow(label = "Map data", value = "(c) OpenStreetMap contributors")
                GroupedListDivider()
                InfoRow(label = "Routing", value = "BRouter (LGPL)")
                GroupedListDivider()
                InfoRow(label = "Tile rendering", value = "MapLibre (BSD)")
                GroupedListDivider()
                DisclosureRow(
                    label = "Open source licenses",
                    value = null,
                    onClick = onOpenLicenses,
                )
                GroupedListDivider()
                DisclosureRow(
                    label = "Send feedback",
                    value = null,
                    onClick = onSendFeedback,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Map data licensed under the Open Database License (ODbL). " +
                    "OpenStreetMap is open data, contributed by people around the world.",
                style = typography.helper.copy(fontSize = 12.sp),
                color = colors.textFaint,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        Spacer(modifier = Modifier.navigationBarsPadding())
    }
}

@Composable
private fun DisclosureRow(
    label: String,
    value: String?,
    onClick: () -> Unit,
) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = label,
            style = typography.listItem.copy(fontSize = 14.sp),
            color = colors.text,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                text = value,
                style = typography.body.copy(fontSize = 14.sp),
                color = colors.textDim,
            )
        }
        Spacer(modifier = Modifier.size(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textFaint,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    helper: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = typography.listItem.copy(fontSize = 14.sp),
                color = colors.text,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = helper,
                style = typography.helper.copy(fontSize = 12.sp),
                color = colors.textDim,
            )
        }
        Spacer(modifier = Modifier.size(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.accentInk,
                checkedTrackColor = colors.accent,
                uncheckedThumbColor = colors.surface,
                uncheckedTrackColor = colors.panel,
                uncheckedBorderColor = colors.line,
            ),
        )
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = label,
            style = typography.listItem.copy(fontSize = 14.sp),
            color = colors.text,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = typography.body.copy(fontSize = 14.sp),
            color = colors.textDim,
        )
    }
}

@Composable
private fun ActionRow(
    label: String,
    onClick: () -> Unit,
) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = label,
            style = typography.listItem.copy(fontSize = 14.sp),
            color = colors.text,
        )
    }
}
