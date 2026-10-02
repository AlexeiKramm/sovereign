package io.github.alexeikramm.sovereign.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeikramm.sovereign.R

/**
 * The Sovereign brand mark + wordmark, defined once so the Home hero and the setup wizard cannot
 * drift. Size is parameterised: Home renders it large, the wizard header compact.
 */
@Composable
fun SovereignWordmark(
    modifier: Modifier = Modifier,
    markSize: Dp = 28.dp,
    fontSize: TextUnit = 28.sp,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_notification),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(markSize),
        )
        Text(
            text = "Sovereign",
            fontSize = fontSize,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.5).sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
