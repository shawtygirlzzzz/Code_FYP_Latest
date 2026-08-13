package com.malacca.guide.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.malacca.guide.ui.theme.ErrorRed
import com.malacca.guide.ui.theme.SuccessGreen
import com.malacca.guide.ui.theme.TextSecondary
import com.malacca.guide.ui.theme.WarningAmber

/**
 * Battery level of the connected glasses, drawn rather than using an icon font so
 * the fill tracks the actual percentage instead of snapping between a handful of
 * stock images.
 */
@Composable
fun BatteryIndicator(
    percent: Int,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
) {
    val level = percent.coerceIn(0, 100)
    val color = batteryColor(level)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Canvas(modifier = Modifier.size(width = 24.dp, height = 12.dp)) {
            val capWidth = size.width * 0.08f
            val bodyWidth = size.width - capWidth
            val stroke = size.height * 0.12f
            val radius = CornerRadius(size.height * 0.22f, size.height * 0.22f)

            // Outline
            drawRoundRect(
                color = TextSecondary,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(bodyWidth - stroke, size.height - stroke),
                cornerRadius = radius,
                style = Stroke(width = stroke)
            )

            // Terminal nub on the right
            drawRoundRect(
                color = TextSecondary,
                topLeft = Offset(bodyWidth, size.height * 0.3f),
                size = Size(capWidth, size.height * 0.4f),
                cornerRadius = CornerRadius(capWidth / 2, capWidth / 2)
            )

            // Fill, inset so it sits inside the outline
            val inset = stroke * 1.6f
            val maxFill = bodyWidth - inset * 2
            val fillWidth = maxFill * (level / 100f)
            if (fillWidth > 0f) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(inset, inset),
                    size = Size(fillWidth.coerceAtLeast(stroke), size.height - inset * 2),
                    cornerRadius = CornerRadius(stroke, stroke)
                )
            }
        }

        if (showLabel) {
            Text(
                text = "$level%",
                color = color,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.height(16.dp)
            )
        }
    }
}

private fun batteryColor(level: Int): Color = when {
    level <= 15 -> ErrorRed
    level <= 30 -> WarningAmber
    else -> SuccessGreen
}
