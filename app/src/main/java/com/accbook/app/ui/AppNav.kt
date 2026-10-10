package com.accbook.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.accbook.app.capture.CaptureCenter
import com.accbook.app.capture.CapturePermission
import com.accbook.app.capture.CapturePermissions
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.ui.accounts.AccountsScreen
import com.accbook.app.ui.accounts.CategoriesScreen
import com.accbook.app.ui.common.PermissionReminderDialog
import com.accbook.app.ui.common.QuickAddPill
import com.accbook.app.ui.debug.CaptureLogScreen
import com.accbook.app.ui.home.HomeScreen
import com.accbook.app.ui.imports.ImportScreen
import com.accbook.app.ui.keepalive.KeepAliveScreen
import com.accbook.app.ui.pending.PendingScreen
import com.accbook.app.ui.profile.ProfileScreen
import com.accbook.app.ui.refund.RefundLinkScreen
import com.accbook.app.ui.settings.SettingsAboutScreen
import com.accbook.app.ui.settings.SettingsAccountScreen
import com.accbook.app.ui.settings.SettingsDeveloperScreen
import com.accbook.app.ui.settings.SettingsImportScreen
import com.accbook.app.ui.settings.SettingsPermissionScreen
import com.accbook.app.ui.settings.SettingsScreen
import com.accbook.app.ui.stats.StatsScreen
import com.accbook.app.ui.theme.accBook
import com.accbook.app.ui.transactions.EditTransactionScreen
import com.accbook.app.ui.transactions.QuickAddScreen
import com.accbook.app.ui.transactions.TransactionListScreen

@Composable
fun AccBookNav(
    vm: LedgerViewModel,
    navEvent: Pair<Long, String?> = 0L to null,
    /** 本次冷启动是否做权限自检（MainActivity 传入；每次启动只提醒一次） */
    promptPermissions: Boolean = false,
) {
    val navController = rememberNavController()
    val context = LocalContext.current

    // 打开 App 时权限自检：只列"功能开着但没授权"的项；从捕获通知直接进入时不打断处理动线
    var permissionMissing by remember {
        mutableStateOf(
            if (promptPermissions && navEvent.second == null) CapturePermissions.missing(context) else emptyList(),
        )
    }
    // 去系统设置授权后返回：重查一次，补齐即自动收起
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && permissionMissing.isNotEmpty()) {
                permissionMissing = CapturePermissions.missing(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 系统设置页直达（权限页 / 保活页共用）
    val openNotificationAccess: () -> Unit = {
        runCatching {
            context.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        Unit
    }
    val openAccessibilitySettings: () -> Unit = {
        runCatching {
            context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        Unit
    }
    val openOverlayPermission: () -> Unit = {
        runCatching {
            context.startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        }
        Unit
    }

    // 悬浮卡/系统通知点击跳转（MainActivity 传入 route 事件；既有路由名是捕获层的对外契约，勿改）
    LaunchedEffect(navEvent.first) {
        val route = navEvent.second
        when {
            route == null -> Unit
            route == CaptureCenter.ROUTE_PENDING -> navController.navigate("pending")
            route == CaptureCenter.ROUTE_REFUND_LINK -> navController.navigate("refundLink")
            // 「已自动入账」横幅：直达该笔记录的编辑页（追加路由：edit/{txId}）
            route.startsWith(CaptureCenter.ROUTE_EDIT_PREFIX) -> navController.navigate(route)
        }
    }
    // 转场：默认的 700ms 淡入淡出太慢，改成 180ms 淡入 + 轻微横向位移（层级更清楚、手感利落）
    NavHost(
        navController = navController,
        startDestination = "main",
        enterTransition = {
            fadeIn(animationSpec = tween(180)) +
                slideInHorizontally(animationSpec = tween(180), initialOffsetX = { it / 14 })
        },
        exitTransition = { fadeOut(animationSpec = tween(120)) },
        popEnterTransition = { fadeIn(animationSpec = tween(160)) },
        popExitTransition = {
            fadeOut(animationSpec = tween(120)) +
                slideOutHorizontally(animationSpec = tween(160), targetOffsetX = { it / 14 })
        },
    ) {
        composable("main") {
            MainScaffold(
                vm = vm,
                onQuickAdd = { navController.navigate("quickAdd") },
                onEditTransaction = { id -> navController.navigate("edit/$id") },
                onOpenPending = { navController.navigate("pending") },
                onOpenSettings = { navController.navigate("settings") },
                onOpenCategories = { navController.navigate("categories") },
            )
        }
        composable("quickAdd") { QuickAddScreen(vm = vm, onBack = { navController.popBackStack() }) }
        composable("edit/{txId}") { entry ->
            val txId = entry.arguments?.getString("txId")?.toLongOrNull() ?: -1L
            EditTransactionScreen(vm = vm, txId = txId, onBack = { navController.popBackStack() })
        }
        composable("accounts") { AccountsScreen(vm = vm, onBack = { navController.popBackStack() }) }
        composable("categories") { CategoriesScreen(vm = vm, onBack = { navController.popBackStack() }) }
        composable("import") { ImportScreen(vm = vm, onBack = { navController.popBackStack() }) }
        composable("pending") { PendingScreen(vm = vm, onBack = { navController.popBackStack() }) }
        composable("refundLink") { RefundLinkScreen(vm = vm, onBack = { navController.popBackStack() }) }
        composable("captureLog") { CaptureLogScreen(vm = vm, onBack = { navController.popBackStack() }) }

        // ---- 设置体系：设置 →（账户 / 账单导入 / 权限 / 开发者功能 / 关于）----
        composable("settings") {
            SettingsScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenAccount = { navController.navigate("settings/account") },
                onOpenImport = { navController.navigate("settings/import") },
                onOpenPermissions = { navController.navigate("settings/permissions") },
                onOpenDeveloper = { navController.navigate("settings/developer") },
                onOpenAbout = { navController.navigate("settings/about") },
            )
        }
        composable("settings/account") {
            SettingsAccountScreen(
                onBack = { navController.popBackStack() },
                onOpenAccounts = { navController.navigate("accounts") },
                onOpenCategories = { navController.navigate("categories") },
            )
        }
        composable("settings/import") {
            SettingsImportScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onImport = { navController.navigate("import") },
            )
        }
        composable("settings/permissions") {
            SettingsPermissionScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenNotificationAccess = openNotificationAccess,
                onOpenAccessibilitySettings = openAccessibilitySettings,
                onOpenOverlayPermission = openOverlayPermission,
            )
        }
        composable("settings/developer") {
            SettingsDeveloperScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenPending = { navController.navigate("pending") },
                onOpenCaptureLog = { navController.navigate("captureLog") },
                onOpenKeepAlive = { navController.navigate("keepAlive") },
            )
        }
        composable("settings/about") {
            SettingsAboutScreen(onBack = { navController.popBackStack() })
        }

        composable("keepAlive") {
            KeepAliveScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenNotificationAccess = openNotificationAccess,
                onOpenAccessibilitySettings = openAccessibilitySettings,
            )
        }
    }

    // 冷启动权限提醒（与 NavHost 并列；调用方 Surface 内容即 Box，弹窗自带独立系统窗口）
    if (permissionMissing.isNotEmpty()) {
        PermissionReminderDialog(
            missing = permissionMissing,
            onOpenPermissions = {
                permissionMissing = emptyList()
                navController.navigate("settings/permissions")
            },
            onDismiss = { permissionMissing = emptyList() },
        )
    }
}

