package com.example.callsummarizer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

class CallReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        if (state == lastState) return
        lastState = state

        val action =
            when (state) {
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    if (!hasMicPermission(context)) {
                        Log.w(TAG, "OFFHOOK but RECORD_AUDIO not granted; skipping session")
                        return
                    }
                    CallService.ACTION_START
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    // Nothing to stop (missed call, or app started after the call). Skip — avoids
                    // pointlessly starting a microphone-type FGS just to immediately stop it.
                    if (!CallService.isRunning) return
                    CallService.ACTION_STOP
                }
                else -> return
            }

        val serviceIntent = Intent(context, CallService::class.java).setAction(action)
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException / SecurityException can fire on
            // Android 12+/14+ if perms got revoked between sessions.
            Log.e(TAG, "startForegroundService failed for action=$action", e)
        }
    }

    private fun hasMicPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "CallReceiver"

        // PHONE_STATE fires twice (once with EXTRA_INCOMING_NUMBER, once without); dedupe consecutive states.
        @Volatile private var lastState: String? = null
    }
}
