package com.example.learninghelper.focus

import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import android.util.LruCache
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

data class InstalledApp(val packageName: String, val label: String, val icon: ImageBitmap?)

object AppCatalog {

    val ALWAYS_BLOCKED_DURING_SESSION = setOf(
        "com.samsung.android.lool",
        "com.samsung.android.sm_cn",
        "com.samsung.android.app.routines",
        "com.android.vending",
        "com.sec.android.app.samsungapps",
    )

    val THESIS_ESSENTIALS = setOf(
        "com.google.android.apps.authenticator2",
        "com.azure.authenticator",
        "com.duosecurity.duomobile",
        "com.authy.authy",
        "com.sec.android.app.popupcalculator",
        "com.samsung.android.app.notes",
        "com.sec.android.app.voicenote",
        "com.sec.android.app.myfiles",
        "com.sec.android.app.camera",
        "com.adobe.reader",
        "com.google.android.apps.docs",
        "com.microsoft.office.word",
    )

    val STATIC_ALWAYS_ALLOWED = setOf(
        "android",
        "com.android.systemui",
        "com.android.server.telecom",
        "com.android.phone",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.incallui",
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",
        "com.samsung.android.app.telephonyui",
        "com.samsung.android.app.contacts",
        "com.samsung.android.contacts",
        "com.google.android.contacts",
        "com.android.contacts",
        "com.sec.android.app.clockpackage",
        "com.google.android.deskclock",
        "com.android.emergency",
        "com.google.android.apps.safetyhub",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.packageinstaller",
    )

    private val labelCache = HashMap<String, String>()
    private val iconCache = LruCache<String, ImageBitmap>(80)

    fun alwaysAllowedPackages(context: Context): Set<String> {
        val result = STATIC_ALWAYS_ALLOWED.toMutableSet()
        result += context.packageName

        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let { result += it }

        val dialIntent = Intent(Intent.ACTION_DIAL)
        context.packageManager.queryIntentActivities(dialIntent, 0)
            .mapTo(result) { it.activityInfo.packageName }

        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.queryIntentActivities(homeIntent, 0)
            .mapTo(result) { it.activityInfo.packageName }

        context.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList
            ?.mapTo(result) { it.packageName }

        return result
    }

    fun loadSelectableApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val alwaysAllowed = alwaysAllowedPackages(context)
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcherIntent, 0)
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName !in alwaysAllowed && it.activityInfo.packageName !in ALWAYS_BLOCKED_DURING_SESSION }
            .map { info ->
                val pkg = info.activityInfo.packageName
                val label = labelCache.getOrPut(pkg) { info.loadLabel(pm).toString() }
                val icon = iconCache.get(pkg) ?: runCatching {
                    info.loadIcon(pm).toBitmap(width = 96, height = 96).asImageBitmap().also {
                        iconCache.put(pkg, it)
                    }
                }.getOrNull()
                InstalledApp(pkg, label, icon)
            }
            .sortedBy { it.label.lowercase() }
    }

    fun labelFor(context: Context, packageName: String): String {
        labelCache[packageName]?.let { return it }
        val label = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
        labelCache[packageName] = label
        return label
    }
}
