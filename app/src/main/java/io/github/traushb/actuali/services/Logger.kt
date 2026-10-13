package io.github.traushb.actuali.services

import android.util.Log

/** `os.Logger` stand-in: one tag per subsystem category. */
class Logger(category: String) {
    private val tag = "Actuali.$category"

    fun debug(message: String) {
        Log.d(tag, message)
    }

    fun info(message: String) {
        Log.i(tag, message)
    }

    fun notice(message: String) {
        Log.i(tag, message)
    }

    fun warning(message: String) {
        Log.w(tag, message)
    }

    fun error(message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
    }
}
