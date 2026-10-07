package com.accbook.app.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.ui.accounts.AccountsScreen
import com.accbook.app.ui.accounts.CategoriesScreen
import com.accbook.app.ui.common.QuickAddPill
import com.accbook.app.ui.debug.CaptureLogScreen
import com.accbook.app.ui.home.HomeScreen
import com.accbook.app.ui.imports.ImportScreen
import com.accbook.app.ui.keepalive.KeepAliveScreen
import com.accbook.app.ui.pending.PendingScreen
import com.accbook.app.ui.profile.ProfileScreen
import com.accbook.app.ui.refund.RefundLinkScreen
import com.accbook.app.ui.stats.StatsScreen
import com.accbook.app.ui.theme.accBook
import com.accbook.app.ui.transactions.EditTransactionScreen
import com.accbook.app.ui.transactions.QuickAddScreen
import com.accbook.app.ui.transactions.TransactionListScreen

@Composable
fun AccBookNav(vm: LedgerViewModel, navEvent: Pair<Long, String?> = 0L to null) {
    val navController = rememberNavController()
    val context = LocalContext.current
    // 悬浮卡/系统通知点击跳转（MainActivity 传入 route 事件；路由名是捕获层的对外契约，勿改）
    LaunchedEffect(navEvent.first) {
        when (navEvent.second) {
            com.accbook.app.capture.CaptureCenter.ROUTE_PENDING -> navController.navigate("pending")
            com.accbook.app.capture.CaptureCenter.ROUTE_REFUND_LINK -> navController.navigate("refundLink")
        }
    }
    NavHost(navController = navController, startDestination = "main") {
        composable("main") {
            MainScaffold(
                vm = vm,
                onQuickAdd = { navController.navigate("quickAdd") },
                onEditTransaction = { id -> navController.navigate("edit/$id") },
                onOpenAccounts = { navController.navigate("accounts") },
                onOpenCategories = { navController.navigate("categories") },
                onImport = { navController.navigate("import") },
                onOpenPending = { navController.navigate("pending") },
                onOpenCaptureLog = { navController.navigate("captureLog") },
                onOpenNotificationAccess = {
                    runCatching {
                        context.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                },
                onOpenAccessibilitySettings = {
                    runCatching {
                        context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                },
                onOpenOverlayPermission = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                },
                onOpenKeepAlive = { navController.navigate("keepAlive") },
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
        composable("keepAlive") {
            KeepAliveScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenNotificationAccess = {
                    runCatching {
                        context.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                },
                onOpenAccessibilitySettings = {
                    runCatching {
                        context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                },
            )
        }
    }
}

@Composable
private fun MainScaffold(
    vm: LedgerViewModel,
    onQuickAdd: () -> Unit,
    onEditTransaction: (Long) -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenCategories: () -> Unit,
    onImport: () -> Unit,
    onOpenPending: () -> Unit,
    onOpenCaptureLog: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlayPermission: () -> Unit,
    onOpenKeepAlive: () -> Unit,
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
                    onOpenAccounts = onOpenAccounts,
                    onOpenCategories = onOpenCategories,
                    onImport = onImport,
                    onOpenPending = onOpenPending,
                    onOpenCaptureLog = onOpenCaptureLog,
                    onOpenNotificationAccess = onOpenNotificationAccess,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onOpenOverlayPermission = onOpenOverlayPermission,
                    onOpenKeepAlive = onOpenKeepAlive,
                )
            }
        }
    }
}
