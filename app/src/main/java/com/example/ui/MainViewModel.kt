package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.example.api.CodexAuthenticationException
import com.example.api.CodexResponsesClient
import com.example.data.AppDatabase
import com.example.data.HomeworkMessage
import com.example.data.HomeworkSession
import com.example.data.Message
import com.example.data.MiraiRepository
import com.example.oauth.OAuthSession
import com.example.oauth.OpenAIConnectionState
import com.example.oauth.OpenAIOAuthConfig
import com.example.oauth.OpenAIProviderDefaults
import com.example.oauth.SharedPrefsOpenAIProviderStore
import com.example.oauth.chatGptAccountId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val db = Room.databaseBuilder(
        application,
        AppDatabase::class.java, "mirai-db"
    ).build()

    private val repository = MiraiRepository(db.miraiDao())

    private val prefs = application.getSharedPreferences("mirai_prefs", Context.MODE_PRIVATE)

    private val openAIProviderStore = SharedPrefsOpenAIProviderStore(application)
    private val oauthManager = OpenAIOAuthConfig.createManager(openAIProviderStore)
    private val codexResponsesClient = CodexResponsesClient(oauthManager, openAIProviderStore)

    private val _isAbiMode = MutableStateFlow(prefs.getBoolean("is_abi_mode", false))
    val isAbiMode: StateFlow<Boolean> = _isAbiMode.asStateFlow()

    private val _openAIConnectionState =
        MutableStateFlow<OpenAIConnectionState>(OpenAIConnectionState.Refreshing)
    val openAIConnectionState: StateFlow<OpenAIConnectionState> =
        _openAIConnectionState.asStateFlow()

    val isOAuthLoggedIn: StateFlow<Boolean> = openAIConnectionState
        .map { it is OpenAIConnectionState.Connected }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val openAIModels: List<String> = OpenAIProviderDefaults.MODELS
    private val _selectedOpenAIModel = MutableStateFlow(OpenAIProviderDefaults.DEFAULT_MODEL)
    val selectedOpenAIModel: StateFlow<String> = _selectedOpenAIModel.asStateFlow()

    private val _openAIResponseText = MutableStateFlow("")
    val openAIResponseText: StateFlow<String> = _openAIResponseText.asStateFlow()

    private val _isOpenAIRequestRunning = MutableStateFlow(false)
    val isOpenAIRequestRunning: StateFlow<Boolean> = _isOpenAIRequestRunning.asStateFlow()

    private val _openAIErrorMessage = MutableStateFlow<String?>(null)
    val openAIErrorMessage: StateFlow<String?> = _openAIErrorMessage.asStateFlow()

    private var modelPersistenceJob: Job? = null
    private var openAIRequestJob: Job? = null
    private var openAILogoutJob: Job? = null
    private var openAIRequestGeneration = 0L

    val allMessages = repository.allMessages.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    val allSessions = repository.allHomeworkSessions.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    init {
        viewModelScope.launch {
            restoreOpenAIProvider()
        }
    }

    fun loginWithOpenAI(context: Context) {
        if (
            _openAIConnectionState.value is OpenAIConnectionState.Authorizing ||
            openAILogoutJob?.isActive == true
        ) return

        _openAIErrorMessage.value = null
        _openAIConnectionState.value = OpenAIConnectionState.Authorizing
        viewModelScope.launch {
            oauthManager.login(context).fold(
                onSuccess = { session ->
                    updateConnectedState(
                        session,
                        "OpenAI sign-in succeeded, but no access token was returned. Log out and try again."
                    )
                },
                onFailure = { error ->
                    _openAIConnectionState.value = OpenAIConnectionState.Error(
                        actionableError("OpenAI sign-in failed.", error)
                    )
                }
            )
        }
    }

    fun logoutOpenAI() {
        if (openAILogoutJob?.isActive == true) return

        openAIRequestGeneration++
        openAIRequestJob?.cancel()
        openAIRequestJob = null
        _openAIResponseText.value = ""
        _isOpenAIRequestRunning.value = false
        _openAIErrorMessage.value = null

        openAILogoutJob = viewModelScope.launch {
            try {
                runCatching { oauthManager.logout() }.fold(
                    onSuccess = {
                        _openAIConnectionState.value = OpenAIConnectionState.LoggedOut
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        _openAIConnectionState.value = OpenAIConnectionState.Error(
                            actionableError("OpenAI logout failed. Try again.", error)
                        )
                    }
                )
            } finally {
                openAILogoutJob = null
            }
        }
    }

    fun selectModel(model: String) {
        if (model !in openAIModels || model == _selectedOpenAIModel.value) return

        _selectedOpenAIModel.value = model
        _openAIErrorMessage.value = null
        modelPersistenceJob?.cancel()
        modelPersistenceJob = viewModelScope.launch {
            runCatching {
                val settings = openAIProviderStore.loadSettings()
                openAIProviderStore.saveSettings(settings.copy(model = model))
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _openAIErrorMessage.value = actionableError(
                    "The OpenAI model could not be saved.",
                    error
                )
            }
        }
    }

    fun sendOpenAIResponse(prompt: String) {
        val requestPrompt = prompt.trim()
        if (
            requestPrompt.isBlank() ||
            _isOpenAIRequestRunning.value ||
            openAILogoutJob?.isActive == true
        ) return

        val requestModel = _selectedOpenAIModel.value
        val requestGeneration = ++openAIRequestGeneration
        _openAIResponseText.value = ""
        _openAIErrorMessage.value = null
        _isOpenAIRequestRunning.value = true

        openAIRequestJob = viewModelScope.launch {
            try {
                val result = codexResponsesClient.createResponse(
                    prompt = requestPrompt,
                    model = requestModel,
                    onTextDelta = { delta ->
                        if (
                            requestGeneration == openAIRequestGeneration &&
                            delta.isNotEmpty()
                        ) {
                            _openAIResponseText.update { currentText -> currentText + delta }
                        }
                    }
                )

                if (requestGeneration != openAIRequestGeneration) return@launch

                result.fold(
                    onSuccess = { response ->
                        if (response.text.isNotEmpty() || _openAIResponseText.value.isEmpty()) {
                            _openAIResponseText.value = response.text
                        }
                    },
                    onFailure = { error ->
                        val message = actionableError(
                            "OpenAI could not complete the request. Check the connection and try again.",
                            error
                        )
                        _openAIErrorMessage.value = message
                        if (error is CodexAuthenticationException) {
                            _openAIConnectionState.value = OpenAIConnectionState.Error(message)
                        }
                    }
                )
            } finally {
                if (requestGeneration == openAIRequestGeneration) {
                    _isOpenAIRequestRunning.value = false
                    openAIRequestJob = null
                }
            }
        }
    }

    fun toggleMode() {
        val newMode = !_isAbiMode.value
        prefs.edit().putBoolean("is_abi_mode", newMode).apply()
        _isAbiMode.value = newMode
    }

    fun sendMessage(text: String) {
        viewModelScope.launch {
            val sender = if (_isAbiMode.value) "abi" else "mirai"
            repository.insertMessage(Message(senderId = sender, text = text))
        }
    }

    suspend fun createHomeworkSession(imageUri: String): Long {
        return repository.insertHomeworkSession(HomeworkSession(imageUri = imageUri))
    }

    suspend fun getSession(sessionId: Int): HomeworkSession? {
        return repository.getSessionById(sessionId)
    }

    fun getHomeworkMessages(sessionId: Int): StateFlow<List<HomeworkMessage>> {
        return repository.getMessagesForSession(sessionId).stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
        )
    }

    fun addHomeworkMessage(sessionId: Int, sender: String, text: String) {
        viewModelScope.launch {
            repository.insertHomeworkMessage(HomeworkMessage(sessionId = sessionId, sender = sender, text = text))
        }
    }

    private suspend fun restoreOpenAIProvider() {
        runCatching { openAIProviderStore.loadSettings() }
            .onSuccess { settings ->
                _selectedOpenAIModel.value =
                    settings.model.ifBlank { OpenAIProviderDefaults.DEFAULT_MODEL }
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                _openAIErrorMessage.value = actionableError(
                    "The saved OpenAI model setting could not be loaded.",
                    error
                )
            }

        _openAIConnectionState.value = OpenAIConnectionState.Refreshing
        oauthManager.validSession().fold(
            onSuccess = { session ->
                updateConnectedState(
                    session,
                    "The saved OpenAI session has no access token. Log in again."
                )
            },
            onFailure = { error ->
                _openAIConnectionState.value = if (error.indicatesMissingSession()) {
                    OpenAIConnectionState.LoggedOut
                } else {
                    OpenAIConnectionState.Error(
                        actionableError(
                            "The saved OpenAI session could not be restored or refreshed. Log in again.",
                            error
                        )
                    )
                }
            }
        )
    }

    private fun updateConnectedState(session: OAuthSession, missingAccessTokenMessage: String) {
        val accountId = session.chatGptAccountId?.trim()?.takeIf { it.isNotEmpty() }
        _openAIConnectionState.value = if (session.accessToken.isNotBlank()) {
            OpenAIConnectionState.Connected(accountId)
        } else {
            OpenAIConnectionState.Error(missingAccessTokenMessage)
        }
    }

    private fun Throwable.indicatesMissingSession(): Boolean {
        val detail = message.orEmpty()
        return detail.contains("no oauth session", ignoreCase = true) ||
            detail.contains("no stored session", ignoreCase = true) ||
            detail.contains("no session is stored", ignoreCase = true)
    }

    private fun actionableError(prefix: String, error: Throwable): String {
        val detail = error.message?.trim().orEmpty()
        return if (detail.isEmpty()) prefix else "$prefix $detail"
    }
}
