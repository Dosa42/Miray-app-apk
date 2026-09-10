package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.automation.FileSystemEngine
import com.example.automation.LocalProcessRunner
import com.example.automation.SystemDiagnostics
import com.example.automation.TermuxBridge
import com.example.db.AppDatabase
import com.example.db.MemoryContextBuilder
import com.example.db.MemoryContext
import com.example.db.MemoryRepository
import com.example.codex.CodexAgentService
import com.example.codex.CodexExecServerClient
import com.example.codex.MultiProviderAIService
import com.example.codex.OpenAIOAuthManager
import com.example.model.AIProviderConfig
import com.example.model.AIProviderType
import com.example.model.ActionStatus
import com.example.model.ActionType
import com.example.model.AgentAction
import com.example.model.AgentMemory
import com.example.model.AppSettingsEntity
import com.example.model.AutomationScript
import com.example.model.ChatMessage
import com.example.model.ChatMessageEntity
import com.example.model.CodexRuntimeMode
import com.example.model.ExecutionLog
import com.example.model.HealthTestResult
import com.example.model.MessageRole
import com.example.model.MemoryLearningQueueEntity
import com.example.speech.DictationCleanupPrompts
import com.example.speech.GroqApiKeyStore
import com.example.speech.GroqSpeechService
import com.example.speech.SttLanguage
import com.example.speech.VoiceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

class TermuxAgentViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val memoryDao = db.agentMemoryDao()
    private val scriptDao = db.automationScriptDao()
    private val logDao = db.executionLogDao()
    private val chatDao = db.chatMessageDao()
    private val providerConfigDao = db.aiProviderConfigDao()
    private val appSettingsDao = db.appSettingsDao()
    private val learningQueueDao = db.memoryLearningQueueDao()
    private val memoryRepository = MemoryRepository(db)
    private val groqApiKeyStore = GroqApiKeyStore(application)
    private val groqSpeechService = GroqSpeechService()
    private val voiceRecorder = VoiceRecorder(application)

    val memories: StateFlow<List<AgentMemory>> = memoryDao.getAllMemories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val scripts: StateFlow<List<AutomationScript>> = scriptDao.getAllScripts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val executionLogs: StateFlow<List<ExecutionLog>> = logDao.getRecentLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    // Multi-provider configuration state
    private val _providerConfigs = MutableStateFlow<Map<String, AIProviderConfig>>(
        MultiProviderAIService.getDefaultConfigs().associateBy { it.providerId }
    )
    val providerConfigs: StateFlow<Map<String, AIProviderConfig>> = _providerConfigs.asStateFlow()

    private val _activeProvider = MutableStateFlow(AIProviderType.OPENAI)
    val activeProvider: StateFlow<AIProviderType> = _activeProvider.asStateFlow()

    private val _testingProviders = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val testingProviders: StateFlow<Map<String, Boolean>> = _testingProviders.asStateFlow()

    private val _healthTestResults = MutableStateFlow<Map<String, HealthTestResult>>(emptyMap())
    val healthTestResults: StateFlow<Map<String, HealthTestResult>> = _healthTestResults.asStateFlow()

    val selectedModel: StateFlow<String> = MutableStateFlow(MultiProviderAIService.DEFAULT_OPENAI_MODEL)
    val customApiKey: StateFlow<String> = MutableStateFlow("")

    private val _codexRuntimeMode = MutableStateFlow(CodexRuntimeMode.HIGH)
    val codexRuntimeMode: StateFlow<CodexRuntimeMode> = _codexRuntimeMode.asStateFlow()

    private val _ultraWebSocketUrl = MutableStateFlow("ws://127.0.0.1:33223")
    val ultraWebSocketUrl: StateFlow<String> = _ultraWebSocketUrl.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _systemDiagnostics = MutableStateFlow<SystemDiagnostics?>(null)
    val systemDiagnostics: StateFlow<SystemDiagnostics?> = _systemDiagnostics.asStateFlow()

    private val _terminalLogs = MutableStateFlow("Termux Agentic Shell initialized.\nType a command or ask the AI agent to orchestrate tasks.\n$ ")
    val terminalLogs: StateFlow<String> = _terminalLogs.asStateFlow()

    private val _isSelfImproving = MutableStateFlow(false)
    val isSelfImproving: StateFlow<Boolean> = _isSelfImproving.asStateFlow()

    private val _notificationMessage = MutableStateFlow<String?>(null)
    val notificationMessage: StateFlow<String?> = _notificationMessage.asStateFlow()

    private val _groqApiKeyConfigured = MutableStateFlow(groqApiKeyStore.isConfigured())
    val groqApiKeyConfigured: StateFlow<Boolean> = _groqApiKeyConfigured.asStateFlow()

    private val _sttLanguage = MutableStateFlow(SttLanguage.DUTCH)
    val sttLanguage: StateFlow<SttLanguage> = _sttLanguage.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing.asStateFlow()

    private val _sttStatus = MutableStateFlow("Ready · whisper-large-v3 · NL")
    val sttStatus: StateFlow<String> = _sttStatus.asStateFlow()

    private val continuedCodexBatches = mutableSetOf<String>()
    private val batchReasoningEfforts = mutableMapOf<String, String>()
    private val batchTurnKeys = mutableMapOf<String, String>()
    private val batchMemoryContexts = mutableMapOf<String, MemoryContext>()
    private val batchTermuxInstalled = mutableMapOf<String, Boolean>()
    private val learningMutex = Mutex()

    init {
        loadInitialData()
        refreshSystemDiagnostics()
        viewModelScope.launch(Dispatchers.IO) { processLearningQueue() }
    }

    private fun loadInitialData() {
        viewModelScope.launch(Dispatchers.IO) {
            // 1. Load and initialize Provider Configurations
            val storedConfigs = providerConfigDao.getAllConfigsList()
            val configMap = mutableMapOf<String, AIProviderConfig>()

            MultiProviderAIService.getDefaultConfigs().forEach { def ->
                configMap[def.providerId] = def
            }

            for (stored in storedConfigs) {
                configMap[stored.providerId] = stored
            }

            // Migrate the old generic OpenAI gateway entry to the fixed Codex backend/model.
            configMap[AIProviderType.OPENAI.id]?.let { old ->
                configMap[AIProviderType.OPENAI.id] = old.copy(
                    baseUrl = MultiProviderAIService.DEFAULT_OPENAI_URL,
                    model = MultiProviderAIService.DEFAULT_OPENAI_MODEL,
                    apiKey = old.apiKey.takeIf(OpenAIOAuthManager::isLoggedIn).orEmpty()
                )
            }

            configMap.values.forEach { config ->
                providerConfigDao.insertOrUpdate(config)
            }
            _providerConfigs.value = configMap

            // Load active provider selection
            // V3 is deliberately OAuth-only Codex. Do not silently fall back to another provider.
            _activeProvider.value = AIProviderType.OPENAI
            appSettingsDao.setValue(AppSettingsEntity("active_provider", AIProviderType.OPENAI.id))

            val storedSttLanguage = SttLanguage.fromCode(appSettingsDao.getValue("stt_language"))
            _sttLanguage.value = storedSttLanguage
            _sttStatus.value = "Ready · ${GroqSpeechService.MODEL} · ${storedSttLanguage.shortLabel}"
            _codexRuntimeMode.value = CodexRuntimeMode.fromStored(appSettingsDao.getValue("codex_runtime_mode"))
            _ultraWebSocketUrl.value = appSettingsDao.getValue("ultra_websocket_url")
                ?.takeIf { it.isNotBlank() } ?: "ws://127.0.0.1:33223"

            val persistedChat = chatDao.getAllMessagesList()
            if (persistedChat.isNotEmpty()) {
                _chatMessages.value = persistedChat.map { entity ->
                    ChatMessage(
                        id = entity.id,
                        role = runCatching { MessageRole.valueOf(entity.role) }.getOrDefault(MessageRole.SYSTEM),
                        text = entity.text,
                        timestamp = entity.timestamp,
                        isError = entity.isError
                    )
                }
            }

            // 2. Check default memories; if empty, seed initial helpful tips
            val currentMems = memoryDao.getAllMemoriesList()
            if (currentMems.isEmpty()) {
                memoryDao.insertMemory(
                    AgentMemory(
                        category = "Termux Config",
                        title = "Termux Run Command Setup",
                        content = "To enable external commands in Termux, add 'allow-external-apps = true' in ~/.termux/termux.properties",
                        importance = 5,
                        stableKey = MemoryRepository.stableKey("Termux Config", "Termux Run Command Setup"),
                        source = "system"
                    )
                )
                memoryDao.insertMemory(
                    AgentMemory(
                        category = "Learned Commands",
                        title = "Essential Packages",
                        content = "Standard dev bootstrap: 'pkg update -y && pkg install -y python nodejs git curl openssh nano'",
                        importance = 4,
                        stableKey = MemoryRepository.stableKey("Learned Commands", "Essential Packages"),
                        source = "system"
                    )
                )
                memoryDao.insertMemory(
                    AgentMemory(
                        category = "Workflows",
                        title = "Background Execution",
                        content = "Termux background execution is available only after a real RUN_COMMAND result verifies access.",
                        importance = 3,
                        stableKey = MemoryRepository.stableKey("Workflows", "Background Execution"),
                        source = "system"
                    )
                )
            }

            // 3. Seed starter scripts if none
            val scriptCount = scriptDao.getScriptById(1)
            if (scriptCount == null) {
                scriptDao.insertScript(
                    AutomationScript(
                        name = "termux_bootstrap.sh",
                        language = "bash",
                        description = "Update packages and install core toolchain (Python, Git, Node)",
                        tags = "setup, termux, dev",
                        code = """
#!/data/data/com.termux/files/usr/bin/bash
echo "=== Updating Termux Packages ==="
pkg update -y && pkg upgrade -y
echo "=== Installing Essential Tools ==="
pkg install -y python nodejs git curl openssh jq nano
echo "=== Setup Completed ==="
python --version
node -v
git --version
                        """.trimIndent()
                    )
                )
                scriptDao.insertScript(
                    AutomationScript(
                        name = "sys_health.py",
                        language = "python",
                        description = "System and storage analyzer script",
                        tags = "python, monitoring",
                        code = """
import os
import shutil
import platform

print("=== Python System Diagnostics ===")
print("System:", platform.system(), platform.release())
print("Machine:", platform.machine())
total, used, free = shutil.disk_usage("/")
print(f"Disk Total: {total // (2**30)} GiB, Free: {free // (2**30)} GiB")
print("Python Environment: OK")
                        """.trimIndent()
                    )
                )
            }

            // 4. Initial welcome message
            val currentProvider = _activeProvider.value
            val currentConfig = _providerConfigs.value[currentProvider.id]
            val welcomeMsg = ChatMessage(
                id = 1L,
                role = MessageRole.MODEL,
                text = "⚡ **Termux Agent AI — Golden Rule V5.2**\n\nPowered only by **ChatGPT Codex OAuth** with `${MultiProviderAIService.DEFAULT_OPENAI_MODEL}`. Direct High/Xhigh/Max and fail-closed exec-server Ultra are available. Voice directives use **Groq whisper-large-v3** for EN/NL/TR and Codex Max cleanup. Tool, permission, stream, and memory failures are shown as hard failures—never as success.\n\nWhat shall I work on?"
            )
            if (_chatMessages.value.isEmpty()) _chatMessages.value = listOf(welcomeMsg)
            processLearningQueue()
        }
    }

    fun setActiveProvider(type: AIProviderType) {
        if (type != AIProviderType.OPENAI) {
            _notificationMessage.value = "Unsupported provider '${type.id}'. No provider fallback was applied."
            return
        }
        _activeProvider.value = type
        viewModelScope.launch(Dispatchers.IO) {
            appSettingsDao.setValue(AppSettingsEntity("active_provider", type.id))
            val conf = _providerConfigs.value[type.id]
            _notificationMessage.value = "Active Provider switched to ${type.displayName} (${conf?.model ?: ""})"
        }
    }

    fun saveProviderConfig(config: AIProviderConfig) {
        if (config.providerId != AIProviderType.OPENAI.id || config.model != MultiProviderAIService.DEFAULT_OPENAI_MODEL) {
            _notificationMessage.value = "Unsupported provider/model configuration. No fallback was applied."
            return
        }
        val current = _providerConfigs.value.toMutableMap()
        current[config.providerId] = config
        _providerConfigs.value = current

        viewModelScope.launch(Dispatchers.IO) {
            providerConfigDao.insertOrUpdate(config)
            _notificationMessage.value = "${AIProviderType.fromId(config.providerId).displayName} settings saved"
        }
    }

    fun testProviderHealth(config: AIProviderConfig) {
        val providerId = config.providerId
        val testMap = _testingProviders.value.toMutableMap()
        testMap[providerId] = true
        _testingProviders.value = testMap

        viewModelScope.launch(Dispatchers.IO) {
            var effectiveConfig = config
            if (AIProviderType.fromId(config.providerId) == AIProviderType.OPENAI) {
                OpenAIOAuthManager.refreshIfNeeded(config.apiKey).onSuccess { session ->
                    effectiveConfig = config.copy(
                        baseUrl = MultiProviderAIService.DEFAULT_OPENAI_URL,
                        model = MultiProviderAIService.DEFAULT_OPENAI_MODEL,
                        apiKey = session.encodeForStorage()
                    )
                    providerConfigDao.insertOrUpdate(effectiveConfig)
                    _providerConfigs.value = _providerConfigs.value + (effectiveConfig.providerId to effectiveConfig)
                }
            }
            val result = MultiProviderAIService.testProviderHealth(effectiveConfig)

            // Update testing status
            val updatedTesting = _testingProviders.value.toMutableMap()
            updatedTesting[providerId] = false
            _testingProviders.value = updatedTesting

            // Update results map
            val updatedResults = _healthTestResults.value.toMutableMap()
            updatedResults[providerId] = result
            _healthTestResults.value = updatedResults

            // Persist test outcome in config
            val updatedConfig = effectiveConfig.copy(
                lastTestedTimestamp = System.currentTimeMillis(),
                lastTestSuccess = result.success,
                lastTestLatencyMs = result.latencyMs,
                lastTestMessage = result.message
            )
            val updatedConfigs = _providerConfigs.value.toMutableMap()
            updatedConfigs[providerId] = updatedConfig
            _providerConfigs.value = updatedConfigs
            providerConfigDao.insertOrUpdate(updatedConfig)

            _notificationMessage.value = if (result.success) {
                "✅ ${AIProviderType.fromId(providerId).displayName} Connected (${result.latencyMs}ms)"
            } else {
                "❌ ${result.message}"
            }
        }
    }

    fun loginOpenAI(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _notificationMessage.value = "Starting ChatGPT OAuth for Codex..."
            val result = OpenAIOAuthManager.authenticate(context)
            result.onSuccess { session ->
                val config = _providerConfigs.value[AIProviderType.OPENAI.id]
                if (config != null) {
                    val updated = config.copy(
                        baseUrl = MultiProviderAIService.DEFAULT_OPENAI_URL,
                        model = MultiProviderAIService.DEFAULT_OPENAI_MODEL,
                        apiKey = session.encodeForStorage()
                    )
                    saveProviderConfig(updated)
                    _notificationMessage.value = "✅ Codex OAuth login successful."
                }
            }.onFailure { err ->
                _notificationMessage.value = "❌ Auth Failed: ${err.message}"
            }
        }
    }

    fun testAllProvidersHealth() {
        _providerConfigs.value[AIProviderType.OPENAI.id]?.let(::testProviderHealth)
    }

    fun setModel(model: String) {
        if (model != MultiProviderAIService.DEFAULT_OPENAI_MODEL) {
            _notificationMessage.value = "Unsupported model '$model'. No fallback was applied."
        }
    }

    fun setCodexRuntimeMode(mode: CodexRuntimeMode) {
        _codexRuntimeMode.value = mode
        viewModelScope.launch(Dispatchers.IO) {
            appSettingsDao.setValue(AppSettingsEntity("codex_runtime_mode", _codexRuntimeMode.value.storedValue))
        }
    }

    fun setUltraWebSocketUrl(url: String) {
        _ultraWebSocketUrl.value = url
        viewModelScope.launch(Dispatchers.IO) {
            appSettingsDao.setValue(AppSettingsEntity("ultra_websocket_url", _ultraWebSocketUrl.value))
        }
    }

    fun clearNotification() {
        _notificationMessage.value = null
    }

    fun saveGroqApiKey(apiKey: String) {
        val result = groqApiKeyStore.save(apiKey)
        result.onSuccess {
            _groqApiKeyConfigured.value = true
            _notificationMessage.value = "✅ Groq STT key encrypted with Android Keystore."
        }.onFailure { error ->
            _groqApiKeyConfigured.value = groqApiKeyStore.isConfigured()
            _notificationMessage.value = "❌ ${error.message ?: "Unable to store Groq API key."}"
        }
    }

    fun clearGroqApiKey() {
        groqApiKeyStore.clear()
        _groqApiKeyConfigured.value = false
        _notificationMessage.value = "Groq STT key removed from this device."
    }

    fun setSttLanguage(language: SttLanguage) {
        _sttLanguage.value = language
        _sttStatus.value = "Ready · ${GroqSpeechService.MODEL} · ${language.shortLabel}"
        viewModelScope.launch(Dispatchers.IO) {
            appSettingsDao.setValue(AppSettingsEntity("stt_language", language.code))
        }
    }

    fun toggleVoiceRecording() {
        if (_isTranscribing.value || _isThinking.value) return

        if (!_isRecording.value) {
            if (!groqApiKeyStore.isConfigured()) {
                _groqApiKeyConfigured.value = false
                _notificationMessage.value = "Configure your Groq STT key in Settings first."
                return
            }
            voiceRecorder.start().onSuccess {
                _isRecording.value = true
                _sttStatus.value = "Recording ${_sttLanguage.value.shortLabel}… tap stop when finished"
            }.onFailure { error ->
                _isRecording.value = false
                _sttStatus.value = "Recording failed"
                _notificationMessage.value = "❌ Microphone: ${error.localizedMessage ?: error.message}"
            }
            return
        }

        val recordingResult = voiceRecorder.stop()
        _isRecording.value = false
        recordingResult.onFailure { error ->
            _sttStatus.value = "Recording failed"
            _notificationMessage.value = "❌ ${error.localizedMessage ?: error.message}"
        }.onSuccess { audioFile ->
            transcribeAndSend(audioFile)
        }
    }

    private fun transcribeAndSend(audioFile: java.io.File) {
        val language = _sttLanguage.value
        _isTranscribing.value = true
        _sttStatus.value = "Transcribing with ${GroqSpeechService.MODEL} · ${language.shortLabel}…"

        viewModelScope.launch(Dispatchers.IO) {
            var stage = "Transcription"
            try {
                val apiKey = groqApiKeyStore.load()
                    ?: error("Groq STT key is not configured.")
                val rawTranscript = groqSpeechService.transcribe(audioFile, language, apiKey).getOrThrow()

                stage = "Cleanup"
                _sttStatus.value = "Cleaning transcript with Codex · ${language.shortLabel}…"
                var codexConfig = _providerConfigs.value[AIProviderType.OPENAI.id]
                    ?: error("Codex configuration is unavailable.")
                val refreshedSession = OpenAIOAuthManager.refreshIfNeeded(codexConfig.apiKey).getOrThrow()
                val encodedSession = refreshedSession.encodeForStorage()
                if (encodedSession != codexConfig.apiKey) {
                    codexConfig = codexConfig.copy(apiKey = encodedSession)
                    providerConfigDao.insertOrUpdate(codexConfig)
                    _providerConfigs.value = _providerConfigs.value + (codexConfig.providerId to codexConfig)
                }
                val cleanedTranscript = CodexAgentService.cleanupDictation(
                    config = codexConfig,
                    rawTranscript = rawTranscript,
                    cleanupPrompt = DictationCleanupPrompts.forLanguage(language)
                ).getOrThrow()

                _sttStatus.value = "Cleaned · ${language.shortLabel}: ${cleanedTranscript.take(80)}"
                _notificationMessage.value = "✅ Whisper transcript cleaned and sent to Codex."
                sendMessage(cleanedTranscript)
            } catch (error: Throwable) {
                _sttStatus.value = "$stage failed"
                _notificationMessage.value = "❌ $stage: ${error.localizedMessage ?: error.message}"
            } finally {
                _isTranscribing.value = false
                runCatching { audioFile.delete() }
            }
        }
    }

    fun refreshSystemDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            val diag = FileSystemEngine.getSystemDiagnostics(getApplication())
            _systemDiagnostics.value = diag
        }
    }

    fun sendMessage(userText: String) {
        if (userText.isBlank() || _isThinking.value) return

        val userMessage = ChatMessage(
            role = MessageRole.USER,
            text = userText
        )

        val conversationBeforeCurrentTurn = _chatMessages.value
        val turnKey = UUID.randomUUID().toString()
        _chatMessages.value = conversationBeforeCurrentTurn + userMessage
        _isThinking.value = true
        _streamingText.value = ""

        viewModelScope.launch(Dispatchers.IO) {
            try {
                chatDao.insertMessage(
                    ChatMessageEntity(role = MessageRole.USER.name, text = userText, turnId = turnKey)
                )

                val allMemories = memoryDao.getAllMemoriesList()
                val memoryContext = MemoryContextBuilder.build(allMemories, userText)
                val isTermuxInstalled = TermuxBridge.isTermuxInstalled(getApplication())
                val currentProvider = _activeProvider.value
                var activeConfig = _providerConfigs.value[currentProvider.id]
                    ?: error("Active Codex configuration is missing.")
                val selectedRuntimeMode = _codexRuntimeMode.value

                if (currentProvider != AIProviderType.OPENAI) {
                    error("Unsupported provider '${currentProvider.id}'. No provider fallback was applied.")
                }
                if (selectedRuntimeMode != CodexRuntimeMode.ULTRA) {
                    val refreshed = OpenAIOAuthManager.refreshIfNeeded(activeConfig.apiKey).getOrThrow()
                    val encoded = refreshed.encodeForStorage()
                    if (encoded != activeConfig.apiKey) {
                        activeConfig = activeConfig.copy(apiKey = encoded)
                        providerConfigDao.insertOrUpdate(activeConfig)
                        _providerConfigs.value = _providerConfigs.value + (activeConfig.providerId to activeConfig)
                    }
                }

                val systemPrompt = MultiProviderAIService.buildSystemInstruction(
                    memoryContext.memories,
                    isTermuxInstalled,
                    memoryContext.trace
                )
                val responseResult = if (selectedRuntimeMode == CodexRuntimeMode.ULTRA) {
                    CodexExecServerClient(
                        endpoint = _ultraWebSocketUrl.value,
                        workingDirectory = TermuxBridge.DEFAULT_TERMUX_HOME
                    ).runUltraTurn(
                        systemPrompt = systemPrompt,
                        history = conversationBeforeCurrentTurn,
                        userMessage = userText,
                        onTextDelta = { delta -> _streamingText.value += delta }
                    ).map { ultra ->
                        com.example.codex.AgentResponse(
                            replyText = ultra.text,
                            actions = emptyList(),
                            rawResponse = ultra.rawEvents.joinToString("\n"),
                            responseId = ultra.turnId
                        )
                    }
                } else {
                    val effort = requireNotNull(selectedRuntimeMode.directReasoningEffort)
                    MultiProviderAIService.sendAgentChat(
                        providerConfig = activeConfig,
                        conversationHistory = conversationBeforeCurrentTurn,
                        userMessage = userText,
                        memories = memoryContext.memories,
                        termuxInstalled = isTermuxInstalled,
                        reasoningEffort = effort,
                        retrievalTrace = memoryContext.trace,
                        onTextDelta = { delta -> _streamingText.value += delta }
                    )
                }

                responseResult.fold(
                    onSuccess = { agentResponse ->
                        require(agentResponse.actions.isEmpty() || agentResponse.responseId.isNotBlank()) {
                            "Tool response omitted the response id required for continuation."
                        }
                        val assistantMessage = ChatMessage(
                            role = MessageRole.MODEL,
                            text = agentResponse.replyText,
                            actions = agentResponse.actions,
                            codexResponseId = agentResponse.responseId
                        )
                        chatDao.insertMessage(assistantMessage.toEntity(turnKey, agentResponse.rawResponse))
                        _chatMessages.value = _chatMessages.value + assistantMessage
                        if (agentResponse.responseId.isNotBlank()) {
                            batchTurnKeys[agentResponse.responseId] = turnKey
                            batchMemoryContexts[agentResponse.responseId] = memoryContext
                            batchTermuxInstalled[agentResponse.responseId] = isTermuxInstalled
                        }
                        if (selectedRuntimeMode != CodexRuntimeMode.ULTRA && agentResponse.responseId.isNotBlank()) {
                            batchReasoningEfforts[agentResponse.responseId] = requireNotNull(selectedRuntimeMode.directReasoningEffort)
                        }
                        if (assistantMessage.actions.isEmpty()) {
                            commitTurnAndEnqueueLearning(turnKey)
                        } else {
                            executePendingCodexActions(_chatMessages.value.lastIndex)
                        }
                    },
                    onFailure = { error -> persistTurnFailure(turnKey, error) }
                )
            } catch (error: Throwable) {
                persistTurnFailureSafely(turnKey, error)
            } finally {
                _isThinking.value = false
                _streamingText.value = ""
            }
        }
    }

    fun executeAction(messageIndex: Int, actionIndex: Int) {
        val messages = _chatMessages.value.toMutableList()
        if (messageIndex !in messages.indices) return
        val targetMsg = messages[messageIndex]
        val actions = targetMsg.actions.toMutableList()
        if (actionIndex !in actions.indices) return

        val action = actions[actionIndex]
        if (action.status != ActionStatus.PENDING) return
        action.status = ActionStatus.RUNNING

        _chatMessages.value = messages.mapIndexed { idx, msg ->
            if (idx == messageIndex) msg.copy(actions = actions) else msg
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (action.type) {
                ActionType.RUN_TERMUX_COMMAND -> {
                    val startMs = System.currentTimeMillis()
                    val termuxResult = TermuxBridge.executeInTermux(
                        context = getApplication(),
                        command = action.commandOrPath,
                        background = false
                    )
                    val duration = System.currentTimeMillis() - startMs
                    
                    action.exitCode = termuxResult.exitCode
                    action.output = termuxResult.rawResult
                    action.status = if (termuxResult.success) ActionStatus.COMPLETED else ActionStatus.FAILED

                    logDao.insertLog(
                        ExecutionLog(
                            command = action.commandOrPath,
                            target = "TERMUX",
                            exitCode = action.exitCode ?: -1,
                            output = termuxResult.rawResult,
                            durationMs = duration,
                            success = termuxResult.success
                        )
                    )
                    appendToTerminal("[TERMUX] ${action.commandOrPath}\n${termuxResult.rawResult}")
                }

                ActionType.RUN_ROOT_COMMAND -> {
                    val result = LocalProcessRunner.execute(
                        command = "su -c ${shellQuote(action.commandOrPath)}",
                        workDir = getApplication<Application>().filesDir,
                        timeoutMs = Long.MAX_VALUE
                    )
                    action.exitCode = result.exitCode
                    action.output = JSONObject()
                        .put("stdout", result.stdout)
                        .put("stderr", result.stderr)
                        .put("exitCode", result.exitCode)
                        .toString()
                    action.status = if (result.success) ActionStatus.COMPLETED else ActionStatus.FAILED

                    logDao.insertLog(
                        ExecutionLog(
                            command = action.commandOrPath,
                            target = "ROOT",
                            exitCode = result.exitCode,
                            output = action.output,
                            durationMs = result.durationMs,
                            success = result.success
                        )
                    )
                    appendToTerminal("[ROOT] ${action.commandOrPath}\n${action.output}")
                }

                ActionType.SPAWN_TERMUX_AGENT -> {
                    val logPath = action.payload.ifBlank {
                        "/data/data/com.termux/files/home/.termux-agent-ai/${action.id}.log"
                    }
                    val logDirectory = logPath.substringBeforeLast('/', TermuxBridge.DEFAULT_TERMUX_HOME)
                    val detachedCommand = "mkdir -p ${shellQuote(logDirectory)} && " +
                        "nohup bash -lc ${shellQuote(action.commandOrPath)} > ${shellQuote(logPath)} 2>&1 < /dev/null & echo ${'$'}!"
                    val startMs = System.currentTimeMillis()
                    val termuxResult = TermuxBridge.executeInTermux(
                        context = getApplication(),
                        command = detachedCommand,
                        background = false
                    )
                    val duration = System.currentTimeMillis() - startMs
                    action.exitCode = termuxResult.exitCode
                    action.output = termuxResult.rawResult
                    action.status = if (termuxResult.success) ActionStatus.COMPLETED else ActionStatus.FAILED

                    logDao.insertLog(
                        ExecutionLog(
                            command = action.commandOrPath,
                            target = "TERMUX_AGENT",
                            exitCode = action.exitCode ?: -1,
                            output = action.output,
                            durationMs = duration,
                            success = termuxResult.success
                        )
                    )
                    appendToTerminal("[TERMUX AGENT] ${action.description}\n${action.output}")
                }

                ActionType.RUN_SANDBOX_COMMAND -> {
                    val result = LocalProcessRunner.execute(
                        command = action.commandOrPath,
                        workDir = getApplication<Application>().filesDir,
                        timeoutMs = Long.MAX_VALUE
                    )
                    action.exitCode = result.exitCode
                    action.output = JSONObject()
                        .put("stdout", result.stdout)
                        .put("stderr", result.stderr)
                        .put("exitCode", result.exitCode)
                        .toString()
                    action.status = if (result.success) ActionStatus.COMPLETED else ActionStatus.FAILED

                    logDao.insertLog(
                        ExecutionLog(
                            command = action.commandOrPath,
                            target = "SANDBOX",
                            exitCode = result.exitCode,
                            output = action.output,
                            durationMs = result.durationMs,
                            success = result.success
                        )
                    )
                    appendToTerminal("[SANDBOX sh -c] ${action.commandOrPath}\n${action.output}")
                }

                ActionType.WRITE_FILE -> {
                    val writeRes = FileSystemEngine.writeFile(action.commandOrPath, action.payload)
                    writeRes.onSuccess { msg ->
                        action.status = ActionStatus.COMPLETED
                        action.output = msg
                        action.exitCode = 0
                    }.onFailure { err ->
                        action.status = ActionStatus.FAILED
                        action.output = err.message ?: "Write failed"
                        action.exitCode = 1
                    }
                }

                ActionType.READ_FILE -> {
                    val readRes = FileSystemEngine.readFile(action.commandOrPath)
                    readRes.onSuccess { content ->
                        action.status = ActionStatus.COMPLETED
                        action.output = JSONObject().put("content", content).toString()
                        action.exitCode = 0
                    }.onFailure { err ->
                        action.status = ActionStatus.FAILED
                        action.output = err.message ?: "Read failed"
                        action.exitCode = 1
                    }
                }

                ActionType.LIST_DIRECTORY -> {
                    val listRes = FileSystemEngine.listDirectory(action.commandOrPath)
                    listRes.onSuccess { items ->
                        action.status = ActionStatus.COMPLETED
                        action.output = JSONArray().apply {
                            items.forEach { item ->
                                put(
                                    JSONObject()
                                        .put("name", item.name)
                                        .put("isDirectory", item.isDirectory)
                                        .put("sizeBytes", item.sizeBytes)
                                )
                            }
                        }.toString()
                        action.exitCode = 0
                    }.onFailure { err ->
                        action.status = ActionStatus.FAILED
                        action.output = err.message ?: "Directory listing failed"
                        action.exitCode = 1
                    }
                }

                ActionType.DELETE_FILE -> {
                    val delRes = FileSystemEngine.deleteFile(action.commandOrPath)
                    delRes.onSuccess { msg ->
                        action.status = ActionStatus.COMPLETED
                        action.output = msg
                        action.exitCode = 0
                    }.onFailure { err ->
                        action.status = ActionStatus.FAILED
                        action.output = err.message ?: "Deletion failed"
                        action.exitCode = 1
                    }
                }

                ActionType.SAVE_MEMORY -> {
                    val args = try {
                        JSONObject(action.codexArgumentsJson)
                    } catch (error: Exception) {
                        throw IllegalStateException("Unparseable save_memory arguments: ${error.message}")
                    }
                    val category = args.optString("category")
                    val importance = args.optInt("importance", 0)
                    val committed = memoryRepository.insertAgentTool(
                        AgentMemory(
                            category = category,
                            title = action.commandOrPath,
                            content = action.payload,
                            importance = importance
                        )
                    )
                    action.status = ActionStatus.COMPLETED
                    action.output = JSONObject()
                        .put("committedId", committed.id)
                        .put("stableKey", committed.stableKey)
                        .put("category", committed.category)
                        .put("importance", committed.importance)
                        .toString()
                    action.exitCode = 0
                }

                ActionType.CREATE_SCRIPT -> {
                    scriptDao.insertScript(
                        AutomationScript(
                            name = action.commandOrPath,
                            language = "bash",
                            description = action.description,
                            code = action.payload
                        )
                    )
                    action.status = ActionStatus.COMPLETED
                    action.output = JSONObject()
                        .put("name", action.commandOrPath)
                        .put("committed", true)
                        .toString()
                    action.exitCode = 0
                }

                ActionType.SYSTEM_DIAGNOSTICS -> {
                    val diag = FileSystemEngine.getSystemDiagnostics(getApplication())
                    _systemDiagnostics.value = diag
                    action.status = ActionStatus.COMPLETED
                    action.output = """
Android: ${diag.androidVersion} (API ${diag.apiLevel})
Device: ${diag.deviceModel} (${diag.cpuArch})
RAM: ${diag.availRamMb} MB / ${diag.totalRamMb} MB free
Storage: ${diag.freeStorageGb} GB / ${diag.totalStorageGb} GB free
Termux App Installed: ${diag.termuxInstalled}
App Sandbox Path: ${diag.appFilesDir}
                    """.trimIndent()
                    action.exitCode = 0
                }
                }
            } catch (error: Throwable) {
                action.status = ActionStatus.FAILED
                action.exitCode = null
                action.output = "Tool hard failure: ${error.localizedMessage ?: error.message ?: error::class.java.name}"
            }

            try {
            if (action.status == ActionStatus.COMPLETED && action.exitCode == null) {
                action.status = ActionStatus.FAILED
                action.output = "Tool completed without an authoritative exitCode."
            }
            if (action.output.isBlank()) {
                action.status = ActionStatus.FAILED
                action.output = "Tool returned a blank authoritative result."
                action.exitCode = action.exitCode ?: -1
            }

            val actionTurnKey = batchTurnKeys[targetMsg.codexResponseId]
                ?: error("No persisted turn key for tool batch ${targetMsg.codexResponseId}")
            chatDao.insertMessage(
                ChatMessageEntity(
                    role = MessageRole.TOOL.name,
                    text = action.output,
                    actionsJson = JSONArray().put(action.toJson()).toString(),
                    turnId = actionTurnKey,
                    isComplete = true,
                    rawResponse = action.output,
                    isError = action.status == ActionStatus.FAILED
                )
            )

            val updatedConversation = withContext(Dispatchers.Main) {
                val updated = _chatMessages.value.mapIndexed { idx, msg ->
                    if (idx == messageIndex) msg.copy(actions = actions) else msg
                }
                _chatMessages.value = updated
                updated
            }

            val allActionsFinished = actions.all {
                it.status == ActionStatus.COMPLETED || it.status == ActionStatus.FAILED
            }
            if (!allActionsFinished) {
                val nextAction = actions.indexOfFirst { it.status == ActionStatus.PENDING }
                if (nextAction >= 0) executeAction(messageIndex, nextAction)
                return@launch
            }
            val batchId = targetMsg.codexResponseId
            val shouldContinue = allActionsFinished && batchId.isNotBlank() && synchronized(continuedCodexBatches) {
                continuedCodexBatches.add(batchId)
            }
            if (shouldContinue) {
                continueCodexAfterToolResults(updatedConversation, batchId)
            }
            } catch (error: Throwable) {
                val failedTurnKey = batchTurnKeys[targetMsg.codexResponseId]
                if (failedTurnKey != null) {
                    persistTurnFailureSafely(failedTurnKey, error)
                } else {
                    val exact = error.localizedMessage ?: error.message ?: error::class.java.name
                    _chatMessages.value = _chatMessages.value + ChatMessage(
                        role = MessageRole.MODEL,
                        text = "CRITICAL_HARD_FAILURE\nExact cause: $exact",
                        isError = true
                    )
                }
                _isThinking.value = false
            }
        }
    }

    private fun executePendingCodexActions(messageIndex: Int) {
        val actions = _chatMessages.value.getOrNull(messageIndex)?.actions.orEmpty()
        val firstPending = actions.indexOfFirst { it.status == ActionStatus.PENDING }
        if (firstPending >= 0) executeAction(messageIndex, firstPending)
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private suspend fun continueCodexAfterToolResults(
        conversation: List<ChatMessage>,
        batchId: String
    ) {
        _isThinking.value = true
        _streamingText.value = ""
        val turnKey = batchTurnKeys[batchId]
            ?: error("Missing persisted turn key for tool continuation $batchId")
        try {
            val config = _providerConfigs.value[AIProviderType.OPENAI.id]
                ?: error("Codex configuration disappeared during tool continuation.")
            val memoryContext = batchMemoryContexts[batchId]
                ?: error("Missing original memory context for tool continuation $batchId")
            val originalTermuxInstalled = batchTermuxInstalled[batchId]
                ?: error("Missing original Termux state for tool continuation $batchId")
            val effort = batchReasoningEfforts[batchId]
                ?: error("Missing original reasoning effort for tool continuation $batchId")

            val refreshed = OpenAIOAuthManager.refreshIfNeeded(config.apiKey).getOrThrow()
            val encodedSession = refreshed.encodeForStorage()
            val activeConfig = if (encodedSession != config.apiKey) {
                config.copy(apiKey = encodedSession).also { updated ->
                    providerConfigDao.insertOrUpdate(updated)
                    _providerConfigs.value = _providerConfigs.value + (updated.providerId to updated)
                }
            } else config

            MultiProviderAIService.continueCodexAgent(
                providerConfig = activeConfig,
                conversationHistory = conversation,
                memories = memoryContext.memories,
                termuxInstalled = originalTermuxInstalled,
                reasoningEffort = effort,
                retrievalTrace = memoryContext.trace,
                onTextDelta = { delta -> _streamingText.value += delta }
            ).fold(
                onSuccess = { response ->
                    require(response.actions.isEmpty() || response.responseId.isNotBlank()) {
                        "Tool continuation omitted the response id required for another continuation."
                    }
                    val message = ChatMessage(
                        role = MessageRole.MODEL,
                        text = response.replyText,
                        actions = response.actions,
                        codexResponseId = response.responseId
                    )
                    chatDao.insertMessage(message.toEntity(turnKey, response.rawResponse))
                    _chatMessages.value = _chatMessages.value + message
                    if (response.responseId.isNotBlank()) {
                        batchTurnKeys[response.responseId] = turnKey
                        batchMemoryContexts[response.responseId] = memoryContext
                        batchTermuxInstalled[response.responseId] = originalTermuxInstalled
                        batchReasoningEfforts[response.responseId] = effort
                    }
                    if (message.actions.isEmpty()) commitTurnAndEnqueueLearning(turnKey)
                    else executePendingCodexActions(_chatMessages.value.lastIndex)
                },
                onFailure = { error -> persistTurnFailure(turnKey, error) }
            )
        } catch (error: Throwable) {
            persistTurnFailureSafely(turnKey, error)
        } finally {
            _isThinking.value = false
            _streamingText.value = ""
        }
    }

    fun runSandboxTerminalCommand(command: String) {
        if (command.isBlank()) return
        appendToTerminal("$ $command")
        viewModelScope.launch(Dispatchers.IO) {
            val result = LocalProcessRunner.execute(
                command = command,
                workDir = getApplication<Application>().filesDir,
                timeoutMs = Long.MAX_VALUE
            )
            val output = JSONObject()
                .put("stdout", result.stdout)
                .put("stderr", result.stderr)
                .put("exitCode", result.exitCode)
                .put("durationMs", result.durationMs)
                .toString()
            appendToTerminal(output)

            logDao.insertLog(
                ExecutionLog(
                    command = command,
                    target = "SANDBOX",
                    exitCode = result.exitCode,
                    output = output,
                    durationMs = result.durationMs,
                    success = result.success
                )
            )
        }
    }

    fun runTermuxTerminalCommand(command: String) {
        if (command.isBlank()) return
        appendToTerminal("[TERMUX] $ $command")
        viewModelScope.launch(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()
            val result = TermuxBridge.executeInTermux(
                context = getApplication(),
                command = command,
                background = false
            )
            val duration = System.currentTimeMillis() - startMs
            
            appendToTerminal(result.rawResult)

            logDao.insertLog(
                ExecutionLog(
                    command = command,
                    target = "TERMUX",
                    exitCode = result.exitCode ?: -1,
                    output = result.rawResult,
                    durationMs = duration,
                    success = result.success
                )
            )
        }
    }

    private fun appendToTerminal(text: String) {
        _terminalLogs.value = _terminalLogs.value + "\n" + text
    }

    private fun ChatMessage.toEntity(turnKey: String, rawResponse: String = ""): ChatMessageEntity =
        ChatMessageEntity(
            role = role.name,
            text = text,
            actionsJson = JSONArray().apply {
                actions.forEach { action -> put(action.toJson()) }
            }.toString(),
            turnId = turnKey,
            isComplete = !isThinking,
            rawResponse = rawResponse,
            isError = isError,
            timestamp = timestamp
        )

    private fun AgentAction.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type.name)
        .put("commandOrPath", commandOrPath)
        .put("payload", payload)
        .put("description", description)
        .put("codexCallId", codexCallId)
        .put("codexArgumentsJson", codexArgumentsJson)
        .put("status", status.name)
        .put("output", output)
        .put("exitCode", exitCode ?: JSONObject.NULL)

    private suspend fun persistTurnFailure(turnKey: String, error: Throwable) {
        val exactError = error.localizedMessage ?: error.message ?: error::class.java.name
        val errorMessage = ChatMessage(
            role = MessageRole.MODEL,
            text = "CRITICAL_HARD_FAILURE\nExact cause: $exactError",
            isError = true
        )
        chatDao.insertMessage(errorMessage.toEntity(turnKey, exactError))
        _chatMessages.value = _chatMessages.value + errorMessage
        commitTurnAndEnqueueLearning(turnKey)
    }

    private suspend fun persistTurnFailureSafely(turnKey: String, error: Throwable) {
        runCatching { persistTurnFailure(turnKey, error) }.onFailure { persistenceError ->
            val original = error.localizedMessage ?: error.message ?: error::class.java.name
            val persistence = persistenceError.localizedMessage ?: persistenceError.message ?: persistenceError::class.java.name
            _chatMessages.value = _chatMessages.value + ChatMessage(
                role = MessageRole.MODEL,
                text = "CRITICAL_HARD_FAILURE\nExact cause: $original\nPersistence failure: $persistence",
                isError = true
            )
        }
    }

    private suspend fun commitTurnAndEnqueueLearning(turnKey: String) {
        db.withTransaction {
            val rows = chatDao.getMessagesForTurn(turnKey)
            check(rows.any { it.role == MessageRole.USER.name }) { "Turn $turnKey has no persisted real user message." }
            check(rows.any { it.role == MessageRole.MODEL.name }) { "Turn $turnKey has no persisted completed/error model response." }
            check(rows.all { it.isComplete }) { "Turn $turnKey contains an incomplete response." }

            val evidence = JSONObject()
                .put("turnKey", turnKey)
                .put("messages", JSONArray().apply {
                    rows.forEach { row ->
                        put(
                            JSONObject()
                                .put("role", row.role)
                                .put("text", row.text)
                                .put("actions", JSONArray(row.actionsJson))
                                .put("rawResponse", row.rawResponse)
                                .put("isError", row.isError)
                                .put("timestamp", row.timestamp)
                        )
                    }
                })
                .toString()

            val inserted = learningQueueDao.enqueue(
                MemoryLearningQueueEntity(turnKey = turnKey, evidenceJson = evidence)
            )
            if (inserted == -1L) {
                val existing = learningQueueDao.byTurnKey(turnKey)
                    ?: error("Learning queue conflict without an existing row for $turnKey")
                check(existing.evidenceJson == evidence) {
                    "Learning queue duplicate for $turnKey contains different evidence."
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) { processLearningQueue() }
    }

    private suspend fun processLearningQueue() = learningMutex.withLock {
        while (true) {
            val queued = learningQueueDao.nextPending() ?: return@withLock
            val config = _providerConfigs.value[AIProviderType.OPENAI.id]
                ?: return@withLock
            val refreshed = OpenAIOAuthManager.refreshIfNeeded(config.apiKey)
            if (refreshed.isFailure) {
                val changed = learningQueueDao.updateOutcome(
                    id = queued.id,
                    status = MemoryLearningQueueEntity.STATUS_PENDING,
                    attemptCount = queued.attemptCount + 1,
                    lastError = refreshed.exceptionOrNull()?.localizedMessage ?: "OAuth refresh failed",
                    decision = ""
                )
                check(changed == 1) { "Failed to persist learning queue retry state for ${queued.id}" }
                return@withLock
            }

            val encoded = refreshed.getOrThrow().encodeForStorage()
            val effectiveConfig = if (encoded != config.apiKey) {
                config.copy(apiKey = encoded).also { updated ->
                    providerConfigDao.insertOrUpdate(updated)
                    _providerConfigs.value = _providerConfigs.value + (updated.providerId to updated)
                }
            } else config

            val decisionResult = CodexAgentService.analyzeMemoryTurn(
                effectiveConfig,
                queued.evidenceJson,
                memoryDao.getAllMemoriesList()
            )
            if (decisionResult.isFailure) {
                val message = decisionResult.exceptionOrNull()?.localizedMessage ?: "Memory analysis failed"
                val isRetryable = message.contains("HTTP", true) ||
                    message.contains("network", true) ||
                    message.contains("timeout", true) ||
                    message.contains("OAuth", true) ||
                    message.contains("stream", true)
                val changed = learningQueueDao.updateOutcome(
                    id = queued.id,
                    status = if (isRetryable) MemoryLearningQueueEntity.STATUS_PENDING else MemoryLearningQueueEntity.STATUS_HARD_FAILURE,
                    attemptCount = queued.attemptCount + 1,
                    lastError = message,
                    decision = ""
                )
                check(changed == 1) { "Failed to persist learning failure for ${queued.id}" }
                if (isRetryable) return@withLock
                _notificationMessage.value = "CRITICAL_HARD_FAILURE · Memory: $message"
                continue
            }

            val decision = decisionResult.getOrThrow()
            val persistedDecision = if (decision.action == "IGNORE") {
                "IGNORE:${decision.reason}"
            } else {
                val upsertResult = runCatching {
                    memoryRepository.upsertLearned(
                        operation = decision.action,
                        title = decision.title,
                        content = decision.content,
                        category = decision.category,
                        importance = decision.importance
                    )
                }
                if (upsertResult.isFailure) {
                    val upsertError = upsertResult.exceptionOrNull()?.localizedMessage ?: "Memory upsert failed"
                    val failed = learningQueueDao.updateOutcome(
                        id = queued.id,
                        status = MemoryLearningQueueEntity.STATUS_HARD_FAILURE,
                        attemptCount = queued.attemptCount + 1,
                        lastError = upsertError,
                        decision = decision.action
                    )
                    check(failed == 1) { "Failed to persist memory upsert failure for ${queued.id}" }
                    _notificationMessage.value = "CRITICAL_HARD_FAILURE · Memory upsert: $upsertError"
                    continue
                }
                val upsert = upsertResult.getOrThrow()
                if (upsert.operation.startsWith("IGNORE_")) {
                    "${upsert.operation}:${upsert.memory?.stableKey}"
                } else {
                    _notificationMessage.value = "Memory saved after verified Room commit: ${upsert.memory?.title}"
                    "${upsert.operation}:${upsert.memory?.stableKey}"
                }
            }
            val changed = learningQueueDao.updateOutcome(
                id = queued.id,
                status = MemoryLearningQueueEntity.STATUS_COMPLETED,
                attemptCount = queued.attemptCount + 1,
                lastError = "",
                decision = persistedDecision
            )
            check(changed == 1) { "Learning queue completion verification failed for ${queued.id}" }
            val verified = learningQueueDao.byTurnKey(queued.turnKey)
            check(verified?.status == MemoryLearningQueueEntity.STATUS_COMPLETED) {
                "Learning queue completion was not durable for ${queued.turnKey}"
            }
        }
    }

    fun clearTerminal() {
        _terminalLogs.value = "Terminal reset.\n$ "
    }

    fun saveScript(script: AutomationScript) {
        viewModelScope.launch(Dispatchers.IO) {
            if (script.id == 0L) {
                scriptDao.insertScript(script)
            } else {
                scriptDao.updateScript(script)
            }
            _notificationMessage.value = "Script saved: ${script.name}"
        }
    }

    fun deleteScript(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            scriptDao.deleteScriptById(id)
            _notificationMessage.value = "Script deleted"
        }
    }

    fun runSavedScript(script: AutomationScript, target: String) {
        viewModelScope.launch(Dispatchers.IO) {
            scriptDao.recordExecution(script.id)
            if (target == "TERMUX") {
                runTermuxTerminalCommand(script.code)
            } else {
                runSandboxTerminalCommand(script.code)
            }
            _notificationMessage.value = "Executing script '${script.name}' on $target"
        }
    }

    fun addMemory(category: String, title: String, content: String, importance: Int = 3) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                memoryRepository.insertManual(
                    AgentMemory(
                        category = category,
                        title = title,
                        content = content,
                        importance = importance
                    )
                )
            }.onSuccess { committed ->
                _notificationMessage.value = "Memory saved after verified Room commit: ${committed.title}"
            }.onFailure { error ->
                _notificationMessage.value = "CRITICAL_HARD_FAILURE · Memory: ${error.localizedMessage ?: error.message}"
            }
        }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            memoryDao.deleteMemoryById(id)
            _notificationMessage.value = "Memory deleted"
        }
    }

    fun triggerSelfImprovement() {
        if (_isSelfImproving.value) return
        _isSelfImproving.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val before = learningQueueDao.pendingCount()
                processLearningQueue()
                val after = learningQueueDao.pendingCount()
                _notificationMessage.value = when {
                    after > 0 -> "Learning queue remains durable: $after pending item(s)."
                    before == 0 -> "No pending learning items; no learning was claimed."
                    else -> "Learning queue processed. Check committed Memory entries for actual changes."
                }
            } catch (error: Throwable) {
                _notificationMessage.value = "CRITICAL_HARD_FAILURE · Reflection: ${error.localizedMessage ?: error.message}"
            } finally {
                _isSelfImproving.value = false
            }
        }
    }

    fun clearChat() {
        viewModelScope.launch(Dispatchers.IO) {
            chatDao.clearAllMessages()
            _chatMessages.value = emptyList()
            loadInitialData()
        }
    }

    fun clearLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            logDao.clearLogs()
            _notificationMessage.value = "Execution logs cleared"
        }
    }

    override fun onCleared() {
        voiceRecorder.release()
        super.onCleared()
    }
}
