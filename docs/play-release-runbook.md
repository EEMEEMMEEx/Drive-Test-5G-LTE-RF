# Runbook: แจกจ่ายผ่าน Google Play (internal testing) + ย้ายจาก sideload

สรุป: เส้นทางใหม่คือ `AAB` → Play Console internal testing → tester ติดตั้งจาก Play → ค่อย promote ห้ามแจก APK เองอีก (นั่นคือสาเหตุของ Play Protect "Harmful app blocked" และของ OTA ที่ติดตั้งไม่ได้)
**จุดที่พลาดบ่อยที่สุด:** การเปลี่ยนจาก APK ที่ติดตั้งด้วยมือ (sideload) ไปเป็น APK ที่ Play เซ็นให้ **ต้องถอนการติดตั้งตัวเก่าก่อน** เพราะ signature ต่างกัน และการถอนจะลบ session ทั้งหมดที่เก็บใน internal storage → ต้อง export ก่อน (§3)

## 0. สิ่งที่ต้องมีก่อนเริ่ม

- Play Console account (ยืนยันตัวตนแล้ว) และสิทธิ์ในแอป `com.drivetest.lte5g`
- ถ้า package นี้เคยถูก upload มาก่อน: upload key ต้องเป็นตัวเดิมตลอด (Play ปฏิเสธ AAB ที่ signed ด้วย debug key — ต้องเห็นข้อความ "signed in debug mode" ถ้าเจอ = ผิดขั้นตอน)
- `RELEASE_KEYSTORE_PATH`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` ใน `local.properties` หรือ environment — ถ้าไม่ครบ build จะ fallback ไป debug signing (บล็อก `signingConfigs` ใน `android-native/app/build.gradle.kts`) และ **ห้าม upload AAB นั้น**

## 1. Build AAB

```powershell
cd android-native
.\gradlew.bat clean :app:bundleRelease
Test-Path .\app\build\outputs\bundle\release\app-release.aab
```
- ตรวจว่า build log **ไม่มี** บรรทัด `Release keystore not configured; falling back to debug signing.`
- `RELEASE_KEYSTORE_PATH` ต้องชี้ไปที่ **ไฟล์ keystore ที่มีอยู่จริง** — ถ้าตั้งค่าไว้แต่ไฟล์ไม่มี build จะไม่ error หนัก แต่จะถือว่า "ยังไม่ได้ตั้งค่า" แล้ว fallback ไป debug signing เงียบ ๆ (กรณีนี้เกิดในเครื่อง dev ที่ยังไม่มี keystore) ให้ `Test-Path` ไฟล์ก่อน build
- ตรวจว่าไม่มี token ในไฟล์ (ตาม `docs/security-token-incident.md` §4)
- `versionCode` ต้องมากกว่าที่เคย upload (ปัจจุบัน 46; รอบนี้จะ bump เป็น 47) — Play ปฏิเสธ versionCode ซ้ำหรือถอยหลัง

ทดสอบ AAB ในเครื่องก่อน upload (ถ้าต้องการ):
```
bundletool build-apks --bundle=app-release.aab --output=app.apks --local-testing
bundletool install-apks --apks=app.apks
```

## 2. Upload เข้า internal testing

1. https://play.google.com/console → เลือกแอป (ถ้ายังไม่มี: `Create app` → ชื่อ `Drive Test LTE 5G`, ภาษาเริ่มต้น, ระบุเป็น App, Free)
2. กรอกสิ่งที่ Play บังคับก่อนปล่อย release: `App content` → privacy policy URL, Data safety, Content rating, Target audience, Ads declaration
3. เมนู `Test and release` → `Testing` → `Internal testing`
4. แท็บ `Releases` → `Create new release`
5. `App bundles` → `Upload` → เลือก `app-release.aab` → รอ processing ผ่าน (ถ้า error เรื่อง signature/debug key ให้กลับไป §0)
6. `Release name` (เช่น `1.13.3 (47)`) และ `Release notes` → `Next` → `Save` → `Review release` → `Start rollout to Internal testing`
7. ปกติ internal testing ไม่ต้องรอ review นาน (ยังไม่ยืนยันเวลาที่แน่นอน) — สถานะจะขึ้น `Available to internal testers`

## 3. ย้ายจาก sideload ไป Play install (สำคัญ — ทำตามลำดับ ห้ามข้าม)

ความจริงที่ทำให้ต้องถอนก่อน: APK ที่แจกอยู่นี้เซ็นด้วย debug key หรือ keystore ของเราเอง (บล็อก `signingConfigs` ใน `android-native/app/build.gradle.kts`) ส่วน Play เซ็นด้วย key ที่ Google ถือ (`Play App Signing`) → `adb install -r` หรือติดตั้งทับจะได้ `INSTALL_FAILED_UPDATE_INCOMPATIBLE` ทางเดียวคือถอนตัวเก่า

**สิ่งที่ผู้ใช้จะเสียเมื่อถอน:** session ทั้งหมดซึ่งอยู่ใน internal storage — `SessionRepository.kt:61` เก็บไว้ที่ `File(context.filesDir, "sessions")` ซึ่งถูกลบพร้อม app data รวมถึง settings/preferences ของแอป ไม่เกี่ยวกับไฟล์ที่ export ไปแล้วหรือไฟล์ใน `Download/`

1. **Export ก่อน (ต่อ session ที่ต้องการเก็บ)**: เปิดแอป → Campaigns → เลือก campaign → export เป็น `CSV` และ/หรือ `KML`/`KMZ` → เลือกปลายทาง `Files`/`Drive` (ไม่ใช่ cache)
2. **ตรวจว่าไฟล์อยู่จริง**: เปิด `Files` app → ดูว่ามีไฟล์ `.csv`/`.kml` ครบทุก campaign ที่ export และขนาดไม่ใช่ 0 KB
3. **จดค่าที่ตั้งไว้**: RSRP threshold, sampling cadence, SIM selection, Drive/Firebase settings — ค่าเหล่านี้จะหาย
4. **ถอนการติดตั้งตัวเก่า**: `Settings` → `Apps` → `Drive Test LTE 5G` → `Uninstall` (หรือ `adb uninstall com.drivetest.lte5g`)
5. **ติดตั้งจาก Play**: เปิด opt-in link (§4) → `Accept invite` → `Download it on Google Play` → `Install`
6. **นำข้อมูลกลับเข้าแอป**: Campaigns → `CSV Log Import` → เลือกไฟล์ที่ export ไว้ในข้อ 1 (ทำทีละไฟล์) — ค่าที่จดไว้ในข้อ 3 ต้องตั้งใหม่ด้วยมือ
7. **ตรวจว่า In-App Updates ทำงาน**: เปิดแอป → Settings → About/Update → ต้องเห็นสถานะจาก Play (ไม่ใช่ปุ่มดาวน์โหลด APK)

ทางเลี่ยงที่ **ไม่แนะนำ**: อย่าพึ่ง Google auto-backup (`allowBackup="true"` ใน manifest) เพื่อกู้ session — ยังไม่ยืนยันว่า restore จะทำงานข้ามการเปลี่ยน signature และทำได้เฉพาะตอนติดตั้งใหม่ครั้งแรกเท่านั้น ถ้าพลาดคือเสียข้อมูลถาวร

## 4. เพิ่ม tester และแจก opt-in link

1. `Internal testing` → แท็บ `Testers`
2. `Create email list` → ตั้งชื่อ (เช่น `qa-team`) → ใส่ email (Google account) ของ tester → `Save`
3. กลับไปแท็บ `Releases` → แก้ release ที่ rollout อยู่ → เลือก email list ในข้อ 2 → `Save`
4. คัดลอก link จากหัวข้อ `How testers join your test`: รูปแบบ `https://play.google.com/apps/internaltest/<long-id>`
5. ส่ง link ให้ tester — tester ต้อง login Google account เดียวกับที่ใส่ใน list, กด `Accept invite`, แล้วติดตั้งจาก Play (ไม่ใช่ APK)
6. tester ที่ติดตั้งแล้วจะได้ update อัตโนมัติผ่าน Play และผ่าน In-App Updates ในแอป

