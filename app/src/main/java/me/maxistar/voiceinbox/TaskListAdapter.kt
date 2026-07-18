package me.maxistar.voiceinbox

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import me.maxistar.voiceinbox.core.AudioTaskPresentation
import me.maxistar.voiceinbox.core.SetupTaskPresentation
import me.maxistar.voiceinbox.core.TaskActionKind
import me.maxistar.voiceinbox.core.TaskActionPresentation
import me.maxistar.voiceinbox.core.TaskListState
import me.maxistar.voiceinbox.core.TaskPresentation
import me.maxistar.voiceinbox.core.TaskProgressPresentation

sealed class TaskListDisplayItem {
    abstract val stableKey: String

    data class Setup(
        val task: SetupTaskPresentation,
    ) : TaskListDisplayItem() {
        override val stableKey: String = task.stableId
    }

    data class Audio(
        val task: AudioTaskPresentation,
    ) : TaskListDisplayItem() {
        override val stableKey: String = task.stableId
    }

    data class BatchAction(
        val eligibleCount: Int,
        val enabled: Boolean,
    ) : TaskListDisplayItem() {
        override val stableKey: String = STABLE_KEY

        companion object {
            const val STABLE_KEY = "action:transcribe-all"
        }
    }

    data class OnboardingHint(
        val presentation: AndroidOnboardingHintPresentation,
    ) : TaskListDisplayItem() {
        override val stableKey: String = STABLE_KEY

        companion object {
            const val STABLE_KEY = "hint:android-onboarding"
        }
    }

    data class Empty(
        val message: String,
        val actions: List<TaskActionPresentation>,
    ) : TaskListDisplayItem() {
        override val stableKey: String = STABLE_KEY

        companion object {
            const val STABLE_KEY = "state:empty"
        }
    }
}

object TaskListDisplayItems {
    fun from(
        state: TaskListState,
        onboardingHint: AndroidOnboardingHintPresentation = AndroidOnboardingHintPresentation.HIDDEN,
    ): List<TaskListDisplayItem> {
        return buildList {
            state.tasks.filterIsInstance<SetupTaskPresentation>().forEach {
                add(TaskListDisplayItem.Setup(it))
            }
            if (onboardingHint.visible) {
                add(TaskListDisplayItem.OnboardingHint(onboardingHint))
            }
            if (state.batchAction.visible) {
                add(
                    TaskListDisplayItem.BatchAction(
                        eligibleCount = state.batchAction.eligibleCount,
                        enabled = state.batchAction.enabled,
                    ),
                )
            }
            state.tasks.filterIsInstance<AudioTaskPresentation>().forEach {
                add(TaskListDisplayItem.Audio(it))
            }
            state.emptyMessage?.let { message ->
                add(TaskListDisplayItem.Empty(message, state.emptyActions))
            }
        }
    }
}

object TaskListDisplayItemDiff : DiffUtil.ItemCallback<TaskListDisplayItem>() {
    override fun areItemsTheSame(oldItem: TaskListDisplayItem, newItem: TaskListDisplayItem): Boolean =
        oldItem::class == newItem::class && oldItem.stableKey == newItem.stableKey

    override fun areContentsTheSame(oldItem: TaskListDisplayItem, newItem: TaskListDisplayItem): Boolean =
        oldItem == newItem

    override fun getChangePayload(oldItem: TaskListDisplayItem, newItem: TaskListDisplayItem): Any? =
        progressOnlyPayload(oldItem, newItem)

    fun progressOnlyPayload(
        oldItem: TaskListDisplayItem,
        newItem: TaskListDisplayItem,
    ): TaskListChangePayload.Progress? = when {
        oldItem is TaskListDisplayItem.Setup && newItem is TaskListDisplayItem.Setup &&
            oldItem.task.progress != newItem.task.progress &&
            oldItem.task.copy(progress = null) == newItem.task.copy(progress = null) ->
            TaskListChangePayload.Progress(newItem.task.progress)
        oldItem is TaskListDisplayItem.Audio && newItem is TaskListDisplayItem.Audio &&
            oldItem.task.progress != newItem.task.progress &&
            oldItem.task.copy(progress = null) == newItem.task.copy(progress = null) ->
            TaskListChangePayload.Progress(newItem.task.progress)
        else -> null
    }
}

sealed class TaskListChangePayload {
    data class Progress(val value: TaskProgressPresentation?) : TaskListChangePayload()
}

