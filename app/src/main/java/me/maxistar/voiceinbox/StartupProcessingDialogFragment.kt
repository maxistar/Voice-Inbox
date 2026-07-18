package me.maxistar.voiceinbox

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.widget.CheckBox
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment

class StartupProcessingDialogFragment : AppCompatDialogFragment() {
    private lateinit var listener: Listener
    private var callbackDelivered = false

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? Listener
            ?: error("Host Activity must implement StartupProcessingDialogFragment.Listener")
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val pendingCount = requireArguments().getInt(ARG_PENDING_COUNT)
        val rememberChoice = CheckBox(requireContext()).apply {
            text = getString(R.string.startup_processing_remember_choice)
        }
        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.startup_processing_prompt_title)
            .setMessage(
                resources.getQuantityString(
                    R.plurals.startup_processing_prompt_message,
                    pendingCount,
                    pendingCount,
                ),
            )
            .setView(rememberChoice)
            .setPositiveButton(R.string.startup_processing_process_now) { _, _ ->
                callbackDelivered = true
                listener.onStartupProcessingConfirmed(rememberChoice.isChecked)
            }
            .setNegativeButton(R.string.startup_processing_leave_queued) { _, _ ->
                callbackDelivered = true
                listener.onStartupProcessingDeclined(rememberChoice.isChecked)
            }
            .create()
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        if (!callbackDelivered) {
            callbackDelivered = true
            listener.onStartupProcessingDeclined(remember = false)
        }
    }

    interface Listener {
        fun onStartupProcessingConfirmed(remember: Boolean)
        fun onStartupProcessingDeclined(remember: Boolean)
    }

    companion object {
        const val TAG = "startup-processing-prompt"
        private const val ARG_PENDING_COUNT = "pending-count"

        fun newInstance(pendingCount: Int): StartupProcessingDialogFragment =
            StartupProcessingDialogFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_PENDING_COUNT, pendingCount)
                }
            }
    }
}
