// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import dev.may.taskmanager.root.RootBatteryStatsCollector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

/**
 * Advanced drain tracker that monitors real-time battery drain across different device states:
 * screen-on (active vs idle), screen-off (awake vs deep sleep), and charging.
 */
class AdvancedDrainTracker private constructor(
    private val context: Context,
    private val shellRunner: ShellRunner = ShellRunner(context.applicationContext),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    companion object {
        private const val TAG = "AdvancedDrainTracker"
        private const val DEEP_SLEEP_THRESHOLD_MS = 30_000L
        private const val DEFAULT_INTERVAL_MS = 60_000L

        private const val PREFS_NAME = "battery_drain_session"
        private const val KEY_HAS_SESSION = "has_session"
        private const val KEY_SESSION_START_TIME = "session_start_time"
        private const val KEY_SESSION_START_REALTIME = "session_start_realtime"
        private const val KEY_SESSION_START_UPTIME = "session_start_uptime"
        private const val KEY_LAST_SAVE_TIME = "last_save_time"
        private const val KEY_LAST_SAVE_REALTIME = "last_save_realtime"
        private const val KEY_LAST_SAVE_UPTIME = "last_save_uptime"
        private const val KEY_LAST_SCREEN_STATE = "last_screen_state"
        private const val KEY_LAST_SCREEN_CHANGE_REALTIME = "last_screen_change_realtime"
        private const val KEY_BOOT_TIME = "boot_time"
        private const val KEY_BOOT_ID = "boot_id"
        private const val KEY_BOOT_COUNT = "boot_count"

        private const val KEY_CUMULATIVE_SCREEN_ON_TIME = "cumulative_screen_on_time"
        private const val KEY_CUMULATIVE_SCREEN_OFF_TIME = "cumulative_screen_off_time"
        private const val KEY_CUMULATIVE_DEEP_SLEEP_TIME = "cumulative_deep_sleep_time"
        private const val KEY_CUMULATIVE_AWAKE_TIME = "cumulative_awake_time"
        private const val KEY_CUMULATIVE_ACTIVE_TIME = "cumulative_active_time"
        private const val KEY_CUMULATIVE_IDLE_TIME = "cumulative_idle_time"

        private const val KEY_CUMULATIVE_SCREEN_ON_DRAIN = "cumulative_screen_on_drain"
        private const val KEY_CUMULATIVE_SCREEN_OFF_DRAIN = "cumulative_screen_off_drain"
        private const val KEY_CUMULATIVE_DEEP_SLEEP_DRAIN = "cumulative_deep_sleep_drain"
        private const val KEY_CUMULATIVE_AWAKE_DRAIN = "cumulative_awake_drain"
        private const val KEY_CUMULATIVE_ACTIVE_DRAIN = "cumulative_active_drain"
        private const val KEY_CUMULATIVE_IDLE_DRAIN = "cumulative_idle_drain"

        private const val KEY_LAST_BATTERY_MAH = "last_battery_mah"
        private const val KEY_LAST_BATTERY_LEVEL = "last_battery_level"

        private const val KEY_LAST_DROP_MAH = "last_drop_mah"
        private const val KEY_LAST_DROP_LEVEL = "last_drop_level"
        private const val KEY_LAST_DROP_SCREEN_ON_TIME = "last_drop_screen_on_time"
        private const val KEY_LAST_DROP_SCREEN_OFF_TIME = "last_drop_screen_off_time"
        private const val KEY_LAST_DROP_DEEP_SLEEP_TIME = "last_drop_deep_sleep_time"
        private const val KEY_LAST_DROP_AWAKE_TIME = "last_drop_awake_time"
        private const val KEY_LAST_DROP_REALTIME = "last_drop_realtime"
        private const val KEY_LAST_DROP_UPTIME = "last_drop_uptime"
        private const val KEY_LAST_DROP_IS_CHARGING = "last_drop_is_charging"

        private const val KEY_CUMULATIVE_SCREEN_ON_CHARGE_MAH = "cumulative_screen_on_charge_mah"
        private const val KEY_CUMULATIVE_SCREEN_OFF_CHARGE_MAH = "cumulative_screen_off_charge_mah"
        private const val KEY_CUMULATIVE_SCREEN_ON_CHARGE_TIME = "cumulative_screen_on_charge_time"
        private const val KEY_CUMULATIVE_SCREEN_OFF_CHARGE_TIME = "cumulative_screen_off_charge_time"

        private fun SharedPreferences.Editor.putDouble(key: String, value: Double): SharedPreferences.Editor =
            putLong(key, java.lang.Double.doubleToRawLongBits(value))

        private fun SharedPreferences.getDouble(key: String, default: Double): Double =
            java.lang.Double.longBitsToDouble(getLong(key, java.lang.Double.doubleToRawLongBits(default)))

        @Volatile
        private var instance: AdvancedDrainTracker? = null

        fun getInstance(context: Context): AdvancedDrainTracker {
            return instance ?: synchronized(this) {
                instance ?: AdvancedDrainTracker(context.applicationContext).also { instance = it }
            }
        }
    }

    private val running = AtomicBoolean(false)
    private val snapshotMutex = Mutex()
    private var trackingJob: Job? = null

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    private val _drainState = MutableStateFlow(DrainState())
    val drainState: StateFlow<DrainState> = _drainState.asStateFlow()

    private val _snapshots = MutableStateFlow<List<DrainSnapshot>>(emptyList())
    val snapshots: StateFlow<List<DrainSnapshot>> = _snapshots.asStateFlow()

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private var lastSnapshot: DrainSnapshot? = null
    private var lastScreenState: Boolean = powerManager.isInteractive
    private var lastScreenChangeTime: Long = System.currentTimeMillis()
    private var lastScreenChangeRealtime: Long = SystemClock.elapsedRealtime()
    private var sessionStartRealtime: Long = SystemClock.elapsedRealtime()
    private var sessionStartUptime: Long = SystemClock.uptimeMillis()
    private var receiverRegistered = false
    private var hasResetForCurrentChargeCycle = false
    private val detailedCollector = DetailedStatsCollector.getInstance(context)

    private fun refreshDetailedStatsAsync() {
        scope.launch(Dispatchers.Default) {
            if (ShellRunner.hasDumpPermission(context) || RootBatteryStatsCollector.isRootAvailable()) {
                detailedCollector.refresh()
            }
        }
    }

    private var lastDropMah: Double? = null
    private var lastDropLevel: Int? = null
    private var lastDropScreenOnTime: Long = 0L
    private var lastDropScreenOffTime: Long = 0L
    private var lastDropDeepSleepTime: Long = 0L
    private var lastDropAwakeTime: Long = 0L
    private var lastDropRealtime: Long = 0L
    private var lastDropUptime: Long = 0L
    private var lastDropIsCharging: Boolean = false

    init {
        registerReceivers()
    }
    private var estimatedCapacityMah: Double = getInitialCapacity()

    private fun getInitialCapacity(): Double {
        try {
            val powerProfileClass = Class.forName("com.android.internal.os.PowerProfile")
            val powerProfile = powerProfileClass.getConstructor(Context::class.java).newInstance(context)
            val cap = powerProfileClass.getMethod("getBatteryCapacity").invoke(powerProfile) as? Double
            if (cap != null && cap > 0.0) return cap
        } catch (_: Throwable) {}

        for (path in listOf(
            "/sys/class/power_supply/battery/charge_full_design",
            "/sys/class/power_supply/battery/charge_full"
        )) {
            try {
                val file = java.io.File(path)
                if (file.exists() && file.canRead()) {
                    val value = file.readText().trim().toLongOrNull() ?: 0L
                    if (value > 100_000L) return value / 1000.0
                    if (value in 1000..20000) return value.toDouble()
                }
            } catch (_: Throwable) {}
        }

        return 4000.0
    }

    fun updateCapacity(capacityMah: Double) {
        if (capacityMah > 0 && capacityMah != estimatedCapacityMah) {
            estimatedCapacityMah = capacityMah
            updateDrainState()
        }
    }

    // Cumulative tracking (ms)
    private var cumulativeScreenOnTime: Long = 0L
    private var cumulativeScreenOffTime: Long = 0L
    private var cumulativeDeepSleepTime: Long = 0L
    private var cumulativeAwakeTime: Long = 0L
    private var cumulativeActiveTime: Long = 0L
    private var cumulativeIdleTime: Long = 0L

    // Cumulative drain (mAh)
    private var cumulativeScreenOnDrain: Double = 0.0
    private var cumulativeScreenOffDrain: Double = 0.0
    private var cumulativeDeepSleepDrain: Double = 0.0
    private var cumulativeAwakeDrain: Double = 0.0
    private var cumulativeActiveDrain: Double = 0.0
    private var cumulativeIdleDrain: Double = 0.0

    // Cumulative charge (mAh) & time (ms)
    private var cumulativeScreenOnChargeMah: Double = 0.0
    private var cumulativeScreenOffChargeMah: Double = 0.0
    private var cumulativeScreenOnChargeTime: Long = 0L
    private var cumulativeScreenOffChargeTime: Long = 0L

    private var cumulativeNetworkRxBytes: Long = 0L
    private var cumulativeNetworkTxBytes: Long = 0L

    private var sessionStartTime: Long = System.currentTimeMillis()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> onScreenStateChanged(true)
                Intent.ACTION_SCREEN_OFF -> onScreenStateChanged(false)
                Intent.ACTION_POWER_CONNECTED -> onPowerConnected()
                Intent.ACTION_POWER_DISCONNECTED -> onPowerDisconnected()
                Intent.ACTION_BATTERY_CHANGED -> handleBatteryChanged(intent)
            }
        }
    }

    private fun handleBatteryChanged(intent: Intent) {
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (scale > 0 && level >= 0) (level * 100) / scale else null

        val prefs = context.getSharedPreferences("battery_prefs", Context.MODE_PRIVATE)
        val resetOnPlug = prefs.getBoolean("auto_reset_on_plug_connected", false)
        val autoResetCharging = prefs.getBoolean("auto_reset_charging_enabled", prefs.getBoolean("auto_reset_enabled", true))
        val target = prefs.getInt("auto_reset_percent", 100).coerceIn(50, 100)

        if (isCharging) {
            val currentPct = pct ?: 0
            if (resetOnPlug && !hasResetForCurrentChargeCycle) {
                hasResetForCurrentChargeCycle = true
                scope.launch {
                    resetSessionSuspending()
                }
                return
            } else if (autoResetCharging && (currentPct >= target || currentPct >= 100 || status == BatteryManager.BATTERY_STATUS_FULL)) {
                if (!hasResetForCurrentChargeCycle) {
                    hasResetForCurrentChargeCycle = true
                    scope.launch {
                        resetSessionSuspending()
                    }
                    return
                }
            }
        } else {
            hasResetForCurrentChargeCycle = false
        }

        // Processamento orientado a eventos para capturar descargas e cargas:
        scope.launch {
            val snapshot = takeSnapshot(batteryLevelOverride = pct) ?: return@launch
            snapshotMutex.withLock {
                val prev = lastSnapshot
                val hasChanged = prev == null ||
                        (pct != null && prev.batteryLevel != pct) ||
                        (snapshot.batteryMah != null && prev.batteryMah != null && abs(prev.batteryMah - snapshot.batteryMah) > 0.05) ||
                        prev.isCharging != snapshot.isCharging

                if (hasChanged) {
                    processSnapshot(snapshot)
                    lastSnapshot = snapshot
                    updateDrainState()
                    saveSession()
                    refreshDetailedStatsAsync()
                } else {
                    updateDrainState()
                }
            }
        }
    }

    fun onPowerConnected() {
        val prefs = context.getSharedPreferences("battery_prefs", Context.MODE_PRIVATE)
        val resetOnPlug = prefs.getBoolean("auto_reset_on_plug_connected", false)
        scope.launch {
            if (resetOnPlug) {
                hasResetForCurrentChargeCycle = true
                resetSessionSuspending()
            } else {
                takeSnapshot()?.let { snapshot ->
                    snapshotMutex.withLock {
                        processSnapshot(snapshot)
                        lastSnapshot = snapshot
                        updateDrainState()
                        saveSession()
                    }
                }
                refreshDetailedStatsAsync()
            }
            DrainNotificationManager.getInstance(context).updateNow()
        }
    }

    fun onPowerDisconnected() {
        val prefs = context.getSharedPreferences("battery_prefs", Context.MODE_PRIVATE)
        val autoResetCharging = prefs.getBoolean("auto_reset_charging_enabled", prefs.getBoolean("auto_reset_enabled", true))
        val target = prefs.getInt("auto_reset_percent", 100).coerceIn(50, 100)

        scope.launch {
            val level = takeSnapshot()?.batteryLevel ?: getBatteryLevel() ?: 0
            if (autoResetCharging && (level >= target || level >= 100)) {
                if (!hasResetForCurrentChargeCycle) {
                    hasResetForCurrentChargeCycle = true
                    resetSessionSuspending()
                }
            }
            hasResetForCurrentChargeCycle = false
            takeSnapshot()?.let { snapshot ->
                snapshotMutex.withLock {
                    processSnapshot(snapshot)
                    lastSnapshot = snapshot
                    updateDrainState()
                    saveSession()
                }
            }
            refreshDetailedStatsAsync()
            DrainNotificationManager.getInstance(context).updateNow()
        }
    }

    fun isRunning(): Boolean = running.get()

    private fun getBootId(): String? {
        return try {
            val file = java.io.File("/proc/sys/kernel/random/boot_id")
            if (file.exists() && file.canRead()) file.readText().trim() else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun getBootCount(): Int {
        return try {
            android.provider.Settings.Global.getInt(
                context.contentResolver,
                android.provider.Settings.Global.BOOT_COUNT,
                -1
            )
        } catch (_: Throwable) {
            -1
        }
    }

    private fun isRebootDetected(prefs: SharedPreferences): Boolean {
        val nowRealtime = SystemClock.elapsedRealtime()
        val savedLastSaveRealtime = prefs.getLong(KEY_LAST_SAVE_REALTIME, 0L)
        if (savedLastSaveRealtime > 0L && nowRealtime < savedLastSaveRealtime) {
            Log.i(TAG, "Reboot detected: elapsedRealtime ($nowRealtime) < savedRealtime ($savedLastSaveRealtime)")
            return true
        }

        val savedBootId = prefs.getString(KEY_BOOT_ID, null)
        val currentBootId = getBootId()
        if (!savedBootId.isNullOrEmpty() && !currentBootId.isNullOrEmpty() && savedBootId != currentBootId) {
            Log.i(TAG, "Reboot detected: boot_id mismatch ($savedBootId != $currentBootId)")
            return true
        }

        val savedBootCount = prefs.getInt(KEY_BOOT_COUNT, -1)
        val currentBootCount = getBootCount()
        if (savedBootCount != -1 && currentBootCount != -1 && savedBootCount != currentBootCount) {
            Log.i(TAG, "Reboot detected: boot_count mismatch ($savedBootCount != $currentBootCount)")
            return true
        }

        val savedBootTime = prefs.getLong(KEY_BOOT_TIME, 0L)
        val currentBootTime = System.currentTimeMillis() - nowRealtime
        if (savedBootTime > 0L && abs(currentBootTime - savedBootTime) > 120_000L) {
            Log.i(TAG, "Reboot detected: boot time shifted by ${abs(currentBootTime - savedBootTime)}ms")
            return true
        }

        return false
    }

    @Synchronized
    fun saveSession() {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val nowRealtime = SystemClock.elapsedRealtime()
            val nowUptime = SystemClock.uptimeMillis()

            val pending = (nowRealtime - lastScreenChangeRealtime).coerceAtLeast(0L)
            val screenOnTotal = cumulativeScreenOnTime + if (lastScreenState) pending else 0L
            val screenOffTotal = cumulativeScreenOffTime + if (!lastScreenState) pending else 0L

            prefs.edit().apply {
                putBoolean(KEY_HAS_SESSION, true)
                putLong(KEY_SESSION_START_TIME, sessionStartTime)
                putLong(KEY_SESSION_START_REALTIME, sessionStartRealtime)
                putLong(KEY_SESSION_START_UPTIME, sessionStartUptime)
                putLong(KEY_LAST_SAVE_TIME, now)
                putLong(KEY_LAST_SAVE_REALTIME, nowRealtime)
                putLong(KEY_LAST_SAVE_UPTIME, nowUptime)
                putBoolean(KEY_LAST_SCREEN_STATE, lastScreenState)
                putLong(KEY_LAST_SCREEN_CHANGE_REALTIME, lastScreenChangeRealtime)

                putLong(KEY_BOOT_TIME, now - nowRealtime)
                getBootId()?.let { putString(KEY_BOOT_ID, it) }
                val bootCount = getBootCount()
                if (bootCount != -1) putInt(KEY_BOOT_COUNT, bootCount)

                putLong(KEY_CUMULATIVE_SCREEN_ON_TIME, screenOnTotal)
                putLong(KEY_CUMULATIVE_SCREEN_OFF_TIME, screenOffTotal)
                putLong(KEY_CUMULATIVE_DEEP_SLEEP_TIME, cumulativeDeepSleepTime)
                putLong(KEY_CUMULATIVE_AWAKE_TIME, cumulativeAwakeTime)
                putLong(KEY_CUMULATIVE_ACTIVE_TIME, cumulativeActiveTime)
                putLong(KEY_CUMULATIVE_IDLE_TIME, cumulativeIdleTime)

                putDouble(KEY_CUMULATIVE_SCREEN_ON_DRAIN, cumulativeScreenOnDrain)
                putDouble(KEY_CUMULATIVE_SCREEN_OFF_DRAIN, cumulativeScreenOffDrain)
                putDouble(KEY_CUMULATIVE_DEEP_SLEEP_DRAIN, cumulativeDeepSleepDrain)
                putDouble(KEY_CUMULATIVE_AWAKE_DRAIN, cumulativeAwakeDrain)
                putDouble(KEY_CUMULATIVE_ACTIVE_DRAIN, cumulativeActiveDrain)
                putDouble(KEY_CUMULATIVE_IDLE_DRAIN, cumulativeIdleDrain)

                putDouble(KEY_CUMULATIVE_SCREEN_ON_CHARGE_MAH, cumulativeScreenOnChargeMah)
                putDouble(KEY_CUMULATIVE_SCREEN_OFF_CHARGE_MAH, cumulativeScreenOffChargeMah)
                putLong(KEY_CUMULATIVE_SCREEN_ON_CHARGE_TIME, cumulativeScreenOnChargeTime)
                putLong(KEY_CUMULATIVE_SCREEN_OFF_CHARGE_TIME, cumulativeScreenOffChargeTime)

                putLong("cumulative_network_rx_bytes", cumulativeNetworkRxBytes)
                putLong("cumulative_network_tx_bytes", cumulativeNetworkTxBytes)

                lastSnapshot?.batteryMah?.let { putDouble(KEY_LAST_BATTERY_MAH, it) }
                lastSnapshot?.batteryLevel?.let { putInt(KEY_LAST_BATTERY_LEVEL, it) }

                lastDropMah?.let { putDouble(KEY_LAST_DROP_MAH, it) } ?: remove(KEY_LAST_DROP_MAH)
                lastDropLevel?.let { putInt(KEY_LAST_DROP_LEVEL, it) } ?: remove(KEY_LAST_DROP_LEVEL)
                putLong(KEY_LAST_DROP_SCREEN_ON_TIME, lastDropScreenOnTime)
                putLong(KEY_LAST_DROP_SCREEN_OFF_TIME, lastDropScreenOffTime)
                putLong(KEY_LAST_DROP_DEEP_SLEEP_TIME, lastDropDeepSleepTime)
                putLong(KEY_LAST_DROP_AWAKE_TIME, lastDropAwakeTime)
                putLong(KEY_LAST_DROP_REALTIME, lastDropRealtime)
                putLong(KEY_LAST_DROP_UPTIME, lastDropUptime)
                putBoolean(KEY_LAST_DROP_IS_CHARGING, lastDropIsCharging)
                apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save battery drain session", e)
        }
    }

    @Synchronized
    fun restoreSession(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_HAS_SESSION, false)) {
            Log.i(TAG, "No active battery drain session to restore")
            return false
        }

        val batteryPrefs = context.getSharedPreferences("battery_prefs", Context.MODE_PRIVATE)
        val resetOnReboot = batteryPrefs.getBoolean("auto_reset_reboot_enabled", true)
        val isReboot = isRebootDetected(prefs)

        if (isReboot) {
            if (resetOnReboot) {
                Log.i(TAG, "Device reboot detected and auto_reset_reboot_enabled is true. Clearing saved session.")
                clearSavedSession()
                return false
            } else {
                Log.i(TAG, "Device reboot detected but auto_reset_reboot_enabled is false. Preserving session across reboot.")
            }
        }

        return try {
            val now = System.currentTimeMillis()
            val nowRealtime = SystemClock.elapsedRealtime()
            val nowUptime = SystemClock.uptimeMillis()
            val savedLastSaveRealtime = prefs.getLong(KEY_LAST_SAVE_REALTIME, nowRealtime)
            val savedLastSaveUptime = prefs.getLong(KEY_LAST_SAVE_UPTIME, nowUptime)
            val savedLastScreenState = prefs.getBoolean(KEY_LAST_SCREEN_STATE, powerManager.isInteractive)

            sessionStartTime = prefs.getLong(KEY_SESSION_START_TIME, now)
            sessionStartRealtime = if (isReboot) nowRealtime - (now - sessionStartTime).coerceAtLeast(0L) else prefs.getLong(KEY_SESSION_START_REALTIME, nowRealtime)
            sessionStartUptime = if (isReboot) nowUptime else prefs.getLong(KEY_SESSION_START_UPTIME, nowUptime)

            var screenOnTime = prefs.getLong(KEY_CUMULATIVE_SCREEN_ON_TIME, 0L)
            var screenOffTime = prefs.getLong(KEY_CUMULATIVE_SCREEN_OFF_TIME, 0L)
            cumulativeDeepSleepTime = prefs.getLong(KEY_CUMULATIVE_DEEP_SLEEP_TIME, 0L)
            cumulativeAwakeTime = prefs.getLong(KEY_CUMULATIVE_AWAKE_TIME, 0L)
            cumulativeActiveTime = prefs.getLong(KEY_CUMULATIVE_ACTIVE_TIME, 0L)
            cumulativeIdleTime = prefs.getLong(KEY_CUMULATIVE_IDLE_TIME, 0L)

            cumulativeScreenOnDrain = prefs.getDouble(KEY_CUMULATIVE_SCREEN_ON_DRAIN, 0.0)
            cumulativeScreenOffDrain = prefs.getDouble(KEY_CUMULATIVE_SCREEN_OFF_DRAIN, 0.0)
            cumulativeDeepSleepDrain = prefs.getDouble(KEY_CUMULATIVE_DEEP_SLEEP_DRAIN, 0.0)
            cumulativeAwakeDrain = prefs.getDouble(KEY_CUMULATIVE_AWAKE_DRAIN, 0.0)
            cumulativeActiveDrain = prefs.getDouble(KEY_CUMULATIVE_ACTIVE_DRAIN, 0.0)
            cumulativeIdleDrain = prefs.getDouble(KEY_CUMULATIVE_IDLE_DRAIN, 0.0)

            cumulativeScreenOnChargeMah = prefs.getDouble(KEY_CUMULATIVE_SCREEN_ON_CHARGE_MAH, 0.0)
            cumulativeScreenOffChargeMah = prefs.getDouble(KEY_CUMULATIVE_SCREEN_OFF_CHARGE_MAH, 0.0)
            cumulativeScreenOnChargeTime = prefs.getLong(KEY_CUMULATIVE_SCREEN_ON_CHARGE_TIME, 0L)
            cumulativeScreenOffChargeTime = prefs.getLong(KEY_CUMULATIVE_SCREEN_OFF_CHARGE_TIME, 0L)

            cumulativeNetworkRxBytes = prefs.getLong("cumulative_network_rx_bytes", 0L)
            cumulativeNetworkTxBytes = prefs.getLong("cumulative_network_tx_bytes", 0L)

            // Restaura referencia do ultimo drop registrado
            lastDropMah = if (prefs.contains(KEY_LAST_DROP_MAH)) prefs.getDouble(KEY_LAST_DROP_MAH, 0.0)
                          else if (prefs.contains(KEY_LAST_BATTERY_MAH)) prefs.getDouble(KEY_LAST_BATTERY_MAH, 0.0)
                          else null
            lastDropLevel = if (prefs.contains(KEY_LAST_DROP_LEVEL)) prefs.getInt(KEY_LAST_DROP_LEVEL, -1).takeIf { it != -1 }
                            else if (prefs.contains(KEY_LAST_BATTERY_LEVEL)) prefs.getInt(KEY_LAST_BATTERY_LEVEL, -1).takeIf { it != -1 }
                            else null
            lastDropScreenOnTime = prefs.getLong(KEY_LAST_DROP_SCREEN_ON_TIME, screenOnTime)
            lastDropScreenOffTime = prefs.getLong(KEY_LAST_DROP_SCREEN_OFF_TIME, screenOffTime)
            lastDropDeepSleepTime = prefs.getLong(KEY_LAST_DROP_DEEP_SLEEP_TIME, cumulativeDeepSleepTime)
            lastDropAwakeTime = prefs.getLong(KEY_LAST_DROP_AWAKE_TIME, cumulativeAwakeTime)
            lastDropRealtime = prefs.getLong(KEY_LAST_DROP_REALTIME, savedLastSaveRealtime)
            lastDropUptime = prefs.getLong(KEY_LAST_DROP_UPTIME, savedLastSaveUptime)
            lastDropIsCharging = prefs.getBoolean(KEY_LAST_DROP_IS_CHARGING, isCharging())

            // Computa intervalo transcorrido durante o desligamento/sono do processo
            val gapRealtime = (nowRealtime - savedLastSaveRealtime).coerceAtLeast(0L)
            val gapUptime = (nowUptime - savedLastSaveUptime).coerceAtLeast(0L)
            val gapDeepSleep = (gapRealtime - gapUptime).coerceAtLeast(0L)
            val gapAwake = (gapRealtime - gapDeepSleep).coerceAtLeast(0L)

            if (gapRealtime > 0L) {
                // Sono profundo ocorre exclusivamente com display desligado
                screenOffTime += gapDeepSleep
                cumulativeDeepSleepTime += gapDeepSleep

                if (!savedLastScreenState) {
                    screenOffTime += gapAwake
                    cumulativeAwakeTime += gapAwake
                } else {
                    screenOnTime += gapAwake
                    cumulativeActiveTime += gapAwake
                }
            }

            cumulativeScreenOnTime = screenOnTime
            cumulativeScreenOffTime = screenOffTime
            lastScreenState = powerManager.isInteractive
            lastScreenChangeRealtime = nowRealtime
            lastScreenChangeTime = now

            val currentMah = getCurrentBatteryMah()
            val currentLevel = getBatteryLevel()
            val nowIsCharging = isCharging()

            // Se houve descarga real durante o periodo inativo, distribui proporcionalmente
            val savedMah = lastDropMah
            if (savedMah != null && currentMah != null && !nowIsCharging && !lastDropIsCharging) {
                val gapDrain = max(0.0, savedMah - currentMah)
                if (gapDrain > 0.0) {
                    val deltaOn = (cumulativeScreenOnTime - lastDropScreenOnTime).coerceAtLeast(0L)
                    val deltaOff = (cumulativeScreenOffTime - lastDropScreenOffTime).coerceAtLeast(0L)
                    val deltaTotal = deltaOn + deltaOff

                    val offRatio = if (deltaTotal > 0L) (deltaOff.toDouble() / deltaTotal).coerceIn(0.0, 1.0)
                                   else if (!savedLastScreenState) 1.0 else 0.0
                    val onRatio = 1.0 - offRatio

                    val offDrain = gapDrain * offRatio
                    val onDrain = gapDrain * onRatio

                    cumulativeScreenOffDrain += offDrain
                    cumulativeScreenOnDrain += onDrain

                    val deltaDeepSleep = (cumulativeDeepSleepTime - lastDropDeepSleepTime).coerceAtLeast(0L)
                    val deltaAwake = (cumulativeAwakeTime - lastDropAwakeTime).coerceAtLeast(0L)
                    val deltaOffTotal = deltaDeepSleep + deltaAwake
                    val sleepRatio = if (deltaOffTotal > 0L) (deltaDeepSleep.toDouble() / deltaOffTotal).coerceIn(0.0, 1.0) else 1.0

                    cumulativeDeepSleepDrain += offDrain * sleepRatio
                    cumulativeAwakeDrain += offDrain * (1.0 - sleepRatio)
                    cumulativeActiveDrain += onDrain

                    lastDropMah = currentMah
                    lastDropLevel = currentLevel
                    lastDropScreenOnTime = cumulativeScreenOnTime
                    lastDropScreenOffTime = cumulativeScreenOffTime
                    lastDropDeepSleepTime = cumulativeDeepSleepTime
                    lastDropAwakeTime = cumulativeAwakeTime
                    lastDropRealtime = nowRealtime
                    lastDropUptime = nowUptime
                }
            }

            val lastLevel = if (prefs.contains(KEY_LAST_BATTERY_LEVEL)) prefs.getInt(KEY_LAST_BATTERY_LEVEL, -1).takeIf { it != -1 } else currentLevel
            lastSnapshot = DrainSnapshot(
                timestamp = now,
                elapsedRealtime = nowRealtime,
                uptimeMillis = nowUptime,
                batteryLevel = currentLevel ?: lastLevel,
                batteryMah = currentMah ?: savedMah,
                currentMa = getCurrentNowMa(),
                isScreenOn = lastScreenState,
                isCharging = nowIsCharging,
                isDeepSleep = !lastScreenState && (nowRealtime - nowUptime > 0),
                isDozing = powerManager.isDeviceIdleMode,
                cpuAwakeTimeMs = nowUptime,
                deepSleepTimeMs = (nowRealtime - nowUptime).coerceAtLeast(0L)
            )

            updateDrainState()
            Log.i(TAG, "Battery drain tracking session successfully restored: sessionStart=${sessionStartTime}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore battery drain session", e)
            false
        }
    }

    fun clearSavedSession() {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
            Log.i(TAG, "Battery drain tracking session preferences cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear battery drain session", e)
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return

        Log.i(TAG, "Starting advanced drain tracking")
        _isTracking.value = true
        val restored = restoreSession()
        if (!restored) {
            resetSession()
        } else {
            lastScreenState = powerManager.isInteractive
            lastScreenChangeRealtime = SystemClock.elapsedRealtime()
            lastScreenChangeTime = System.currentTimeMillis()
        }
        registerReceivers()

        if (lastScreenState) {
            startTrackingLoop()
        }
        updateDrainState()
        saveSession()
        refreshDetailedStatsAsync()
    }

    private fun startTrackingLoop() {
        if (!running.get() || !lastScreenState) return
        trackingJob?.cancel()
        trackingJob = scope.launch {
            while (isActive && running.get() && lastScreenState) {
                delay(DEFAULT_INTERVAL_MS)
                if (!lastScreenState) break
                try {
                    takeSnapshot()?.let { currentSnapshot ->
                        snapshotMutex.withLock {
                            processSnapshot(currentSnapshot)
                            lastSnapshot = currentSnapshot
                            _snapshots.update { (it + currentSnapshot).takeLast(500) }
                            updateDrainState()
                            saveSession()
                        }
                    }
                    refreshDetailedStatsAsync()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in tracking loop", e)
                }
            }
        }
    }

    private fun stopTrackingLoop() {
        trackingJob?.cancel()
        trackingJob = null
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return

        Log.i(TAG, "Stopping advanced drain tracking")
        _isTracking.value = false

        val nowRealtime = SystemClock.elapsedRealtime()
        val pending = (nowRealtime - lastScreenChangeRealtime).coerceAtLeast(0L)
        if (lastScreenState) {
            cumulativeScreenOnTime += pending
        } else {
            cumulativeScreenOffTime += pending
        }
        lastScreenChangeRealtime = nowRealtime

        stopTrackingLoop()

        updateDrainState()
        saveSession()
    }

    suspend fun resetSessionSuspending() {
        clearSavedSession()
        val now = System.currentTimeMillis()
        val nowRealtime = SystemClock.elapsedRealtime()
        val nowUptime = SystemClock.uptimeMillis()

        sessionStartTime = now
        sessionStartRealtime = nowRealtime
        sessionStartUptime = nowUptime

        cumulativeScreenOnTime = 0L
        cumulativeScreenOffTime = 0L
        cumulativeDeepSleepTime = 0L
        cumulativeAwakeTime = 0L
        cumulativeActiveTime = 0L
        cumulativeIdleTime = 0L

        cumulativeScreenOnDrain = 0.0
        cumulativeScreenOffDrain = 0.0
        cumulativeDeepSleepDrain = 0.0
        cumulativeAwakeDrain = 0.0
        cumulativeActiveDrain = 0.0
        cumulativeIdleDrain = 0.0

        cumulativeScreenOnChargeMah = 0.0
        cumulativeScreenOffChargeMah = 0.0
        cumulativeScreenOnChargeTime = 0L
        cumulativeScreenOffChargeTime = 0L

        cumulativeNetworkRxBytes = 0L
        cumulativeNetworkTxBytes = 0L

        lastScreenState = powerManager.isInteractive
        lastScreenChangeTime = now
        lastScreenChangeRealtime = nowRealtime

        _snapshots.value = emptyList()
        _drainState.value = DrainState(
            sessionStartTime = sessionStartTime,
            batteryCapacityMah = estimatedCapacityMah,
            batteryTemperatureC = getBatteryTemperatureC()
        )

        snapshotMutex.withLock {
            val snapshot = takeSnapshot()
            lastSnapshot = snapshot
            if (snapshot != null) {
                lastDropMah = snapshot.batteryMah
                lastDropLevel = snapshot.batteryLevel
                lastDropScreenOnTime = 0L
                lastDropScreenOffTime = 0L
                lastDropDeepSleepTime = 0L
                lastDropAwakeTime = 0L
                lastDropRealtime = snapshot.elapsedRealtime
                lastDropUptime = snapshot.uptimeMillis
                lastDropIsCharging = snapshot.isCharging
            } else {
                lastDropMah = null
                lastDropLevel = null
            }
            updateDrainState()
            saveSession()
        }

        withContext(Dispatchers.IO) {
            try {
                detailedCollector.clearSnapshot()
                shellRunner.exec("dumpsys batterystats --reset", allowEmpty = true)
                Log.d(TAG, "dumpsys batterystats --reset executed on resetSession")
                refreshDetailedStatsAsync()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to run dumpsys batterystats --reset on resetSession", e)
            }
        }

        DrainNotificationManager.getInstance(context).updateNow()
        Log.i(TAG, "Battery drain tracking session reset")
    }

    fun resetSession() {
        scope.launch {
            resetSessionSuspending()
        }
    }

    private fun registerReceivers() {
        if (receiverRegistered) return
        try {
            val screenFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    screenReceiver,
                    screenFilter,
                    Context.RECEIVER_EXPORTED
                )
            } else {
                context.registerReceiver(screenReceiver, screenFilter)
            }
            receiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register screen receivers", e)
        }
    }

    private fun unregisterReceivers() {
        if (!receiverRegistered) return
        receiverRegistered = false
        try {
            context.unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister screen receivers", e)
        }
    }

    private fun onScreenStateChanged(screenOn: Boolean) {
        if (screenOn == lastScreenState) return

        val nowRealtime = SystemClock.elapsedRealtime()
        val now = System.currentTimeMillis()
        val duration = (nowRealtime - lastScreenChangeRealtime).coerceAtLeast(0L)

        val previousScreenState = lastScreenState
        if (previousScreenState) {
            cumulativeScreenOnTime += duration
        } else {
            cumulativeScreenOffTime += duration
        }

        lastScreenState = screenOn
        lastScreenChangeRealtime = nowRealtime
        lastScreenChangeTime = now

        if (!screenOn) {
            stopTrackingLoop()
        }

        scope.launch {
            val snapshot = takeSnapshot()
            if (snapshot != null) {
                snapshotMutex.withLock {
                    processSnapshot(snapshot)
                    lastSnapshot = snapshot
                    updateDrainState()
                    saveSession()
                }
            }
            refreshDetailedStatsAsync()
            if (screenOn && running.get()) {
                startTrackingLoop()
            }
        }
    }

    private suspend fun takeSnapshot(batteryLevelOverride: Int? = null): DrainSnapshot? {
        return try {
            val level = batteryLevelOverride?.takeIf { it in 0..100 } ?: getBatteryLevel()
            val mah = getCurrentBatteryMah(level)
            val currentMa = getCurrentNowMa()
            val isScreenOn = powerManager.isInteractive
            val isCharging = isCharging()
            val isDozing = powerManager.isDeviceIdleMode

            val nowRealtime = SystemClock.elapsedRealtime()
            val nowUptime = SystemClock.uptimeMillis()
            val deepSleepTime = (nowRealtime - nowUptime).coerceAtLeast(0L)

            DrainSnapshot(
                timestamp = System.currentTimeMillis(),
                elapsedRealtime = nowRealtime,
                uptimeMillis = nowUptime,
                batteryLevel = level,
                batteryMah = mah,
                currentMa = currentMa,
                isScreenOn = isScreenOn,
                isCharging = isCharging,
                isDeepSleep = !isScreenOn && (nowRealtime - nowUptime > 0),
                isDozing = isDozing,
                cpuAwakeTimeMs = nowUptime,
                deepSleepTimeMs = deepSleepTime
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to take snapshot", e)
            null
        }
    }

    private fun processSnapshot(current: DrainSnapshot) {
        val nowRealtime = current.elapsedRealtime
        val pending = (nowRealtime - lastScreenChangeRealtime).coerceAtLeast(0L)
        val currentScreenOnTime = cumulativeScreenOnTime + if (lastScreenState) pending else 0L
        val currentScreenOffTime = cumulativeScreenOffTime + if (!lastScreenState) pending else 0L

        val sessionElapsed = (nowRealtime - sessionStartRealtime).coerceAtLeast(0L)
        val sessionUptime = (current.uptimeMillis - sessionStartUptime).coerceAtLeast(0L)
        val currentDeepSleep = (sessionElapsed - sessionUptime).coerceAtLeast(0L).coerceAtMost(currentScreenOffTime)
        val currentAwake = (currentScreenOffTime - currentDeepSleep).coerceAtLeast(0L)

        // Atualizacao de trafego de rede por delta de snapshots
        val prevSnapshot = lastSnapshot
        if (prevSnapshot != null) {
            val rxDelta = (current.networkRxBytes - prevSnapshot.networkRxBytes).coerceAtLeast(0L)
            val txDelta = (current.networkTxBytes - prevSnapshot.networkTxBytes).coerceAtLeast(0L)
            cumulativeNetworkRxBytes += rxDelta
            cumulativeNetworkTxBytes += txDelta
        }

        val prevMah = lastDropMah
        val currMah = current.batteryMah
        val currIsCharging = current.isCharging

        // Se ainda nao ha ponto de referencia, inicializa a ancora do ciclo de leitura
        if (prevMah == null) {
            lastDropMah = currMah
            lastDropLevel = current.batteryLevel
            lastDropScreenOnTime = currentScreenOnTime
            lastDropScreenOffTime = currentScreenOffTime
            lastDropDeepSleepTime = currentDeepSleep
            lastDropAwakeTime = currentAwake
            lastDropRealtime = nowRealtime
            lastDropUptime = current.uptimeMillis
            lastDropIsCharging = currIsCharging
            return
        }

        // Transicao entre carregando e descarregando: redefine o ponto de referencia para evitar contaminacao de deltas
        if (lastDropIsCharging != currIsCharging) {
            lastDropMah = currMah
            lastDropLevel = current.batteryLevel
            lastDropScreenOnTime = currentScreenOnTime
            lastDropScreenOffTime = currentScreenOffTime
            lastDropDeepSleepTime = currentDeepSleep
            lastDropAwakeTime = currentAwake
            lastDropRealtime = nowRealtime
            lastDropUptime = current.uptimeMillis
            lastDropIsCharging = currIsCharging
            return
        }

        if (currIsCharging) {
            val deltaMah = if (currMah != null) max(0.0, currMah - prevMah) else 0.0
            val liveCurrentMa = abs(getCurrentNowMa().toDouble())
            val deltaOn = (currentScreenOnTime - lastDropScreenOnTime).coerceAtLeast(0L)
            val deltaOff = (currentScreenOffTime - lastDropScreenOffTime).coerceAtLeast(0L)
            val deltaTotal = deltaOn + deltaOff

            val addedMah = if (deltaMah > 0.0) {
                deltaMah
            } else if (liveCurrentMa > 0.0 && deltaTotal >= 2000L) {
                liveCurrentMa * (deltaTotal / 3600000.0)
            } else 0.0

            if (addedMah > 0.0) {
                val onRatio = if (deltaTotal > 0L) (deltaOn.toDouble() / deltaTotal).coerceIn(0.0, 1.0) else if (lastScreenState) 1.0 else 0.0
                val offRatio = 1.0 - onRatio

                cumulativeScreenOnChargeMah += addedMah * onRatio
                cumulativeScreenOffChargeMah += addedMah * offRatio
                cumulativeScreenOnChargeTime += deltaOn
                cumulativeScreenOffChargeTime += deltaOff

                lastDropMah = currMah
                lastDropLevel = current.batteryLevel
                lastDropScreenOnTime = currentScreenOnTime
                lastDropScreenOffTime = currentScreenOffTime
                lastDropDeepSleepTime = currentDeepSleep
                lastDropAwakeTime = currentAwake
                lastDropRealtime = nowRealtime
                lastDropUptime = current.uptimeMillis
            }
            return
        }

        // Modo Descarga:
        if (currMah == null) return
        val drainMah = max(0.0, prevMah - currMah)

        // Importante: Apenas atribui e avanca a referencia quando houver descarga real registrada
        if (drainMah > 0.0) {
            val deltaOn = (currentScreenOnTime - lastDropScreenOnTime).coerceAtLeast(0L)
            val deltaOff = (currentScreenOffTime - lastDropScreenOffTime).coerceAtLeast(0L)
            val deltaTotal = deltaOn + deltaOff

            val offRatio = if (deltaTotal > 0L) {
                (deltaOff.toDouble() / deltaTotal).coerceIn(0.0, 1.0)
            } else {
                if (!lastScreenState) 1.0 else 0.0
            }
            val onRatio = 1.0 - offRatio

            val screenOffDrain = drainMah * offRatio
            val screenOnDrain = drainMah * onRatio

            cumulativeScreenOffDrain += screenOffDrain
            cumulativeScreenOnDrain += screenOnDrain

            // Divisao proporcional entre Sono Profundo e Acordado dentro do tempo de tela desligada
            val deltaDeepSleep = (currentDeepSleep - lastDropDeepSleepTime).coerceAtLeast(0L)
            val deltaAwake = (currentAwake - lastDropAwakeTime).coerceAtLeast(0L)
            val deltaOffTotal = deltaDeepSleep + deltaAwake
            val sleepRatio = if (deltaOffTotal > 0L) {
                (deltaDeepSleep.toDouble() / deltaOffTotal).coerceIn(0.0, 1.0)
            } else {
                1.0
            }

            cumulativeDeepSleepDrain += screenOffDrain * sleepRatio
            cumulativeAwakeDrain += screenOffDrain * (1.0 - sleepRatio)

            // Divisao proporcional entre Ativo e Ocioso dentro do tempo de tela ligada
            val isActiveMa = abs(current.currentMa) > 200
            val activeRatio = if (isActiveMa) 1.0 else 0.0
            cumulativeActiveDrain += screenOnDrain * activeRatio
            cumulativeIdleDrain += screenOnDrain * (1.0 - activeRatio)
            if (isActiveMa) {
                cumulativeActiveTime += deltaOn
            } else {
                cumulativeIdleTime += deltaOn
            }

            lastDropMah = currMah
            lastDropLevel = current.batteryLevel
            lastDropScreenOnTime = currentScreenOnTime
            lastDropScreenOffTime = currentScreenOffTime
            lastDropDeepSleepTime = currentDeepSleep
            lastDropAwakeTime = currentAwake
            lastDropRealtime = nowRealtime
            lastDropUptime = current.uptimeMillis
            lastDropIsCharging = false
        }
    }

    fun updateDrainState() {
        val now = System.currentTimeMillis()
        val nowRealtime = SystemClock.elapsedRealtime()
        val nowUptime = SystemClock.uptimeMillis()

        val isRunning = running.get()
        val pending = if (isRunning) (nowRealtime - lastScreenChangeRealtime).coerceAtLeast(0L) else 0L
        val screenOnTimeTotal = cumulativeScreenOnTime + if (lastScreenState) pending else 0L
        val screenOffTimeTotal = cumulativeScreenOffTime + if (!lastScreenState) pending else 0L

        val sessionElapsed = if (isRunning) (nowRealtime - sessionStartRealtime).coerceAtLeast(0L) else (screenOnTimeTotal + screenOffTimeTotal)
        val sessionUptime = if (isRunning) (nowUptime - sessionStartUptime).coerceAtLeast(0L) else (screenOnTimeTotal + cumulativeAwakeTime)
        val totalSessionDeepSleep = (sessionElapsed - sessionUptime).coerceAtLeast(0L)

        val deepSleepTimeMs = totalSessionDeepSleep.coerceAtMost(screenOffTimeTotal)
        val awakeTimeMs = (screenOffTimeTotal - deepSleepTimeMs).coerceAtLeast(0L)

        val activeTimeMs = cumulativeActiveTime.coerceAtMost(screenOnTimeTotal)
        val idleTimeMs = (screenOnTimeTotal - activeTimeMs).coerceAtLeast(0L)

        fun calculateRate(drainMah: Double, timeMs: Long): Double {
            return if (timeMs > 0) drainMah / (timeMs / 3600000.0) else 0.0
        }

        val screenOnDrainRate = calculateRate(cumulativeScreenOnDrain, screenOnTimeTotal)
        val screenOffDrainRate = calculateRate(cumulativeScreenOffDrain, screenOffTimeTotal)
        val activeDrainRate = calculateRate(cumulativeActiveDrain, activeTimeMs)
        val idleDrainRate = calculateRate(cumulativeIdleDrain, idleTimeMs)
        val deepSleepDrainRate = calculateRate(cumulativeDeepSleepDrain, deepSleepTimeMs)
        val awakeDrainRate = calculateRate(cumulativeAwakeDrain, awakeTimeMs)

        val isNowCharging = isCharging()
        val liveChargeMa = if (isNowCharging) abs(getCurrentNowMa().toDouble()).let { if (it > 0) it else 1500.0 } else 0.0
        val screenOnChargeRate = calculateRate(cumulativeScreenOnChargeMah, cumulativeScreenOnChargeTime).let {
            if (it > 0.0) it else if (isNowCharging && powerManager.isInteractive) liveChargeMa else 0.0
        }
        val screenOffChargeRate = calculateRate(cumulativeScreenOffChargeMah, cumulativeScreenOffChargeTime).let {
            if (it > 0.0) it else if (isNowCharging && !powerManager.isInteractive) liveChargeMa else 0.0
        }

        _drainState.value = DrainState(
            timestamp = now,
            batteryLevel = getBatteryLevel(),
            batteryLevelMah = getCurrentBatteryMah(),
            batteryTemperatureC = getBatteryTemperatureC(),
            batteryCapacityMah = estimatedCapacityMah,
            isScreenOn = powerManager.isInteractive,
            isCharging = isNowCharging,
            isDeepSleep = !powerManager.isInteractive && (nowRealtime - nowUptime > 0),
            isDozing = powerManager.isDeviceIdleMode,

            screenOnDrainMah = cumulativeScreenOnDrain,
            screenOffDrainMah = cumulativeScreenOffDrain,
            activeDrainMah = cumulativeActiveDrain,
            idleDrainMah = cumulativeIdleDrain,
            deepSleepDrainMah = cumulativeDeepSleepDrain,
            awakeDrainMah = cumulativeAwakeDrain,

            screenOnTimeMs = screenOnTimeTotal,
            screenOffTimeMs = screenOffTimeTotal,
            activeTimeMs = activeTimeMs,
            idleTimeMs = idleTimeMs,
            deepSleepTimeMs = deepSleepTimeMs,
            awakeTimeMs = awakeTimeMs,

            screenOnDrainRate = screenOnDrainRate,
            screenOffDrainRate = screenOffDrainRate,
            activeDrainRate = activeDrainRate,
            idleDrainRate = idleDrainRate,
            deepSleepDrainRate = deepSleepDrainRate,
            awakeDrainRate = awakeDrainRate,

            sessionStartTime = sessionStartTime,
            lastUpdateTime = now,

            networkRxBytes = cumulativeNetworkRxBytes,
            networkTxBytes = cumulativeNetworkTxBytes,

            screenOnChargeMah = cumulativeScreenOnChargeMah,
            screenOffChargeMah = cumulativeScreenOffChargeMah,
            screenOnChargeTimeMs = cumulativeScreenOnChargeTime,
            screenOffChargeTimeMs = cumulativeScreenOffChargeTime,
            screenOnChargeRate = screenOnChargeRate,
            screenOffChargeRate = screenOffChargeRate,
            currentChargeRateMa = liveChargeMa
        )
    }

    fun getBatteryLevel(): Int? {
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level in 0..100) return level

        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val raw = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (raw >= 0 && scale > 0) return (raw * 100 / scale)

        // Fallback: sysfs
        try {
            val file = File("/sys/class/power_supply/battery/capacity")
            if (file.exists() && file.canRead()) {
                val cap = file.readText().trim().toIntOrNull()
                if (cap != null && cap in 0..100) return cap
            }
        } catch (_: Throwable) {}

        return null
    }

    fun getBatteryTemperatureC(): Float? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val raw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        if (raw > 0) return raw / 10.0f

        // Fallback: sysfs
        try {
            val f = File("/sys/class/power_supply/battery/temp")
            if (f.exists() && f.canRead()) {
                val tempVal = f.readText().trim().toIntOrNull()
                if (tempVal != null && tempVal > 0) {
                    return if (tempVal > 1000) tempVal / 1000.0f else tempVal / 10.0f
                }
            }
        } catch (_: Throwable) {}

        return null
    }

    fun getCurrentBatteryMah(levelOverride: Int? = null): Double? {
        val rawCounter = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val chargeCounter = if (rawCounter != Long.MIN_VALUE && rawCounter != 0L) abs(rawCounter) else 0L
        val counterMah = when {
            chargeCounter > 100_000L -> chargeCounter / 1000.0
            chargeCounter in 500L..25000L -> chargeCounter.toDouble()
            else -> null
        }

        val level = levelOverride?.takeIf { it in 0..100 } ?: getBatteryLevel()
        val levelMah = level?.let { (it / 100.0) * estimatedCapacityMah }

        if (levelOverride != null && levelMah != null) {
            if (counterMah != null && abs(counterMah - levelMah) <= (estimatedCapacityMah * 0.03)) {
                return counterMah
            }
            return levelMah
        }

        if (counterMah != null) return counterMah

        for (path in listOf(
            "/sys/class/power_supply/battery/charge_now",
            "/sys/class/power_supply/battery/charge_counter"
        )) {
            try {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    val v = file.readText().trim().toLongOrNull()?.let { abs(it) } ?: 0L
                    if (v > 100_000L) return v / 1000.0
                    if (v in 500L..25000L) return v.toDouble()
                }
            } catch (_: Throwable) {}
        }

        return levelMah
    }

    fun getCurrentNowMa(): Int {
        var current = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (current == 0L || current == Long.MIN_VALUE) {
            current = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        }
        return (current / 1000).toInt()
    }

    fun isCharging(): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return plugged != 0
    }
}
