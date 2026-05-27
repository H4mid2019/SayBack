package com.example.callsummarizer

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.example.callsummarizer.databinding.OverlayLayoutBinding

class OverlayManager(
    private val context: Context,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var binding: OverlayLayoutBinding? = null

    fun showOverlay() =
        mainHandler.post {
            if (binding != null) return@post

            val b = OverlayLayoutBinding.inflate(LayoutInflater.from(context))
            binding = b

            val params =
                WindowManager
                    .LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT,
                    ).apply {
                        gravity = Gravity.TOP
                        y = 100
                    }

            try {
                windowManager.addView(b.root, params)
            } catch (e: WindowManager.BadTokenException) {
                // Overlay permission revoked between the manifest grant and now.
                Log.e("OverlayManager", "addView denied — overlay permission missing?", e)
                binding = null
            }
        }

    fun showStatus(text: String) =
        mainHandler.post {
            val b = binding ?: return@post
            b.heardRow.visibility = View.GONE
            b.meansRow.visibility = View.GONE
            b.divider.visibility = View.GONE
            b.replyText.text = text
        }

    fun showAnswer(
        heard: String,
        translation: String,
        reply: String,
    ) = mainHandler.post {
        val b = binding ?: return@post

        if (heard.isNotBlank()) {
            b.heardText.text = heard
            b.heardRow.visibility = View.VISIBLE
        } else {
            b.heardRow.visibility = View.GONE
        }

        if (translation.isNotBlank()) {
            b.meansText.text = translation
            b.meansRow.visibility = View.VISIBLE
        } else {
            b.meansRow.visibility = View.GONE
        }

        b.divider.visibility =
            if (b.heardRow.visibility == View.VISIBLE || b.meansRow.visibility == View.VISIBLE) {
                View.VISIBLE
            } else {
                View.GONE
            }

        b.replyText.text = reply.ifBlank { context.getString(R.string.overlay_waiting) }
    }

    fun hideOverlay() =
        mainHandler.post {
            binding?.let {
                runCatching { windowManager.removeView(it.root) }
                binding = null
            }
        }
}
