package com.example.learninghelper.focus

import android.content.Context

/**
 * Compatibility facade for legacy callers. The application has migrated from
 * BIND_ACCESSIBILITY_SERVICE to [ForegroundAppMonitor] using Android's official
 * UsageStatsManager (PACKAGE_USAGE_STATS) to prevent banking RASP security blocks.
 */
@Deprecated("Migrated to ForegroundAppMonitor using UsageStatsManager")
object BlockerAccessibilityService {
    val instance: BlockerAccessibilityService get() = this

    fun enforceCurrentWindows(context: Context? = null) {
        ForegroundAppMonitor.enforceCurrentWindows(context)
    }
}
