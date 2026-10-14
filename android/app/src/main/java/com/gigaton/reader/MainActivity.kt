package com.gigaton.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gigaton.reader.ui.BrowseScreen
import com.gigaton.reader.ui.ConnectScreen
import com.gigaton.reader.ui.DownloadsScreen
import com.gigaton.reader.ui.ReaderScreen
import com.gigaton.reader.ui.AnnotationListScreen
import com.gigaton.reader.ui.SettingsScreen
import com.gigaton.reader.ui.theme.GigatonTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GigatonTheme {
                val container = (application as GigatonReaderApp).container
                AppNavHost(container)
            }
        }
    }
}

@Composable
private fun AppNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val start = if (container.settings.isConfigured) Routes.BROWSE else Routes.CONNECT

    NavHost(navController = navController, startDestination = start) {
        composable(Routes.CONNECT) {
            ConnectScreen(
                container = container,
                onConnected = {
                    navController.navigate(Routes.BROWSE) {
                        popUpTo(Routes.CONNECT) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.BROWSE) {
            BrowseScreen(
                container = container,
                onOpenPdf = { path, name ->
                    navController.navigate(Routes.reader(path, name))
                },
                onOpenAnnotations = { path, name ->
                    navController.navigate(Routes.annotations(path, name))
                },
                onSettings = {
                    navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                },
                onDownloads = {
                    navController.navigate(Routes.DOWNLOADS) { launchSingleTop = true }
                },
                onReconnect = {
                    navController.navigate(Routes.CONNECT) {
                        popUpTo(Routes.BROWSE) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                container = container,
                onEditConnection = {
                    navController.navigate(Routes.CONNECT) { launchSingleTop = true }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.DOWNLOADS) {
            DownloadsScreen(
                container = container,
                onOpen = { path, name -> navController.navigate(Routes.reader(path, name)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.READER,
            arguments = listOf(navArgument("target") { type = NavType.StringType }),
        ) { entry ->
            val (path, name) = Routes.splitTarget(entry.arguments?.getString("target").orEmpty())
            ReaderScreen(
                container = container,
                paperPath = path,
                paperName = name,
                onBack = { navController.popBackStack() },
                onAnnotations = { p, n ->
                    navController.navigate(Routes.annotations(p, n))
                },
            )
        }
        composable(
            route = Routes.ANNOTATIONS,
            arguments = listOf(navArgument("target") { type = NavType.StringType }),
        ) { entry ->
            val (path, name) = Routes.splitTarget(entry.arguments?.getString("target").orEmpty())
            AnnotationListScreen(
                container = container,
                paperPath = path,
                paperName = name,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

object Routes {
    const val CONNECT = "connect"
    const val BROWSE = "browse"
    const val SETTINGS = "settings"
    const val DOWNLOADS = "downloads"
    const val READER = "reader/{target}"
    const val ANNOTATIONS = "annotations/{target}"

    /**
     * Paper paths and names go through a single URL-safe Base64 argument.
     * `Uri.encode` leaves `/,&,?,=` raw which breaks Navigation's query-arg
     * matching; Base64-URL-safe contains none of those characters, so a
     * folder path can never fragment a route or be mis-decoded.
     */
    fun reader(path: String, name: String) =
        "reader/${target(path, name)}"

    fun annotations(path: String, name: String) =
        "annotations/${target(path, name)}"

    private fun target(path: String, name: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$path\u0000$name".toByteArray(Charsets.UTF_8))

    fun splitTarget(target: String): Pair<String, String> {
        val decoded = runCatching {
            String(java.util.Base64.getUrlDecoder().decode(target), Charsets.UTF_8)
        }.getOrDefault("")
        val sep = decoded.indexOf('\u0000')
        return if (sep >= 0) {
            decoded.substring(0, sep) to decoded.substring(sep + 1)
        } else {
            decoded to ""
        }
    }
}