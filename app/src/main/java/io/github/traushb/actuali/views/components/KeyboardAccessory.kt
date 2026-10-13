package io.github.traushb.actuali.views.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * UIKit's `inputAccessoryView`: a bar the focused field puts on top of the
 * keyboard. Android keyboards have no such slot, so each window hosts one
 * ([KeyboardAccessoryHost]) and draws it above the IME.
 */
class KeyboardAccessory {
    var content: (@Composable () -> Unit)? by mutableStateOf(null)
}

/** The bar's height, which forms leave free above the keyboard. */
val KeyboardAccessoryHeight = 56.dp

val LocalKeyboardAccessory = staticCompositionLocalOf<KeyboardAccessory> { error("No KeyboardAccessoryHost in this window") }

/** One per window: the app's content, and over it the accessory bar while the keyboard is up. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardAccessoryHost(content: @Composable BoxScope.() -> Unit) {
    val accessory = remember { KeyboardAccessory() }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalKeyboardAccessory provides accessory) { content() }
        val bar = accessory.content
        if (bar != null && WindowInsets.isImeVisible) {
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding().height(KeyboardAccessoryHeight), tonalElevation = 3.dp, shadowElevation = 2.dp) { bar() }
        }
    }
}
