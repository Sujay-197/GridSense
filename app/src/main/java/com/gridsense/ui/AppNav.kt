package com.gridsense.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gridsense.net.requiredPermissions

object Routes {
    const val HOME = "home"
    const val PERMISSIONS = "permissions"
    const val SETUP = "setup?editId={editId}&copyOf={copyOf}"
    const val SURVEY = "survey/{id}"

    fun setup(editId: Long? = null) = if (editId == null) "setup" else "setup?editId=$editId"
    fun duplicate(id: Long) = "setup?copyOf=$id"
    fun survey(id: Long) = "survey/$id"
}

/** Leaves the permission screen: back where it was opened from, or on to Home at first launch. */
private fun NavHostController.leavePermissions() {
    if (previousBackStackEntry != null) {
        popBackStack()
    } else {
        navigate(Routes.HOME) { popUpTo(Routes.PERMISSIONS) { inclusive = true } }
    }
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    // Shared by every destination: created here, outside the NavHost, so it outlives them.
    val vm: SurveyViewModel = viewModel()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.refreshReadiness() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshReadiness()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val start = remember { if (vm.readiness.canLog) Routes.HOME else Routes.PERMISSIONS }

    NavHost(navController = nav, startDestination = start) {
        composable(Routes.PERMISSIONS) {
            val firstLaunch = nav.previousBackStackEntry == null
            // At first launch, move on by itself once everything is granted.
            LaunchedEffect(vm.readiness.canLog) {
                if (firstLaunch && vm.readiness.canLog) nav.leavePermissions()
            }
            PermissionScreen(
                readiness = vm.readiness,
                onGrant = { permissionLauncher.launch(requiredPermissions()) },
                onContinue = { nav.leavePermissions() },
                onBack = if (firstLaunch) null else ({ nav.popBackStack() })
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                vm = vm,
                onNew = { nav.navigate(Routes.setup()) },
                onOpen = { nav.navigate(Routes.survey(it)) },
                onDuplicate = { nav.navigate(Routes.duplicate(it)) },
                onPermissions = { nav.navigate(Routes.PERMISSIONS) }
            )
        }
        composable(
            Routes.SETUP,
            arguments = listOf(
                navArgument("editId") { type = NavType.LongType; defaultValue = -1L },
                navArgument("copyOf") { type = NavType.LongType; defaultValue = -1L }
            )
        ) {
            val setupVm: SetupViewModel = viewModel()
            SetupScreen(
                vm = setupVm,
                onClose = { nav.popBackStack() },
                onSaved = { id ->
                    if (setupVm.editing) {
                        nav.popBackStack()
                    } else {
                        nav.navigate(Routes.survey(id)) { popUpTo(Routes.HOME) }
                    }
                }
            )
        }
        composable(
            Routes.SURVEY,
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            val id = entry.arguments!!.getLong("id")
            SurveyScreen(
                vm = vm,
                roomId = id,
                onBack = { nav.popBackStack() },
                onEdit = { nav.navigate(Routes.setup(id)) },
                onDuplicate = { nav.navigate(Routes.duplicate(id)) },
                onFixPermissions = { nav.navigate(Routes.PERMISSIONS) }
            )
        }
    }
}
