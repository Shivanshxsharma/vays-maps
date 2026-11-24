package com.shivansh.raasta

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.shivansh.raasta.ui.RaastaApp

class MainActivity : ComponentActivity() {
    private val vm: AppModel by viewModels()
    private var afterPermission: (() -> Unit)? = null

    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            vm.startLive()
            afterPermission?.invoke()
        } else {
            vm.startLive()
            vm.error = if (granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
                "Only approximate location is allowed. Turn on “Precise” in app settings to be guided turn by turn."
            else "Location is off for Vays Maps. Allow it in app settings to see where you are."
        }
        afterPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always-dark UI: light status/navigation bar icons regardless of the phone's theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent { RaastaApp(vm, ::withLocation) }
    }

    override fun onStart() { super.onStart(); vm.startLive() }
    override fun onStop() { super.onStop(); vm.stopLive() }

    private fun withLocation(action: () -> Unit) {
        // People who allowed location before 0.6 never saw the notification question: ask it once.
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        val askNotify = android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked_notify", false) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        prefs.edit().putBoolean("asked_notify", true).apply()
        if (!askNotify && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            afterPermission = action
            permission.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) +
                    // Asked together so the ride's notification can show the next turn on the lock screen.
                    (if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()),
            )
        }
    }
}
