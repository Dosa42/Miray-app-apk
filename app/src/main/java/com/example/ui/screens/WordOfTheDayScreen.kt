package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.api.Content
import com.example.api.GeminiClient
import com.example.api.GenerateContentRequest
import com.example.api.Part
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordOfTheDayScreen(navController: NavController) {
    var wordData by remember { mutableStateOf("Fetching today's word...") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            val req = GenerateContentRequest(
                contents = listOf(Content(parts = listOf(Part(text = "Give me a playful word of the day in Dutch or French (useful for Belgium). Include the meaning, an example sentence, and a fun fact. Format nicely as a short paragraph.")))),
                systemInstruction = Content(parts = listOf(Part(text = "You are a fun language tutor.")))
            )
            val resp = GeminiClient.service.generateFlashContent(
                com.example.BuildConfig.GEMINI_API_KEY, req
            )
            wordData = resp.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: "Oops, couldn't fetch a word!"
        } catch (e: Exception) {
            wordData = "Error: ${e.message}"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Word of the Day") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = wordData,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
