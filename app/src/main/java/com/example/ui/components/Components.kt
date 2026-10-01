package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.StatusCloneableBg
import com.example.ui.theme.StatusCloneableText
import com.example.ui.theme.StatusClonedBg
import com.example.ui.theme.StatusClonedText
import com.example.ui.theme.StatusSystemBg
import com.example.ui.theme.StatusSystemText
import com.example.ui.theme.StatusUnsupportedBg
import com.example.ui.theme.StatusUnsupportedText

import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix

@Composable
fun AppIconView(
    bitmap: Bitmap?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    badgeNumber: Int? = null,
    badgeColor: Color = Color(0xFF4F46E5),
    rotationDegrees: Float = 0f,
    invertColors: Boolean = false
) {
    val colorFilter = if (invertColors) {
        ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    } else null

    Box(
        modifier = modifier.size(52.dp),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = contentDescription,
                colorFilter = colorFilter,
                modifier = Modifier
                    .size(48.dp)
                    .rotate(rotationDegrees)
                    .clip(RoundedCornerShape(12.dp))
            )
        } else {
            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .rotate(rotationDegrees),
                shape = RoundedCornerShape(12.dp),
                color = if (invertColors) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Android,
                        contentDescription = contentDescription,
                        tint = if (invertColors) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        if (badgeNumber != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(badgeColor)
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badgeNumber.toString(),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun StatusBadge(
    text: String,
    textColor: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = backgroundColor
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun CloneableBadge(modifier: Modifier = Modifier) {
    StatusBadge(
        text = "Cloneable",
        textColor = StatusCloneableText,
        backgroundColor = StatusCloneableBg,
        modifier = modifier
    )
}

/** Marks an app that Android shipped as an app bundle; all parts are cloned together. */
@Composable
fun BundleBadge(parts: Int, modifier: Modifier = Modifier) {
    StatusBadge(
        text = "Bundle ($parts parts)",
        textColor = StatusCloneableText,
        backgroundColor = StatusCloneableBg,
        modifier = modifier
    )
}

@Composable
fun ClonedBadge(count: Int, modifier: Modifier = Modifier) {
    StatusBadge(
        text = if (count > 1) "Cloned ($count)" else "Cloned",
        textColor = StatusClonedText,
        backgroundColor = StatusClonedBg,
        modifier = modifier
    )
}

@Composable
fun UnsupportedBadge(text: String = "Split APK", modifier: Modifier = Modifier) {
    StatusBadge(
        text = text,
        textColor = StatusUnsupportedText,
        backgroundColor = StatusUnsupportedBg,
        modifier = modifier
    )
}

@Composable
fun SystemAppBadge(modifier: Modifier = Modifier) {
    StatusBadge(
        text = "System",
        textColor = StatusSystemText,
        backgroundColor = StatusSystemBg,
        modifier = modifier
    )
}

@Composable
fun EmptyStateView(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    actionButtonText: String? = null,
    onActionClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (actionButtonText != null && onActionClick != null) {
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onActionClick,
                modifier = Modifier.testTag("empty_state_action_button")
            ) {
                Text(actionButtonText)
            }
        }
    }
}
