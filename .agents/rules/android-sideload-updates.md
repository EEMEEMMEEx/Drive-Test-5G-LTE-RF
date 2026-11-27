---
trigger: model_decision
description: Android in-app update UI and Settings screen rules for sideloaded vs Google Play installs
---

# Android Sideload vs Play Store Update UI Rule

- Always gate all Google Play Store update UI (`"อัปเดตผ่าน Play Store"` button, Play download progress, `"ติดตั้งจาก Play ก่อนหนึ่งครั้ง..."`, and `PlayUpdateState.Unavailable` / fallback prompts) behind `PlayUpdateManager.isInstalledFromPlay(PlayUpdateManager.installerPackage(context))`.
- When installed via sideload (`!isFromPlay`):
  - Hide every Google Play Store prompt, button, and fallback message in `SettingsScreen` and dialogs.
  - Show only the GitHub release version check (`OtaUpdateManager.checkForUpdates` / `CHECK FOR UPDATES` / `CHECK AGAIN`).
