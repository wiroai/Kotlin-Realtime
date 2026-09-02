package ai.wiro.realtime.services.speech

import ai.wiro.realtime.app.ViewStatus
import ai.wiro.realtime.audio.AudioFailure
import ai.wiro.realtime.audio.AudioPreparationResult
import ai.wiro.realtime.audio.PcmAudioPlayer
import ai.wiro.realtime.audio.RealtimeAudioProfile
import ai.wiro.realtime.audio.RealtimeAudioRouteWatcher
import ai.wiro.realtime.audio.RealtimeAudioSession
import ai.wiro.realtime.models.ModelDefinition
import ai.wiro.realtime.models.ModelParameterValues
import ai.wiro.realtime.models.RealtimeDirection
import ai.wiro.realtime.models.WiroModelCatalog
import ai.wiro.realtime.models.WiroRunResult
import ai.wiro.realtime.networking.RealtimeTaskLauncher
import ai.wiro.realtime.networking.WiroApiClient
import ai.wiro.realtime.networking.WiroFailure
import ai.wiro.realtime.networking.WiroFailureCode
import ai.wiro.realtime.realtime.ProtocolEventLog
import ai.wiro.realtime.realtime.RealtimeEvent
import ai.wiro.realtime.realtime.RealtimeEventType
import ai.wiro.realtime.realtime.RealtimeFailureMapper
import ai.wiro.realtime.realtime.RealtimeOperationGuard
import ai.wiro.realtime.realtime.RealtimeSessionClient
import ai.wiro.realtime.realtime.RealtimeSessionGuard
import ai.wiro.realtime.realtime.RealtimeSessionLease
import ai.wiro.realtime.realtime.RealtimeSessionOwner
import ai.wiro.realtime.realtime.RealtimeSessionState
import ai.wiro.realtime.realtime.RealtimeTrace
import ai.wiro.realtime.realtime.SessionElapsedClock
import ai.wiro.realtime.services.TerminalWait
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Owns task creation, realtime audio playback, and session
 * termination.
 *
 * The service reports itself only through [snapshots]. A TTS model
 * can spin up cold and drop the subscriber socket; the session
 * resubscribes to the task it already has instead of launching a
 * second billed run.
 */
