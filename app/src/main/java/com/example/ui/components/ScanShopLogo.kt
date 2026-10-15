package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AliExpressRed
import com.example.ui.theme.ShopeeOrange

/**
 * ScanShop official brand logo identical to the application launcher icon (ic_launcher).
 * Features the signature curved shopping bag with the bold letter "S" in the center,
 * rendered over the dual AliExpress Red to Shopee Orange gradient.
 */
@Composable
fun ScanShopLogo(
    modifier: Modifier = Modifier,
    size: Dp = 38.dp
) {
    val cornerRadius = size * 0.22f

    Box(
        modifier = modifier
            .size(size)
            .shadow(elevation = 2.dp, shape = RoundedCornerShape(cornerRadius))
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.linearGradient(
                    listOf(AliExpressRed, ShopeeOrange)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val canvasW = this.size.width
            val canvasH = this.size.height

            // Scaling factor: map viewport 108x108 onto the canvas with 1.15x scale to make logo bigger
            val scale = (canvasW / 108f) * 1.15f
            val offsetX = (canvasW - (108f * scale)) / 2f
            val offsetY = (canvasH - (108f * scale)) / 2f

            fun sx(x: Float) = x * scale + offsetX
            fun sy(y: Float) = y * scale + offsetY

            // 1. Shopping bag handle: M43,38 C43,30 65,30 65,38
            val handlePath = Path().apply {
                moveTo(sx(43f), sy(38f))
                cubicTo(
                    sx(43f), sy(30f),
                    sx(65f), sy(30f),
                    sx(65f), sy(38f)
                )
            }
            drawPath(
                path = handlePath,
                color = Color.White,
                style = Stroke(
                    width = 5.2f * scale,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // 2. Shopping bag body: M33,40 L75,40 C77.2,40 79,41.8 78.8,44 L75.8,77 C75.6,79.2 73.8,81 71.6,81 L36.4,81 C34.2,81 32.4,79.2 32.2,77 L29.2,44 C29,41.8 30.8,40 33,40 Z
            val bodyPath = Path().apply {
                moveTo(sx(33f), sy(40f))
                lineTo(sx(75f), sy(40f))
                cubicTo(
                    sx(77.2f), sy(40f),
                    sx(79f), sy(41.8f),
                    sx(78.8f), sy(44f)
                )
                lineTo(sx(75.8f), sy(77f))
                cubicTo(
                    sx(75.6f), sy(79.2f),
                    sx(73.8f), sy(81f),
                    sx(71.6f), sy(81f)
                )
                lineTo(sx(36.4f), sy(81f))
                cubicTo(
                    sx(34.2f), sy(81f),
                    sx(32.4f), sy(79.2f),
                    sx(32.2f), sy(77f)
                )
                lineTo(sx(29.2f), sy(44f))
                cubicTo(
                    sx(29f), sy(41.8f),
                    sx(30.8f), sy(40f),
                    sx(33f), sy(40f)
                )
                close()
            }
            drawPath(
                path = bodyPath,
                color = Color.White
            )

            // 3. Bold letter "S" inside the bag: M62,53 C60,49.5 46,49.5 46,56 C46,62 62,60.5 62,67.5 C62,74 47.5,74 45.5,70.5
            val sPath = Path().apply {
                moveTo(sx(62f), sy(53f))
                cubicTo(
                    sx(60f), sy(49.5f),
                    sx(46f), sy(49.5f),
                    sx(46f), sy(56f)
                )
                cubicTo(
                    sx(46f), sy(62f),
                    sx(62f), sy(60.5f),
                    sx(62f), sy(67.5f)
                )
                cubicTo(
                    sx(62f), sy(74f),
                    sx(47.5f), sy(74f),
                    sx(45.5f), sy(70.5f)
                )
            }
            drawPath(
                path = sPath,
                color = AliExpressRed,
                style = Stroke(
                    width = 5.2f * scale,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}
