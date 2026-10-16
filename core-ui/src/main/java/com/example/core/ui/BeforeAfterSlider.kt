package com.example.core.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun BeforeAfterSlider(
    originalBitmap: Bitmap?,
    reconstructedBitmap: Bitmap?,
    modifier: Modifier = Modifier,
    heightDp: Int = 320,
) {
    if (originalBitmap == null && reconstructedBitmap == null) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(heightDp.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No image selected",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    if (originalBitmap != null && reconstructedBitmap == null) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(heightDp.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = originalBitmap.asImageBitmap(),
                contentDescription = "Original image preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.65f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp),
            ) {
                Text(
                    text = "ORIGINAL SOURCE",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        return
    }

    val origBmp = originalBitmap ?: reconstructedBitmap!!
    val reconBmp = reconstructedBitmap ?: originalBitmap!!

    var sliderFraction by remember { mutableFloatStateOf(0.5f) }
    val animatedFraction by animateFloatAsState(
        targetValue = sliderFraction,
        animationSpec = tween(durationMillis = 60),
        label = "sliderDrag",
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Color(0xFF0F1015))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(26.dp)),
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val density = LocalDensity.current

        // Touch & Drag handler
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        sliderFraction = (offset.x / widthPx).coerceIn(0.05f, 0.95f)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        sliderFraction = (sliderFraction + (dragAmount.x / widthPx)).coerceIn(0.02f, 0.98f)
                    }
                },
        ) {
            // Layer 1: Reconstructed (Right side)
            Image(
                bitmap = reconBmp.asImageBitmap(),
                contentDescription = "AI Reconstructed image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )

            // Layer 2: Original (Left side clipped to divider)
            Image(
                bitmap = origBmp.asImageBitmap(),
                contentDescription = "Original image",
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        clipRect(
                            left = 0f,
                            top = 0f,
                            right = size.width * animatedFraction,
                            bottom = size.height,
                        ) {
                            this@drawWithContent.drawContent()
                        }
                    },
                contentScale = ContentScale.Fit,
            )

            // Layer 3: Vertical Divider line
            val dividerXOffsetPx = (widthPx * animatedFraction).roundToInt()
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(3.dp)
                    .offset { IntOffset(dividerXOffsetPx - with(density) { 1.5.dp.roundToPx() }, 0) }
                    .background(Color.White.copy(alpha = 0.95f)),
            )

            // Layer 4: Samsung One UI Tactile Center Grip Handle
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .offset {
                        IntOffset(
                            x = dividerXOffsetPx - with(density) { 22.dp.roundToPx() },
                            y = (heightPx.toInt() / 2) - with(density) { 22.dp.roundToPx() },
                        )
                    }
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.size(3.dp, 14.dp).background(Color(0xFF2A2D34), RoundedCornerShape(2.dp)))
                    Spacer(modifier = Modifier.width(3.dp))
                    Box(modifier = Modifier.size(3.dp, 14.dp).background(Color(0xFF2A2D34), RoundedCornerShape(2.dp)))
                }
            }

            // Layer 5: Glassmorphic Chips
            // Left Chip: ORIGINAL
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.65f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp),
            ) {
                Text(
                    text = "ORIGINAL",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            // Right Chip: AI RECONSTRUCTED
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.90f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "AI RECONSTRUCTED",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
