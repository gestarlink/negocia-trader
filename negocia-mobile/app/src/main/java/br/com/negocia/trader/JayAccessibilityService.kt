package br.com.negocia.trader

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Explicitly enabled by the user in Android Accessibility Settings. */
class JayAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: JayAccessibilityService? = null
            private set
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun scrollForward(): Boolean = scroll(rootInActiveWindow, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    fun scrollBackward(): Boolean = scroll(rootInActiveWindow, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    private fun scroll(node: AccessibilityNodeInfo?, action: Int): Boolean {
        if (node == null) return false
        if (node.isScrollable && node.performAction(action)) return true
        for (i in 0 until node.childCount) if (scroll(node.getChild(i), action)) return true
        return false
    }
}
