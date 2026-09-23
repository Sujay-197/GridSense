package com.gridsense

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gridsense.net.requiredPermissions
import com.gridsense.ui.GridScreen
import com.gridsense.ui.HomeScreen
import com.gridsense.ui.PermissionScreen
import com.gridsense.ui.Screen
import com.gridsense.ui.LayoutScreen
import com.gridsense.ui.SurveyViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
fun AppRoot(vm: SurveyViewModel = viewModel()) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.refreshReadiness() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.refreshReadiness()
                vm.refreshArStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val ready = vm.readiness
    if (!ready.canLog && !vm.rationaleDismissed) {
        PermissionScreen(
            readiness = vm.readiness,
            onGrant = { permissionLauncher.launch(requiredPermissions()) },
            onContinue = { vm.dismissRationale() }
        )
        return
    }

    when (val screen = vm.screen) {
        is Screen.Home -> HomeScreen(vm)
        is Screen.Layout -> LayoutScreen(vm)
        is Screen.Survey -> GridScreen(vm, screen.roomId)
    }
}
