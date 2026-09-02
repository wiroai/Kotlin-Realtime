package ai.wiro.realtime.services.transcription

import ai.wiro.realtime.app.ViewStatus
import ai.wiro.realtime.audio.AudioPreparationResult
import ai.wiro.realtime.audio.PcmAudioRecorder
import ai.wiro.realtime.audio.PcmCaptureProfile
import ai.wiro.realtime.audio.RealtimeAudioProfile
import ai.wiro.realtime.audio.RealtimeAudioRouteWatcher
import ai.wiro.realtime.audio.RealtimeAudioSession
import ai.wiro.realtime.models.ModelDefinition
import ai.wiro.realtime.models.ModelParameterValues
import ai.wiro.realtime.models.WiroModelCatalog
import ai.wiro.realtime.models.WiroRunResult
import ai.wiro.realtime.networking.RealtimeTaskLauncher
import ai.wiro.realtime.networking.WiroApiClient
import ai.wiro.realtime.networking.WiroFailure
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
import ai.wiro.realtime.realtime.SessionElapsedClock
import ai.wiro.realtime.realtime.WiroRealtimeProtocol
import ai.wiro.realtime.services.TerminalWait
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs microphone capture and the realtime transcription session.
 *
 * The service reports itself only through [snapshots] and never
 * touches view state. There is no playback, barge-in, or reconnect:
 * leaving the tab ends the billed task.
 */
