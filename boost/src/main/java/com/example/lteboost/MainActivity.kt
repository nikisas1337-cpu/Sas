package com.example.lteboost

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var shizukuStatus: TextView
    private lateinit var grantButton: Button
    private lateinit var toggleButton: Button
    private lateinit var report: TextView
    private lateinit var signal: TextView
    private lateinit var signalInfo: SignalInfo

    private val main = Handler(Looper.getMainLooper())
    private var busy = false

    private val refresher = object : Runnable {
        override fun run() {
            signal.text = signalInfo.read()
            main.postDelayed(this, 2000)
        }
    }

    private val binderListener = Shizuku.OnBinderReceivedListener { main.post { updateUi() } }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { main.post { updateUi() } }
    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> main.post { updateUi() } }

    private val requestPhonePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        shizukuStatus = findViewById(R.id.shizukuStatus)
        grantButton = findViewById(R.id.grantButton)
        toggleButton = findViewById(R.id.toggleButton)
        report = findViewById(R.id.report)
        signal = findViewById(R.id.signal)
        signalInfo = SignalInfo(this)

        bindOption(findViewById(R.id.optWifi), BoostController.KEY_OPT_WIFI)
        bindOption(findViewById(R.id.optSaver), BoostController.KEY_OPT_SAVER)

        grantButton.setOnClickListener { onGrantClicked() }
        toggleButton.setOnClickListener { onToggleClicked() }
        findViewById<Button>(R.id.addTileButton).setOnClickListener { addTile() }

        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)

        requestPhonePermissions.launch(
            arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_FINE_LOCATION)
        )
    }

    override fun onResume() {
        super.onResume()
        updateUi()
        main.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(refresher)
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    private fun bindOption(box: CheckBox, key: String) {
        box.isChecked = BoostController.option(this, key)
        box.setOnCheckedChangeListener { _, checked -> BoostController.setOption(this, key, checked) }
    }

    private fun updateUi() {
        val status = Privileged.status()
        shizukuStatus.setText(
            when (status) {
                Privileged.Status.READY -> R.string.shizuku_ready
                Privileged.Status.NO_PERMISSION -> R.string.shizuku_no_permission
                Privileged.Status.TOO_OLD -> R.string.shizuku_old
                Privileged.Status.NOT_RUNNING -> R.string.shizuku_not_running
            }
        )
        grantButton.visibility = if (status == Privileged.Status.READY) View.GONE else View.VISIBLE
        grantButton.setText(
            if (status == Privileged.Status.NO_PERMISSION) R.string.btn_grant else R.string.btn_get_shizuku
        )
        toggleButton.isEnabled = status == Privileged.Status.READY && !busy
        toggleButton.setText(
            if (BoostController.isActive(this)) R.string.btn_disable else R.string.btn_enable
        )
    }

    private fun onGrantClicked() {
        when (Privileged.status()) {
            Privileged.Status.NO_PERMISSION -> Shizuku.requestPermission(REQUEST_SHIZUKU)
            Privileged.Status.READY -> updateUi()
            else -> openShizukuPage()
        }
    }

    private fun openShizukuPage() {
        val launch = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        val intent = launch ?: Intent(
            Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE")
        )
        runCatching { startActivity(intent) }
    }

    private fun onToggleClicked() {
        busy = true
        updateUi()
        Thread {
            val result = BoostController.toggle(applicationContext)
            main.post {
                busy = false
                report.text = result.toString()
                updateUi()
                requestTileRefresh()
            }
        }.start()
    }

    private fun requestTileRefresh() {
        android.service.quicksettings.TileService.requestListeningState(
            this, ComponentName(this, BoostTileService::class.java)
        )
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val sbm = getSystemService(StatusBarManager::class.java)
            sbm.requestAddTileService(
                ComponentName(this, BoostTileService::class.java),
                getString(R.string.tile_label),
                Icon.createWithResource(this, R.drawable.ic_tile),
                mainExecutor
            ) { result ->
                val added = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                    result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                Toast.makeText(
                    this, if (added) R.string.tile_added else R.string.tile_manual, Toast.LENGTH_LONG
                ).show()
            }
        } else {
            Toast.makeText(this, R.string.tile_manual, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REQUEST_SHIZUKU = 1
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}
