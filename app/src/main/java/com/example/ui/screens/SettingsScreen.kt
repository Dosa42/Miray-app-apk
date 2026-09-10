package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.automation.SystemDiagnostics
import com.example.automation.TermuxBridge
import com.example.codex.MultiProviderAIService
import com.example.model.AIProviderConfig
import com.example.model.AIProviderType
import com.example.model.CodexRuntimeMode
import com.example.model.HealthTestResult
import com.example.speech.GroqSpeechService
import com.example.speech.SttLanguage
import com.example.ui.TermuxAgentViewModel
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.TerminalCyan
import com.example.ui.theme.TerminalGreen
import com.example.ui.theme.TerminalPurple
import com.example.ui.theme.TerminalRed
import com.example.ui.theme.TerminalYellow

@Composable
fun SettingsScreen(
    viewModel: TermuxAgentViewModel,
    systemDiagnostics: SystemDiagnostics?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val providerConfigs by viewModel.providerConfigs.collectAsStateWithLifecycle()
    val activeProvider by viewModel.activeProvider.collectAsStateWithLifecycle()
    val testingProviders by viewModel.testingProviders.collectAsStateWithLifecycle()
    val healthTestResults by viewModel.healthTestResults.collectAsStateWithLifecycle()
    val groqApiKeyConfigured by viewModel.groqApiKeyConfigured.collectAsStateWithLifecycle()
    val sttLanguage by viewModel.sttLanguage.collectAsStateWithLifecycle()
    val sttStatus by viewModel.sttStatus.collectAsStateWithLifecycle()
    val codexRuntimeMode by viewModel.codexRuntimeMode.collectAsStateWithLifecycle()
    val ultraWebSocketUrl by viewModel.ultraWebSocketUrl.collectAsStateWithLifecycle()

    var groqKeyEntry by remember { mutableStateOf("") }
    var groqKeyVisible by remember { mutableStateOf(false) }

    var selectedProviderTab by remember { mutableIntStateOf(0) }

    val providersList = listOf(AIProviderType.OPENAI)

    val currentEditingType = providersList.getOrElse(selectedProviderTab) { AIProviderType.OPENAI }
    val currentConfig = providerConfigs[currentEditingType.id]
        ?: MultiProviderAIService.getDefaultConfigs().first { it.providerId == currentEditingType.id }

    val termuxSetupCommand = """
mkdir -p ~/.termux && if grep -qE '^[[:space:]]*allow-external-apps[[:space:]]*=' ~/.termux/termux.properties 2>/dev/null; then sed -i -E 's/^[[:space:]]*allow-external-apps[[:space:]]*=.*/allow-external-apps = true/' ~/.termux/termux.properties; else echo 'allow-external-apps = true' >> ~/.termux/termux.properties; fi && termux-reload-settings
    """.trimIndent()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        // 1. Header & Active AI Engine Status Card
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = CardDefaults.outlinedCardBorder().copy(
                    width = 1.dp,
                    brush = androidx.compose.ui.graphics.SolidColor(TerminalGreen.copy(alpha = 0.5f))
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(TerminalGreen.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Public,
                                    contentDescription = null,
                                    tint = TerminalGreen,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Codex OAuth Agent",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Direct ChatGPT OAuth · native Responses tools",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Button(
                            onClick = { viewModel.testAllProvidersHealth() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TerminalCyan.copy(alpha = 0.15f),
                                contentColor = TerminalCyan
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Test Codex", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "ACTIVE AGENT ENGINE:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Active Provider Selector Pills
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        providersList.forEach { pType ->
                            val isActive = activeProvider == pType
                            val pColor = when (pType) {
                                AIProviderType.OPENAI -> TerminalCyan
                                AIProviderType.ANTHROPIC -> TerminalPurple
                            }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isActive) pColor.copy(alpha = 0.2f) else DarkSurfaceVariant,
                                border = if (isActive) androidx.compose.foundation.BorderStroke(1.dp, pColor) else androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { viewModel.setActiveProvider(pType) }
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isActive) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = pColor,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                        Text(
                                            text = when (pType) {
                                                AIProviderType.OPENAI -> "Codex"
                                                AIProviderType.ANTHROPIC -> "Anthropic"
                                            },
                                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                            fontSize = 12.sp,
                                            color = if (isActive) pColor else MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                    val conf = providerConfigs[pType.id]
                                    Text(
                                        text = conf?.model?.take(16) ?: "default",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Multi-Provider Gateway Configuration Card
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = CardDefaults.outlinedCardBorder().copy(width = 1.dp, brush = androidx.compose.ui.graphics.SolidColor(DarkBorder)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Fixed Codex Connection",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "OAuth-only connection to the Codex Responses backend. No API key or fallback provider.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Provider Tabs
                    TabRow(
                        selectedTabIndex = selectedProviderTab,
                        containerColor = DarkSurfaceVariant,
                        contentColor = TerminalGreen,
                        divider = {},
                        indicator = { tabPositions ->
                            TabRowDefaults.SecondaryIndicator(
                                Modifier.tabIndicatorOffset(tabPositions[selectedProviderTab]),
                                color = when (currentEditingType) {
                                    AIProviderType.OPENAI -> TerminalCyan
                                    AIProviderType.ANTHROPIC -> TerminalPurple
                                }
                            )
                        },
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    ) {
                        providersList.forEachIndexed { index, pType ->
                            val isSelected = selectedProviderTab == index
                            val pColor = when (pType) {
                                AIProviderType.OPENAI -> TerminalCyan
                                AIProviderType.ANTHROPIC -> TerminalPurple
                            }

                            Tab(
                                selected = isSelected,
                                onClick = { selectedProviderTab = index },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = pType.displayName.substringBefore(" /"),
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) pColor else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (activeProvider == pType) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(pColor)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Provider Form Editor
                    ProviderConfigForm(
                        config = currentConfig,
                        providerType = currentEditingType,
                        isActive = activeProvider == currentEditingType,
                        isTesting = testingProviders[currentEditingType.id] == true,
                        testResult = healthTestResults[currentEditingType.id],
                        onSave = { updated -> viewModel.saveProviderConfig(updated) },
                        onTest = { updated -> viewModel.testProviderHealth(updated) },
                        onMakeActive = { viewModel.setActiveProvider(currentEditingType) },
                        onOpenAILogin = { viewModel.loginOpenAI(context) }
                    )
                }
            }
        }

        // 3. Codex reasoning and real exec-server Ultra
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = CardDefaults.outlinedCardBorder().copy(
                    width = 1.dp,
                    brush = androidx.compose.ui.graphics.SolidColor(TerminalCyan.copy(alpha = 0.55f))
                ),
                modifier = Modifier.fillMaxWidth().testTag("codex_reasoning_card")
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Codex Reasoning Mode",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Fixed model: ${MultiProviderAIService.DEFAULT_OPENAI_MODEL}. High, Xhigh and Max use the direct Responses stream. Ultra requires a server-advertised real ultra effort; there is no fallback.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(CodexRuntimeMode.entries) { mode ->
                            FilterChip(
                                selected = codexRuntimeMode == mode,
                                onClick = { viewModel.setCodexRuntimeMode(mode) },
                                label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = TerminalCyan.copy(alpha = 0.2f),
                                    selectedLabelColor = TerminalCyan
                                ),
                                modifier = Modifier.testTag("reasoning_${mode.storedValue}")
                            )
                        }
                    }
                    OutlinedTextField(
                        value = ultraWebSocketUrl,
                        onValueChange = viewModel::setUltraWebSocketUrl,
                        label = { Text("Exec-server WebSocket") },
                        supportingText = { Text("Example: ws://127.0.0.1:33223") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("ultra_websocket_url"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TerminalCyan,
                            unfocusedBorderColor = DarkBorder,
                            focusedContainerColor = DarkSurfaceVariant,
                            unfocusedContainerColor = DarkSurfaceVariant
                        ),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            }
        }

        // 4. Groq Speech-to-Text Configuration
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = CardDefaults.outlinedCardBorder().copy(
                    width = 1.dp,
                    brush = androidx.compose.ui.graphics.SolidColor(TerminalCyan.copy(alpha = 0.55f))
                ),
                modifier = Modifier.fillMaxWidth().testTag("groq_stt_card")
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(TerminalCyan.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Key, contentDescription = null, tint = TerminalCyan, modifier = Modifier.size(19.dp))
                            }
                            Spacer(modifier = Modifier.width(9.dp))
                            Column {
                                Text(
                                    text = "Groq Speech-to-Text",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = GroqSpeechService.MODEL,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = TerminalCyan
                                )
                            }
                        }
                        Text(
                            text = if (groqApiKeyConfigured) "KEY CONFIGURED" else "KEY REQUIRED",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = if (groqApiKeyConfigured) TerminalGreen else TerminalYellow
                        )
                    }

                    Text(
                        text = "Whisper Large V3 only. No Turbo and no TTS. The key is encrypted locally with Android Keystore and is never placed in the APK or source code.",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = groqKeyEntry,
                        onValueChange = { groqKeyEntry = it.trim() },
                        placeholder = {
                            Text(
                                if (groqApiKeyConfigured) "Enter a new gsk_… key to replace the stored key" else "gsk_…",
                                fontSize = 11.sp
                            )
                        },
                        singleLine = true,
                        visualTransformation = if (groqKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { groqKeyVisible = !groqKeyVisible },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        if (groqKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle Groq key visibility",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        clipboard.getText()?.text?.trim()?.let { value ->
                                            if (value.isNotBlank()) groqKeyEntry = value
                                        }
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste Groq key", tint = TerminalCyan, modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("groq_api_key_field"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TerminalCyan,
                            unfocusedBorderColor = DarkBorder,
                            focusedContainerColor = DarkSurfaceVariant,
                            unfocusedContainerColor = DarkSurfaceVariant
                        ),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.saveGroqApiKey(groqKeyEntry)
                                groqKeyEntry = ""
                            },
                            enabled = groqKeyEntry.startsWith("gsk_") && groqKeyEntry.length > 8,
                            colors = ButtonDefaults.buttonColors(containerColor = TerminalCyan, contentColor = Color.Black),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(36.dp).testTag("save_groq_key_button")
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(if (groqApiKeyConfigured) "Replace Key" else "Save Key", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { viewModel.clearGroqApiKey() },
                            enabled = groqApiKeyConfigured,
                            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TerminalRed),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(36.dp).testTag("clear_groq_key_button")
                        ) {
                            Text("Remove Key", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Text(
                        text = "Allowed transcription languages",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(SttLanguage.entries) { language ->
                            FilterChip(
                                selected = sttLanguage == language,
                                onClick = { viewModel.setSttLanguage(language) },
                                label = { Text("${language.shortLabel} · ${language.label}", fontSize = 10.sp) },
                                leadingIcon = if (sttLanguage == language) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(13.dp)) }
                                } else null,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = TerminalCyan.copy(alpha = 0.2f),
                                    selectedLabelColor = TerminalCyan
                                )
                            )
                        }
                    }

                    Surface(
                        color = DarkSurfaceVariant,
                        shape = RoundedCornerShape(7.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "$sttStatus\nUse the microphone button in Agent Chat for a real Groq transcription test. The raw result is minimally cleaned with the selected EN/NL/TR prompt before it is sent to the agent.",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(9.dp)
                        )
                    }
                }
            }
        }

        // 4. Termux Bridge Setup Status Card
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = CardDefaults.outlinedCardBorder().copy(
                    width = 1.dp,
                    brush = androidx.compose.ui.graphics.SolidColor(
                        if (systemDiagnostics?.termuxInstalled == true) TerminalGreen.copy(alpha = 0.5f) else TerminalYellow.copy(alpha = 0.5f)
                    )
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (systemDiagnostics?.termuxInstalled == true) TerminalGreen.copy(alpha = 0.2f) else TerminalYellow.copy(alpha = 0.2f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Terminal,
                                    contentDescription = null,
                                    tint = if (systemDiagnostics?.termuxInstalled == true) TerminalGreen else TerminalYellow,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Termux Integration Status",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (systemDiagnostics?.termuxInstalled == true) "Termux app detected — command access not tested here" else "Termux not detected on device",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (systemDiagnostics?.termuxInstalled == true) TerminalGreen else TerminalYellow
                                )
                            }
                        }

                        if (systemDiagnostics?.termuxInstalled == true) {
                            Button(
                                onClick = { TermuxBridge.launchTermuxApp(context) },
                                colors = ButtonDefaults.buttonColors(containerColor = TerminalGreen, contentColor = Color.Black),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Open", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "To allow this AI Agent to execute commands in Termux, open Termux and run this one-line setup command:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Surface(
                        color = Color.Black,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = termuxSetupCommand,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = TerminalGreen,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { clipboard.setText(AnnotatedString(termuxSetupCommand)) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = TerminalGreen, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }

        // 5. Device & System Diagnostics Card
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = CardDefaults.outlinedCardBorder().copy(width = 1.dp, brush = androidx.compose.ui.graphics.SolidColor(DarkBorder)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Device & Storage Diagnostics",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        IconButton(
                            onClick = { viewModel.refreshSystemDiagnostics() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh Diag", tint = TerminalCyan, modifier = Modifier.size(16.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    systemDiagnostics?.let { diag ->
                        DiagnosticsRow("OS & Version", "${diag.androidVersion} (API ${diag.apiLevel})")
                        DiagnosticsRow("Device Model", diag.deviceModel)
                        DiagnosticsRow("CPU Architectures", diag.cpuArch)
                        DiagnosticsRow("RAM Status", "${diag.availRamMb} MB available / ${diag.totalRamMb} MB total")
                        DiagnosticsRow("Storage Space", "${diag.freeStorageGb} GB free / ${diag.totalStorageGb} GB total")
                        DiagnosticsRow("App Sandbox Path", diag.appFilesDir)
                    }
                }
            }
        }
    }
}

