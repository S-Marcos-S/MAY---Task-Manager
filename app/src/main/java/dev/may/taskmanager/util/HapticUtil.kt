// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.util

import android.view.HapticFeedbackConstants
import android.view.View

object HapticUtil {
    fun tick(view: View, enabled: Boolean = true) {
        if (!enabled) return
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
}
