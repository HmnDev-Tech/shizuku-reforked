# Shizuku Reforked - Initial Release

## 🚀 Key Features

*   **Premium UI**: Completely redesigned manager using Jetpack Compose and Material 3 Expressive.
*   **Floating Navigation**: Modern bottom bar for quick access to Modules, Logs, and Settings.
*   **ADB Module Engine**: Install and run shell-based modules with ZIP support.
*   **Watchdog Service**: Automatic background recovery for Shizuku service (Root mode).
*   **Dhizuku Integration**: Experimental support for binding via Dhizuku (Device Owner).
*   **TCP Mode**: Enhanced networking options for local ADB connections.
*   **Log Scanner**: Safe log reading with OOM protection and multi-byte character support.

## 🛠️ Technical Fixes

*   Fixed JavaScript bridge minification (ProGuard keep rules).
*   Fixed OOM vulnerability when reading large module logs.
*   Fixed multi-byte UTF-8 character corruption in stream tails.
*   Optimized window insets for edge-to-edge transparency.

## 📦 Distribution
This is an independent fork based on the upstream Shizuku project.