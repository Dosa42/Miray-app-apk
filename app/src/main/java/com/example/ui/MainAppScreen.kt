package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.MemoryScreen
import com.example.ui.screens.SettingsScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    viewModel: TermuxAgentViewModel,
    initialTab: Int = 0,
    onOpenMiray: () -> Unit
) {
    val messages by viewModel.chatMessages.collectAsStateWithLifecycle()
    val isThinking by viewModel.isThinking.collectAsStateWithLifecycle()
    val selectedModel by viewModel.selectedModel.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val isSelfImproving by viewModel.isSelfImproving.collectAsStateWithLifecycle()
    val diagnostics by viewModel.systemDiagnostics.collectAsStateWithLifecycle()
    val notification by viewModel.notificationMessage.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var currentTab by rememberSaveable(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
    val titles = listOf("Codex Chat", "Memory", "Settings")
    val icons = listOf(Icons.Default.AutoAwesome, Icons.Default.Psychology, Icons.Default.Settings)

    LaunchedEffect(notification) {
        notification?.let {
            snackbar.showSnackbar(it)
            viewModel.clearNotification()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Miray · ${titles[currentTab]}") },
                actions = { TextButton(onClick = onOpenMiray) { Text("Miray Home") } }
            )
        },
        bottomBar = {
            NavigationBar {
                titles.forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = currentTab == index,
                        onClick = { currentTab = index },
                        icon = { Icon(icons[index], contentDescription = title) },
                        label = { Text(title) }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (currentTab) {
                0 -> ChatScreen(viewModel, messages, isThinking, selectedModel, viewModel::setModel)
                1 -> MemoryScreen(viewModel, memories, isSelfImproving)
                2 -> SettingsScreen(viewModel, diagnostics)
            }
        }
    }
}
