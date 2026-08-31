package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ui.MainViewModel
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.HomeworkCameraScreen
import com.example.ui.screens.HomeworkChatScreen
import com.example.ui.screens.HomeworkListScreen
import com.example.ui.screens.SiblingChatScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val viewModel: MainViewModel = viewModel()

                    NavHost(navController = navController, startDestination = "home") {
                        composable("home") {
                            HomeScreen(navController, viewModel)
                        }
                        composable("word_of_the_day") {
                            com.example.ui.screens.WordOfTheDayScreen(navController)
                        }
                        composable("sibling_chat") {
                            SiblingChatScreen(navController, viewModel)
                        }
                        composable("homework_camera") {
                            HomeworkCameraScreen(navController, viewModel)
                        }
                        composable(
                            "homework_chat/{sessionId}",
                            arguments = listOf(navArgument("sessionId") { type = NavType.IntType })
                        ) { backStackEntry ->
                            val sessionId = backStackEntry.arguments?.getInt("sessionId") ?: 0
                            HomeworkChatScreen(navController, viewModel, sessionId)
                        }
                        composable("homework_list") {
                            HomeworkListScreen(navController, viewModel)
                        }
                    }
                }
            }
        }
    }
}