class TextToSpeechSessionService(
    val audioSession: RealtimeAudioSession,
    val apiClient: WiroApiClient,
    val sessionFactory: (String, String) -> RealtimeSessionClient,
    val player: PcmAudioPlayer,
    override val sessionGuard: RealtimeSessionGuard,
    model: ModelDefinition = WiroModelCatalog.mossTtsRealtime,
    val terminalTimeout: Duration = 3.seconds,
    val connectRetryDelay: Duration = 4.seconds,
    val maxConnectAttempts: Int = 8,
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default,
    ),
) : SpeechSessionController {
    private val lock = Any()
    private val snapshotEvents = MutableSharedFlow<SpeechSessionSnapshot>(
        replay = 1,
        extraBufferCapacity = 64,
    )
    private val routeWatcher: RealtimeAudioRouteWatcher
    private val taskLauncher = RealtimeTaskLauncher(apiClient)
    private val operationGuard = RealtimeOperationGuard()
    private val protocolLog = ProtocolEventLog()
    private val elapsedClock = SessionElapsedClock()
    private val pendingStop =
        AtomicReference<CompletableDeferred<Unit>?>(null)

    private var selectedModel: ModelDefinition = model
    private var currentSnapshot = SpeechSessionSnapshot(selectedModel = model)
    private var storedParameters = ModelParameterValues(
        fields = model.firstPassFields,
    )
    private var sessionLease: RealtimeSessionLease? = null
    private var session: RealtimeSessionClient? = null
    private var terminalWait: TerminalWait? = null
    private var playbackCompletion: Job? = null
    private var naturalCompletion: Job? = null
    private var startJob: Job? = null
    private var naturalCompletionRunning = false
    private var connectingSession = false
    private var listenerGeneration = 0

    override val snapshots: Flow<SpeechSessionSnapshot>
        get() = snapshotEvents.asSharedFlow()

    override val snapshot: SpeechSessionSnapshot
        get() = synchronized(lock) { currentSnapshot }

    override val parameters: ModelParameterValues
        get() = synchronized(lock) { storedParameters }

    override var prompt: String
        get() = parameters.value(promptFieldId()).orEmpty()
        set(value) {
            synchronized(lock) {
                storedParameters = storedParameters.withValue(
                    promptFieldId(),
                    value,
                )
            }
        }

    init {
        snapshotEvents.tryEmit(currentSnapshot)
        routeWatcher = RealtimeAudioRouteWatcher(
            audioSession = audioSession,
            playbackFailures = player.failures,
            onShouldStop = { stopForAudioRouteSafety() },
            onRouteChanged = { handleRouteChange() },
            onPlaybackFailure = { handlePlayerFailure(it) },
            scope = scope,
        )
    }

    override fun selectModel(model: ModelDefinition) {
        if (
            operationGuard.isDisposed ||
            snapshot.sessionState.isActive ||
            model.direction != RealtimeDirection.TextToSpeech
        ) {
            return
        }
        selectedModel = model
        synchronized(lock) {
            storedParameters = ModelParameterValues(
                fields = model.firstPassFields,
            )
        }
        publish(snapshot.copy(selectedModel = model, errorMessage = null))
    }

    override fun setParameter(id: String, value: String) {
        if (operationGuard.isDisposed) {
            return
        }
        synchronized(lock) {
            storedParameters = storedParameters.withValue(id, value)
        }
    }

    override suspend fun start() {
        if (operationGuard.isDisposed) {
            return
        }
        val trimmed = prompt.trim()
        val job = synchronized(lock) {
            startJob ?: run {
                if (!snapshot.sessionState.canStart) {
                    return
                }
                if (trimmed.isEmpty()) {
                    return@synchronized null
                }
                scope.launch { acquireAndStart(trimmed) }.also { startJob = it }
            }
        }
        if (job == null) {
            publish(
                snapshot.copy(
                    status = ViewStatus.Error,
                    sessionState = RealtimeSessionState.Failure,
                    errorMessage = ENTER_TEXT,
                ),
            )
            return
        }
        job.join()
        synchronized(lock) {
            if (startJob === job) {
                startJob = null
            }
        }
    }

    override suspend fun stop() {
        if (operationGuard.isDisposed) {
            return
        }
        val existing = pendingStop.get()
        if (existing != null) {
            existing.await()
            return
        }
        if (!snapshot.sessionState.canStop) {
            return
        }
        val created = CompletableDeferred<Unit>()
        if (!pendingStop.compareAndSet(null, created)) {
            pendingStop.get()?.await()
            return
        }
        try {
            performStop()
            created.complete(Unit)
        } finally {
            pendingStop.compareAndSet(created, null)
        }
    }

    override suspend fun dispose() {
        if (operationGuard.isDisposed) {
            return
        }
        operationGuard.dispose()
        taskLauncher.cancelPendingRequest()
        elapsedClock.stop()
        disposeResources()
    }

    private suspend fun acquireAndStart(text: String) {
        val operationId = operationGuard.begin()
        publish(
            snapshot.copy(
                status = ViewStatus.Loading,
                sessionState = RealtimeSessionState.PreparingAudio,
                errorMessage = null,
            ),
        )
        val lease = sessionGuard.acquireWhenFree(RealtimeSessionOwner.Speech)
        if (!operationGuard.isCurrent(operationId)) {
            sessionGuard.release(lease)
            return
        }
        if (lease == null) {
            publish(
                snapshot.copy(
                    status = ViewStatus.Error,
                    sessionState = RealtimeSessionState.Failure,
                    errorMessage = WiroFailure.SessionBusy.message,
                ),
            )
            return
        }
        sessionLease = lease
        RealtimeTrace.line("speech start")
        startSession(text)
    }

    private suspend fun startSession(text: String) {
        val operationId = operationGuard.begin()
        protocolLog.clear()
        publish(clearedLoadingSnapshot())
        try {
            if (!prepareAudio(operationId)) {
                return
            }
            publish(
                snapshot.copy(
                    sessionState = RealtimeSessionState.CreatingTask,
                ),
            )
            val runResult = launchTask(text, operationId)
            if (runResult == null) {
                if (operationGuard.isCurrent(operationId)) {
                    publish(
                        snapshot.copy(
                            status = ViewStatus.Initial,
                            sessionState = RealtimeSessionState.Idle,
                        ),
                    )
                }
                return
            }
            val connected = connectToTask(runResult, operationId)
            if (connected == null || !operationGuard.isCurrent(operationId)) {
                player.stop()
                connected?.close()
                return
            }
            startElapsedTimer(operationId)
            publish(
                snapshot.copy(
                    status = ViewStatus.Success,
                    sessionState = RealtimeSessionState.Streaming,
                    protocolEvents = protocolLog.add(
                        "playback_stream_started",
                    ),
                ),
            )
        } catch (error: Exception) {
            if (error is CancellationException) {
                throw error
            }
            if (operationGuard.isCurrent(operationId)) {
                fail(operationId, error)
            }
        }
    }

    private suspend fun connectToTask(
        runResult: WiroRunResult,
        operationId: Int,
    ): RealtimeSessionClient? {
        var attempt = 0
        while (true) {
            attempt += 1
            val created = attachSession(runResult, operationId)
            player.start()
            RealtimeTrace.line("player listening")
            if (!operationGuard.isCurrent(operationId)) {
                player.stop()
                created.close()
                return null
            }
            val error = attemptConnect(created, operationId)
            if (error == null) {
                RealtimeTrace.line("socket ready")
                return created
            }
            created.close()
            session = null
            if (!canRetry(error, attempt, operationId)) {
                throw error
            }
            appendProtocolEvent("socket_reconnect:$attempt")
            if (connectRetryDelay > Duration.ZERO) {
                delay(connectRetryDelay)
            }
            if (!operationGuard.isCurrent(operationId)) {
                return null
            }
        }
    }

    private fun attachSession(
        runResult: WiroRunResult,
        operationId: Int,
    ): RealtimeSessionClient {
        listenerGeneration += 1
        val generation = listenerGeneration
        val created = sessionFactory(runResult.taskId, runResult.taskToken)
        session = created
        terminalWait = TerminalWait()
        playbackCompletion = null
        naturalCompletion = null
        naturalCompletionRunning = false
        connectingSession = true
        created.addEventListener { event ->
            handleEvent(event, operationId, generation)
        }
        created.addErrorListener { error ->
            failLater(error, operationId, generation)
        }
        created.addDoneListener {
            handleEventStreamDone(operationId, generation)
        }
        publish(
            snapshot.copy(
                sessionState = RealtimeSessionState.ConnectingSocket,
            ),
        )
        return created
    }

    private suspend fun attemptConnect(
        created: RealtimeSessionClient,
        operationId: Int,
    ): WiroFailure? {
        if (operationGuard.isCurrent(operationId)) {
            publish(
                snapshot.copy(
                    sessionState = RealtimeSessionState.WaitingForStream,
                ),
            )
        }
        RealtimeTrace.line("socket attempt")
        connectingSession = true
        return try {
            created.connect()
            null
        } catch (error: WiroFailure) {
            error
        } finally {
            connectingSession = false
        }
    }

    private fun canRetry(
        error: WiroFailure,
        attempt: Int,
        operationId: Int,
    ): Boolean {
        val recoverable = error.code == WiroFailureCode.SocketConnection ||
            error.code == WiroFailureCode.SocketConnectTimeout
        return recoverable &&
            attempt < maxConnectAttempts &&
            operationGuard.isCurrent(operationId)
    }

    private suspend fun performStop() {
        RealtimeTrace.line("speech stop")
        val operationId = operationGuard.begin()
        publish(
            snapshot.copy(
                status = ViewStatus.Loading,
                sessionState = RealtimeSessionState.Stopping,
                isPlaying = false,
            ),
        )
        taskLauncher.cancelPendingRequest()
        elapsedClock.stop()
        player.stop()
        naturalCompletion?.join()
        audioSession.deactivate()
        releaseLease()
        val shutdownTimedOut = windDownSession()
        startJob?.join()
        clearSessionReferences()
        taskLauncher.release()
        if (!operationGuard.isCurrent(operationId)) {
            return
        }
        val event = if (shutdownTimedOut) {
            "shutdown_timeout"
        } else {
            "session_stopped"
        }
        publish(
            snapshot.copy(
                status = ViewStatus.Success,
                sessionState = RealtimeSessionState.Completed,
                isPlaying = false,
                errorMessage = null,
                protocolEvents = protocolLog.add(event),
            ),
        )
    }

    private suspend fun launchTask(
        text: String,
        operationId: Int,
    ): WiroRunResult? {
        val body = parameters.requestBody().toMutableMap()
        body[promptFieldId()] = text
        return taskLauncher.launch(
            model = selectedModel,
            parameters = body,
            isStillWanted = { operationGuard.isCurrent(operationId) },
        )
    }

    private suspend fun prepareAudio(operationId: Int): Boolean {
        val preparation = audioSession.prepare(RealtimeAudioProfile.Speech)
        if (!operationGuard.isCurrent(operationId)) {
            audioSession.deactivate()
            return false
        }
        if (preparation == AudioPreparationResult.Ready) {
            return true
        }
        audioSession.deactivate()
        releaseLease()
        publish(
            snapshot.copy(
                status = ViewStatus.Error,
                sessionState = RealtimeSessionState.Failure,
                errorMessage = preparation.errorMessage,
            ),
        )
        return false
    }

    private fun handleEvent(
        event: RealtimeEvent,
        operationId: Int,
        generation: Int,
    ) {
        if (generation != listenerGeneration) {
            return
        }
        if (
            event is RealtimeEvent.Status &&
            event.type == RealtimeEventType.PostprocessEnded
        ) {
            terminalWait?.complete()
        }
        if (!operationGuard.isCurrent(operationId)) {
            return
        }
        when (event) {
            is RealtimeEvent.Binary -> queueAudio(event.bytes, operationId)

            is RealtimeEvent.Status -> handleStatus(event, operationId)

            is RealtimeEvent.Error,
            is RealtimeEvent.Output,
            is RealtimeEvent.Unknown,
            -> appendProtocolEvent(event.wireType)

            RealtimeEvent.Cancel -> {
                appendProtocolEvent(event.wireType)
                scope.launch { fail(operationId, WiroFailure.Cancelled) }
            }

            RealtimeEvent.Malformed -> {
                appendProtocolEvent(event.wireType)
                scope.launch {
                    fail(operationId, WiroFailure.RealtimeProtocol)
                }
            }
        }
    }

    private fun handleStatus(
        event: RealtimeEvent.Status,
        operationId: Int,
    ) {
        appendProtocolEvent(event.wireType)
        when (event.type) {
            RealtimeEventType.StreamEnded -> {
                publish(
                    snapshot.copy(
                        sessionState = RealtimeSessionState.Stopping,
                    ),
                )
                if (playbackCompletion == null) {
                    playbackCompletion = scope.launch {
                        finishPlayback(operationId)
                    }
                }
            }

            RealtimeEventType.PostprocessEnded -> {
                if (naturalCompletion == null) {
                    naturalCompletion = scope.launch {
                        completeNaturally(operationId)
                    }
                }
            }

            else -> Unit
        }
    }

    private fun queueAudio(bytes: ByteArray, operationId: Int) {
        if (!operationGuard.isCurrent(operationId)) {
            return
        }
        if (!snapshot.isPlaying) {
            publish(snapshot.copy(isPlaying = true))
        }
        if (!player.tryEnqueue(bytes)) {
            appendProtocolEvent("audio_chunk_dropped")
        }
    }

    private suspend fun finishPlayback(operationId: Int) {
        if (!operationGuard.isCurrent(operationId)) {
            return
        }
        player.complete()
        if (operationGuard.isCurrent(operationId)) {
            publish(snapshot.copy(isPlaying = false))
        }
    }

    private suspend fun completeNaturally(operationId: Int) {
        if (
            !operationGuard.isCurrent(operationId) ||
            naturalCompletionRunning
        ) {
            return
        }
        naturalCompletionRunning = true
        if (playbackCompletion == null) {
            playbackCompletion = scope.launch { finishPlayback(operationId) }
        }
        playbackCompletion?.join()
        if (!operationGuard.isCurrent(operationId)) {
            naturalCompletionRunning = false
            return
        }
        val completionId = operationGuard.begin()
        elapsedClock.stop()
        audioSession.deactivate()
        session?.close()
        clearSessionReferences()
        naturalCompletionRunning = false
        taskLauncher.release()
        releaseLease()
        if (!operationGuard.isCurrent(completionId)) {
            return
        }
        publish(
            snapshot.copy(
                status = ViewStatus.Success,
                sessionState = RealtimeSessionState.Completed,
                isPlaying = false,
            ),
        )
    }

    private fun failLater(
        error: Throwable,
        operationId: Int,
        generation: Int,
    ) {
        if (generation != listenerGeneration || connectingSession) {
            return
        }
        if (operationGuard.isCurrent(operationId)) {
            scope.launch { fail(operationId, error) }
        }
    }

    private fun handleEventStreamDone(operationId: Int, generation: Int) {
        if (generation != listenerGeneration || connectingSession) {
            return
        }
        val terminalReceived = terminalWait?.isCompleted ?: false
        if (operationGuard.isCurrent(operationId) && !terminalReceived) {
            scope.launch { fail(operationId, WiroFailure.SocketConnection) }
        }
    }

    private fun handlePlayerFailure(failure: AudioFailure) {
        if (snapshot.sessionState.isActive) {
            scope.launch { fail(operationGuard.current, failure) }
        }
    }

    private suspend fun fail(operationId: Int, error: Throwable) {
        if (!operationGuard.isCurrent(operationId)) {
            return
        }
        val failureId = operationGuard.begin()
        taskLauncher.cancelPendingRequest()
        taskLauncher.terminateBestEffort()
        elapsedClock.stop()
        player.stop()
        audioSession.deactivate()
        session?.close()
        clearSessionReferences()
        naturalCompletionRunning = false
        releaseLease()
        if (!operationGuard.isCurrent(failureId)) {
            return
        }
        publish(
            snapshot.copy(
                status = ViewStatus.Error,
                sessionState = RealtimeSessionState.Failure,
                isPlaying = false,
                errorMessage = FailureMapper.message(error),
                protocolEvents = protocolLog.add(
                    "failure:${FailureMapper.code(error)}",
                ),
                revealProtocolPanel = true,
            ),
        )
    }

    private fun publish(next: SpeechSessionSnapshot) {
        synchronized(lock) {
            if (operationGuard.isDisposed || next == currentSnapshot) {
                return
            }
            currentSnapshot = next
        }
        snapshotEvents.tryEmit(next)
    }

    private fun clearSessionReferences() {
        session = null
        terminalWait = null
        playbackCompletion = null
        naturalCompletion = null
    }

    private suspend fun disposeResources() {
        routeWatcher.dispose()
        player.stop()
        audioSession.deactivate()
        session?.sendSessionEnd()
        taskLauncher.terminateBestEffort()
        session?.close()
        player.dispose()
        clearSessionReferences()
        releaseLease()
    }

    private fun stopForAudioRouteSafety() {
        if (snapshot.sessionState.canStop) {
            scope.launch { stop() }
        }
    }

    private fun handleRouteChange() {
        if (snapshot.sessionState.isActive) {
            appendProtocolEvent("audio_route_changed")
        }
    }

    private fun releaseLease() {
        val lease = sessionLease
        sessionLease = null
        sessionGuard.release(lease)
    }

    private fun startElapsedTimer(operationId: Int) {
        elapsedClock.start { elapsed ->
            if (operationGuard.isCurrent(operationId)) {
                publish(snapshot.copy(elapsed = elapsed))
            }
        }
    }

    private fun appendProtocolEvent(event: String) {
        publish(snapshot.copy(protocolEvents = protocolLog.add(event)))
    }

    private suspend fun windDownSession(): Boolean {
        val current = session ?: return false
        if (current.isClosed) {
            return false
        }
        val shouldWait = current.isReady
        current.sendSessionEnd()
        val wait = terminalWait
        val timedOut = if (
            shouldWait &&
            wait != null &&
            !wait.isCompleted
        ) {
            !waitForTerminal()
        } else {
            false
        }
        current.close()
        return timedOut
    }

    private suspend fun waitForTerminal(): Boolean {
        val wait = terminalWait ?: return true
        if (terminalTimeout <= Duration.ZERO) {
            return wait.isCompleted
        }
        return withTimeoutOrNull(terminalTimeout) {
            wait.wait()
            true
        } ?: false
    }

    private fun clearedLoadingSnapshot(): SpeechSessionSnapshot = snapshot.copy(
        status = ViewStatus.Loading,
        sessionState = RealtimeSessionState.PreparingAudio,
        elapsed = Duration.ZERO,
        isPlaying = false,
        errorMessage = null,
        protocolEvents = protocolLog.events,
        revealProtocolPanel = false,
    )

    private fun promptFieldId(): String = SpeechFields.promptFieldId(selectedModel)

    private companion object {
        const val ENTER_TEXT: String = "Enter text to synthesize."
        val FailureMapper = RealtimeFailureMapper(
            fallbackMessage = "The speech session could not be completed.",
        )
    }
}
