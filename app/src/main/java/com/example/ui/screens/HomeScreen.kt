package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.oauth.OpenAIConnectionState
import com.example.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController, viewModel: MainViewModel) {
    val isAbiMode by viewModel.isAbiMode.collectAsStateWithLifecycle()
    val connectionState by viewModel.openAIConnectionState.collectAsStateWithLifecycle()
    val selectedModel by viewModel.selectedOpenAIModel.collectAsStateWithLifecycle()
    val responseText by viewModel.openAIResponseText.collectAsStateWithLifecycle()
    val isRequestRunning by viewModel.isOpenAIRequestRunning.collectAsStateWithLifecycle()
    val openAIErrorMessage by viewModel.openAIErrorMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showModelMenu by remember { mutableStateOf(false) }
    var openAIPrompt by rememberSaveable { mutableStateOf("") }
    
    var tapCount by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = if (isAbiMode) "Abi's Dashboard" else "Mirai's Space",
                        modifier = Modifier.clickable { 
                            tapCount++
                            if (tapCount >= 5) {
                                viewModel.toggleMode()
                                tapCount = 0
                            }
                        }
                    ) 
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HomeCard(
                title = if (isAbiMode) "Chat with Mirai" else "Chat with Abi",
                icon = Icons.Default.Chat,
                onClick = { navController.navigate("sibling_chat") }
            )

            if (isAbiMode) {
                HomeCard(
                    title = "Mirai's Homework Sessions",
                    icon = Icons.Default.List,
                    onClick = { navController.navigate("homework_list") }
                )
                
                // OAuth Developer Settings for Abi
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Developer Actions (OAuth)", style = MaterialTheme.typography.titleMedium)

                        when (val state = connectionState) {
                            is OpenAIConnectionState.LoggedOut -> {
                                Text("OpenAI is not connected.")
                                Button(onClick = { viewModel.loginWithOpenAI(context) }) {
                                    Text("Login with OpenAI")
                                }
                            }

                            is OpenAIConnectionState.Authorizing -> {
                                OpenAIProgressStatus("Waiting for OpenAI authorization…")
                            }

                            is OpenAIConnectionState.Refreshing -> {
                                OpenAIProgressStatus("Checking and refreshing the saved OpenAI session…")
                            }

                            is OpenAIConnectionState.Error -> {
                                Text(
                                    text = state.message,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { viewModel.loginWithOpenAI(context) }) {
                                        Text("Login with OpenAI")
                                    }
                                    TextButton(onClick = { viewModel.logoutOpenAI() }) {
                                        Text("Clear session")
                                    }
                                }
                            }

                            is OpenAIConnectionState.Connected -> {
                                Text(
                                    text = "Connected account: ${state.accountId}",
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    style = MaterialTheme.typography.bodyMedium
                                )

                                Box {
                                    OutlinedButton(
                                        onClick = { showModelMenu = true },
                                        enabled = !isRequestRunning
                                    ) {
                                        Text("Model: $selectedModel")
                                    }
                                    DropdownMenu(
                                        expanded = showModelMenu,
                                        onDismissRequest = { showModelMenu = false }
                                    ) {
                                        viewModel.openAIModels.forEach { model ->
                                            DropdownMenuItem(
                                                text = { Text(model) },
                                                onClick = {
                                                    viewModel.selectModel(model)
                                                    showModelMenu = false
                                                }
                                            )
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value = openAIPrompt,
                                    onValueChange = { openAIPrompt = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Prompt") },
                                    placeholder = { Text("Ask Codex…") },
                                    minLines = 3,
                                    maxLines = 8
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { viewModel.sendOpenAIResponse(openAIPrompt) },
                                        enabled = openAIPrompt.isNotBlank() && !isRequestRunning
                                    ) {
                                        Text(if (isRequestRunning) "Sending…" else "Send")
                                    }
                                    OutlinedButton(onClick = { viewModel.logoutOpenAI() }) {
                                        Text("Logout")
                                    }
                                }

                                if (isRequestRunning) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    Text(
                                        text = "Streaming response…",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }

                        openAIErrorMessage?.let { message ->
                            Text(
                                text = message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        if (responseText.isNotEmpty() || isRequestRunning) {
                            Text("Response", style = MaterialTheme.typography.titleSmall)
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                            ) {
                                SelectionContainer {
                                    Text(
                                        text = responseText.ifEmpty { "Waiting for response…" },
                                        modifier = Modifier.padding(12.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                HomeCard(
                    title = "Homework Helper",
                    icon = Icons.Default.CameraAlt,
                    onClick = { navController.navigate("homework_camera") }
                )
                HomeCard(
                    title = "Word of the Day",
                    icon = Icons.Default.Lightbulb,
                    onClick = { navController.navigate("word_of_the_day") }
                )
                HomeCard(
                    title = "Voice Postcard",
                    icon = Icons.Default.Mail,
                    onClick = { /* TODO */ }
                )
            }
        }
    }
}

@Composable
private fun OpenAIProgressStatus(message: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun HomeCard(title: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon, 
                contentDescription = title,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
