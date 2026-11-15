# Development

## Requirements

- JDK 17
- Android SDK for compile SDK 36
- Android platform tools for device installation

## Common checks

```sh
./gradlew :app:testStableDebugUnitTest
./gradlew :app:testNightlyDebugUnitTest
./gradlew :app:assemblePlayStableDebug :app:assemblePlayNightlyDebug
```

Build and install a Nightly debug APK with the project wrapper:

```sh
./scripts/build-nightly-debug.sh 26.09 --install
```

The Kotlin namespace is `rocks.pkb.plektra`. The four app identities and their update behaviour are defined in `app/build.gradle.kts`.

## Testing on a real phone (fork only)

[qa/README.md](../qa/README.md) ([Deutsch](../qa/README.de.md)) is a hardware-in-the-loop harness: a person types on the phone's keyboard while the computer prepares each field, says what to type, and scores the result.
