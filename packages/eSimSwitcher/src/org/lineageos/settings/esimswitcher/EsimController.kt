/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.esimswitcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.SystemProperties
import android.telephony.TelephonyManager
import android.telephony.UiccSlotInfo
import android.telephony.UiccSlotMapping
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Switches Samsung's tsds2 hybrid slot the way stock TelephonyUI does: a plain
 * TelephonyManager.setSimSlotMapping() with a fixed mapping. The stock RIL performs the remux in
 * its SET_LOGICAL_TO_PHYSICAL_SLOT_MAPPING handler and moves the eUICC entry to port 1 itself.
 */
class EsimController private constructor(private val context: Context) {

    /** Notified on the main thread when a switch requested earlier finishes. */
    fun interface SwitchCallback {
        fun onSwitchFinished(ok: Boolean, esimEnabled: Boolean)
    }

    companion object {
        private const val TAG = "EsimController"

        private const val PROP_SIM_SLOT_TYPE_2 = "ril.simslottype2"

        // Mapping requests use stock's tsds2 numbering: 0 and 1 are the tray, 2 the eUICC. Slot
        // status only ever reports two slots, though, so the framework sees whichever of the
        // hybrid tray or the eUICC is muxed in at index HYBRID_TRAY_SLOT.
        private const val HYBRID_TRAY_SLOT = 1
        private const val EUICC_SLOT = 2

        // Logical slot 0 always stays on tray slot 0; logical slot 1 moves between the hybrid
        // tray and the eUICC. Stock calls these PS1_PS2 and PS1_EP1.
        private val MAPPING_PSIM =
            listOf(UiccSlotMapping(0, 0, 0), UiccSlotMapping(0, HYBRID_TRAY_SLOT, 1))
        private val MAPPING_ESIM =
            listOf(UiccSlotMapping(0, 0, 0), UiccSlotMapping(0, EUICC_SLOT, 1))

        private const val MAX_UNCROSS_ATTEMPTS = 2
        private const val SLOT_STATUS_TIMEOUT_MS = 5000L
        private const val SLOT_STATUS_SETTLE_MS = 2000L
        private const val SLOT_STATUS_POLL_MS = 200L

        @Volatile private var instance: EsimController? = null

        fun getInstance(context: Context): EsimController {
            return instance
                ?: synchronized(this) {
                    instance ?: EsimController(context.applicationContext).also { instance = it }
                }
        }
    }

    private val telephonyManager: TelephonyManager?
        get() = context.getSystemService(TelephonyManager::class.java)

    private val mainHandler = Handler(Looper.getMainLooper())

    // Owned by the singleton, not by the UI, so a switch finishes even if the user leaves
    // Settings.
    private val switchExecutor =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "esim-switch") }
    private val switchInFlight = AtomicBoolean(false)

    @Volatile private var switchCallback: SwitchCallback? = null

    fun isSwitchInProgress(): Boolean = switchInFlight.get()

    /** Registers the observer for an in-flight switch; pass null to detach. */
    fun setSwitchCallback(callback: SwitchCallback?) {
        switchCallback = callback
    }

    /**
     * Starts a switch on a background thread owned by this singleton and returns whether it was
     * accepted (false when one is already running).
     */
    fun requestEsimEnabled(enable: Boolean): Boolean {
        if (!switchInFlight.compareAndSet(false, true)) {
            Log.w(TAG, "switch already in progress; ignoring request $enable")
            return false
        }
        switchExecutor.execute {
            val ok =
                try {
                    setEsimEnabled(enable)
                } finally {
                    switchInFlight.set(false)
                }
            // The RIL may publish the slot type after the mapping request returns, so report
            // the requested state on success rather than reading it back.
            val enabled = if (ok) enable else isEsimEnabled()
            mainHandler.post { switchCallback?.onSwitchFinished(ok, enabled) }
        }
        return true
    }

    /** Same test stock uses: the RIL reports the hybrid slot as eSIM. */
    fun isEsimEnabled(): Boolean = SystemProperties.get(PROP_SIM_SLOT_TYPE_2, "0") == "1"

    /**
     * True when the hybrid tray holds a card. Enabling eSIM remuxes that slot to the eUICC and
     * drops the SIM, so callers should warn before proceeding.
     */
    fun hasPhysicalSimInHybridSlot(): Boolean {
        if (isEsimEnabled()) return false
        val slot = telephonyManager?.uiccSlotsInfo?.getOrNull(HYBRID_TRAY_SLOT) ?: return false
        return !slot.isEuicc && slot.cardStateInfo == UiccSlotInfo.CARD_STATE_INFO_PRESENT
    }

    private fun setEsimEnabled(enable: Boolean): Boolean {
        val tm = telephonyManager ?: return false
        if (!restoreUncrossedMapping(tm)) return false
        if (isEsimEnabled() == enable) {
            Log.i(TAG, "hybrid slot already ${if (enable) "eSIM" else "pSIM"}")
            return true
        }
        return applyMapping(tm, if (enable) MAPPING_ESIM else MAPPING_PSIM)
    }

    private fun isMappingCrossed(tm: TelephonyManager): Boolean =
        tm.simSlotMapping.any { it.logicalSlotIndex == 1 && it.physicalSlotIndex == 0 }

    // The modem keeps a crossed logical mapping across reboots, and the RIL then routes the
    // eSIM mapping wrongly. MAPPING_PSIM only remaps logical slots while both
    // ril.simslottype props are 0; otherwise it first remuxes the hybrid slot to the tray.
    private fun restoreUncrossedMapping(tm: TelephonyManager): Boolean {
        if (!isMappingCrossed(tm)) return true
        repeat(MAX_UNCROSS_ATTEMPTS) {
            Log.w(TAG, "logical slots crossed; sending pSIM mapping")
            if (!applyMapping(tm, MAPPING_PSIM)) return false
            if (awaitSettledUncrossed(tm)) return true
        }
        return false
    }

    // Slot status passes through transient layouts after a remap. Switching the eUICC in before
    // it settles lets its card status arrive without an ATR ahead of the slot status, which
    // makes the framework create a plain UiccCard for it and crash on the next EuiccCard cast.
    private fun awaitSettledUncrossed(tm: TelephonyManager): Boolean {
        val deadline = SystemClock.elapsedRealtime() + SLOT_STATUS_TIMEOUT_MS
        var uncrossedSince = -1L
        while (true) {
            val now = SystemClock.elapsedRealtime()
            if (isMappingCrossed(tm)) {
                if (now >= deadline) return false
                uncrossedSince = -1L
            } else if (uncrossedSince < 0) {
                uncrossedSince = now
            } else if (now - uncrossedSince >= SLOT_STATUS_SETTLE_MS) {
                return true
            }
            SystemClock.sleep(SLOT_STATUS_POLL_MS)
        }
    }

    private fun applyMapping(tm: TelephonyManager, mapping: List<UiccSlotMapping>): Boolean {
        return try {
            // Blocks until the RIL answers; throws if the request is rejected.
            tm.setSimSlotMapping(mapping)
            Log.i(TAG, "setSimSlotMapping($mapping) done")
            true
        } catch (e: RuntimeException) {
            Log.e(TAG, "setSimSlotMapping($mapping) failed", e)
            false
        }
    }
}
