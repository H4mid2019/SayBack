package com.example.callsummarizer

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.callsummarizer.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.topAppBar)
        binding.topAppBar.setNavigationOnClickListener { finish() }

        settings = AppSettings(this)

        binding.deepgramKeyInput.setText(settings.deepgramKey)
        binding.openrouterKeyInput.setText(settings.openrouterKey)
        binding.deepgramModelInput.setText(settings.deepgramModel)
        binding.openrouterModelInput.setText(settings.openrouterModel)
        binding.languageInput.setText(settings.sourceLanguage)

        val scripts = ReplyScript.values()
        binding.replyScriptInput.setSimpleItems(scripts.map { it.displayName }.toTypedArray())
        binding.replyScriptInput.setText(settings.replyScript.displayName, false)

        binding.saveSettingsButton.setOnClickListener {
            settings.deepgramKey =
                binding.deepgramKeyInput.text
                    ?.toString()
                    .orEmpty()
            settings.openrouterKey =
                binding.openrouterKeyInput.text
                    ?.toString()
                    .orEmpty()
            settings.deepgramModel =
                binding.deepgramModelInput.text
                    ?.toString()
                    .orEmpty()
            settings.openrouterModel =
                binding.openrouterModelInput.text
                    ?.toString()
                    .orEmpty()
            settings.sourceLanguage =
                binding.languageInput.text
                    ?.toString()
                    .orEmpty()
            settings.replyScript = scripts.firstOrNull {
                it.displayName == binding.replyScriptInput.text?.toString()
            } ?: settings.replyScript
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
