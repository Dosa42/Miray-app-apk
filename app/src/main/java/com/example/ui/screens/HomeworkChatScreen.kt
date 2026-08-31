package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.api.Content
import com.example.api.GeminiClient
import com.example.api.GenerateContentRequest
import com.example.api.Part
import com.example.ui.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeworkChatScreen(navController: NavController, viewModel: MainViewModel, sessionId: Int) {
    val messages by viewModel.getHomeworkMessages(sessionId).collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    
    // Check if we need to send initial greeting
    LaunchedEffect(messages) {
        if (messages.isEmpty()) {
            val initialMsg = "Hi Mirai! I see you have a homework problem. What do you think is the first step?"
            viewModel.addHomeworkMessage(sessionId, "ai", initialMsg)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Homework Helper") }
            )
        },
        bottomBar = {
            BottomAppBar {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Your answer...") },
                        colors = TextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                    IconButton(
                        onClick = {
                            if (text.isNotBlank()) {
                                val userText = text
                                text = ""
                                viewModel.addHomeworkMessage(sessionId, "mirai", userText)
                                
                                // Call Gemini API
                                scope.launch {
                                    try {
                                        val history = messages.map {
                                            Content(role = if (it.sender == "ai") "model" else "user", parts = listOf(Part(text = it.text)))
                                        } + Content(role = "user", parts = listOf(Part(text = userText)))
                                        
                                        val req = GenerateContentRequest(
                                            contents = history,
                                            systemInstruction = Content(parts = listOf(Part(text = "You are a friendly hint-only homework tutor for an 8 year old girl named Mirai. Do NOT give her the final answer. Ask guiding questions, point at the relevant rule, and escalate hints gradually. Be encouraging and playful.")))
                                        )
                                        
                                        val resp = GeminiClient.service.generateProContent(
                                            com.example.BuildConfig.GEMINI_API_KEY, 
                                            req
                                        )
                                        val aiResponse = resp.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: "Hmm, let's think about this together."
                                        viewModel.addHomeworkMessage(sessionId, "ai", aiResponse)
                                    } catch (e: Exception) {
                                        viewModel.addHomeworkMessage(sessionId, "ai", "Oops, I'm having trouble thinking right now. Could you try again? (${e.message})")
                                    }
                                }
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            reverseLayout = true
        ) {
            items(messages.reversed()) { msg ->
                val isMe = msg.sender == "mirai"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        color = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.widthIn(max = 280.dp)
                    ) {
                        Text(
                            text = msg.text,
                            color = if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}
