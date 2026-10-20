package com.jadbarakat.blooddonor.requests

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jadbarakat.blooddonor.R
import com.jadbarakat.blooddonor.data.BloodRequest
import com.jadbarakat.blooddonor.data.Component
import com.jadbarakat.blooddonor.data.RequestStatus
import com.jadbarakat.blooddonor.ui.components.ChipStatus
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@StringRes
fun componentLabel(component: String): Int = when (component) {
    Component.WHOLE_BLOOD -> R.string.component_whole_blood
    Component.RED_CELLS -> R.string.component_red_cells
    Component.PLATELETS -> R.string.component_platelets
    Component.PLASMA -> R.string.component_plasma
    else -> R.string.label_unknown
}

fun BloodRequest.neededByInstant(): Instant? = runCatching { Instant.parse(neededBy) }.getOrNull()

/** The status to show: an open request past its deadline is expired, even if the list is a bit old. */
fun BloodRequest.effectiveStatus(now: Instant): String {
    val deadline = neededByInstant()
    return if (status == RequestStatus.OPEN && deadline != null && !now.isBefore(deadline)) RequestStatus.EXPIRED
    else status
}

/** The current time, updated at the start of every minute. */
@Composable
fun rememberNow(): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            now = Instant.now()
        }
    }
    return now
}

/** "5 h 20 min left" for open requests, null otherwise. */
@Composable
fun timeLeftText(request: BloodRequest, now: Instant): String? =
    if (request.status != RequestStatus.OPEN) null else timeLeftText(request.neededBy, now)

/** "5 h 20 min left" until the ISO instant [neededBy], or null once it has passed. */
@Composable
fun timeLeftText(neededBy: String, now: Instant): String? {
    val deadline = runCatching { Instant.parse(neededBy) }.getOrNull() ?: return null
    if (!now.isBefore(deadline)) return null
    val minutes = Duration.between(now, deadline).toMinutes().coerceAtLeast(1)
    return if (minutes >= 60) {
        stringResource(R.string.request_time_left_hours, minutes / 60, minutes % 60)
    } else {
        stringResource(R.string.request_time_left_minutes, minutes)
    }
}

/** The chip for a request status (see [effectiveStatus]). */
fun requestChipStatus(status: String): ChipStatus = when (status) {
    RequestStatus.OPEN -> ChipStatus.OPEN
    RequestStatus.FULFILLED -> ChipStatus.FULFILLED
    RequestStatus.EXPIRED -> ChipStatus.EXPIRED
    else -> ChipStatus.CANCELLED
}

fun formatDateTime(iso: String?): String? = iso?.let {
    runCatching {
        Instant.parse(it).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    }.getOrNull()
}

/** A label on the left and its value on the right, e.g. "Needed by   Mon 14:00". */
@Composable
fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.5f),
        )
    }
}
