# Hermes Android

A minimal WebView-based Android app that connects to your local Hermes AI agent gateway.

## Features
- Connects to local Hermes gateway (default: `http://192.168.1.100:18789`)
- Full WebView with JavaScript, DOM storage, cleartext traffic enabled
- Back button navigation support
- Material Design theme

## Building
Press **Run** on the GitHub Actions page to build, or:
```bash
./gradlew assembleDebug   # Debug APK
./gradlew assembleRelease # Release APK
```

APKs are uploaded as build artifacts.

## Configuration
Edit `app/src/main/java/com/hermes/android/MainActivity.kt` and change `gatewayUrl` to match your laptop's local IP and Hermes gateway port.
