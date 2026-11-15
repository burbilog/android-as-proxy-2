package net.isaeff.android.asproxy

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** A bounded in-memory log; notifications are always delivered on the main thread. */
object AAPLog {
    private const val MAX_LINES = 1000
    private const val MAX_MESSAGE_CHARS = 2048
    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun append(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "[$timestamp] ${message.take(MAX_MESSAGE_CHARS)}\n"
        val callbacks = synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            listeners.toList()
        }
        if (callbacks.isNotEmpty()) {
            main.post {
                callbacks.forEach { callback ->
                    try { callback() } catch (_: Exception) { }
                }
            }
        }
    }

    fun getLog(): String = synchronized(lock) { lines.joinToString(separator = "") }

    fun addListener(listener: () -> Unit) = synchronized(lock) { listeners.add(listener) }

    fun removeListener(listener: () -> Unit) = synchronized(lock) { listeners.remove(listener) }
}
