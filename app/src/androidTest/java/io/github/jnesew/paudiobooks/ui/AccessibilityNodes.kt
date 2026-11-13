package io.github.jnesew.paudiobooks.ui

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/** Preserve node ownership on the API 26 floor without using pooled APIs on newer OSes. */
@Suppress("DEPRECATION") // The copy constructor is available from API 30.
internal fun AccessibilityNodeInfo.copyNode(): AccessibilityNodeInfo =
    if (Build.VERSION.SDK_INT >= 30) AccessibilityNodeInfo(this)
    else AccessibilityNodeInfo.obtain(this)

@Suppress("DEPRECATION") // Nodes still need returning to the pool through API 32.
internal fun AccessibilityNodeInfo.recycleIfPooled() {
    if (Build.VERSION.SDK_INT < 33) recycle()
}
