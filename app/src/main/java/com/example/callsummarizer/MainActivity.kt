package com.example.callsummarizer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.callsummarizer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: AppSettings

    private val requestPermissions =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { /* result intentionally ignored; user can retry from settings */ }

    private val requestOverlay =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { /* permission is read live via Settings.canDrawOverlays */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.topAppBar)

        settings = AppSettings(this)

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        binding.contextInput.setText(prefs.getString("call_context", ""))

        binding.saveContextButton.setOnClickListener {
            prefs.edit {
                putString(
                    "call_context",
                    binding.contextInput.text
                        ?.toString()
                        .orEmpty(),
                )
            }
            Toast.makeText(this, R.string.context_saved, Toast.LENGTH_SHORT).show()
        }

        binding.testButton.setOnClickListener { toggleTestSession() }

        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshTestButton()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    private fun refreshTestButton() {
        binding.testButton.setText(
            if (CallService.isRunning) R.string.test_stop else R.string.test_start,
        )
        binding.testButton.isEnabled = CallService.isRunning || settings.hasKeys()
    }

    private fun toggleTestSession() {
        if (CallService.isRunning) {
            sendServiceAction(CallService.ACTION_STOP)
        } else {
            if (!settings.hasKeys()) {
                Toast.makeText(this, R.string.keys_missing_toast, Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, SettingsActivity::class.java))
                return
            }
            if (!hasRecordAudio()) {
                requestNeededPermissions()
                Toast.makeText(this, "Grant microphone permission first", Toast.LENGTH_SHORT).show()
                return
            }
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
                Toast.makeText(this, "Grant overlay permission first", Toast.LENGTH_SHORT).show()
                return
            }
            sendServiceAction(CallService.ACTION_START)
        }
        binding.testButton.postDelayed({ refreshTestButton() }, 300)
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, CallService::class.java).setAction(action)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun hasRecordAudio(): Boolean = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestNeededPermissions() {
        val needed =
            buildList {
                add(Manifest.permission.RECORD_AUDIO)
                add(Manifest.permission.READ_PHONE_STATE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isNotEmpty()) {
            requestPermissions.launch(needed.toTypedArray())
        }

        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
        }
    }

    private fun requestOverlayPermission() {
        requestOverlay.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }
}
