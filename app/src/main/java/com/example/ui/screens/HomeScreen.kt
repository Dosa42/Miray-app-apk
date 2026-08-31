package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.example.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController, viewModel: MainViewModel) {
    val isAbiMode by viewModel.isAbiMode.collectAsState()
    val isOAuthLoggedIn by viewModel.isOAuthLoggedIn.collectAsState()
    val selectedModel by viewModel.selectedOpenAIModel.collectAsState()
    val context = LocalContext.current
    var showModelMenu by remember { mutableStateOf(false) }
    
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
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text("Developer Actions (OAuth)", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        if (!isOAuthLoggedIn) {
                            Button(onClick = { viewModel.loginWithOpenAI(context) }) {
                                Text("Login with OpenAI")
                            }
                        } else {
                            Text("OpenAI Connected", color = MaterialTheme.colorScheme.onTertiaryContainer)
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Box {
                                OutlinedButton(onClick = { showModelMenu = true }) {
                                    Text("Model: $selectedModel")
                                }
                                DropdownMenu(expanded = showModelMenu, onDismissRequest = { showModelMenu = false }) {
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
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = { viewModel.logoutOpenAI() }) {
                                Text("Logout")
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
