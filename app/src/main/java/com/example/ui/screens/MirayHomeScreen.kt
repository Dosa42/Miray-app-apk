package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.gemini.OpenAIOAuthManager
import com.example.model.AIProviderType
import com.example.ui.MainViewModel
import com.example.ui.TermuxAgentViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MirayHomeScreen(
    navController: NavController,
    viewModel: MainViewModel,
    agentViewModel: TermuxAgentViewModel,
    onOpenAgent: (Int) -> Unit
) {
    val isAbiMode by viewModel.isAbiMode.collectAsStateWithLifecycle()
    val providers by agentViewModel.providerConfigs.collectAsStateWithLifecycle()
    val codexConfig = providers[AIProviderType.OPENAI.id]
    val hasSavedLogin = codexConfig?.apiKey?.let(OpenAIOAuthManager::isLoggedIn) == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isAbiMode) "Abi's Dashboard" else "Mirai's Space") },
                actions = {
                    TextButton(onClick = viewModel::toggleMode) {
                        Text(if (isAbiMode) "Switch to Mirai" else "Switch to Abi")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { navController.navigate("sibling_chat") },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (isAbiMode) "Chat with Mirai" else "Chat with Abi") }
            Button(
                onClick = { navController.navigate("homework_camera") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Homework Helper") }
            OutlinedButton(
                onClick = { navController.navigate("homework_list") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Homework Sessions") }
            OutlinedButton(
                onClick = { navController.navigate("word_of_the_day") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Word of the Day") }
            Text(if (hasSavedLogin) "ChatGPT sign-in saved" else "ChatGPT sign-in required")
            Button(onClick = { onOpenAgent(0) }, modifier = Modifier.fillMaxWidth()) {
                Text("Codex Chat")
            }
            OutlinedButton(onClick = { onOpenAgent(2) }, modifier = Modifier.fillMaxWidth()) {
                Text("ChatGPT Login & Settings")
            }
        }
    }
}
