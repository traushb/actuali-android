package io.github.traushb.actuali.views.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.traushb.actuali.models.Tag

/** The tag's color, else the accent — as iOS's `Tag.swiftUIColor`. */
val Tag.composeColor: Color
    @Composable get() = color?.let(::parseHexColor) ?: MaterialTheme.colorScheme.primary

/** `#RRGGBB` or `#RRGGBBAA`, the two forms Actual stores. */
fun parseHexColor(hex: String): Color? {
    val digits = hex.trim().replace("#", "")
    val value = digits.toLongOrNull(16) ?: return null
    return when (digits.length) {
        6 -> Color(0xFF000000 or value)
        8 -> Color(((value and 0xFF) shl 24) or (value ushr 8))
        else -> null
    }
}
