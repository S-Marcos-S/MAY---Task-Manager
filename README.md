# MAY - Task Manager (Make Android Yours)

A powerful, Windows-inspired Task Manager and real-time telemetry diagnostics hub for Android, built with Jetpack Compose and Material 3 Expressive.

## Features
- **Process Manager**: Full process list (Foreground, Background, System) with real-time CPU, RAM, and Power Impact metrics.
- **Deep Process Inspector**: Inspect app PIDs, native threads, RSS/PSS/Swap memory breakdowns, open network sockets, background services, and permissions.
- **Root Control**: Superuser-powered force stop, SIGKILL, renice priority, OOM score adjustment, and cache cleaner.
- **Telemetry & Performance**: Real-time graphs for CPU frequency/usage across all cores, RAM & zRAM, GPU load, network speed (rx/tx), and thermal sensors.
- **Background Anomaly Watcher**: Real-time monitoring and alerts for apps causing CPU spikes, RAM abuse, or excessive wakeups.
- **Live Terminal & Logcat**: Live logcat viewer, regex filter engine, crash detection, and floating overlay window.
- **Clean Material 3 UI**: Polished glassmorphism styling, dark/light theme support, and responsive layouts.

## Architecture & Requirements
- **Android SDK**: Min SDK 26 (Android 8.0+), Target SDK 35 (Android 15).
- **Permissions**: Root (Magisk / KernelSU / APatch / SuperSU) recommended for advanced system inspection, process control, and kernel telemetry.
- **Build**: Automated via GitHub Actions.
