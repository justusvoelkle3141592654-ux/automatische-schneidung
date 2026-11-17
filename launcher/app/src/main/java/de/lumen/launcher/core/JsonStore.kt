package de.lumen.launcher.core

import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Shared JSON configuration: tolerant to schema evolution between app versions. */
val LumenJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
    explicitNulls = false
}

/**
 * Small persistent state holder backed by a JSON file.
 *
 * - Loads synchronously on creation (files are tiny) so the UI never flashes an empty state.
 * - Writes are debounced and atomic ([AtomicFile]) so a crash can never corrupt the file.
 */
class JsonStore<T>(
    file: File,
    private val serializer: KSerializer<T>,
    default: () -> T,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 250,
) {
    private val atomicFile = AtomicFile(file)
    private val _state = MutableStateFlow(load() ?: default())
    val state: StateFlow<T> = _state.asStateFlow()
    val value: T get() = _state.value

    private var pendingSave: Job? = null

    private fun load(): T? = try {
        if (!atomicFile.baseFile.exists()) null
        else LumenJson.decodeFromString(serializer, atomicFile.readFully().decodeToString())
    } catch (t: Throwable) {
        Log.w("JsonStore", "Could not read ${atomicFile.baseFile.name}, using defaults", t)
        null
    }

    fun update(transform: (T) -> T) {
        _state.update(transform)
        scheduleSave()
    }

    fun set(value: T) = update { value }

    private fun scheduleSave() {
        pendingSave?.cancel()
        pendingSave = scope.launch(Dispatchers.IO) {
            delay(debounceMs)
            writeNow()
        }
    }

    /** Writes the current state immediately (used when the app goes to background). */
    fun flush() {
        pendingSave?.cancel()
        writeNow()
    }

    @Synchronized
    private fun writeNow() {
        val text = LumenJson.encodeToString(serializer, _state.value)
        val out = try { atomicFile.startWrite() } catch (t: Throwable) {
            Log.e("JsonStore", "Cannot open ${atomicFile.baseFile.name}", t); return
        }
        try {
            out.write(text.encodeToByteArray())
            atomicFile.finishWrite(out)
        } catch (t: Throwable) {
            atomicFile.failWrite(out)
            Log.e("JsonStore", "Write failed for ${atomicFile.baseFile.name}", t)
        }
    }
}
