package org.schabi.newpipe.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persistent, bounded diagnostics visible from the app settings. */
object AppLog {
    private const val FILE_NAME = "app_diagnostics.log"
    private const val MAX_FILE_BYTES = 1024 * 1024
    private const val MAX_ENTRY_CHARS = 48 * 1024
    private const val TAG = "AppDiagnostics"
    private val lock = Any()

    fun record(
        context: Context,
        source: String,
        message: String,
        throwable: Throwable? = null
    ) {
        val details = buildString {
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date()))
            append(" [")
            append(source)
            append("] ")
            append(message)
            appendLine()
            throwable?.let {
                val trace = StringWriter()
                it.printStackTrace(PrintWriter(trace))
                append(trace.toString().take(MAX_ENTRY_CHARS))
                if (trace.toString().length > MAX_ENTRY_CHARS) {
                    appendLine("… stack trace truncado")
                }
            }
            appendLine()
        }

        try {
            synchronized(lock) {
                val file = File(context.applicationContext.filesDir, FILE_NAME)
                val existing = if (file.exists()) file.readText() else ""
                val combined = existing + details
                val bounded = if (combined.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) {
                    "[Registros antigos removidos para limitar o tamanho do arquivo]\n" +
                        combined.takeLast(MAX_FILE_BYTES / 2)
                } else {
                    combined
                }
                file.writeText(bounded)
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Não foi possível salvar o registro de diagnóstico", exception)
        }
    }

    fun read(context: Context): String = synchronized(lock) {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (file.exists()) file.readText() else ""
    }

    fun clear(context: Context) {
        synchronized(lock) {
            File(context.applicationContext.filesDir, FILE_NAME).delete()
        }
    }

    fun installUncaughtExceptionHandler(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            record(context, "Falha fatal", "Exceção não tratada na thread ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }
}
