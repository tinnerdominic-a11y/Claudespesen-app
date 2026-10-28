package ch.expenses.app.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ch.expenses.app.ExpensesApp

@Composable
fun AppNavigation() {
    val nav = rememberNavController()
    val repo = (LocalContext.current.applicationContext as ExpensesApp).repository

    NavHost(navController = nav, startDestination = "list") {
        composable("list") {
            ReceiptListScreen(
                repository = repo,
                onScan = { nav.navigate("camera") },
                onOpen = { id -> nav.navigate("edit/$id") },
                onImported = { path -> nav.navigate("new?path=${Uri.encode(path)}") },
            )
        }
        composable("camera") {
            CameraScreen(
                repository = repo,
                onCaptured = { path ->
                    nav.navigate("new?path=${Uri.encode(path)}") { popUpTo("list") }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            "new?path={path}",
            arguments = listOf(navArgument("path") { type = NavType.StringType }),
        ) { entry ->
            EditReceiptScreen(
                repository = repo,
                receiptId = null,
                newPhotoPath = entry.arguments?.getString("path"),
                onDone = { nav.popBackStack("list", inclusive = false) },
            )
        }
        composable(
            "edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            EditReceiptScreen(
                repository = repo,
                receiptId = entry.arguments?.getLong("id"),
                newPhotoPath = null,
                onDone = { nav.popBackStack("list", inclusive = false) },
            )
        }
    }
}
