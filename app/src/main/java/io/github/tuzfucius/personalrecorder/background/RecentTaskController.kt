package io.github.tuzfucius.personalrecorder.background

import android.app.ActivityManager
import android.content.Context
import android.util.Log

/** Controls whether the current app task is shown in the system recents list. */
interface RecentTaskController {
    fun setExcludeFromRecents(exclude: Boolean, taskId: Int? = null): Result<Unit>

    companion object {
        fun create(context: Context): RecentTaskController =
            AndroidRecentTaskController(context.applicationContext)
    }

    /** In-memory implementation for unit tests and host-side UI tests. */
    class Fake : RecentTaskController {
        var excluded: Boolean = false
            private set
        var updateCount: Int = 0
            private set

        override fun setExcludeFromRecents(exclude: Boolean, taskId: Int?): Result<Unit> {
            excluded = exclude
            updateCount++
            return Result.success(Unit)
        }
    }
}

private class AndroidRecentTaskController(
    private val context: Context,
) : RecentTaskController {
    override fun setExcludeFromRecents(exclude: Boolean, taskId: Int?): Result<Unit> {
        val manager = context.getSystemService(ActivityManager::class.java)
            ?: return failure("ActivityManager 不可用")
        val task = manager.appTasks.firstOrNull { taskId == null || it.taskInfo.id == taskId }
            ?: return failure("未找到当前应用任务")
        return runCatching {
            // AppTask is the public API for the task owned by this process. No global task
            // or launcher state is changed.
            task.setExcludeFromRecents(exclude)
        }.onFailure { error ->
            Log.w(TAG, "setExcludeFromRecents failed", error)
        }
    }

    private fun failure(message: String): Result<Unit> {
        val error = IllegalStateException(message)
        Log.w(TAG, "setExcludeFromRecents: $message")
        return Result.failure(error)
    }

    private companion object { const val TAG = "PR-Background" }
}

/** Alias kept convenient for tests that prefer a top-level fake type. */
class FakeRecentTaskController : RecentTaskController {
    var excluded: Boolean = false
        private set
    var updateCount: Int = 0
        private set

    override fun setExcludeFromRecents(exclude: Boolean, taskId: Int?): Result<Unit> {
        excluded = exclude
        updateCount++
        return Result.success(Unit)
    }
}
