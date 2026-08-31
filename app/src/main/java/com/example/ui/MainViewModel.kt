package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.HomeworkMessage
import com.example.data.HomeworkSession
import com.example.data.Message
import com.example.data.MiraiRepository
import androidx.room.Room
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.example.oauth.OAuthPkceManager
import com.example.oauth.OpenAIOAuthConfig
import com.example.oauth.SharedPrefsOAuthSessionStore

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val db = Room.databaseBuilder(
        application,
        AppDatabase::class.java, "mirai-db"
    ).build()

    private val repository = MiraiRepository(db.miraiDao())

    private val prefs = application.getSharedPreferences("mirai_prefs", Context.MODE_PRIVATE)

    private val sessionStore = SharedPrefsOAuthSessionStore(application)
    private val oauthManager = OAuthPkceManager(OpenAIOAuthConfig.config, sessionStore)

    private val _isAbiMode = MutableStateFlow(prefs.getBoolean("is_abi_mode", false))
    val isAbiMode: StateFlow<Boolean> = _isAbiMode.asStateFlow()

    private val _isOAuthLoggedIn = MutableStateFlow(false)
    val isOAuthLoggedIn: StateFlow<Boolean> = _isOAuthLoggedIn.asStateFlow()

    val openAIModels = listOf(
        "gpt-4o",
        "gpt-4o-mini",
        "o1-preview",
        "o1-mini",
        "gpt-4-turbo",
        "gpt-3.5-turbo"
    )
    private val _selectedOpenAIModel = MutableStateFlow(openAIModels[0])
    val selectedOpenAIModel: StateFlow<String> = _selectedOpenAIModel.asStateFlow()

    val allMessages = repository.allMessages.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    val allSessions = repository.allHomeworkSessions.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    init {
        viewModelScope.launch {
            _isOAuthLoggedIn.value = sessionStore.load() != null
        }
    }

    fun loginWithOpenAI(context: Context) {
        viewModelScope.launch {
            val result = oauthManager.login(context)
            if (result.isSuccess) {
                _isOAuthLoggedIn.value = true
            }
        }
    }

    fun logoutOpenAI() {
        viewModelScope.launch {
            oauthManager.logout()
            _isOAuthLoggedIn.value = false
        }
    }

    fun selectModel(model: String) {
        _selectedOpenAIModel.value = model
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
}
