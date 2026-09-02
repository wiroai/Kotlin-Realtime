package ai.wiro.realtime.support

import ai.wiro.realtime.audio.MicrophonePermission
import ai.wiro.realtime.audio.MicrophonePermissionStatus
import ai.wiro.realtime.audio.SystemSettingsLink

class FakeMicrophonePermission(
    var result: MicrophonePermissionStatus =
        MicrophonePermissionStatus.Granted,
) : MicrophonePermission {
    var requestCount: Int = 0

    override suspend fun request(): MicrophonePermissionStatus {
        requestCount += 1
        return result
    }
}

class FakeSystemSettingsLink : SystemSettingsLink {
    var openCount: Int = 0

    override fun open() {
        openCount += 1
    }
}
