<div align="center">

<img src="https://raw.githubusercontent.com/wiroai/Kotlin-Realtime/main/.github/assets/wiro-logo.png" alt="Wiro AI" width="240">

# Wiro Realtime for Kotlin: Voice AI Example for Android

Realtime voice AI for Android in Kotlin and Jetpack Compose: live conversation, speech-to-text and text-to-speech with the Wiro AI API.

[![Kotlin 2.4](https://img.shields.io/badge/Kotlin-2.4-111111?style=for-the-badge&color=111111&logo=kotlin&logoColor=white)](https://kotlinlang.org) [![Android API 26+](https://img.shields.io/badge/Android-API%2026%2B-111111?style=for-the-badge&color=111111&logo=android&logoColor=white)](#requirements) [![JDK 17](https://img.shields.io/badge/JDK-17-111111?style=for-the-badge&color=111111&logo=openjdk&logoColor=white)](#requirements) [![License: MIT](https://img.shields.io/badge/License-MIT-111111?style=for-the-badge&color=111111)](LICENSE)

[Website](https://wiro.ai) · [Models](https://wiro.ai/models) · [Docs](https://wiro.ai/docs/realtime-voice-conversation) · [Dashboard](https://wiro.ai/panel)

</div>

Wiro Realtime for Kotlin is the official open-source example for
building realtime voice AI into an Android app with Kotlin, Jetpack
Compose and the Wiro AI API. Its three tabs stream audio over
WebSocket for a live speech-to-speech conversation with barge-in,
live speech-to-text from the microphone, and text-to-speech playback.
The auth, networking, realtime and audio layers have no Compose
dependency, so you can lift them into your own app.

<p align="center">
  <img src="docs/screenshots/conversation-idle.png" alt="Conversation screen of the Android app before a call, with a Start conversation button" width="220">
  <img src="docs/screenshots/transcription.png" alt="Live transcription with a timer, the wavy level line and the transcript text" width="220">
</p>

## What's inside

Three realtime audio flows, each a tab in the app, each backed by a
live Wiro AI model over one WebSocket protocol:

| Flow | Model | What it does |
| --- | --- | --- |
| Conversation | [`openai/gpt-realtime-mini`](https://wiro.ai/models/openai/gpt-realtime-mini) | Full-duplex voice chat with barge-in |
| Transcription | [`mistralai/voxtral-mini-4b-realtime-2602`](https://wiro.ai/models/mistralai/voxtral-mini-4b-realtime-2602) | Live speech-to-text as you talk |
| Speech | [`openmoss/moss-tts-realtime`](https://wiro.ai/models/openmoss/moss-tts-realtime) or [`openbmb/voxcpm2`](https://wiro.ai/models/openbmb/voxcpm2) | Streaming text-to-speech playback |

The chrome is Material 3 Expressive, all system components:
`Scaffold`, `TopAppBar`, `NavigationBar`, a floating toolbar for
the live call controls, a wavy progress line driven by the real
microphone level, chat-bubble transcripts, and the Expressive
loading shape while connecting. Light and dark Wiro themes follow
the system. There is no custom orb, wave, or tab bar.

## Features

- Live speech-to-speech conversation with voice activity detection,
  barge-in, and a noise gate
- Realtime microphone transcription with streaming partial results
- Streaming text-to-speech with bounded PCM playback
- One `AudioRecord` / `AudioTrack` graph for capture and playback,
  so the platform echo canceller (AEC) actually works
- Signed REST Run requests and the full Wiro AI realtime WebSocket
  protocol
- Bounded capture/playback queues, safe PCM overflow shedding, audio
  route and interruption handling
- Cross-feature guard so two tabs never hold the microphone at once
- Automatic reconnect to the same task on socket drops, with a
  bounded restart budget
- Session lifecycle covered by JVM unit tests and fakes, so no
  device is needed to verify the plumbing

## Requirements

- JDK 17
- Android Studio (or the Android SDK) with compileSdk 37
- A **physical** Android phone on API 26 or later (the emulator
  microphone is not a usable voice path)
- A [Wiro AI project and API key](https://wiro.ai/panel/project/new)

## Run

Copy the credential template and set the key:

```sh
cp secrets.properties.example secrets.properties
```

Edit `secrets.properties`:

```
WIRO_API_KEY=YOUR_API_KEY
WIRO_API_SECRET=
```

`WIRO_API_SECRET` is only for signature-based Wiro AI projects. Leave
it empty for API-key-only projects. The secrets file is gitignored.

Then open the project in Android Studio, pick a physical phone, and
run the `app` configuration. Or:

```sh
./gradlew :app:installDebug
```

Alternatively, leave `secrets.properties` empty and set
`WIRO_API_KEY` (and optionally `WIRO_API_SECRET`) in the process
environment when you assemble. Environment values win over the
file.

Launch fails fast when `WIRO_API_KEY` is missing.

> **Credentials warning:** values from `secrets.properties` or the
> environment are compiled into the app binary via `BuildConfig`, in
> debug and release builds alike, and are for local runs only. Never
> distribute builds containing real credentials: production apps
> should authenticate through a controlled backend or a short-lived
> credential flow.

## Make it yours

[`app/src/main/java/ai/wiro/realtime/app/ConversationConfig.kt`](app/src/main/java/ai/wiro/realtime/app/ConversationConfig.kt)
holds the persona, the voice, and the model.
Editing that one file is the whole of turning this example into a
product.

```kotlin
const val PERSONA = "...who the assistant is..."
const val VOICE = "marin"
```

Common changes:

- **Give the assistant a job**: rewrite `PERSONA`.
- **Change the voice**: set `VOICE` to any option the model catalog
  lists.
- **Tune the engineering parameters**: `parameters` overrides the
  catalog defaults for turn detection, audio formats, and rates.
- **Ship only the conversation**: delete
  `app/src/main/java/ai/wiro/realtime/ui/transcription/`,
  `app/src/main/java/ai/wiro/realtime/ui/speech/`,
  `app/src/main/java/ai/wiro/realtime/services/transcription/`,
  and
  `app/src/main/java/ai/wiro/realtime/services/speech/`
  (plus their `app/src/test` mirrors), then remove the two
  destinations from
  `app/src/main/java/ai/wiro/realtime/ui/home/RealtimeHomeView.kt`.
  Shared layers under `models/`, `networking/`, `auth/`,
  `realtime/`, and `audio/` are untouched.

## How a conversation runs

```text
REST:      https://api.wiro.ai/v1
WebSocket: wss://socket.wiro.ai/v1
Run:       POST /Run/{owner}/{model}
```

1. `POST /Run/{owner}/{model}` returns a task id and a task token.
2. A WebSocket opens and subscribes with
   `{ "type": "task_info", "tasktoken": "..." }`.
3. After `task_stream_ready`, 20 ms PCM chunks stream up as
   `UTF8(tasktoken + "|") + pcm_bytes`; audio and transcripts stream
   back.
4. `task_session_end` closes the session. The app sends it on leave,
   or the server sends it when the task goes quiet.

Audio is signed 16-bit PCM, 24 kHz, mono, little-endian on both
sides.

Authentication is `x-api-key` alone, or `x-api-key` + `x-nonce` +
`x-signature` where the signature is
`HMAC-SHA256(key = API_KEY, message = API_SECRET + NONCE)`.

## Project layout

```text
app/src/main/java/ai/wiro/realtime/
  app/            entry config, ConversationConfig, theme
  models/         catalog, parameters, run result
  networking/     HTTP, endpoints, Run launcher
  auth/           credentials, nonce, headers
  realtime/       socket, session, framing
  audio/          PCM, graph, session
  services/       Conversation / Transcription / Speech
  ui/             home / conversation / transcription / speech / shared
app/src/test/java/ai/wiro/realtime/
                  mirrors the app; support/ holds fakes
secrets.properties.example   credential template (copy locally)
```

`models/`, `networking/`, `auth/`, `realtime/`, and `audio/` have
no dependency on Compose or any screen service and cover the whole
Wiro AI integration, so they are the part worth lifting into your
own app.

## Tests

```sh
WIRO_API_KEY=test ./gradlew spotlessApply detekt \
  :app:testDebugUnitTest
```

Tests mirror `ai.wiro.realtime` and ship fakes for audio, network,
and the socket, so the entire session lifecycle runs without a
device. CI runs `spotlessCheck`, `detekt`, and the unit tests on
pushes to `main` and on pull requests.

## Contributing

Read [`CONTRIBUTING.md`](CONTRIBUTING.md) before opening a pull
request. To report a security problem, follow
[`SECURITY.md`](SECURITY.md) instead of opening a public issue.

## Documentation

- [Realtime voice guide](https://wiro.ai/docs/realtime-voice-conversation)
- [Wiro AI documentation](https://wiro.ai/docs)
- [Available models](https://wiro.ai/models)
- Same example on other platforms:
  [Swift](https://github.com/wiroai/Swift-Realtime) ·
  [Flutter](https://github.com/wiroai/Dart-Realtime) ·
  [React Native](https://github.com/wiroai/ReactNative-Realtime)
- [Kotlin-QuickStart](https://github.com/wiroai/Kotlin-QuickStart):
  WiroKit, the Wiro AI SDK for Android

## More from Wiro AI

| Repository | What it is |
| --- | --- |
| [Wiro-MCP](https://github.com/wiroai/Wiro-MCP) | Official MCP server for Wiro AI (`@wiro-ai/wiro-mcp`) |
| [Wiro-Plugin](https://github.com/wiroai/Wiro-Plugin) | Wiro AI plugin for Cursor, Claude Code, Gemini CLI and Devin |
| [Swift-QuickStart](https://github.com/wiroai/Swift-QuickStart) | WiroKit, the Wiro AI SDK for iOS |
| [Kotlin-QuickStart](https://github.com/wiroai/Kotlin-QuickStart) | WiroKit, the Wiro AI SDK for Android |
| [Dart-QuickStart](https://github.com/wiroai/Dart-QuickStart) | Wiro AI SDK for Dart and Flutter (`wiro` on pub.dev) |
| [ReactNative-QuickStart](https://github.com/wiroai/ReactNative-QuickStart) | Wiro AI SDK for React Native and Expo (`@wiro-ai/wiro-react-native`) |
| [Swift-Realtime](https://github.com/wiroai/Swift-Realtime) | Realtime voice AI example for iOS |
| [Dart-Realtime](https://github.com/wiroai/Dart-Realtime) | Realtime voice AI example for Flutter |
| [ReactNative-Realtime](https://github.com/wiroai/ReactNative-Realtime) | Realtime voice AI example for React Native |
| [n8n-nodes-wiroai](https://github.com/wiroai/n8n-nodes-wiroai) | Wiro AI nodes for n8n |
| [Wiro-Docs](https://github.com/wiroai/Wiro-Docs) | Source of the Wiro AI API docs |

[Website](https://wiro.ai) · [Models](https://wiro.ai/models) · [Docs](https://wiro.ai/docs) · [X](https://x.com/wiroai)

## License

[MIT](LICENSE)

---

<div align="center">

<img src="https://wiro.ai/images/koala/accent-heavy-koala.png" alt="Wiro AI koala mascot" width="80" />

**Built by the Wiro AI team**

[wiro.ai](https://wiro.ai) · [GitHub @wiroai](https://github.com/wiroai)

</div>