@Composable
private fun MainScaffold(
    vm: LedgerViewModel,
    onQuickAdd: () -> Unit,
    onEditTransaction: (Long) -> Unit,
    onOpenPending: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCategories: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 再次点击当前标签 → 计数 +1，由页面自己"回到顶部"（列表/滚动页通用）
    var reenterTick by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            // 白底导航 + 顶部发丝线（页面底色是浅蓝，白底栏与内容自然分层）
            Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                HorizontalDivider(color = MaterialTheme.accBook.hairline)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    val tabs = listOf(
                        Triple("首页", Icons.Filled.Home, 0),
                        Triple("账单", Icons.Filled.List, 1),
                        Triple("统计", Icons.Filled.DateRange, 2),
                        Triple("个人", Icons.Filled.Person, 3),
                    )
                    tabs.forEach { (label, icon, index) ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = {
                                if (tab == index) reenterTick++ else tab = index
                            },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            // 首页/账单页共用悬浮"记账"胶囊（填充主色 + 悬浮动效），随手补录都能一眼看到
            if (tab == 0 || tab == 1) {
                QuickAddPill(onClick = onQuickAdd)
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen(vm = vm, onOpenPending = onOpenPending)
                1 -> TransactionListScreen(
                    vm = vm,
                    onEditTransaction = onEditTransaction,
                    onOpenPending = onOpenPending,
                    scrollToTopTick = reenterTick,
                )
                2 -> StatsScreen(vm = vm, scrollToTopTick = reenterTick)
                3 -> ProfileScreen(
                    vm = vm,
                    onOpenSettings = onOpenSettings,
                    onOpenCategories = onOpenCategories,
                )
            }
        }
    }
}
