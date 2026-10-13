package io.github.traushb.actuali.views.components

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import io.github.traushb.actuali.services.Defaults
import io.github.traushb.actuali.services.bool
import io.github.traushb.actuali.services.contains
import io.github.traushb.actuali.services.string

/**
 * SwiftUI's `@AppStorage`: a preference as Compose state. One state per key,
 * so every screen showing the same setting agrees. Main-thread only.
 */
object AppStorage {
    private val states = HashMap<String, MutableState<*>>()

    @Suppress("UNCHECKED_CAST") // One key, one type: each key is only ever read through one of these.
    fun bool(key: String, default: Boolean): MutableState<Boolean> = states.getOrPut(key) {
        val defaults = Defaults.standard
        WriteThrough(if (defaults.contains(key)) defaults.bool(key) else default) { defaults.set(key, it) }
    } as MutableState<Boolean>

    @Suppress("UNCHECKED_CAST") // As above.
    fun string(key: String, default: String): MutableState<String> = states.getOrPut(key) {
        val defaults = Defaults.standard
        WriteThrough(defaults.string(key) ?: default) { defaults.set(key, it) }
    } as MutableState<String>

    private class WriteThrough<T>(initial: T, private val write: (T) -> Unit) : MutableState<T> {
        private val state = mutableStateOf(initial)

        override var value: T
            get() = state.value
            set(value) {
                state.value = value
                write(value)
            }

        override fun component1(): T = value

        override fun component2(): (T) -> Unit = { value = it }
    }
}
