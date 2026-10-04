package com.accbook.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.ui.accounts.AccountsScreen
import com.accbook.app.ui.accounts.CategoriesScreen
import com.accbook.app.ui.imports.ImportScreen
import com.accbook.app.ui.settings.SettingsScreen
import com.accbook.app.ui.stats.StatsScreen
import com.accbook.app.ui.transactions.EditTransactionScreen
import com.accbook.app.ui.transactions.TransactionListScreen

@Composable
fun AccBookNav(vm: LedgerViewModel) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "main") {
        composable("main") {
            MainScaffold(
                vm = vm,
                onAddTransaction = { navController.navigate("edit/-1") },
                onEditTransaction = { id -> navController.navigate("edit/$id") },
                onOpenAccounts = { navController.navigate("accounts") },
                onOpenCategories = { navController.navigate("categories") },
                onImport = { navController.navigate("import") },
            )
        }
        composable("edit/{txId}") { entry ->
            val txId = entry.arguments?.getString("txId")?.toLongOrNull() ?: -1L
            EditTransactionScreen(vm = vm, txId = txId, onBack = { navController.popBackStack() })
        }
        composable("accounts") { AccountsScreen(vm = vm, onBack = { navController.popBackStack() }) }
        composable("categories") { CategoriesScreen(vm = vm, onBack = { navController.popBackStack() }) }
        composable("import") { ImportScreen(vm = vm, onBack = { navController.popBackStack() }) }
    }
}

@Composable
private fun MainScaffold(
    vm: LedgerViewModel,
    onAddTransaction: () -> Unit,
    onEditTransaction: (Long) -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenCategories: () -> Unit,
    onImport: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                val tabs = listOf(
                    Triple("流水", Icons.Filled.List, 0),
                    Triple("统计", Icons.Filled.DateRange, 1),
                    Triple("设置", Icons.Filled.Settings, 2),
                )
                tabs.forEach { (label, icon, index) ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == 0) {
                FloatingActionButton(onClick = onAddTransaction) {
                    Icon(Icons.Filled.Add, contentDescription = "记一笔")
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> TransactionListScreen(vm = vm, onEditTransaction = onEditTransaction)
                1 -> StatsScreen(vm = vm)
                2 -> SettingsScreen(
                    vm = vm,
                    onOpenAccounts = onOpenAccounts,
                    onOpenCategories = onOpenCategories,
                    onImport = onImport,
                )
            }
        }
    }
}
