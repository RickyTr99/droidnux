package app.droidnux.desktop

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Hands the desktop the keys Android keeps for itself (Meta, Alt+Tab,
 * Ctrl+Esc...), while the desktop has the focus. Key filtering is all it
 * does; the user turns it on in Android's accessibility settings. It runs in
 * the desktop's process, next to the X server.
 */
class DesktopKeysService : AccessibilityService() {

    override fun onKeyEvent(event: KeyEvent): Boolean = DesktopActivity.onSystemKey(event)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        fun isEnabled(context: Context): Boolean {
            val name = ComponentName(context, DesktopKeysService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return enabled.orEmpty().split(':').any { it.equals(name, ignoreCase = true) }
        }
    }
}