class TaskListAdapter(
    private val onAction: (AndroidTaskActionRequest) -> Unit,
    private val onDismissOnboarding: () -> Unit,
) : ListAdapter<TaskListDisplayItem, RecyclerView.ViewHolder>(TaskListDisplayItemDiff) {
    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = stableLongId(getItem(position).stableKey)

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is TaskListDisplayItem.Setup -> VIEW_SETUP
        is TaskListDisplayItem.Audio -> VIEW_AUDIO
        is TaskListDisplayItem.BatchAction -> VIEW_BATCH
        is TaskListDisplayItem.OnboardingHint -> VIEW_ONBOARDING
        is TaskListDisplayItem.Empty -> VIEW_EMPTY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_SETUP -> SetupTaskViewHolder(inflater.inflate(R.layout.row_task, parent, false), onAction)
            VIEW_AUDIO -> AudioTaskViewHolder(inflater.inflate(R.layout.row_task, parent, false), onAction)
            VIEW_BATCH -> BatchActionViewHolder(
                inflater.inflate(R.layout.row_batch_action, parent, false),
                onAction,
            )
            VIEW_ONBOARDING -> OnboardingHintViewHolder(
                inflater.inflate(R.layout.row_onboarding_hint, parent, false),
                onAction,
                onDismissOnboarding,
            )
            VIEW_EMPTY -> EmptyTaskViewHolder(
                inflater.inflate(R.layout.row_empty_task, parent, false),
                onAction,
            )
            else -> error("Unknown task-list view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is TaskListDisplayItem.Setup -> (holder as SetupTaskViewHolder).bind(item.task)
            is TaskListDisplayItem.Audio -> (holder as AudioTaskViewHolder).bind(item.task)
            is TaskListDisplayItem.BatchAction -> (holder as BatchActionViewHolder).bind(item)
            is TaskListDisplayItem.OnboardingHint -> (holder as OnboardingHintViewHolder).bind(item)
            is TaskListDisplayItem.Empty -> (holder as EmptyTaskViewHolder).bind(item)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        val progressPayload = payloads.filterIsInstance<TaskListChangePayload.Progress>().lastOrNull()
        if (progressPayload != null && payloads.all { it is TaskListChangePayload.Progress }) {
            when (holder) {
                is SetupTaskViewHolder -> holder.bindProgress(progressPayload.value)
                is AudioTaskViewHolder -> holder.bindProgress(progressPayload.value)
                else -> onBindViewHolder(holder, position)
            }
        } else {
            onBindViewHolder(holder, position)
        }
    }

    private class SetupTaskViewHolder(
        itemView: View,
        onAction: (AndroidTaskActionRequest) -> Unit,
    ) : TaskViewHolder(itemView, onAction) {
        fun bind(task: SetupTaskPresentation) = bindTask(task, entryId = null)
    }

    private class AudioTaskViewHolder(
        itemView: View,
        onAction: (AndroidTaskActionRequest) -> Unit,
    ) : TaskViewHolder(itemView, onAction) {
        fun bind(task: AudioTaskPresentation) = bindTask(task, entryId = task.entryId)
    }

    private open class TaskViewHolder(
        itemView: View,
        private val onAction: (AndroidTaskActionRequest) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.taskTitle)
        private val badge: TextView = itemView.findViewById(R.id.taskBadge)
        private val detail: TextView = itemView.findViewById(R.id.taskDetail)
        private val progressPhase: TextView = itemView.findViewById(R.id.taskProgressPhase)
        private val progress: ProgressBar = itemView.findViewById(R.id.taskProgress)
        private val progressMeta: TextView = itemView.findViewById(R.id.taskProgressMeta)
        private val error: TextView = itemView.findViewById(R.id.taskError)
        private val actions: LinearLayout = itemView.findViewById(R.id.taskActions)
        private var boundActionOwner: Pair<String, Long?>? = null
        private var boundActions: List<TaskActionPresentation>? = null

        protected fun bindTask(task: TaskPresentation, entryId: Long?) {
            itemView.contentDescription = task.title
            title.text = task.title
            badge.text = task.badge
            bindOptional(detail, task.detail)
            bindProgress(task.progress)
            bindOptional(error, task.errorMessage)
            bindActions(task, entryId)
        }

        fun bindProgress(value: TaskProgressPresentation?) {
            progressPhase.isVisible = value != null
            progress.isVisible = value != null
            if (value == null) {
                progressMeta.isVisible = false
                return
            }
            progressPhase.text = value.phase
            progress.isIndeterminate = value.percent == null
            progress.progress = value.percent ?: 0
            bindOptional(progressMeta, progressMetadata(value))
        }

        private fun bindActions(task: TaskPresentation, entryId: Long?) {
            val owner = task.stableId to entryId
            if (boundActionOwner == owner && boundActions == task.actions) return
            boundActionOwner = owner
            boundActions = task.actions
            actions.removeAllViews()
            task.actions.forEachIndexed { index, action ->
                actions.addView(
                    MaterialButton(actions.context).apply {
                        text = action.label
                        isEnabled = action.enabled
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            if (index > 0) marginStart = actionSpacing(actions)
                        }
                        setOnClickListener {
                            onAction(AndroidTaskActionRequest(task.stableId, entryId, action.kind))
                        }
                    },
                )
            }
            actions.isVisible = task.actions.isNotEmpty()
        }
    }

    private class BatchActionViewHolder(
        itemView: View,
        private val onAction: (AndroidTaskActionRequest) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {
        private val button: MaterialButton = itemView.findViewById(R.id.batchAction)

        fun bind(item: TaskListDisplayItem.BatchAction) {
            button.text = itemView.resources.getQuantityString(
                R.plurals.transcribe_all_count,
                item.eligibleCount,
                item.eligibleCount,
            )
            button.isEnabled = item.enabled
            button.setOnClickListener {
                onAction(
                    AndroidTaskActionRequest(
                        stableId = item.stableKey,
                        entryId = null,
                        kind = TaskActionKind.TRANSCRIBE_ALL,
                    ),
                )
            }
        }
    }

    private class OnboardingHintViewHolder(
        itemView: View,
        private val onAction: (AndroidTaskActionRequest) -> Unit,
        onDismiss: () -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.onboardingTitle)
        private val explanation: TextView = itemView.findViewById(R.id.onboardingExplanation)
        private val disclosure: TextView = itemView.findViewById(R.id.onboardingDownloadDisclosure)
        private val modelStep: TextView = itemView.findViewById(R.id.onboardingModelStep)
        private val outputStep: TextView = itemView.findViewById(R.id.onboardingOutputStep)
        private val folderStep: TextView = itemView.findViewById(R.id.onboardingFolderStep)
        private val action: MaterialButton = itemView.findViewById(R.id.onboardingAction)
        private val close: ImageButton = itemView.findViewById(R.id.onboardingClose)

        init {
            close.setOnClickListener { onDismiss() }
        }

        fun bind(item: TaskListDisplayItem.OnboardingHint) {
            val presentation = item.presentation
            title.text = presentation.title
            explanation.text = presentation.explanation
            disclosure.text = presentation.downloadDisclosure.orEmpty()
            disclosure.isVisible = presentation.downloadDisclosure != null
            bindStep(modelStep, presentation.steps.first { it.kind == AndroidOnboardingStepKind.MODEL })
            bindStep(outputStep, presentation.steps.first { it.kind == AndroidOnboardingStepKind.OUTPUT })
            bindStep(folderStep, presentation.steps.first { it.kind == AndroidOnboardingStepKind.FOLDER })
            action.text = presentation.action?.label.orEmpty()
            action.isEnabled = presentation.action?.enabled == true
            action.isVisible = presentation.action != null
            action.setOnClickListener {
                presentation.action?.let { current ->
                    onAction(AndroidTaskActionRequest(item.stableKey, null, current.kind))
                }
            }
        }

        private fun bindStep(view: TextView, step: AndroidOnboardingChecklistStep) {
            view.text = itemView.resources.getString(
                if (step.complete) R.string.onboarding_step_complete else R.string.onboarding_step_incomplete,
                step.label,
            )
            view.contentDescription = itemView.resources.getString(
                if (step.complete) R.string.onboarding_step_complete_accessibility else R.string.onboarding_step_incomplete_accessibility,
                step.label,
            )
        }
    }

    private class EmptyTaskViewHolder(
        itemView: View,
        private val onAction: (AndroidTaskActionRequest) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {
        private val message: TextView = itemView.findViewById(R.id.emptyMessage)
        private val actions: LinearLayout = itemView.findViewById(R.id.emptyActions)

        fun bind(item: TaskListDisplayItem.Empty) {
            message.text = item.message
            actions.removeAllViews()
            item.actions.forEachIndexed { index, action ->
                actions.addView(
                    MaterialButton(actions.context).apply {
                        text = action.label
                        isEnabled = action.enabled
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            if (index > 0) topMargin = actionSpacing(actions)
                        }
                        setOnClickListener {
                            onAction(AndroidTaskActionRequest(item.stableKey, null, action.kind))
                        }
                    },
                )
            }
            actions.isVisible = item.actions.isNotEmpty()
        }
    }

    companion object {
        const val VIEW_SETUP = 1
        const val VIEW_AUDIO = 2
        const val VIEW_BATCH = 3
        const val VIEW_EMPTY = 4
        const val VIEW_ONBOARDING = 5

        fun stableLongId(value: String): Long {
            var hash = -0x340d631b8c46753bL
            value.forEach { character ->
                hash = hash xor character.code.toLong()
                hash *= 0x100000001b3L
            }
            return hash
        }

        private fun bindOptional(view: TextView, value: String?) {
            view.text = value.orEmpty()
            view.isVisible = !value.isNullOrBlank()
        }

        private fun actionSpacing(view: View): Int =
            (8 * view.resources.displayMetrics.density).toInt()

        private fun progressMetadata(progress: TaskProgressPresentation): String? = buildList {
            progress.percent?.let { add("$it%") }
            val totalFiles = progress.totalFiles
            if (totalFiles != null && totalFiles > 0) {
                add("${progress.completedFiles ?: 0}/$totalFiles files")
            }
            progress.failedFiles?.takeIf { it > 0 }?.let { add("$it failed") }
            val processedUs = progress.processedUs
            val durationUs = progress.durationUs
            if (processedUs != null && durationUs != null && durationUs > 0) {
                add("${formatDuration(processedUs)}/${formatDuration(durationUs)}")
            }
        }.takeIf(List<String>::isNotEmpty)?.joinToString(" • ")

        private fun formatDuration(microseconds: Long): String {
            val totalSeconds = microseconds.coerceAtLeast(0) / 1_000_000
            return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
        }
    }
}
