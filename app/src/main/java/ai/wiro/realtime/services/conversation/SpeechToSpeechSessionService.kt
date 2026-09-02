package ai.wiro.realtime.services.conversation

import ai.wiro.realtime.app.ConversationConfig
import ai.wiro.realtime.app.ViewStatus
import ai.wiro.realtime.audio.AudioFailure
import ai.wiro.realtime.audio.AudioPreparationResult
import ai.wiro.realtime.audio.PcmAudioPlayer
import ai.wiro.realtime.audio.PcmAudioRecorder
import ai.wiro.realtime.audio.PcmCaptureProfile
import ai.wiro.realtime.audio.PcmNoiseGate
import ai.wiro.realtime.audio.RealtimeAudioProfile
import ai.wiro.realtime.audio.RealtimeAudioRouteWatcher
import ai.wiro.realtime.audio.RealtimeAudioSession
import ai.wiro.realtime.models.ModelDefinition
import ai.wiro.realtime.models.ModelParameterValues
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Runs a two-way speech conversation: microphone capture, the
 * realtime socket, assistant playback, and every way the session
 * can end.
 *
 * The service reports itself only through [snapshots] and never
 * touches view state.
 */
class SpeechToSpeechSessionService(
    val audioSession: RealtimeAudioSession,
    val apiClient: WiroApiClient,
    val sessionFactory: (String, String) -> RealtimeSessionClient,
    val recorder: PcmAudioRecorder,
    val player: PcmAudioPlayer,
    override val sessionGuard: RealtimeSessionGuard,
    override val model: ModelDefinition = ConversationConfig.model,
    persona: String = ConversationConfig.PERSONA,
    voice: String = ConversationConfig.VOICE,
    parameterOverrides: Map<String, String> = ConversationConfig.parameters,
    // Zero — no ceiling. The server already ends a quiet task;
    // this is only for tests that need a client cap.
    val maxSessionDuration: Duration = Duration.ZERO,
    val suspendGracePeriod: Duration = ConversationConfig.suspendGracePeriod,
    val reconnectTimeout: Duration = 4.seconds,
    val terminalTimeout: Duration = 3.seconds,
    val maxRetryAttempts: Int = 5,
    val retryBaseDelay: Duration = 500.milliseconds,
    val micStallTimeout: Duration = 3.seconds,
    val autoRestartDelay: Duration = 5.seconds,
    val maxAutoRestarts: Int = 2,
    val interruptHoldTimeout: Duration = 900.milliseconds,
    val interruptHoldCooldown: Duration = 1_500.milliseconds,
    val noiseGate: PcmNoiseGate? = null,
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default,
    ),
    nowMillis: () -> Long = { System.currentTimeMillis() },
) : ConversationSessionController {
    private val lock = Any()
    private val snapshotEvents = MutableSharedFlow<
        ConversationSessionSnapshot,
        >(
        replay = 1,
        extraBufferCapacity = 64,
    )
    private val taskLauncher = RealtimeTaskLauncher(apiClient)
    private val operationGuard = RealtimeOperationGuard()
    private val protocolLog = ProtocolEventLog()
    private val elapsedClock = SessionElapsedClock(scope = scope)
    private val playbackCoordinator = ConversationPlaybackCoordinator(
        player = player,
        nowMillis = nowMillis,
    )
    override val voiceActivity = ConversationVoiceActivity(
        scope = scope,
        nowMillis = nowMillis,
    )
    private val bargeIn: ConversationBargeIn
    private val micPipeline: ConversationMicPipeline
    private val recovery: ConversationRecoveryPolicy
    private val suspension: ConversationSuspension
    private val routeWatcher: RealtimeAudioRouteWatcher
    private val streams = Streams()
    private val pendingStop =
        AtomicReference<CompletableDeferred<Unit>?>(null)

    private var currentSnapshot = ConversationSessionSnapshot()
    private var storedParameters = ConversationConfig.parameterValues(
        model = model,
        persona = persona,
        voice = voice,
        overrides = parameterOverrides,
    )
    private var sessionLease: RealtimeSessionLease? = null
    private var session: RealtimeSessionClient? = null
    private var pcmJob: Job? = null
    private var terminalWait: TerminalWait? = null
    private var naturalCompletion: Job? = null
    private var startJob: Job? = null
    private var relaunchJob: Job? = null
    private var watchdogOperationId = 0
    private var naturalCompletionRunning = false

    override val snapshots: Flow<ConversationSessionSnapshot>
        get() = snapshotEvents.asSharedFlow()

    override val snapshot: ConversationSessionSnapshot
        get() = synchronized(lock) { currentSnapshot }

    override val parameters: ModelParameterValues
        get() = synchronized(lock) { storedParameters }

    init {
        snapshotEvents.tryEmit(currentSnapshot)
        bargeIn = ConversationBargeIn(
            playback = playbackCoordinator,
            voiceActivity = voiceActivity,
            onProtocolEvent = { appendProtocolEvent(it) },
            scope = scope,
            holdTimeout = interruptHoldTimeout,
            holdCooldown = interruptHoldCooldown,
            nowMillis = nowMillis,
        )
        micPipeline = ConversationMicPipeline(
            onStall = { streams.handleMicStall() },
            scope = scope,
            noiseGate = noiseGate,
            stallTimeout = micStallTimeout,
            nowMillis = nowMillis,
        )
        recovery = ConversationRecoveryPolicy(
            scope = scope,
            maxRetryAttempts = maxRetryAttempts,
            retryBaseDelay = retryBaseDelay,
            autoRestartDelay = autoRestartDelay,
            maxAutoRestarts = maxAutoRestarts,
        )
        suspension = ConversationSuspension(
            gracePeriod = suspendGracePeriod,
            onExpired = { onHoldExpired() },
            scope = scope,
            nowMillis = nowMillis,
        )
        routeWatcher = RealtimeAudioRouteWatcher(
            audioSession = audioSession,
            playbackFailures = player.failures,
            onShouldStop = { stopForAudioRouteSafety() },
            onRouteChanged = { handleRouteChange() },
            onPlaybackFailure = { handlePlayerFailure(it) },
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
                recovery.cancelAutoRestart()
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
     * The loading state is published before the wait so a second tap
     * cannot start twice.
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
            RealtimeSessionOwner.Conversation,
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
        recovery.resetRetries()
        startSession(isRetry = false)
    }

    private suspend fun startSession(isRetry: Boolean) {
        val operationId = operationGuard.begin()
        if (!isRetry) {
            protocolLog.clear()
        }
        publish(
            snapshot.copy(
                status = ViewStatus.Loading,
                sessionState = RealtimeSessionState.RequestingPermission,
                elapsed = if (isRetry) snapshot.elapsed else Duration.ZERO,
                userTranscript = if (isRetry) snapshot.userTranscript else "",
                aiTranscript = if (isRetry) snapshot.aiTranscript else "",
                microphoneActive = false,
                errorMessage = null,
                canOpenSystemSettings = false,
                protocolEvents = protocolLog.events,
            ),
        )
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
            val stream = recorder.start(PcmCaptureProfile.Conversation)
            if (!player.isPlaying) {
                player.start()
            }
            if (!operationGuard.isCurrent(operationId)) {
                recorder.stop()
                player.stop()
                session?.close()
                return
            }
            streams.listenForPcm(stream, operationId)
            streams.startElapsedTimer(operationId)
            publish(
                snapshot.copy(
                    status = ViewStatus.Success,
                    sessionState = RealtimeSessionState.Streaming,
                    microphoneActive = !snapshot.muted,
                    protocolEvents = protocolLog.add(
                        "conversation_stream_started",
                    ),
                ),
            )
            streams.startMicWatchdog(operationId)
        } catch (error: Exception) {
            if (error is CancellationException) {
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
        micPipeline.stopWatchdog()
        recovery.cancelAutoRestart()
        bargeIn.cancelHold()
        publish(
            snapshot.copy(
                status = ViewStatus.Loading,
                sessionState = RealtimeSessionState.Stopping,
                microphoneActive = false,
                isReconfiguring = false,
            ),
        )
        taskLauncher.cancelPendingRequest()
        elapsedClock.stop()
        streams.stopCapture()
        player.stop()
        playbackCoordinator.reset()
        voiceActivity.reset()
        noiseGate?.reset()
        naturalCompletion?.join()
        audioSession.deactivate()
        releaseLease()
        var shutdownTimedOut = false
        var endedCleanly = false
        val current = session
        if (current != null && !current.isClosed) {
            endedCleanly = true
            val shouldWait = current.isReady
            current.sendSessionEnd()
            val wait = terminalWait
            if (
                shouldWait &&
                wait != null &&
                !wait.isCompleted
            ) {
                shutdownTimedOut = !waitForTerminal()
            }
            current.close()
        }
        startJob?.join()
        session = null
        terminalWait = null
        naturalCompletion = null
        if (endedCleanly) {
            taskLauncher.release()
        } else {
            taskLauncher.terminateBestEffort()
        }
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
                microphoneActive = false,
                isSuspended = false,
                errorMessage = null,
                protocolEvents = protocolLog.add(event),
            ),
        )
    }

    override suspend fun changeVoice(voice: String) {
        if (operationGuard.isDisposed) {
            return
        }
        if (!snapshot.sessionState.isActive || snapshot.isReconfiguring) {
            relaunchJob?.join()
            return
        }
        if (parameters.value("voice") == voice) {
            return
        }
        setParameter("voice", voice)
        runRelaunch("voice_change")
    }

    override suspend fun suspend() {
        if (operationGuard.isDisposed) {
            return
        }
        recovery.cancelAutoRestart()
        if (snapshot.isSuspended || !snapshot.sessionState.isActive) {
            return
        }
        if (
            !suspension.holdsAnything ||
            startJob != null ||
            relaunchJob != null
        ) {
            stop()
            return
        }
        val operationId = operationGuard.begin()
        micPipeline.stopWatchdog()
        recovery.cancelAutoRestart()
        bargeIn.cancelHold()
        elapsedClock.stop()
        taskLauncher.cancelPendingRequest()
        streams.stopCapture()
        player.flush()
        player.stop()
        playbackCoordinator.reset()
        voiceActivity.reset()
        noiseGate?.reset()
        audioSession.deactivate()
        dropSocket()
        if (!operationGuard.isCurrent(operationId)) {
            return
        }
        releaseLease()
        suspension.begin()
        publish(
            snapshot.copy(
                microphoneActive = false,
                isSuspended = true,
                protocolEvents = protocolLog.add("conversation_held"),
            ),
        )
    }

    override suspend fun resume() {
        if (operationGuard.isDisposed || !snapshot.isSuspended) {
            return
        }
        suspension.cancel()
        if (suspension.hasLapsed) {
            endHeldSession("hold_expired")
            return
        }
        val lease = sessionGuard.acquireWhenFree(
            RealtimeSessionOwner.Conversation,
        )
        if (lease == null) {
            endHeldSession("hold_lost_to_another_session")
            return
        }
        if (!snapshot.isSuspended) {
            sessionGuard.release(lease)
            return
        }
        sessionLease = lease
        suspension.clear()
        runRelaunch("resume", reuseTask = true, prepareAudio = true)
    }

    override fun setMuted(muted: Boolean) {
        if (operationGuard.isDisposed) {
            return
        }
        recorder.setMuted(muted)
        if (muted) {
            voiceActivity.resetUser()
        }
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

    override fun interruptAssistant() {
        if (
            operationGuard.isDisposed ||
            snapshot.sessionState != RealtimeSessionState.Streaming
        ) {
            return
        }
        if (!voiceActivity.aiAudioPending) {
            return
        }
        if (playbackCoordinator.interruptTurn()) {
            bargeIn.cancelHold()
            voiceActivity.resetAi()
            appendProtocolEvent("assistant_silenced_by_user")
        }
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
        micPipeline.dispose()
        recovery.dispose()
        suspension.dispose()
        bargeIn.dispose()
        taskLauncher.cancelPendingRequest()
        elapsedClock.stop()
        disposeResources()
        voiceActivity.dispose()
    }

    private fun onHoldExpired() {
        if (operationGuard.isDisposed || !snapshot.isSuspended) {
            return
        }
        scope.launch { endHeldSession("hold_expired") }
    }

    private suspend fun endHeldSession(reason: String) {
        suspension.clear()
        appendProtocolEvent(reason)
        stop()
    }

    private suspend fun runRelaunch(
        reason: String,
        reuseTask: Boolean = false,
        prepareAudio: Boolean = false,
    ) {
        val job = scope.launch {
            relaunch(reason, reuseTask, prepareAudio)
        }
        relaunchJob = job
        job.join()
        if (relaunchJob == job) {
            relaunchJob = null
        }
    }

    /**
     * Rebuilds the live conversation in place, keeping the transcript
     * and the clock as they are.
     */
    private suspend fun relaunch(
        reason: String,
        reuseTask: Boolean,
        prepareAudio: Boolean,
    ) {
        val operationId = operationGuard.begin()
        val preservedUser = snapshot.userTranscript
        val preservedAi = snapshot.aiTranscript
        publish(
            snapshot.copy(
                status = ViewStatus.Loading,
                sessionState = RealtimeSessionState.CreatingTask,
                microphoneActive = false,
                isReconfiguring = true,
                isSuspended = false,
                protocolEvents = protocolLog.add("${reason}_started"),
            ),
        )
        try {
            streams.stopCapture()
            bargeIn.cancelHold()
            player.flush()
            playbackCoordinator.reset()
            voiceActivity.reset()
            noiseGate?.reset()
            if (prepareAudio && !prepareAudio(operationId)) {
                return
            }
            var reconnected = false
            if (reuseTask) {
                reconnected = reconnectToHeldTask(
                    operationId,
                    preservedUser,
                    preservedAi,
                )
            }
            if (!operationGuard.isCurrent(operationId)) {
                return
            }
            if (!reconnected) {
                closeActiveSessionForRelaunch()
                if (!operationGuard.isCurrent(operationId)) {
                    return
                }
                val runResult = launchTask(operationId) ?: return
                if (
                    !openSession(
                        runResult,
                        operationId,
                        preservedUser,
                        preservedAi,
                    )
                ) {
                    return
                }
            }
            val pcmStream = recorder.start(PcmCaptureProfile.Conversation)
            if (!operationGuard.isCurrent(operationId)) {
                recorder.stop()
                session?.close()
                return
            }
            if (!player.isPlaying) {
                player.start()
            }
            streams.listenForPcm(pcmStream, operationId)
            streams.startElapsedTimer(operationId)
            publish(
                snapshot.copy(
                    status = ViewStatus.Success,
                    sessionState = RealtimeSessionState.Streaming,
                    microphoneActive = !snapshot.muted,
                    isReconfiguring = false,
                    protocolEvents = protocolLog.add("${reason}_completed"),
                ),
            )
            streams.startMicWatchdog(operationId)
        } catch (error: Exception) {
            if (error is CancellationException) {
                throw error
            }
            if (operationGuard.isCurrent(operationId)) {
                fail(operationId, error)
            }
        }
    }

    private suspend fun launchTask(operationId: Int): WiroRunResult? = taskLauncher.launch(
        model = model,
        parameters = parameters.requestBody(),
        isStillWanted = { operationGuard.isCurrent(operationId) },
    )

    private suspend fun openSession(
        runResult: WiroRunResult,
        operationId: Int,
        userTranscript: String? = null,
        aiTranscript: String? = null,
    ): Boolean {
        val created = sessionFactory(runResult.taskId, runResult.taskToken)
        session = created
        terminalWait = TerminalWait()
        naturalCompletion = null
        naturalCompletionRunning = false
        playbackCoordinator.reset()
        created.addEventListener { event ->
            streams.handleEvent(event, operationId)
        }
        created.addErrorListener { error ->
            streams.handleEventError(error, operationId)
        }
        created.addDoneListener {
            streams.handleEventStreamDone(operationId)
        }
        publish(
            snapshot.copy(
                sessionState = RealtimeSessionState.ConnectingSocket,
                userTranscript = userTranscript ?: snapshot.userTranscript,
                aiTranscript = aiTranscript ?: snapshot.aiTranscript,
            ),
        )
        player.start()
        if (!operationGuard.isCurrent(operationId)) {
            player.stop()
            created.close()
            return false
        }
        if (operationGuard.isCurrent(operationId)) {
            publish(
                snapshot.copy(
                    sessionState = RealtimeSessionState.WaitingForStream,
                ),
            )
        }
        try {
            created.connect()
        } catch (error: Exception) {
            if (error is CancellationException) {
                throw error
            }
            player.stop()
            throw error
        }
        if (!operationGuard.isCurrent(operationId)) {
            player.stop()
            created.close()
            return false
        }
        return true
    }

    private suspend fun prepareAudio(operationId: Int): Boolean {
        val preparation = audioSession.prepare(
            RealtimeAudioProfile.Conversation,
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
                isReconfiguring = false,
                isSuspended = false,
                errorMessage = preparation.errorMessage,
                canOpenSystemSettings = preparation.needsSystemSettings,
            ),
        )
        return false
    }

    private suspend fun reconnectToHeldTask(
        operationId: Int,
        userTranscript: String,
        aiTranscript: String,
    ): Boolean {
        val heldTask = taskLauncher.currentRunResult ?: return false
        taskLauncher.cancelPendingRequest()
        dropSocket()
        if (!operationGuard.isCurrent(operationId)) {
            return false
        }
        appendProtocolEvent("reconnect_attempted")
        return try {
            val reconnected = withTimeoutOrNull(reconnectTimeout) {
                openSession(
                    heldTask,
                    operationId,
                    userTranscript,
                    aiTranscript,
                )
            }
            when (reconnected) {
                true -> {
                    appendProtocolEvent("reconnect_accepted")
                    true
                }

                false -> false

                null -> {
                    appendProtocolEvent("reconnect_refused")
                    dropSocket()
                    false
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) {
                throw error
            }
            appendProtocolEvent("reconnect_refused")
            dropSocket()
            false
        }
    }

    /**
     * Lets go of the socket without telling the server anything, so
     * the task on the other end stays alive and reconnectable.
     */
    private suspend fun dropSocket() {
        val current = session
        session = null
        terminalWait = null
        naturalCompletion = null
        naturalCompletionRunning = false
        current?.close()
    }

    private suspend fun closeActiveSessionForRelaunch() {
        taskLauncher.cancelPendingRequest()
        val current = session
        if (current != null && !current.isClosed) {
            current.sendSessionEnd()
        }
        dropSocket()
        taskLauncher.terminateBestEffort()
    }

    private suspend fun fail(operationId: Int, error: Throwable) {
        if (!operationGuard.isCurrent(operationId)) {
            return
        }
        val shouldRetry = recovery.shouldRetry(
            error,
            isReconfiguring = snapshot.isReconfiguring,
        )
        val isCancellation = (error as? WiroFailure)?.code ==
            WiroFailureCode.Cancelled
        val canReuseTask = shouldRetry &&
            taskLauncher.currentRunResult != null &&
            !isCancellation &&
            !(terminalWait?.isCompleted ?: false) &&
            snapshot.sessionState == RealtimeSessionState.Streaming
        val failureId = operationGuard.begin()
        micPipeline.stopWatchdog()
        bargeIn.cancelHold()
        if (!canReuseTask) {
            taskLauncher.terminateBestEffort()
        }
        taskLauncher.cancelPendingRequest()
        elapsedClock.stop()
        streams.stopCapture()
        player.stop()
        playbackCoordinator.reset()
        voiceActivity.reset()
        noiseGate?.reset()
        audioSession.deactivate()
        dropSocket()
        if (!operationGuard.isCurrent(failureId)) {
            return
        }
        if (shouldRetry) {
            recovery.noteRetryScheduled()
            publish(
                snapshot.copy(
                    status = ViewStatus.Loading,
                    sessionState = RealtimeSessionState.CreatingTask,
                    microphoneActive = false,
                    isReconfiguring = false,
                    errorMessage = null,
                    protocolEvents = protocolLog.add(
                        "retry_scheduled:${recovery.retryAttempt}/" +
                            "$maxRetryAttempts",
                    ),
                ),
            )
            delay(recovery.nextRetryDelay)
            if (!operationGuard.isCurrent(failureId)) {
                return
            }
            appendProtocolEvent("retry_started:${recovery.retryAttempt}")
            if (canReuseTask) {
                relaunch(
                    "reconnect",
                    reuseTask = true,
                    prepareAudio = true,
                )
            } else {
                startSession(isRetry = true)
            }
            return
        }
        releaseLease()
        publish(
            snapshot.copy(
                status = ViewStatus.Error,
                sessionState = RealtimeSessionState.Failure,
                microphoneActive = false,
                isReconfiguring = false,
                errorMessage = FailureMapper.message(error),
                canOpenSystemSettings = false,
                protocolEvents = protocolLog.add(
                    "failure:${FailureMapper.code(error)}",
                ),
            ),
        )
        if (recovery.isRecoverable(error)) {
            scheduleAutoRestart()
        }
    }

    private fun scheduleAutoRestart() {
        if (operationGuard.isDisposed) {
            return
        }
        val armedState = snapshot.sessionState
        val scheduled = recovery.scheduleAutoRestart {
            if (
                operationGuard.isDisposed ||
                snapshot.sessionState != armedState
            ) {
                return@scheduleAutoRestart
            }
            scope.launch { start() }
        }
        if (scheduled) {
            appendProtocolEvent("auto_restart_scheduled")
        }
    }

    private suspend fun disposeResources() {
        routeWatcher.dispose()
        streams.stopCapture()
        player.stop()
        audioSession.deactivate()
        session?.sendSessionEnd()
        taskLauncher.terminateBestEffort()
        session?.close()
        recorder.dispose()
        player.dispose()
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

    private fun handlePlayerFailure(failure: AudioFailure) {
        if (snapshot.sessionState.isActive) {
            scope.launch { fail(operationGuard.current, failure) }
        }
    }

    private fun releaseLease() {
        val lease = sessionLease
        sessionLease = null
        sessionGuard.release(lease)
    }

    private fun publish(next: ConversationSessionSnapshot) {
        synchronized(lock) {
            if (operationGuard.isDisposed || next == currentSnapshot) {
                return
            }
            currentSnapshot = next
        }
        snapshotEvents.tryEmit(next)
    }

    private fun appendProtocolEvent(event: String) {
        publish(snapshot.copy(protocolEvents = protocolLog.add(event)))
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

    private inner class Streams {
        fun listenForPcm(stream: Flow<ByteArray>, operationId: Int) {
            pcmJob?.cancel()
            pcmJob = scope.launch {
                try {
                    stream.collect { chunk -> sendPcm(chunk, operationId) }
                } catch (error: Exception) {
                    if (error is CancellationException) {
                        throw error
                    }
                    handleAudioError(error, operationId)
                }
            }
        }

        fun sendPcm(chunk: ByteArray, operationId: Int) {
            if (!operationGuard.isCurrent(operationId)) {
                return
            }
            val processed = micPipeline.process(
                chunk,
                assistantAudible = voiceActivity.aiAudioPending,
            )
            val sent = session?.sendAudio(processed.gated) ?: false
            if (!sent) {
                scope.launch {
                    fail(operationId, WiroFailure.SocketConnection)
                }
                return
            }
            recovery.noteHealthy()
            voiceActivity.addUserChunk(processed.gated, processed.level)
            bargeIn.evaluate()
        }

        fun handleEvent(event: RealtimeEvent, operationId: Int) {
            if (
                event is RealtimeEvent.Status &&
                (
                    event.type == RealtimeEventType.Ended ||
                        event.type == RealtimeEventType.PostprocessEnded
                    )
            ) {
                terminalWait?.complete()
            }
            if (!operationGuard.isCurrent(operationId)) {
                return
            }
            when (event) {
                is RealtimeEvent.Binary -> queueAudio(event.bytes, operationId)

                is RealtimeEvent.Output -> handleOutput(event.message)

                is RealtimeEvent.Status -> {
                    appendProtocolEvent(event.wireType)
                    when (event.type) {
                        RealtimeEventType.StreamEnded ->
                            finishAiTurn(operationId)

                        RealtimeEventType.PostprocessEnded ->
                            startNaturalCompletion(operationId)

                        else -> Unit
                    }
                }

                is RealtimeEvent.Error -> {
                    appendProtocolEvent(event.wireType)
                    startNaturalCompletion(operationId)
                }

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

                is RealtimeEvent.Unknown ->
                    appendProtocolEvent(event.wireType)
            }
        }

        fun queueAudio(bytes: ByteArray, operationId: Int) {
            if (!operationGuard.isCurrent(operationId)) {
                return
            }
            val outcome = playbackCoordinator.enqueue(bytes)
            if (outcome.silencedChunk || outcome.heldChunk) {
                return
            }
            if (outcome.flushedPreviousTurn) {
                voiceActivity.resetAi()
                appendProtocolEvent("previous_turn_dropped")
            }
            if (outcome.droppedChunk) {
                appendProtocolEvent("audio_chunk_dropped")
            } else {
                voiceActivity.addAiChunk(bytes)
            }
        }

        fun handleOutput(message: String) {
            when (val parsed = TranscriptParser.parse(message)) {
                is ConversationTranscript.User -> publish(
                    snapshot.copy(
                        userTranscript = parsed.text,
                        protocolEvents = protocolLog.add("task_output"),
                    ),
                )

                is ConversationTranscript.Ai -> publish(
                    snapshot.copy(
                        aiTranscript = parsed.text,
                        protocolEvents = protocolLog.add("task_output"),
                    ),
                )

                null -> appendProtocolEvent("task_output")
            }
        }

        fun finishAiTurn(operationId: Int) {
            if (!operationGuard.isCurrent(operationId)) {
                return
            }
            playbackCoordinator.markTurnFinished()
            publish(
                snapshot.copy(
                    sessionState = RealtimeSessionState.Streaming,
                    microphoneActive = !snapshot.muted,
                    protocolEvents = protocolLog.add("listening_resumed"),
                ),
            )
        }

        fun startNaturalCompletion(operationId: Int) {
            if (naturalCompletion == null) {
                naturalCompletion = scope.launch {
                    completeNaturally(operationId)
                }
            }
        }

        suspend fun completeNaturally(operationId: Int) {
            if (
                !operationGuard.isCurrent(operationId) ||
                naturalCompletionRunning
            ) {
                return
            }
            naturalCompletionRunning = true
            micPipeline.stopWatchdog()
            bargeIn.cancelHold()
            stopCapture()
            player.complete()
            player.stop()
            playbackCoordinator.reset()
            voiceActivity.reset()
            noiseGate?.reset()
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
                    isReconfiguring = false,
                ),
            )
            if (pendingStop.get() == null) {
                appendProtocolEvent("conversation_ended_remotely")
            }
        }

        fun handleEventError(error: Throwable, operationId: Int) {
            if (operationGuard.isCurrent(operationId)) {
                scope.launch { fail(operationId, error) }
            }
        }

        fun handleEventStreamDone(operationId: Int) {
            val terminalReceived = terminalWait?.isCompleted ?: false
            if (operationGuard.isCurrent(operationId) && !terminalReceived) {
                appendProtocolEvent("socket_closed_remotely")
                scope.launch {
                    fail(operationId, WiroFailure.SocketConnection)
                }
            }
        }

        fun handleAudioError(error: Throwable, operationId: Int) {
            if (operationGuard.isCurrent(operationId)) {
                scope.launch { fail(operationId, error) }
            }
        }

        suspend fun stopCapture() {
            pcmJob?.cancel()
            pcmJob = null
            recorder.stop()
        }

        fun startMicWatchdog(operationId: Int) {
            watchdogOperationId = operationId
            micPipeline.startWatchdog()
        }

        fun handleMicStall() {
            val operationId = watchdogOperationId
            if (
                !operationGuard.isCurrent(operationId) ||
                snapshot.sessionState != RealtimeSessionState.Streaming
            ) {
                return
            }
            if (snapshot.muted) {
                micPipeline.markAlive()
                return
            }
            micPipeline.stopWatchdog()
            appendProtocolEvent("microphone_stalled")
            scope.launch { fail(operationId, AudioFailure.Capture) }
        }

        fun startElapsedTimer(operationId: Int) {
            val base = snapshot.elapsed
            elapsedClock.start { elapsed ->
                if (!operationGuard.isCurrent(operationId)) {
                    return@start
                }
                val total = base + elapsed
                publish(snapshot.copy(elapsed = total))
                enforceSessionLimit(total)
            }
        }

        fun enforceSessionLimit(elapsed: Duration) {
            val limit = maxSessionDuration
            if (
                limit <= Duration.ZERO ||
                elapsed < limit ||
                pendingStop.get() != null
            ) {
                return
            }
            appendProtocolEvent("session_limit_reached")
            scope.launch { stop() }
        }
    }

    private companion object {
        val TranscriptParser = ConversationTranscriptParser()
        val FailureMapper = RealtimeFailureMapper(
            fallbackMessage =
            "The conversation session could not be completed.",
        )
    }
}
