package io.github.tuzfucius.personalrecorder.background

import android.app.ActivityManager
import android.content.Context

/** Controls whether the current app task is shown in the system recents list. */
interface RecentTaskController {
    fun setExcludeFromRecents(exclude: Boolean)

    companion object {
        fun create(context: Context): RecentTaskController =
            AndroidRecentTaskController(context.applicationContext)
    }

    /** In-memory implementation for unit tests and host-side UI tests. */
    class Fake : RecentTaskController {
        var excluded: Boolean = false
            private set

        override fun setExcludeFromRecents(exclude: Boolean) {
            excluded = exclude
        }
    }
}

private class AndroidRecentTaskController(
    private val context: Context,
) : RecentTaskController {
    override fun setExcludeFromRecents(exclude: Boolean) {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        // AppTask is the public API for the task owned by this process. No global task
        // or launcher state is changed.
        manager.appTasks.firstOrNull()?.setExcludeFromRecents(exclude)
    }
}

/** Alias kept convenient for tests that prefer a top-level fake type. */
class FakeRecentTaskController : RecentTaskController {
    var excluded: Boolean = false
        private set

    override fun setExcludeFromRecents(exclude: Boolean) {
        excluded = exclude
    }
}
