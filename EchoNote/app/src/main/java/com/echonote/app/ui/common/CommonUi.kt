package com.echonote.app.ui.common

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.echonote.app.data.db.SpeakerEntity
import com.echonote.app.data.db.TranscriptionStatus
import com.echonote.app.ui.theme.SpeakerPalette
import com.echonote.app.ui.theme.UnknownSpeakerColor

/** Formats milliseconds as H:MM:SS (or MM:SS under an hour). */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(java.util.Locale.US, "%02d:%02d", m, s)
}

/** Formats milliseconds as H:MM:SS.mmm for SRT-style labels. */
fun formatClockWithMillis(ms: Long): String {
    val clamped = ms.coerceAtLeast(0)
    val h = clamped / 3_600_000
    val m = (clamped % 3_600_000) / 60_000
    val s = (clamped % 60_000) / 1000
    val milli = clamped % 1000
    return String.format(java.util.Locale.US, "%02d:%02d:%02d.%03d", h, m, s, milli)
}

fun formatDate(epochMs: Long): String {
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return fmt.format(java.util.Date(epochMs))
}

fun formatRelativeDay(epochMs: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    return if (sameDay) "今天 " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(epochMs))
    else formatDate(epochMs)
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.2f GB", bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> String.format(java.util.Locale.US, "%.0f KB", bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}

@Composable
fun SpeakerDot(index: Int?, size: Int = 12) {
    val color = colorForSpeaker(index)
    Box(
        Modifier
            .size(size.dp)
            .background(color, CircleShape)
    )
}

/** Stable per-speaker colour. `null` (or unknown) → grey. */
fun colorForSpeaker(index: Int?): Color =
    if (index == null || index < 0) UnknownSpeakerColor
    else SpeakerPalette[index % SpeakerPalette.size]

/** Human label for a speaker, given a possible [speakerId]. */
fun speakerLabel(speakerId: Long?, byId: Map<Long, SpeakerEntity>, clusterIndex: Int? = null): String {
    if (speakerId != null) byId[speakerId]?.let { return it.name }
    if (clusterIndex == null) return "未知"
    return "说话人 ${clusterIndex + 1}"
}

/** Small coloured chip for a speaker, used throughout the transcript list. */
@Composable
fun SpeakerChip(name: String, index: Int?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SpeakerDot(index)
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = colorForSpeaker(index),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Transcription status pill shown in lists. */
@Composable
fun TranscriptionBadge(status: TranscriptionStatus) {
    val (icon, label, tint) = when (status) {
        TranscriptionStatus.NOT_STARTED -> Triple(Icons.Default.HourglassTop, "未转写", MaterialTheme.colorScheme.outline)
        TranscriptionStatus.QUEUED -> Triple(Icons.Default.HourglassTop, "排队中", MaterialTheme.colorScheme.secondary)
        TranscriptionStatus.RUNNING -> Triple(Icons.Default.HourglassTop, "转写中", MaterialTheme.colorScheme.primary)
        TranscriptionStatus.COMPLETED -> Triple(Icons.Default.CheckCircle, "已转写", Color(0xFF2E7D32))
        TranscriptionStatus.FAILED -> Triple(Icons.Default.Error, "转写失败", MaterialTheme.colorScheme.error)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (status == TranscriptionStatus.RUNNING) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(14.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

/** Generic empty-state with an icon and message. */
@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** Inline warning card (used for the WeChat-capture reality check). */
@Composable
fun InfoCard(title: String, body: String, tone: CardTone = CardTone.INFO) {
    val container = when (tone) {
        CardTone.INFO -> MaterialTheme.colorScheme.secondaryContainer
        CardTone.WARN -> MaterialTheme.colorScheme.tertiaryContainer
        CardTone.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val icon = when (tone) {
        CardTone.INFO -> Icons.Filled.Warning
        CardTone.WARN -> Icons.Filled.Warning
        CardTone.ERROR -> Icons.Default.Error
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

enum class CardTone { INFO, WARN, ERROR }

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/** One-line truncating body text used in lists. */
@Composable
fun TruncatedText(text: String, maxLines: Int = 2) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
