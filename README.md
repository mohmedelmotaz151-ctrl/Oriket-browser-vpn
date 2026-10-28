# Oriket Browser VPN

Native Android browser project built with Kotlin and Jetpack Compose.

## Identity
- App name: Oriket Browser VPN
- Application ID: `com.orkeit.browservpn`
- Brand palette: navy, red, white
- Arabic-first interface

## Current design foundation
- Browser home screen and address/search bar
- WebView-based browsing
- Quick links, bookmarks, browsing history and downloads screen
- Private-mode UI and tab counter
- VPN status entry point that clearly explains VPN is not connected yet

## Important VPN status
A real VPN requires a VPN provider or server configuration and Android's `VpnService` integration. This starter UI does not claim to provide an active VPN connection. Do not market VPN protection as active until the service is implemented and tested.

## Build
Open this repository in Android Studio, use JDK 17, allow Gradle sync, then build the debug APK from **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
