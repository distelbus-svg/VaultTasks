package app.vaulttasks.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class Glyph { Back, Up, Down, Left, Right, More, Check, Calendar, Plus }

/**
 * Small stroke icons drawn on a 24-unit grid with round caps and joins, so every arrow and symbol in the app has the
 * same soft corners (no icon font or icon library needed).
 */
@Composable
fun GlyphIcon(glyph: Glyph, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val u = this.size.width / 24f
        val stroke = Stroke(width = 2.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (glyph) {
            Glyph.Back -> {
                line(tint, stroke, 19f to 12f, 5f to 12f)
                poly(tint, stroke, 11f to 6f, 5f to 12f, 11f to 18f)
            }
            Glyph.Right -> poly(tint, stroke, 9f to 6f, 15f to 12f, 9f to 18f)
            Glyph.Left -> poly(tint, stroke, 15f to 6f, 9f to 12f, 15f to 18f)
            Glyph.Down -> poly(tint, stroke, 6f to 9f, 12f to 15f, 18f to 9f)
            Glyph.Up -> poly(tint, stroke, 6f to 15f, 12f to 9f, 18f to 15f)
            Glyph.Check -> poly(tint, stroke, 5f to 12.5f, 10f to 17.5f, 19f to 7.5f)
            Glyph.Plus -> {
                line(tint, stroke, 12f to 5f, 12f to 19f)
                line(tint, stroke, 5f to 12f, 19f to 12f)
            }
            Glyph.More -> listOf(5.5f, 12f, 18.5f).forEach { y -> drawCircle(tint, radius = 1.7f * u, center = pt(12f, y)) }
            Glyph.Calendar -> {
                drawRoundRect(tint, topLeft = pt(4f, 5.5f), size = Size(16f * u, 14.5f * u), cornerRadius = CornerRadius(3.8f * u), style = stroke)
                line(tint, stroke, 4f to 10.5f, 20f to 10.5f)
                line(tint, stroke, 8.5f to 3.5f, 8.5f to 7.5f)
                line(tint, stroke, 15.5f to 3.5f, 15.5f to 7.5f)
                listOf(8.5f, 12f, 15.5f).forEach { x -> drawCircle(tint, radius = 1.1f * u, center = pt(x, 15f)) }
            }
        }
    }
}

private fun DrawScope.pt(x: Float, y: Float) = Offset(x * size.width / 24f, y * size.width / 24f)

private fun DrawScope.line(color: Color, stroke: Stroke, a: Pair<Float, Float>, b: Pair<Float, Float>) =
    drawLine(color, pt(a.first, a.second), pt(b.first, b.second), strokeWidth = stroke.width, cap = StrokeCap.Round)

private fun DrawScope.poly(color: Color, stroke: Stroke, vararg points: Pair<Float, Float>) {
    val path = Path().apply {
        points.forEachIndexed { i, p ->
            val o = pt(p.first, p.second)
            if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
        }
    }
    drawPath(path, color, style = stroke)
}
