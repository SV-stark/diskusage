package com.google.android.diskusage.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ─── Utilities ────────────────────────────────────────────────────────────────

/** Format bytes human-readably: 1.23 GiB / 456 MiB / 789 KiB / N B */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.2f GiB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L     -> "%.1f MiB".format(bytes / 1_048_576.0)
    bytes >= 1_024L         -> "%d KiB".format(bytes / 1_024)
    else                    -> "$bytes B"
}

// ─── Top App Bar ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiskUsageTopBar() {
    TopAppBar(
        title = {
            Text(
                text = "DiskUsage",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                ),
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
        ),
    )
}

// ─── Animated Ring Chart ─────────────────────────────────────────────────────

@Composable
private fun TotalStorageRingCard(volumes: List<StorageVolume>) {
    val totalBytes = volumes.sumOf { it.totalBytes }
    val usedBytes  = volumes.sumOf { it.usedBytes }
    val usedFrac   = if (totalBytes == 0L) 0f else usedBytes.toFloat() / totalBytes.toFloat()

    // Animate ring sweep from 0 → actual usage
    var triggerAnim by remember { mutableStateOf(false) }
    LaunchedEffect(volumes) {
        delay(200) // slight delay so layout is stable first
        triggerAnim = true
    }
    val sweepAngle by animateFloatAsState(
        targetValue = if (triggerAnim) usedFrac * 300f else 0f,
        animationSpec = tween(durationMillis = 900),
        label = "ringAngle",
    )

    val primaryColor   = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val trackColor     = MaterialTheme.colorScheme.surfaceVariant
    val onSurface      = MaterialTheme.colorScheme.onSurface
    val onSurfaceVar   = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Ring + centre label
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(200.dp),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeW = 28.dp.toPx()
                    val inset   = strokeW / 2f
                    val arcRect = Size(size.width - strokeW, size.height - strokeW)
                    val topLeft = Offset(inset, inset)

                    // Background track arc
                    drawArc(
                        color      = trackColor,
                        startAngle = 120f,
                        sweepAngle = 300f,
                        useCenter  = false,
                        topLeft    = topLeft,
                        size       = arcRect,
                        style      = Stroke(width = strokeW, cap = StrokeCap.Round),
                    )

                    // Gradient used arc
                    if (sweepAngle > 0f) {
                        drawArc(
                            brush = Brush.sweepGradient(
                                0f   to primaryColor,
                                0.5f to secondaryColor,
                                1f   to primaryColor,
                                center = center,
                            ),
                            startAngle = 120f,
                            sweepAngle = sweepAngle,
                            useCenter  = false,
                            topLeft    = topLeft,
                            size       = arcRect,
                            style      = Stroke(width = strokeW, cap = StrokeCap.Round),
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text  = "${(usedFrac * 100).toInt()}%",
                        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold),
                        color = onSurface,
                    )
                    Text(
                        text  = "used",
                        style = MaterialTheme.typography.labelMedium,
                        color = onSurfaceVar,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Used / Free chips
            Row(
                modifier            = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StorageLegendChip(
                    label     = "Used",
                    value     = formatBytes(usedBytes),
                    dotColor  = primaryColor,
                )
                StorageLegendChip(
                    label    = "Free",
                    value    = formatBytes(totalBytes - usedBytes),
                    dotColor = trackColor,
                )
                StorageLegendChip(
                    label    = "Total",
                    value    = formatBytes(totalBytes),
                    dotColor = primaryContainer,
                )
            }
        }
    }
}

@Composable
private fun StorageLegendChip(label: String, value: String, dotColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(10.dp)) {
            drawCircle(color = dotColor)
        }
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                text  = value,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text  = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─── Volume Card ──────────────────────────────────────────────────────────────

@Composable
private fun StorageVolumeCard(
    volume:       StorageVolume,
    onClick:      () -> Unit,
    animateDelay: Int = 0,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(animateDelay.toLong())
        visible = true
    }

    val primary    = MaterialTheme.colorScheme.primary
    val secondary  = MaterialTheme.colorScheme.secondary

    AnimatedVisibility(
        visible = visible,
        enter   = fadeIn(tween(350)) + slideInVertically(
            animationSpec  = spring(stiffness = Spring.StiffnessMediumLow),
            initialOffsetY = { it / 2 },
        ),
    ) {
        ElevatedCard(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            shape = RoundedCornerShape(20.dp),
        ) {
            Row(
                modifier            = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment   = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Icon bubble
                Surface(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (volume.isInternal) Icons.Outlined.Storage else Icons.Outlined.SdCard,
                            contentDescription = null,
                            tint     = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }

                // Text + progress bar
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text     = volume.title,
                        style    = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress           = { volume.usedFraction },
                        modifier           = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color              = if (volume.usedFraction > 0.85f) MaterialTheme.colorScheme.error else primary,
                        trackColor         = MaterialTheme.colorScheme.surfaceVariant,
                        strokeCap          = StrokeCap.Round,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text  = "${formatBytes(volume.usedBytes)} used of ${formatBytes(volume.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Chevron
                Icon(
                    imageVector     = Icons.Rounded.ChevronRight,
                    contentDescription = "Open",
                    tint             = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier         = Modifier.size(24.dp),
                )
            }
        }
    }
}

// ─── Section Header ───────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.labelLarge.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
        ),
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp),
    )
}

// ─── Root composable ─────────────────────────────────────────────────────────

@Composable
fun HomeScreen(
    volumes:  List<StorageVolume>,
    onVolumeSelected: (StorageVolume) -> Unit,
) {
    Scaffold(
        topBar = { DiskUsageTopBar() },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        LazyColumn(
            modifier      = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            // Ring chart overview
            item {
                SectionHeader("Overview")
                if (volumes.isNotEmpty()) {
                    TotalStorageRingCard(volumes)
                }
            }

            // Volume cards
            item {
                Spacer(Modifier.height(4.dp))
                SectionHeader("Storage")
            }

            itemsIndexed(volumes) { index, volume ->
                StorageVolumeCard(
                    volume       = volume,
                    onClick      = { onVolumeSelected(volume) },
                    animateDelay = 80 + index * 100,
                )
            }

            // Empty state
            if (volumes.isEmpty()) {
                item {
                    Box(
                        modifier           = Modifier
                            .fillMaxWidth()
                            .padding(48.dp),
                        contentAlignment   = Alignment.Center,
                    ) {
                        Text(
                            text  = "No storage volumes found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
