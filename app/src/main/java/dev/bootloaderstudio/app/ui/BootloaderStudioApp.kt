package dev.bootloaderstudio.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.res.stringResource
import dev.bootloaderstudio.app.R
import dev.bootloaderstudio.app.BootloaderStudioApplication
import dev.bootloaderstudio.app.fastboot.UsbFastbootManager
import dev.bootloaderstudio.app.ui.account.AccountScreen
import dev.bootloaderstudio.app.ui.components.UsbHostDialog
import dev.bootloaderstudio.app.ui.device.DeviceScreen
import dev.bootloaderstudio.app.ui.guide.GuideScreen
import dev.bootloaderstudio.app.ui.home.HomeScreen
import dev.bootloaderstudio.app.ui.login.LoginScreen
import dev.bootloaderstudio.app.ui.login.XIAOMI_END_PATTERN
import dev.bootloaderstudio.app.ui.login.XIAOMI_LOGIN_URL
import dev.bootloaderstudio.app.ui.login.XIAOMI_MOBILE_UA
import dev.bootloaderstudio.app.ui.preflight.PreflightScreen
import dev.bootloaderstudio.app.ui.result.ResultScreen
import dev.bootloaderstudio.app.ui.settings.SettingsScreen
import dev.bootloaderstudio.app.ui.unlock.UnlockScreen
import dev.bootloaderstudio.app.ui.webview.AppWebViewScreen
import dev.bootloaderstudio.app.xiaomi.XiaomiSession
import dev.bootloaderstudio.app.ubl.UblEvent
import dev.bootloaderstudio.app.ubl.UblViewModel

private object Routes {
    const val HOME = "home"
    const val GUIDE = "guide"
    const val LOGIN = "login"
    const val WEBVIEW = "webview"
    const val ACCOUNT = "account"
    const val DEVICE = "device"
    const val PREFLIGHT = "preflight"
    const val UNLOCK = "unlock"
    const val RESULT = "result"
    const val SETTINGS = "settings"
}

@Composable
fun BootloaderStudioApp(
    navController: NavHostController = rememberNavController()
) {
    val application = LocalContext.current.applicationContext as BootloaderStudioApplication
    val viewModel: UblViewModel = viewModel(factory = UblViewModel.Factory(application.container))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                UblEvent.SessionExpired -> {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.HOME) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            }
        }
    }

    val usbManager = remember { UsbFastbootManager(application) }
    val hasUsbHost = remember { usbManager.hasUsbHost() }

    var showUsbHostDialog by remember { mutableStateOf(false) }

    fun startUnlock() {
        if (!hasUsbHost) {
            showUsbHostDialog = true
            return
        }
        if (uiState.session == null) {
            navController.navigate(Routes.LOGIN) { launchSingleTop = true }
        } else {
            navController.navigate(Routes.ACCOUNT) { launchSingleTop = true }
        }
    }

    fun goHome() {
        viewModel.reset()
        navController.navigate(Routes.HOME) {
            popUpTo(Routes.HOME) { inclusive = true }
            launchSingleTop = true
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.HOME
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                hasUsbHost = hasUsbHost,
                session = uiState.session,
                onStartUnlock = { startUnlock() },
                onGuide = { navController.navigate(Routes.GUIDE) { launchSingleTop = true } },
                onSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } }
            )
        }

        composable(Routes.GUIDE) {
            GuideScreen(
                onBack = { navController.popBackStack() },
                onContinue = {
                    if (uiState.session == null) {
                        navController.navigate(Routes.LOGIN) { launchSingleTop = true }
                    } else {
                        navController.navigate(Routes.ACCOUNT) { launchSingleTop = true }
                    }
                }
            )
        }

        composable(Routes.LOGIN) {
            LoginScreen(
                uiState = uiState,
                onStartWebLogin = {
                    navController.navigate(Routes.WEBVIEW) { launchSingleTop = true }
                },
                onLogout = { viewModel.logout() },
                onContinue = {
                    navController.navigate(Routes.ACCOUNT) { launchSingleTop = true }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.WEBVIEW) {
            AppWebViewScreen(
                initialUrl = XIAOMI_LOGIN_URL,
                title = stringResource(R.string.login_title),
                subtitle = "account.xiaomi.com",
                userAgent = XIAOMI_MOBILE_UA,
                endPattern = XIAOMI_END_PATTERN,
                onBack = { navController.popBackStack() },
                onCookiesExtracted = { cookies ->
                    val passToken = cookies["passToken"]
                    val userId = cookies["userId"]
                    val deviceId = cookies["deviceId"]
                    if (!passToken.isNullOrBlank() && !userId.isNullOrBlank() && !deviceId.isNullOrBlank()) {
                        val session = XiaomiSession(
                            passToken = passToken,
                            deviceId = deviceId,
                            userId = userId
                        )
                        viewModel.onLoginSuccess(session)
                        true
                    } else {
                        false
                    }
                },
                onSuccess = {
                    navController.navigate(Routes.ACCOUNT) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.ACCOUNT) {
            AccountScreen(
                uiState = uiState,
                onLoadAccount = { viewModel.loadAccount() },
                onBack = { navController.popBackStack() },
                onContinue = {
                    navController.navigate(Routes.DEVICE) { launchSingleTop = true }
                },
                onLogout = {
                    viewModel.logout()
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.HOME) { inclusive = false }
                    }
                }
            )
        }

        composable(Routes.DEVICE) {
            DeviceScreen(
                uiState = uiState,
                onStartDetection = { viewModel.startDeviceDetection() },
                onRefresh = { viewModel.startDeviceDetection(force = true) },
                onStopDetection = { viewModel.stopDeviceDetection() },
                onDeviceAttached = { viewModel.onDeviceAttached() },
                onDeviceDetached = { viewModel.onDeviceDetached() },
                onBack = { navController.popBackStack() },
                onContinue = {
                    navController.navigate(Routes.PREFLIGHT) { launchSingleTop = true }
                }
            )
        }

        composable(Routes.PREFLIGHT) {
            PreflightScreen(
                uiState = uiState,
                onPrepare = { viewModel.prepareUnlock() },
                onBack = { navController.popBackStack() },
                onConfirmUnlock = { viewModel.confirmUnlock() },
                onStartUnlock = {
                    navController.navigate(Routes.UNLOCK) { launchSingleTop = true }
                }
            )
        }

        composable(Routes.UNLOCK) {
            UnlockScreen(
                uiState = uiState,
                onFinished = {
                    navController.navigate(Routes.RESULT) {
                        popUpTo(Routes.UNLOCK) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.RESULT) {
            ResultScreen(
                uiState = uiState,
                onHome = { goHome() },
                onGuide = {
                    navController.navigate(Routes.GUIDE) { launchSingleTop = true }
                }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                uiState = uiState,
                onBack = { navController.popBackStack() },
                onLogout = { viewModel.logout() }
            )
        }
    }

    if (showUsbHostDialog) {
        UsbHostDialog(
            onDismiss = { showUsbHostDialog = false },
            onOpenGuide = {
                showUsbHostDialog = false
                navController.navigate(Routes.GUIDE) { launchSingleTop = true }
            }
        )
    }
}