class SpeechToTextSessionService(
    val audioSession: RealtimeAudioSession,
    val apiClient: WiroApiClient,
    val sessionFactory: (String, String) -> RealtimeSessionClient,
    val recorder: PcmAudioRecorder,
    override val sessionGuard: RealtimeSessionGuard,
    override val model: ModelDefinition =
        WiroModelCatalog.voxtralMiniRealtime,
    val terminalTimeout: Duration = 3.seconds,
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default,
    ),
) : TranscriptionSessionController {
    private val lock = Any()
    private val snapshotEvents = MutableSharedFlow<
        TranscriptionSessionSnapshot,
        >(
        replay = 1,
        extraBufferCapacity = 64,
    )
    private val routeWatcher: RealtimeAudioRouteWatcher
    private val taskLauncher = RealtimeTaskLauncher(apiClient)
    private val operationGuard = RealtimeOperationGuard()
    private val protocolLog = ProtocolEventLog()
    private val elapsedClock = SessionElapsedClock()
    private val transcript = TranscriptAssembler()
    private val pendingStop =
        AtomicReference<CompletableDeferred<Unit>?>(null)

    private var currentSnapshot = TranscriptionSessionSnapshot()
    private var sessionLease: RealtimeSessionLease? = null
    private var session: RealtimeSessionClient? = null
    private var pcmJob: Job? = null
    private var terminalWait: TerminalWait? = null
    private var naturalCompletion: Job? = null
    private var startJob: Job? = null
    private var naturalCompletionRunning = false
    private var storedParameters = ModelParameterValues(
        fields = model.firstPassFields,
    )

    override val snapshots: Flow<TranscriptionSessionSnapshot>
        get() = snapshotEvents.asSharedFlow()

    override val snapshot: TranscriptionSessionSnapshot
        get() = synchronized(lock) { currentSnapshot }

    override val parameters: ModelParameterValues
        get() = synchronized(lock) { storedParameters }

    init {
        snapshotEvents.tryEmit(currentSnapshot)
        routeWatcher = RealtimeAudioRouteWatcher(
            audioSession = audioSession,
            playbackFailures = emptyFlow(),
            onShouldStop = { stopForAudioRouteSafety() },
            onRouteChanged = { handleRouteChange() },
            onPlaybackFailure = { },
            scope = scope,
        )
    }

    override suspend fun start() {
        if (operationGuard.isDisposed) {
            return
        }
        val job = synchronized(lock) {
            startJob ?: run {
                if (!snapshot.sessionState.canStart) {
                    return
                }
                scope.launch { acquireAndStart() }.also { startJob = it }
            }
        }
        job.join()
        synchronized(lock) {
            if (startJob === job) {
                startJob = null
            }
        }
    }

    /**
     * Waits out another feature's teardown before starting.
     *
     * Switching tabs ends the previous session, but its lease is
     * released a beat after the tap that lands here. Waiting turns
     * that race into a slightly longer connect instead of a busy
     * error. The loading state is published before the wait so a
     * second tap cannot start twice.
     */
    private suspend fun acquireAndStart() {
        val operationId = operationGuard.begin()
        publish(
            snapshot.copy(
                status = ViewStatus.Loading,
                sessionState = RealtimeSessionState.RequestingPermission,
                errorMessage = null,
            ),
        )
        val lease = sessionGuard.acquireWhenFree(
            RealtimeSessionOwner.Transcription,
        )
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
        startSession()
    }

    private suspend fun startSession() {
        val operationId = operationGuard.begin()
        protocolLog.clear()
        transcript.reset()
        publish(clearedLoadingSnapshot())
        recorder.setMuted(false)
        try {
            if (!prepareAudio(operationId)) {
                return
            }
            publish(
                snapshot.copy(
                    sessionState = RealtimeSessionState.PreparingAudio,
                ),
            )
            publish(
                snapshot.copy(
                    sessionState = RealtimeSessionState.CreatingTask,
                ),
            )
            val runResult = launchTask(operationId) ?: return
            if (!openSession(runResult, operationId)) {
                return
            }
            beginStreaming(operationId)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) {
                throw error
            }
            if (operationGuard.isCurrent(operationId)) {
                fail(operationId, error)
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

    private suspend fun performStop() {
        val operationId = operationGuard.begin()
        publish(
            snapshot.copy(
                status = ViewStatus.Loading,
                sessionState = RealtimeSessionState.Stopping,
                microphoneActive = false,
            ),
        )
        taskLauncher.cancelPendingRequest()
        elapsedClock.stop()
        stopCapture()
        naturalCompletion?.join()
        audioSession.deactivate()
        // Hardware is free; holding the lease through the terminal
        // wait would block the next feature for those seconds.
        releaseLease()
        val shutdownTimedOut = windDownSession()
        startJob?.join()
        session = null
        terminalWait = null
        naturalCompletion = null
        taskLauncher.release()
        if (!operationGuard.isCurrent(operationId)) {
            return
        }
        // A slow shutdown confirmation is not news the user can
        // act on. It stays in the protocol log as diagnostics.
        val event = if (shutdownTimedOut) {
            "shutdown_timeout"
        } else {
            "session_stopped"
        }
        publish(
            snapshot.copy(
                status = ViewStatus.Success,
                sessionState = RealtimeSessionState.Completed,
                microphoneActive = false,
                errorMessage = null,
                protocolEvents = protocolLog.add(event),
            ),
        )
    }

    override fun setMuted(muted: Boolean) {
        if (operationGuard.isDisposed) {
            return
        }
        recorder.setMuted(muted)
        publish(
            snapshot.copy(
                microphoneActive = snapshot.sessionState ==
                    RealtimeSessionState.Streaming && !muted,
                muted = muted,
            ),
        )
    }

    override fun setParameter(id: String, value: String) {
        if (operationGuard.isDisposed) {
            return
        }
        synchronized(lock) {
            storedParameters = storedParameters.withValue(id, value)
        }
    }

    override fun clearTranscript() {
        if (operationGuard.isDisposed || snapshot.sessionState.isActive) {
            return
        }
        transcript.reset()
        publish(snapshot.copy(transcript = ""))
    }

    override suspend fun openSystemSettings() {
        if (operationGuard.isDisposed) {
            return
        }
        if (!audioSession.openSystemSettings()) {
            appendProtocolEvent("system_settings_unavailable")
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

    private suspend fun launchTask(operationId: Int): WiroRunResult? = taskLauncher.launch(
        model = model,
        parameters = parameters.requestBody(),
        isStillWanted = { operationGuard.isCurrent(operationId) },
    )

    private suspend fun openSession(
        runResult: WiroRunResult,
        operationId: Int,
    ): Boolean {
        val created = sessionFactory(runResult.taskId, runResult.taskToken)
        session = created
        terminalWait = TerminalWait()
        naturalCompletion = null
        naturalCompletionRunning = false
        created.addEventListener { event ->
            handleEvent(event, operationId)
        }
        created.addErrorListener { error ->
            failLater(operationId, error)
        }
        created.addDoneListener {
            handleEventStreamDone(operationId)
        }
        publish(
            snapshot.copy(
                sessionState = RealtimeSessionState.ConnectingSocket,
            ),
        )
        val connection = scope.async { created.connect() }
        if (operationGuard.isCurrent(operationId)) {
            publish(
                snapshot.copy(
                    sessionState = RealtimeSessionState.WaitingForStream,
                ),
            )
        }
        connection.await()
        if (!operationGuard.isCurrent(operationId)) {
            created.close()
            return false
        }
        return true
    }

    private suspend fun beginStreaming(operationId: Int) {
        val stream = recorder.start(PcmCaptureProfile.Transcription)
        if (!operationGuard.isCurrent(operationId)) {
            recorder.stop()
            session?.close()
            return
        }
        listenForPcm(stream, operationId)
        startElapsedTimer(operationId)
        publish(
            snapshot.copy(
                status = ViewStatus.Success,
                sessionState = RealtimeSessionState.Streaming,
                microphoneActive = true,
                protocolEvents = protocolLog.add(
                    "microphone_stream_started",
                ),
            ),
        )
    }

    private suspend fun prepareAudio(operationId: Int): Boolean {
        val preparation = audioSession.prepare(
            RealtimeAudioProfile.Transcription,
        )
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
                microphoneActive = false,
                errorMessage = preparation.errorMessage,
                canOpenSystemSettings = preparation.needsSystemSettings,
            ),
        )
        return false
    }

    private fun publish(next: TranscriptionSessionSnapshot) {
        synchronized(lock) {
            if (operationGuard.isDisposed || next == currentSnapshot) {
                return
            }
            currentSnapshot = next
        }
        snapshotEvents.tryEmit(next)
    }

    private fun listenForPcm(
        stream: Flow<ByteArray>,
        operationId: Int,
    ) {
        pcmJob?.cancel()
        pcmJob = scope.launch {
            try {
                stream.collect { chunk -> sendPcm(chunk, operationId) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) {
                    throw error
                }
                failLater(operationId, error)
            }
        }
    }

    private fun sendPcm(chunk: ByteArray, operationId: Int) {
        if (!operationGuard.isCurrent(operationId)) {
            return
        }
        val sent = session?.sendAudio(chunk) ?: false
        if (!sent) {
            scope.launch { fail(operationId, WiroFailure.SocketConnection) }
        }
    }

    private fun handleEvent(event: RealtimeEvent, operationId: Int) {
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
            is RealtimeEvent.Output -> handleOutput(event.message)

            is RealtimeEvent.Status -> handleStatus(event, operationId)

            is RealtimeEvent.Error -> appendProtocolEvent(event.wireType)

            RealtimeEvent.Cancel -> {
                appendProtocolEvent(event.wireType)
                scope.launch { fail(operationId, WiroFailure.Cancelled) }
            }

            RealtimeEvent.Malformed,
            is RealtimeEvent.Binary,
            -> {
                appendProtocolEvent(event.wireType)
                scope.launch {
                    fail(operationId, WiroFailure.RealtimeProtocol)
                }
            }

            is RealtimeEvent.Unknown -> appendProtocolEvent(event.wireType)
        }
    }

    private fun handleStatus(
        event: RealtimeEvent.Status,
        operationId: Int,
    ) {
        appendProtocolEvent(event.wireType)
        when (event.type) {
            RealtimeEventType.StreamEnded -> {
                scope.launch { handleStreamEnded(operationId) }
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

    private fun handleOutput(message: String) {
        val prefix = WiroRealtimeProtocol.TRANSCRIPT_USER_PREFIX
        if (!message.startsWith(prefix)) {
            appendProtocolEvent("task_output")
            return
        }
        val joined = transcript.applyUserText(
            message.removePrefix(prefix),
        )
        if (joined == null) {
            appendProtocolEvent("task_output")
            return
        }
        publish(
            snapshot.copy(
                transcript = joined,
                protocolEvents = protocolLog.add("task_output"),
            ),
        )
    }

    private suspend fun handleStreamEnded(operationId: Int) {
        stopCapture()
        audioSession.deactivate()
        if (!operationGuard.isCurrent(operationId)) {
            return
        }
        publish(
            snapshot.copy(
                sessionState = RealtimeSessionState.Stopping,
                microphoneActive = false,
            ),
        )
    }

    private suspend fun completeNaturally(operationId: Int) {
        if (
            !operationGuard.isCurrent(operationId) ||
            naturalCompletionRunning
        ) {
            return
        }
        naturalCompletionRunning = true
        stopCapture()
        audioSession.deactivate()
        if (!operationGuard.isCurrent(operationId)) {
            naturalCompletionRunning = false
            return
        }
        val completionId = operationGuard.begin()
        elapsedClock.stop()
        session?.close()
        session = null
        terminalWait = null
        naturalCompletion = null
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
                microphoneActive = false,
            ),
        )
    }

    private fun failLater(operationId: Int, error: Throwable) {
        if (operationGuard.isCurrent(operationId)) {
            scope.launch { fail(operationId, error) }
        }
    }

    private fun handleEventStreamDone(operationId: Int) {
        val terminalReceived = terminalWait?.isCompleted ?: false
        if (operationGuard.isCurrent(operationId) && !terminalReceived) {
            scope.launch { fail(operationId, WiroFailure.SocketConnection) }
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
        stopCapture()
        audioSession.deactivate()
        session?.close()
        session = null
        terminalWait = null
        naturalCompletion = null
        naturalCompletionRunning = false
        releaseLease()
        if (!operationGuard.isCurrent(failureId)) {
            return
        }
        publish(
            snapshot.copy(
                status = ViewStatus.Error,
                sessionState = RealtimeSessionState.Failure,
                microphoneActive = false,
                errorMessage = FailureMapper.message(error),
                protocolEvents = protocolLog.add(
                    "failure:${FailureMapper.code(error)}",
                ),
                revealProtocolPanel = true,
            ),
        )
    }

    private suspend fun stopCapture() {
        pcmJob?.cancel()
        pcmJob = null
        recorder.stop()
    }

    private suspend fun disposeResources() {
        routeWatcher.dispose()
        stopCapture()
        audioSession.deactivate()
        session?.sendSessionEnd()
        taskLauncher.terminateBestEffort()
        session?.close()
        recorder.dispose()
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

    private fun clearedLoadingSnapshot(): TranscriptionSessionSnapshot = snapshot.copy(
        status = ViewStatus.Loading,
        sessionState = RealtimeSessionState.RequestingPermission,
        elapsed = Duration.ZERO,
        transcript = "",
        microphoneActive = false,
        muted = false,
        errorMessage = null,
        canOpenSystemSettings = false,
        protocolEvents = protocolLog.events,
        revealProtocolPanel = false,
    )

    private companion object {
        val FailureMapper = RealtimeFailureMapper(
            fallbackMessage =
            "The transcription session could not be completed.",
        )
    }
}
