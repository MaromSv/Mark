package com.example.emergency.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.emergency.ui.screen.common.SubScreenTopBar
import com.example.emergency.ui.state.EmergencyContact
import com.example.emergency.ui.state.MedicalInfo
import com.example.emergency.ui.state.PersonalInfoUiState
import com.example.emergency.ui.theme.EmergencyShapes
import com.example.emergency.ui.theme.EmergencyTheme
import com.example.emergency.ui.theme.JetBrainsMonoFamily

@Composable
fun PersonalInfoScreen(
    state: PersonalInfoUiState,
    onBack: () -> Unit = {},
    onEditIdentity: () -> Unit = {},
    onEditMedical: () -> Unit = {},
    onAddContact: () -> Unit = {},
    onContactClick: (EmergencyContact) -> Unit = {},
    onContactCall: (EmergencyContact) -> Unit = {},
    onLockScreenToggle: (Boolean) -> Unit = {},
) {
    val colors = EmergencyTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding(),
    ) {
        SubScreenTopBar(
            title = "Personal info",
            onBack = onBack,
            trailing = { EditPillButton(onClick = onEditIdentity) },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            MedicalCardHero(
                name = state.name,
                dateOfBirth = state.dateOfBirth,
                bloodType = state.medical.bloodType,
            )

            Spacer(modifier = Modifier.height(14.dp))
            PrivacyStrip()

            SectionBlock(
                title = "ALLERGIES",
                onAdd = onEditMedical,
            ) {
                if (state.medical.allergies.isEmpty()) {
                    EmptyHint(text = "None added")
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        state.medical.allergies.forEach { Chip(text = it, variant = ChipVariant.Warn) }
                    }
                }
            }

            SectionBlock(
                title = "CONDITIONS",
                onAdd = onEditMedical,
            ) {
                if (state.medical.conditions.isEmpty()) {
                    EmptyHint(text = "None added")
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        state.medical.conditions.forEach { Chip(text = it, variant = ChipVariant.Neutral) }
                    }
                }
            }

            SectionBlock(
                title = "MEDICATIONS",
                onAdd = onEditMedical,
            ) {
                if (state.medical.medications.isEmpty()) {
                    EmptyHint(text = "None added")
                } else {
                    Column {
                        state.medical.medications.forEach { med ->
                            Text(
                                text = med,
                                style = EmergencyTheme.typography.body.copy(fontSize = 14.sp),
                                color = EmergencyTheme.colors.text,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            SectionBlock(
                title = "EMERGENCY CONTACTS",
                onAdd = onAddContact,
            ) {
                if (state.contacts.isEmpty()) {
                    EmptyHint(text = "No contacts yet")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.contacts.forEach { contact ->
                            ContactCard(
                                contact = contact,
                                onClick = { onContactClick(contact) },
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            LockScreenCard(
                checked = state.showOnLockScreen,
                onCheckedChange = onLockScreenToggle,
            )
        }

        Spacer(modifier = Modifier.navigationBarsPadding())
    }
}

@Composable
private fun EditPillButton(onClick: () -> Unit) {
    val colors = EmergencyTheme.colors
    Box(
        modifier = Modifier
            .clip(EmergencyShapes.full)
            .border(1.dp, colors.line, EmergencyShapes.full)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = "Edit",
            style = EmergencyTheme.typography.listItem.copy(fontSize = 13.sp),
            color = colors.text,
        )
    }
}

@Composable
private fun MedicalCardHero(
    name: String?,
    dateOfBirth: String?,
    bloodType: String?,
) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography
    val ink = colors.bg
    val inkDim = colors.bg.copy(alpha = 0.65f)
    val inkFaint = colors.bg.copy(alpha = 0.55f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.text)
            .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "MEDICAL CARD",
                    style = typography.monoMicro.copy(fontSize = 10.sp, letterSpacing = 0.8.sp),
                    color = inkFaint,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = name ?: "Add your name",
                    style = typography.listItem.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium),
                    color = ink,
                )
                val sub = buildSubLine(dateOfBirth)
                if (sub != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = sub,
                        style = typography.helper.copy(fontSize = 12.sp),
                        color = inkDim,
                    )
                }
            }
            if (!bloodType.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = bloodType,
                        color = ink,
                        style = typography.listItem.copy(
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }
            }
        }
    }
}