@Composable
fun ProviderConfigForm(
    config: AIProviderConfig,
    providerType: AIProviderType,
    isActive: Boolean,
    isTesting: Boolean,
    testResult: HealthTestResult?,
    onSave: (AIProviderConfig) -> Unit,
    onTest: (AIProviderConfig) -> Unit,
    onMakeActive: () -> Unit,
    onOpenAILogin: (() -> Unit)? = null
) {
    val clipboard = LocalClipboardManager.current

    var baseUrl by remember(config.providerId, config.baseUrl) { mutableStateOf(config.baseUrl) }
    var apiKey by remember(config.providerId, config.apiKey) { mutableStateOf(config.apiKey) }
    var model by remember(config.providerId, config.model) { mutableStateOf(config.model) }
    var passwordVisible by remember { mutableStateOf(false) }

    val providerColor = when (providerType) {
        AIProviderType.OPENAI -> TerminalCyan
        AIProviderType.ANTHROPIC -> TerminalPurple
    }

    val modelSuggestions = MultiProviderAIService.PRESET_OPENAI_MODELS

    val urlPresets = listOf("Codex" to MultiProviderAIService.DEFAULT_OPENAI_URL)

    fun buildCurrentConfig(): AIProviderConfig {
        if (providerType == AIProviderType.OPENAI) {
            return config.copy(
                baseUrl = MultiProviderAIService.DEFAULT_OPENAI_URL,
                model = MultiProviderAIService.DEFAULT_OPENAI_MODEL
            )
        }
        return config.copy(
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            model = model.trim()
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

        // Status banner if active
        if (isActive) {
            Surface(
                color = providerColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, providerColor.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = providerColor, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Current Active Agent Provider",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = providerColor
                    )
                }
            }
        }

        // 1. BASE URL Field
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Base URL / Gateway Endpoint",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "HTTP/HTTPS",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                enabled = providerType != AIProviderType.OPENAI,
                placeholder = { Text("https://...", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("base_url_field"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = providerColor,
                    unfocusedBorderColor = DarkBorder,
                    focusedContainerColor = DarkSurfaceVariant,
                    unfocusedContainerColor = DarkSurfaceVariant
                ),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Quick URL Presets
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(urlPresets) { (label, presetUrl) ->
                    Surface(
                        color = if (baseUrl == presetUrl) providerColor.copy(alpha = 0.2f) else DarkSurfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (baseUrl == presetUrl) providerColor else DarkBorder),
                        modifier = Modifier.clickable { baseUrl = presetUrl }
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = if (baseUrl == presetUrl) providerColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }

        // 2. Codex uses an OAuth session only; never expose the stored bearer/refresh tokens.
        if (providerType == AIProviderType.OPENAI) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ChatGPT OAuth Session",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (com.example.codex.OpenAIOAuthManager.isLoggedIn(apiKey)) "CONNECTED" else "LOGIN REQUIRED",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = if (com.example.codex.OpenAIOAuthManager.isLoggedIn(apiKey)) TerminalGreen else TerminalYellow
                    )
                }
                Text(
                    text = "Credentials are obtained through ChatGPT OAuth and kept private inside the app. No API key is accepted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
                if (onOpenAILogin != null) {
                    Button(
                        onClick = onOpenAILogin,
                        colors = ButtonDefaults.buttonColors(containerColor = TerminalCyan, contentColor = Color.Black),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(36.dp)
                    ) {
                        Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (com.example.codex.OpenAIOAuthManager.isLoggedIn(apiKey)) "Renew ChatGPT OAuth Login" else "Login with ChatGPT OAuth",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "API Key or Bearer Token",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = when (providerType) {
                        AIProviderType.OPENAI -> "Bearer sk-... / token"
                        AIProviderType.ANTHROPIC -> "x-api-key / Bearer token"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                placeholder = {
                    Text(
                        when (providerType) {
                            AIProviderType.OPENAI -> "sk-... or gateway token"
                            AIProviderType.ANTHROPIC -> "sk-ant-... or gateway token"
                        },
                        fontSize = 12.sp
                    )
                },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { passwordVisible = !passwordVisible },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle password visibility",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                val clip = clipboard.getText()?.text
                                if (!clip.isNullOrBlank()) {
                                    apiKey = clip.trim()
                                }
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = "Paste",
                                tint = providerColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("api_key_field"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = providerColor,
                    unfocusedBorderColor = DarkBorder,
                    focusedContainerColor = DarkSurfaceVariant,
                    unfocusedContainerColor = DarkSurfaceVariant
                ),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            )

            if (providerType == AIProviderType.OPENAI && onOpenAILogin != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = { onOpenAILogin.invoke() },
                    colors = ButtonDefaults.buttonColors(containerColor = TerminalCyan, contentColor = Color.Black),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    modifier = Modifier.fillMaxWidth().height(32.dp)
                ) {
                    Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Login via OpenAI Desktop Auth (Localhost)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 3. MODEL NAME Field
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Model Identifier",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Custom string supported",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                enabled = providerType != AIProviderType.OPENAI,
                placeholder = { Text("Model name e.g. claude-3-7-sonnet-20250219", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("model_field"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = providerColor,
                    unfocusedBorderColor = DarkBorder,
                    focusedContainerColor = DarkSurfaceVariant,
                    unfocusedContainerColor = DarkSurfaceVariant
                ),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Model suggestion chips
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(modelSuggestions) { suggestion ->
                    Surface(
                        color = if (model == suggestion) providerColor.copy(alpha = 0.2f) else DarkSurfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (model == suggestion) providerColor else DarkBorder),
                        modifier = Modifier.clickable { model = suggestion }
                    ) {
                        Text(
                            text = suggestion,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = if (model == suggestion) providerColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 4. ACTION BUTTONS: Health Test & Save & Set Active
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Health Test Connection Button
            Button(
                onClick = {
                    val cfg = buildCurrentConfig()
                    onSave(cfg)
                    onTest(cfg)
                },
                enabled = !isTesting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = providerColor,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f).height(38.dp).testTag("health_test_button")
            ) {
                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.Black
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Testing...", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Health Test", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Save Config Button
            OutlinedButton(
                onClick = { onSave(buildCurrentConfig()) },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                modifier = Modifier.weight(1f).height(38.dp).testTag("save_config_button")
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp), tint = TerminalGreen)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Save Config", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Set as Active Provider if not active
        if (!isActive) {
            Button(
                onClick = {
                    val cfg = buildCurrentConfig()
                    onSave(cfg)
                    onMakeActive()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = DarkSurfaceVariant,
                    contentColor = providerColor
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, providerColor.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().height(36.dp).testTag("set_active_provider_button")
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Set as Active Agent Provider", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // 5. Health Test Connection Live Feedback Card
        if (testResult != null || config.lastTestedTimestamp > 0L) {
            val success = testResult?.success ?: (config.lastTestSuccess == true)
            val latency = testResult?.latencyMs ?: config.lastTestLatencyMs
            val message = testResult?.message ?: config.lastTestMessage

            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (success) Color(0xFF062012) else Color(0xFF280B0B)
                ),
                border = CardDefaults.outlinedCardBorder().copy(
                    width = 1.dp,
                    brush = androidx.compose.ui.graphics.SolidColor(
                        if (success) TerminalGreen.copy(alpha = 0.6f) else TerminalRed.copy(alpha = 0.6f)
                    )
                ),
                modifier = Modifier.fillMaxWidth().testTag("health_test_result_card")
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (success) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (success) TerminalGreen else TerminalRed,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (success) "Connection Test: HEALTHY" else "Connection Test: FAILED",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (success) TerminalGreen else TerminalRed
                            )
                        }

                        if (latency > 0) {
                            Surface(
                                color = Color.Black.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "⚡ ${latency}ms",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = if (success) TerminalGreen else TerminalYellow,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.9f)
                    )

                    if (testResult != null && testResult.details.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = testResult.details,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticsRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.labelSmall, color = TerminalGreen, fontFamily = FontFamily.Monospace)
    }
}
