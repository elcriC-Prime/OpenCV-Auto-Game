package com.battlecats.autobot

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.battlecats.autobot.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var config: BotConfig

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshStatus()
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val svcIntent = Intent(this, BotForegroundService::class.java).apply {
                    action = BotForegroundService.ACTION_START
                    putExtra(BotForegroundService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(BotForegroundService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, svcIntent)
                Toast.makeText(this, "懸浮控制列已啟動，請切換到遊戲畫面", Toast.LENGTH_SHORT).show()
                refreshStatus()
            } else {
                Toast.makeText(this, "需要允許螢幕錄製才能辨識畫面", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        config = BotConfig(this)
        loadConfigIntoInputs()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        binding.btnOverlayPerm.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        binding.btnAccessibilityPerm.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnSaveConfig.setOnClickListener {
            saveInputsToConfig()
            Toast.makeText(this, "設定已儲存", Toast.LENGTH_SHORT).show()
        }

        binding.btnStartOverlay.setOnClickListener {
            saveInputsToConfig()
            if (BotForegroundService.isServiceRunning) {
                stopService(Intent(this, BotForegroundService::class.java))
                binding.root.postDelayed({ refreshStatus() }, 200)
                return@setOnClickListener
            }

            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "請先授權「顯示在其他應用程式上層」", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!BotAccessibilityService.isReady()) {
                Toast.makeText(this, "請先到無障礙設定開啟「貓咪大戰爭自動點擊服務」", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun loadConfigIntoInputs() {
        binding.etBattleInterval.setText(config.battleIntervalMs.toString())
        binding.etRewardWait.setText(config.rewardWaitMs.toString())
        binding.etThreshold.setText(config.matchThreshold.toString())
    }

    private fun saveInputsToConfig() {
        binding.etBattleInterval.text?.toString()?.toLongOrNull()?.let {
            config.battleIntervalMs = it
        }
        binding.etRewardWait.text?.toString()?.toLongOrNull()?.let {
            config.rewardWaitMs = it
        }
        binding.etThreshold.text?.toString()?.toFloatOrNull()?.let {
            config.matchThreshold = it
        }
        loadConfigIntoInputs()
    }

    private fun refreshStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val accOk = BotAccessibilityService.isReady()
        val running = BotForegroundService.isServiceRunning

        binding.tvPermStatus.text = buildString {
            append("• 懸浮窗權限：${if (overlayOk) "已授權 ✅" else "未授權 ❌"}\n")
            append("• 無障礙服務：${if (accOk) "已開啟 ✅" else "未開啟 ❌"}\n")
            append("• 懸浮控制列：${if (running) "運作中 ✅" else "未啟動"}")
        }
        binding.btnStartOverlay.text = if (running) "停止懸浮控制面板" else "啟動懸浮控制面板"
    }
}