private fun buildSubLine(dateOfBirth: String?): String? {
    if (dateOfBirth.isNullOrBlank()) return null
    val year = Regex("\\d{4}").find(dateOfBirth)?.value
    return if (year != null) "b. $year" else dateOfBirth
}

@Composable
private fun PrivacyStrip() {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography

    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.panel)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Shield,
            contentDescription = null,
            tint = colors.textDim,
            modifier = Modifier.size(16.dp).padding(top = 2.dp),
        )
        Spacer(modifier = Modifier.size(10.dp))
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = colors.text, fontWeight = FontWeight.Medium)) {
                    append("Stays on your device. ")
                }
                append("Mark uses these fields silently to give you better advice - they're never sent anywhere.")
            },
            style = typography.helper.copy(fontSize = 12.sp, lineHeight = 18.sp),
            color = colors.textDim,
        )
    }
}

@Composable
private fun SectionBlock(
    title: String,
    onAdd: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography

    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = title,
                style = typography.eyebrow,
                color = colors.textDim,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(EmergencyShapes.full)
                    .clickable(onClick = onAdd)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "+ Add",
                    style = typography.helper.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    color = colors.textDim,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = EmergencyTheme.typography.helper.copy(fontSize = 13.sp),
        color = EmergencyTheme.colors.textFaint,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

private enum class ChipVariant { Warn, Neutral }

@Composable
private fun Chip(text: String, variant: ChipVariant) {
    val colors = EmergencyTheme.colors
    val (bg, fg) = when (variant) {
        ChipVariant.Warn -> Color(0xFFF6DCC9) to Color(0xFF7C2D12)
        ChipVariant.Neutral -> colors.panel to colors.text
    }
    Box(
        modifier = Modifier
            .clip(EmergencyShapes.full)
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = EmergencyTheme.typography.listItem.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
            color = fg,
        )
    }
}

@Composable
private fun ContactCard(
    contact: EmergencyContact,
    onClick: () -> Unit,
) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.panel)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        ContactMonogram(name = contact.name)
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.name,
                style = typography.listItem.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                color = colors.text,
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = "${contact.relation} \u00B7 ${contact.phone}",
                style = typography.helper.copy(fontSize = 12.sp),
                color = colors.textDim,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textFaint,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun ContactMonogram(name: String) {
    val colors = EmergencyTheme.colors
    val initials = name
        .split(" ")
        .mapNotNull { it.firstOrNull()?.uppercase() }
        .take(2)
        .joinToString("")
        .ifEmpty { "?" }

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(EmergencyShapes.full)
            .background(colors.surface),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = EmergencyTheme.typography.listItem.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = colors.text,
        )
    }
}

@Composable
private fun LockScreenCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = EmergencyTheme.colors
    val typography = EmergencyTheme.typography

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.panel)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.NotificationsActive,
            contentDescription = null,
            tint = colors.text,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Show on lock screen",
                style = typography.listItem.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                color = colors.text,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Responders can see your blood type and allergies without unlocking.",
                style = typography.helper.copy(fontSize = 12.sp, lineHeight = 17.sp),
                color = colors.textDim,
            )
        }
        Spacer(modifier = Modifier.size(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.bg,
                checkedTrackColor = colors.text,
                uncheckedThumbColor = colors.surface,
                uncheckedTrackColor = colors.panel2,
                uncheckedBorderColor = colors.line,
            ),
        )
    }
}

@Suppress("unused")
private val MedicalInfoSentinel: MedicalInfo? = null
