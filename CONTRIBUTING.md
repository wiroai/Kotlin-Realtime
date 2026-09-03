# Contributing

Thanks for taking the time. This is a small, opinionated example
app; the bar for a change is that it keeps the example small and
opinionated.

## Before you start

- Read the [README](README.md) end to end. "How a conversation
  runs" and "Project layout" are the architecture document: the
  layer rules, the chrome rules, and the reasons behind the session
  lifecycle. Pull requests that contradict them will be asked to
  change.
- Open an issue first for anything that adds a screen, a
  dependency, or a new layer. Bug fixes and test improvements can go
  straight to a pull request.

## Setup

```sh
cp secrets.properties.example secrets.properties
```

The test suite does not talk to Wiro; any non-empty `WIRO_API_KEY`
(in `secrets.properties` or the environment) satisfies the build.
A physical Android phone is needed only to hear audio — the
emulator has no usable microphone path.

## Running the checks

```sh
WIRO_API_KEY=test ./gradlew spotlessCheck detekt :app:testDebugUnitTest
```

CI runs the same command. `spotlessApply` fixes formatting in
place; `spotlessCheck` only reports it.

## What a good change looks like

- Root layers (`models/`, `networking/`, `auth/`, `realtime/`,
  `audio/`) never import Compose, a screen, or a screen service.
  Services never touch view state. Screens never import each other;
  only Home mounts the three destinations.
- Anything that waits on time takes a clock or a dispatcher and is
  tested with `runTest` and virtual time, not with `delay` in the
  test.
- Comments explain why, never what. No comment restates the line
  below it.
- 80 columns, trailing commas, Material 3 typography and spacing.
  No spacing / radius / shadow tokens for chrome.
- Tests live in `app/src/test` mirroring `app/src/main`. Reuse the
  fakes under each `support/` package before writing a new one.

## Commit messages

One change per commit, imperative subject under 72 characters, and
a body that says why when the diff does not.

## Reporting a problem

Include the Android version, the device, the model slug from
`ConversationConfig`, and the Logcat output around the failure. A
recording of what you heard is worth more than a paragraph about
it. Never paste an API key or a task token.
