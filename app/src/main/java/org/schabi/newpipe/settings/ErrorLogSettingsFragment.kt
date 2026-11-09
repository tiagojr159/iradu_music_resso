package org.schabi.newpipe.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.schabi.newpipe.R
import org.schabi.newpipe.util.AppLog
import org.schabi.newpipe.util.ThemeHelper

class ErrorLogSettingsFragment : Fragment(R.layout.settings_error_logs) {
    private var logText: TextView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        logText = view.findViewById(R.id.error_log_text)
        view.findViewById<View>(R.id.error_log_copy).setOnClickListener { copyLogs() }
        view.findViewById<View>(R.id.error_log_clear).setOnClickListener { confirmClearLogs() }
        refreshLogs()
    }

    override fun onResume() {
        super.onResume()
        ThemeHelper.setTitleToAppCompatActivity(
            activity,
            getString(R.string.resso_diagnostic_logs_title)
        )
        refreshLogs()
    }

    override fun onDestroyView() {
        logText = null
        super.onDestroyView()
    }

    private fun refreshLogs() {
        val logs = AppLog.read(requireContext())
        logText?.text = logs.ifBlank { getString(R.string.resso_diagnostic_logs_empty) }
    }

    private fun copyLogs() {
        val logs = AppLog.read(requireContext())
        if (logs.isBlank()) {
            Toast.makeText(requireContext(), R.string.resso_diagnostic_logs_empty, Toast.LENGTH_SHORT)
                .show()
            return
        }
        val clipboard = ContextCompat.getSystemService(requireContext(), ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(getString(R.string.resso_diagnostic_logs_title), logs))
        Toast.makeText(requireContext(), R.string.resso_diagnostic_logs_copied, Toast.LENGTH_SHORT)
            .show()
    }

    private fun confirmClearLogs() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.resso_diagnostic_logs_clear_confirm_title)
            .setMessage(R.string.resso_diagnostic_logs_clear_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.resso_diagnostic_logs_clear) { _, _ ->
                AppLog.clear(requireContext())
                refreshLogs()
                Toast.makeText(
                    requireContext(),
                    R.string.resso_diagnostic_logs_cleared,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }
}