หมายเหตุ: tester ที่ยังมี APK เก่าติดตั้งอยู่ต้องทำ §3 ก่อนเช่นกัน ไม่งั้นจะเจอ `INSTALL_FAILED_UPDATE_INCOMPATIBLE` หรือ Play ขึ้น "uninstall" ให้เอง

**URL ที่ปุ่มอัปเดตใช้:** `PlayUpdateManager.playStoreUrl()` ที่ `android-native/app/src/main/kotlin/com/drivetest/lte5g/updater/PlayUpdateManager.kt:83` (สร้างจาก `context.packageName` ที่ `:80`) คืนค่า `https://play.google.com/store/apps/details?id=<packageName>` ซึ่งจะ **404 จนกว่าแอปจะ published** เมื่อ track internal testing พร้อมแล้ว ให้แก้ที่ฟังก์ชันนี้จุดเดียวเป็น opt-in link เต็มจาก Play Console (`Test and release` → `Testing` → `Internal testing` → `Testers` → `How testers join your test`) เพราะผู้ใช้ต้องผ่านหน้ารับคำเชิญก่อนจึงติดตั้งได้ — จนถึงตอนนั้นผู้ใช้ sideload ที่กดปุ่มอัปเดตจะเจอหน้า 404 และต้องส่ง link ให้ด้วยมือ

## 5. Promote ไป track ถัดไป

1. `Internal testing` → `Releases` → `Promote release` → เลือก `Closed testing` / `Open testing` / `Production`
2. Production ต้องมี store listing ครบ (screenshots, feature graphic, คำอธิบาย, privacy policy) และผ่าน review (ใช้เวลาหลายชั่วโมงถึงหลายวัน — ยังไม่ยืนยัน)
3. หลัง promote ให้ tester ยังใช้ link เดิมได้จนกว่า track นั้นจะถูกปิด และ **อย่าลด versionCode** ในรอบถัดไป

## 6. Checklist ก่อนกด rollout (สรุป)

- [ ] AAB signed ด้วย release keystore (ไม่มี fallback log), versionCode ใหม่กว่าเดิม
- [ ] ไม่มี `ya29.`/`GDRIVE_ACCESS_TOKEN` ใน AAB (ดู incident runbook §4)
- [ ] ไม่มี `REQUEST_INSTALL_PACKAGES` ใน merged manifest (`android-native/app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml`) และไม่มีโค้ดติดตั้ง APK เหลือ
- [ ] `App content` กรอกครบ (Data safety ต้องระบุการเก็บตำแหน่ง/telemetry ให้ตรงความจริง)
- [ ] tester list มี email ครบ, opt-in link ถูกส่งแล้ว, และแจ้ง tester ว่าต้องถอนตัวเก่าก่อน + export session ก่อนถอน
- [ ] มีคนรับผิดชอบยืนยันหลัง promote ว่า tester ติดตั้งได้และ In-App Updates ทำงาน