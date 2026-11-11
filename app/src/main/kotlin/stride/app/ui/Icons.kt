package stride.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import stride.app.ui.theme.DesignTokens

@Composable
fun FileTypeIcon(fileName: String, modifier: Modifier = Modifier, color: Color = DesignTokens.muted) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.dp.toPx()
        val page = Path().apply {
            moveTo(w * 0.16f, h * 0.06f)
            lineTo(w * 0.62f, h * 0.06f)
            lineTo(w * 0.86f, h * 0.30f)
            lineTo(w * 0.86f, h * 0.94f)
            lineTo(w * 0.16f, h * 0.94f)
            close()
        }
        drawPath(page, color.copy(alpha = 0.16f))
        drawPath(page, color.copy(alpha = 0.85f), style = Stroke(stroke, join = StrokeJoin.Round))
        val folded = color.copy(alpha = 0.85f)
        drawLine(folded, Offset(w * 0.62f, h * 0.06f), Offset(w * 0.62f, h * 0.30f), stroke)
        drawLine(folded, Offset(w * 0.62f, h * 0.30f), Offset(w * 0.86f, h * 0.30f), stroke)
        val line = color.copy(alpha = 0.55f)
        drawLine(line, Offset(w * 0.32f, h * 0.54f), Offset(w * 0.70f, h * 0.54f), stroke, cap = StrokeCap.Round)
        drawLine(line, Offset(w * 0.32f, h * 0.70f), Offset(w * 0.70f, h * 0.70f), stroke, cap = StrokeCap.Round)
    }
}

@Composable
fun FolderIcon(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val folder = Path().apply {
            moveTo(w * 0.08f, h * 0.26f)
            lineTo(w * 0.38f, h * 0.26f)
            lineTo(w * 0.46f, h * 0.38f)
            lineTo(w * 0.92f, h * 0.38f)
            lineTo(w * 0.92f, h * 0.82f)
            lineTo(w * 0.08f, h * 0.82f)
            close()
        }
        drawPath(folder, DesignTokens.muted.copy(alpha = 0.75f))
    }
}

@Composable
fun ChevronIcon(expanded: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val chevron = Path().apply {
            moveTo(w * 0.36f, h * 0.26f)
            lineTo(w * 0.64f, h * 0.50f)
            lineTo(w * 0.36f, h * 0.74f)
        }
        rotate(if (expanded) 90f else 0f) {
            drawPath(
                chevron,
                DesignTokens.muted,
                style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

@Composable
fun SearchIcon(modifier: Modifier = Modifier, color: Color = DesignTokens.muted) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.4.dp.toPx()
        val center = Offset(w * 0.44f, h * 0.44f)
        val radius = w * 0.30f
        drawCircle(color, radius = radius, center = center, style = Stroke(stroke))
        drawLine(
            color,
            Offset(center.x + radius * 0.72f, center.y + radius * 0.72f),
            Offset(w * 0.90f, h * 0.90f),
            stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun CloseIcon(modifier: Modifier = Modifier, color: Color = DesignTokens.muted) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.2.dp.toPx()
        drawLine(color, Offset(w * 0.24f, h * 0.24f), Offset(w * 0.76f, h * 0.76f), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.76f, h * 0.24f), Offset(w * 0.24f, h * 0.76f), stroke, cap = StrokeCap.Round)
    }
}
