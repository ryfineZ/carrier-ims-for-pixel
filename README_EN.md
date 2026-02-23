# Carrier IMS for Pixel (TurboIMS)

<p align="center">
  <img src="app/src/main/ic_launcher-playstore.png" width="128" alt="Carrier IMS logo" />
</p>

<p align="center">
  <strong>Carrier and IMS toolkit for Google Pixel</strong><br/>
  Tune VoLTE / VoWiFi / VoNR, 5G display behavior, and network compatibility with Shizuku privileges.
</p>

<p align="center">
  <a href="README.md">中文（默认）</a> | English
</p>

<p align="center">
  <a href="https://github.com/ryfineZ/carrier-ims-for-pixel/releases"><img alt="Release" src="https://img.shields.io/github/v/release/ryfineZ/carrier-ims-for-pixel"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/ryfineZ/carrier-ims-for-pixel"></a>
  <img alt="Platform" src="https://img.shields.io/badge/Platform-Android%2013%2B-3DDC84">
  <img alt="Device" src="https://img.shields.io/badge/Device-Pixel%20Tensor-blue">
  <img alt="Permission" src="https://img.shields.io/badge/Requires-Shizuku-orange">
</p>

## Repository Migration Note

- Repository has been renamed from `ryfineZ/TurboIMS` to `ryfineZ/carrier-ims-for-pixel`.
- Use `3.8.5` or later. In-app actions (`Check Update / Submit Issue / Open Repo`) now point to the new repository.
- If an old build fails to update or open issue links, install manually from the new Releases page.

## Positioning

This project is a continuously maintained branch based on [Mystery00/TurboIMS](https://github.com/Mystery00/TurboIMS), with major usability and compatibility improvements for Mainland China and cross-region use cases.

Recent improvements include:

- network diagnostics with side-by-side App vs CarrierConfig readback
- denser card layout and tighter spacing for faster operation

## Screenshots

<p align="center">
  <img src="docs/Screenshot1.png" width="46%" alt="screenshot-1" />
  <img src="docs/Screenshot2.png" width="46%" alt="screenshot-2" />
</p>

## Feature Matrix

| Module | Capability | Notes |
|---|---|---|
| System Info | app/device/patch/Shizuku status | quick environment visibility |
| IMS Registration | status query + manual register | one-tap register workflow |
| Carrier Features | VoLTE / VoWiFi / ViLTE / VoNR / UT / Cross-SIM | realtime switches, rollback on failure |
| 5G Features | 5G NR / 5G signal threshold / 5G+ icon | optimized for common CN scenarios |
| Network Fix | captive portal one-tap fix | fixes restricted/exclamation network states |
| TikTok Fix | no-network fix for TikTok (Mainland SIM) | shown only for Mainland SIM |
| Diagnostics | logs / full config view / issue shortcut | submit issues with useful context |
| In-app Update | check, download, install updates | integrated with GitHub Releases |

## Quick Start

1. Download APK from [Releases](https://github.com/ryfineZ/carrier-ims-for-pixel/releases)
2. Install and start [Shizuku](https://shizuku.rikka.app/)
3. Open app and grant Shizuku permission
4. Select SIM and toggle required features

## Requirements

- Pixel Tensor devices (Pixel 6/7/8/9/10, Fold, Tablet)
- Android 13+
- Shizuku running and authorized

## Build (Developers)

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If local signing is required, configure `local.properties`:

```properties
SIGN_KEY_STORE_FILE=/path/to/your.keystore
SIGN_KEY_STORE_PASSWORD=***
SIGN_KEY_ALIAS=***
SIGN_KEY_PASSWORD=***
```

## FAQ

### IMS not registered

- confirm Shizuku is ready
- verify VoLTE / VoWiFi availability
- collect logs and submit an issue

### Network has signal but no internet

- check APN first
- then try the network verification fix card

### TikTok still unavailable

- TikTok fix switch only appears for Mainland SIM
- restart target app or refresh its session after changes

### "Check update / Submit issue" fails on old versions

- The repository has been renamed to `ryfineZ/carrier-ims-for-pixel`; old links may fail in some clients or network conditions.
- Upgrade to `3.8.5` or later. New versions use the new repository endpoints and include a legacy API fallback for update checks.
- If in-app update fails on an old build, download and install APK manually from the new Releases page.

### Why "country code modification" was removed and replaced by "TikTok one-tap fix"

- The old "country code" flow only wrote CarrierConfig override `sim_country_iso_override_string`; it did not truly modify baseband-level MCC/MNC.
- Real network identity values (for example `gsm.operator.numeric` and registered MCC/MNC) are usually not changed by this override, so it is not a stable or universal "change country code" method.
- In practice, TikTok availability was not determined by "switching to another country", but by setting ISO to an abnormal value, which could trigger an app-side identification fallback path and bypass part of SIM-region checks.
- Based on this actual behavior, the project changed the entry to a clearer switch: "Fix TikTok No Network", to avoid implying that the app can truly rewrite carrier identity.
- This behavior depends on target app versions and risk-control policy, and may change over time. It is provided for compatibility troubleshooting and testing only.

## Changelog

- Full changelog: [CHANGELOG.md](CHANGELOG.md)
- Releases: [GitHub Releases](https://github.com/ryfineZ/carrier-ims-for-pixel/releases)

## Credits

- [Mystery00/TurboIMS](https://github.com/Mystery00/TurboIMS)
- [vvb2060/Ims](https://github.com/vvb2060/Ims)
- [kyujin-cho/pixel-volte-patch](https://github.com/kyujin-cho/pixel-volte-patch)
- [nullbytepl/CarrierVanityName](https://github.com/nullbytepl/CarrierVanityName)

## Disclaimer

This app modifies carrier-related system configuration for learning, testing, and personal tuning purposes. Use at your own risk.

## License

Apache-2.0
