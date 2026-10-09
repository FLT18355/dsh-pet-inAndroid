package com.dshpet.android.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dshpet.android.data.PetConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 聊天界面状态管理：会话列表、当前会话、流式输出。
 *
 * 进程级**共享单例**（[shared]）：全屏对话界面、悬浮对话窗、快捷对话气泡共用同一个
 * 实例，因此"当前会话 / 流式输出 / 会话列表"三处实时一致——否则每个窗口各持一份
 * 内存副本，全量写盘会互相覆盖，表现为消息丢失或两边看到的内容不一样。
 */
class ChatViewModel private constructor(app: Application) : AndroidViewModel(app) {

    private val repo = ChatRepo(app)
    private val config = PetConfig.get(app)

    private val _sessions = MutableStateFlow<List<ChatRepo.Session>>(emptyList())
    val sessions: StateFlow<List<ChatRepo.Session>> = _sessions.asStateFlow()

    private val _current = MutableStateFlow<ChatRepo.Session?>(null)
    val current: StateFlow<ChatRepo.Session?> = _current.asStateFlow()

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private val _streamText = MutableStateFlow("")
    val streamText: StateFlow<String> = _streamText.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var streamJob: Job? = null

    /** 流式代次：停止/重新发送后，旧流的迟到回调一律丢弃 */
    private var streamToken = 0

    init {
        refresh()
        viewModelScope.launch { ensureSession() }
    }

    fun refresh() {
        viewModelScope.launch {
            _sessions.value = repo.list()
            // 顺带从磁盘重读当前会话：其它窗口（悬浮窗/快捷气泡）追加的消息立即可见
            _current.value?.id?.let { id -> repo.get(id)?.let { _current.value = it } }
        }
    }

    /** 界面回到前台时同步一次磁盘内容（多窗口/多界面共用同一份会话） */
    fun reloadCurrent() = refresh()

    private suspend fun ensureSession() {
        if (_current.value == null) {
            val list = repo.list()
            _current.value = list.firstOrNull() ?: repo.create()
            refresh()
        }
    }

    fun newSession() {
        viewModelScope.launch {
            val s = repo.create()
            _current.value = s
            refresh()
        }
    }

    fun selectSession(id: String) {
        viewModelScope.launch {
            _current.value = repo.get(id)
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            repo.delete(id)
            if (_current.value?.id == id) {
                val list = repo.list()
                _current.value = list.firstOrNull() ?: repo.create()
            }
            refresh()
        }
    }

    /** 当前会话的消息 + 正在流式的增量 */
    fun displayedMessages(): List<ChatRepo.Message> {
        val base = _current.value?.messages?.toList() ?: emptyList()
        val st = _streamText.value
        return if (st.isNotEmpty()) base + ChatRepo.Message("assistant", st) else base
    }

    /**
     * 发送消息。返回 false 表示未受理（内容为空 / 正在等回复），调用方据此决定
     * 是否清空输入框——避免发送被拒时把用户已输入的内容丢掉。
     */
    fun send(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _busy.value) return false
        if (!_busy.compareAndSet(expect = false, update = true)) return false
        _streaming.value = true
        _streamText.value = ""
        val token = ++streamToken
        viewModelScope.launch {
            // 会话可能尚未从磁盘恢复（窗口刚打开就发送）→ 这里补一次
            val session = _current.value ?: (repo.list().firstOrNull() ?: repo.create()).also {
                _current.value = it
            }
            val id = session.id
            if (session.messages.none { it.role == "user" }) {
                repo.rename(id, repo.deriveTitle(trimmed))
            }
            // 追加写（读-改-写）：不吃掉其它窗口写入的消息
            repo.appendMessage(id, ChatRepo.Message("user", trimmed))
            val fresh = repo.get(id) ?: session
            _current.value = fresh
            refresh()

            val cfg = SseClient.ChatCfg(
                baseUrl = config.chatBaseUrl(),
                chatPath = config.chatPath(),
                model = config.chatModel(),
                apiKey = config.chatApiKey(),
                temperature = config.chatTemperature(),
                maxTokens = config.chatMaxTokens(),
                timeoutSec = config.chatTimeout(),
                verifySsl = config.chatVerifySsl(),
            )
            val history = fresh.messages.takeLast(20).map { SseClient.Msg(it.role, it.content) }
            // 结束（正常 onDone / 出错 onError）都必须复位 streaming/busy，
            // 否则一次失败后界面永久停在"思考中…"且无法再发送。
            var finished = false
            fun finish() {
                if (finished || token != streamToken) return
                finished = true
                val full = _streamText.value
                _streamText.value = ""
                _streaming.value = false
                _busy.value = false
                viewModelScope.launch {
                    if (full.isNotBlank()) repo.appendMessage(id, ChatRepo.Message("assistant", full))
                    repo.get(id)?.let { _current.value = it }
                    refresh()
                }
            }
            streamJob = SseClient.stream(
                cfg = cfg,
                messages = history,
                onDelta = { if (token == streamToken) _streamText.value += it },
                onError = { err ->
                    if (token == streamToken) {
                        _streamText.value =
                            if (_streamText.value.isBlank()) "（错误）$err"
                            else _streamText.value + "\n\n（错误）$err"
                    }
                    finish()
                },
                onDone = { finish() },
            )
        }
        return true
    }

    fun stopStream() {
        streamToken++
        streamJob?.cancel()
        streamJob = null
        _streaming.value = false
        _busy.value = false
        val id = _current.value?.id
        val full = _streamText.value
        _streamText.value = ""
        if (id != null) {
            viewModelScope.launch {
                if (full.isNotBlank()) repo.appendMessage(id, ChatRepo.Message("assistant", full))
                repo.get(id)?.let { _current.value = it }
                refresh()
            }
        }
    }

    override fun onCleared() {
        // 流式任务挂在独立作用域，必须显式取消，否则界面关掉后仍会继续消耗请求
        streamToken++
        streamJob?.cancel()
        streamJob = null
        super.onCleared()
    }

    companion object {
        @Volatile
        private var shared: ChatViewModel? = null

        /** 进程级共享实例（全屏对话 / 悬浮对话 / 快捷气泡共用） */
        fun shared(app: Application): ChatViewModel =
            shared ?: synchronized(this) {
                shared ?: ChatViewModel(app).also { shared = it }
            }
    }
}
