# Incident: Google OAuth token ฝังอยู่ใน APK ที่แจกผ่าน GitHub Releases

สรุป: APK ของแอปฝัง Google OAuth **access token** (`ya29.` ขึ้นต้น, ยาว 253 ตัวอักษร) ไว้ใน `classes2.dex` ผ่าน `buildConfigField("String","GDRIVE_ACCESS_TOKEN", ...)` ในบล็อก `defaultConfig` ของ `android-native/app/build.gradle.kts` ที่อ่านจาก `local.properties` ทุกคนที่โหลด APK ไปสามารถดึง token ออกได้ด้วย `unzip` อย่างเดียว ต้อง revoke ก่อน แล้วย้าย credential ไปฝั่ง Cloudflare Worker

วันที่ตรวจ: 2026-11-12 (สแกน APK ในเครื่องด้วยสคริปต์อ่านอย่างเดียว, ไม่ได้แก้ไฟล์ใด)

## 0. ข้อเท็จจริงที่ยืนยันแล้ว vs ยังไม่ยืนยัน

- ยืนยัน: APK 6 ไฟล์ฝัง `ya29.` ใน `classes2.dex` (ตาราง §6) — เวอร์ชัน `v1.12.0` เป็นตัวแรก, `v1.6.0`–`v1.11.2` ไม่มี
- ยืนยัน: APK ไม่ได้ถูก track ใน git (`git ls-files "*.apk"` = 0, `.gitignore` มี `*.apk`) แต่ `docs/index.html:71,113` ชี้ปุ่มดาวน์โหลดไปที่ `github.com/EEMEEMMEEx/Drive-Test-5G-LTE-RF/releases/latest` ดังนั้นถือว่า **token รั่วสู่สาธารณะ** ไม่ใช่แค่ไฟล์ในเครื่อง
- ยืนยัน: token เป็น access token (`ya29.` = Google OAuth 2.0 access token, อายุปกติ ~1 ชั่วโมง) ตัวที่อยู่ใน APK จึงหมดอายุไปแล้วในทางทฤษฎี
- ยังไม่ยืนยัน: refresh token ที่ใช้สร้าง access token นั้นยังมีชีวิตหรือไม่ และ token ถูกสร้างด้วย client/บัญชีไหน — **ให้ถือว่ายังมีชีวิตจนกว่าจะ revoke** เพราะถ้า refresh token ยังอยู่ ผู้โจมตีขอ access token ใหม่ได้ตลอด
- ยังไม่ยืนยัน: scope ของ token (ถ้าเป็น `drive.file` เสี่ยงต่ำกว่า `drive` เต็มรูปแบบ) ดูได้จากหน้า consent grant ที่ revoke (§1.2)

## 1. Revoke — ทำก่อนทุกอย่าง

### 1.1 ตรวจสภาพ token (อ่านอย่างเดียว, ไม่ทำอันตราย)
```
curl "https://oauth2.googleapis.com/tokeninfo?access_token=<token>"
```
ถ้าตอบ `invalid_token` = หมดอายุแล้ว ให้ไปต่อที่ §1.2 ซึ่งเป็นด่านที่สำคัญจริง

### 1.2 Revoke grant ของบัญชี Google (ทางหลัก ปิด refresh token ด้วย)
1. เปิด https://myaccount.google.com/permissions (หรือ `Google Account` → `Security` → `Third-party apps & services`)
2. หาแอปที่ให้สิทธิ์ Drive ไว้ (ชื่ออาจเป็นชื่อ OAuth client ใน Google Cloud project) แล้วคลิกชื่อแอป
3. ดูรายการ scope ที่ให้ไป — บันทึกไว้เป็นหลักฐาน (ถ้ามี `See, edit, create, and delete all of your Google Drive files` = สิทธิ์เต็ม)
4. คลิก `Delete all connections you have with <app>` → ยืนยัน
5. กลับมาหน้าเดิมแล้วยืนยันว่าแอปหายจากรายการ

### 1.3 ถ้า token มาจาก OAuth client ของโปรเจกต์ (กรณีนี้ควรทำเพิ่ม)
1. เปิด https://console.cloud.google.com/apis/credentials (เลือก project ให้ถูก)
2. หัวข้อ `OAuth 2.0 Client IDs` → คลิก client ที่ใช้สร้าง token
3. ถ้า client secret อาจรั่ว (เช่นอยู่ใน repo/สคริปต์) → `Reset Secret` → `Add secret` แล้วลบ secret เก่า
4. `APIs & Services` → `OAuth consent screen` → ตรวจ `Publishing status`: ถ้าเป็น `Testing` ให้ลบ test user ที่ไม่ใช้, ถ้าเป็น `In production` และไม่ได้ใช้งานจริงให้ `Back to testing` เพื่อหยุดการให้สิทธิ์ใหม่
5. ถ้าใช้ **service account** แทน user token: `IAM & Admin` → `Service Accounts` → เลือก SA → แท็บ `Keys` → `Delete` key เก่า แล้ว `Add key` → `Create new key` (JSON) เก็บนอก repo

### 1.4 ปิดช่องที่ทำให้ token หลุดซ้ำ
- ลบ `GDRIVE_ACCESS_TOKEN` และ `GDRIVE_FOLDER_ID` ออกจาก `local.properties` (ทั้ง root และ `android-native/`) และจาก `android-native/local.properties.example`
- ต้องไม่มี `buildConfigField` ชื่อ `GDRIVE_ACCESS_TOKEN` ใน `app/build.gradle.kts` อีก และต้องมี Gradle guard `guardNoDriveToken` ที่ fail build ถ้าเจอ `ya29.` หรือ field นั้นกลับมา (`cd android-native; .\gradlew.bat :app:guardNoDriveToken` ต้อง PASS)
- token ที่สร้างจาก OAuth Playground/มือ ให้เลิกใช้วิธีนั้นกับ CI: ใช้ Worker secret (§3)

## 2. ตรวจร่องรอยการใช้งาน (misuse audit)

ช่วงเวลาที่ต้องตรวจ: **ตั้งแต่ `v1.12.0` ถูกเผยแพร่ ถึงเวลาที่ revoke เสร็จ**

1. **Cloud Audit Logs**
   - https://console.cloud.google.com/logs/query
   - ตัวอย่าง query:
     ```
     logName:"cloudaudit.googleapis.com"
     protoPayload.authenticationInfo.principalEmail="<google-account-of-token>"
     ```
   - ดู `protoPayload.methodName` ที่เกี่ยวกับ Drive (`google.apps.drive.*`, `drive.files.*`) และ `timestamp`, `callerIp`, `userAgent` ที่ไม่ใช่แอป/เครื่องของเรา
2. **Drive activity**
   - บัญชีส่วนตัว: https://myactivity.google.com/product/drive (ดูรายการ "เข้าถึงไฟล์/สร้าง/แชร์" ย้อนหลัง)
   - Google Workspace: https://admin.google.com → `Reporting` → `Audit and investigation` → `Drive log events` → filter `Actor` = บัญชีที่ถูกใช้ และช่วงวันที่
   - ใน Drive UI: คลิกไฟล์ที่น่าสงสัย → `Activity`/`Details` เพื่อดูว่าใครแชร์/แก้ไข
3. **สัญญาณที่ต้องถือว่ารั่วจริง**: มีไฟล์แปลกใน Drive, มีการแชร์ออกนอกองค์กร, มีการสร้าง OAuth app อื่น, มี `403/quotaExceeded` ผิดปกติ, หรือ Drive storage เพิ่มขึ้นเอง
4. บันทึกผลลงท้ายไฟล์นี้: ช่วงเวลาที่ตรวจ, query ที่ใช้, ผล (พบ/ไม่พบ), ผู้ตรวจ

## 3. ย้าย credential ไปฝั่ง Cloudflare Worker

หลักการ: client secret / refresh token อยู่ใน Worker เท่านั้น แอปส่งไฟล์ไปที่ endpoint ของ Worker โดยไม่ถือ credential ของ Google

ส่วน server มีอยู่ใน repo แล้ว: `worker/drive-upload.js` ผูกกับ Worker ผ่าน `wrangler.jsonc:7` (`"main": "worker/drive-upload.js"`) ให้บริการ `POST /api/drive-upload` (path อื่นตกไปที่ `env.ASSETS.fetch` จึงยังเสิร์ฟ dashboard ได้) ตัว Worker ตรวจ `x-app-auth` แบบ constant-time เทียบกับ `env.APP_AUTH_TOKEN` แล้วแลก access token ที่ `oauth2.googleapis.com/token` ด้วย `GOOGLE_REFRESH_TOKEN` ก่อนอัปโหลดเข้าโฟลเดอร์จาก `GDRIVE_FOLDER_ID` (ตอบ `{"id": ...}` เมื่อสำเร็จ, 401/400/502 เมื่อผิดพลาด)

```powershell
cd <repo-root>
wrangler secret put GOOGLE_CLIENT_ID
wrangler secret put GOOGLE_CLIENT_SECRET
wrangler secret put GOOGLE_REFRESH_TOKEN
wrangler secret put APP_AUTH_TOKEN
wrangler secret put GDRIVE_FOLDER_ID
wrangler secret list        # ต้องเห็นชื่อ secret ทั้ง 5 แต่ไม่เห็นค่า
wrangler deploy
```
แล้วตั้งฝั่งแอป: `CLOUD_UPLOAD_URL=https://<worker-host>/api/drive-upload` ใน `android-native/local.properties` (หรือ environment)

- **สถานะปัจจุบัน (2026-11-12): ยังไม่ deploy** — `CLOUD_UPLOAD_URL` ค่าเริ่มต้นเป็นค่าว่าง อย่าอ่านเอกสารนี้แล้วคิดว่าฟีเจอร์อัปโหลดพร้อมใช้
  - ยังไม่ตั้ง `CLOUD_UPLOAD_URL`: `enqueueSessionForWifiUpload` ข้ามการอัปโหลด (status คงเป็น `NONE`) เหมือน build ที่ token ว่าง — session ยังอยู่ใน `SessionRepository` ในเครื่อง ไม่ถูกลบ ไม่มี data loss
  - ตั้งแล้วแต่ endpoint ล่ม: ได้ `Result.failure` → status `FAILED` + ข้อความ error ภาษาไทย, WorkManager retry 3 ครั้งแล้ว `FAILED`, ไม่ crash
- ห้ามใส่ค่า secret เหล่านี้ใน `local.properties`, `BuildConfig`, `wrangler.jsonc`, หรือไฟล์ที่ commit (`.dev.vars` ต้องอยู่ใน `.gitignore`)
- ฝั่งแอปส่ง multipart `POST` ไปที่ `CLOUD_UPLOAD_URL` (มาจาก `local.properties` → `BuildConfig.CLOUD_UPLOAD_URL`) พร้อม header `x-app-auth` ที่ใช้ค่า `FIREBASE_AUTH_TOKEN` (`GoogleDriveAutoUploader.kt:208` และ `:246`) — `CLOUD_UPLOAD_URL` เป็น config ไม่ลับ แต่ **`x-app-auth` เป็นความลับที่ยังฝังอยู่ใน APK** ดู §3.1

### 3.1 ความเสี่ยงที่ยังเหลือ (follow-up, ไม่ได้แก้ในรอบนี้)

- `x-app-auth` ใช้ค่า `FIREBASE_AUTH_TOKEN` ซึ่งเป็น `buildConfigField` จึงดึงออกจาก APK ได้เหมือน `ya29.` — Worker เทียบค่านี้กับ `APP_AUTH_TOKEN` (§3) การ rotate จึงต้องแก้ทั้ง Worker secret และค่าที่ build ลงแอป ใครมีค่าจาก APK ก็ POST ไฟล์เข้า Worker ได้ ถือว่า **`FIREBASE_AUTH_TOKEN` รั่วไปด้วย** ถ้าค่าเดียวกันถูกฝังใน APK v1.12.0 ขึ้นไป ให้ rotate และตรวจด้วย grep แบบเดียวกับ §4 (ห้ามพิมพ์ค่าลง log/เอกสาร)
- ทางแก้ระยะกลาง: ให้ Worker ตรวจ Firebase ID token ของผู้ใช้ (หรือ signed request) แทน shared secret ที่ฝังในแอป แล้วเลิกแจกค่า `x-app-auth` แบบคงที่
- `GEMINI_API_KEY` / `MAPS_API_KEY` ก็ถูกฝังใน `BuildConfig` เช่นกัน (`MAPS_API_KEY` ถูกจำกัดด้วย package/SHA-1 ได้ใน Cloud Console ส่วน Gemini key ควรย้ายไป Worker) — นอกขอบเขตรอบนี้ รายงานเป็น follow-up

## 4. Checklist ยืนยันว่า APK ที่ build ใหม่ไม่มี token แล้ว

ทำทุกข้อ ได้ผลตามที่ระบุจึงถือว่าปิด incident:

- [ ] `cd android-native; .\gradlew.bat :app:guardNoDriveToken` PASS (guard นี้ผูกกับ `assembleDebug`/`assembleRelease`/`bundleRelease` อยู่แล้ว แต่ยังไม่สแกน dex จริง จึงต้องทำข้อถัดไปด้วย)
- [ ] `cd android-native; .\gradlew.bat :app:assembleRelease` ผ่าน และ build ไม่ได้ fallback ไป debug signing (ดู `docs/play-release-runbook.md`)
- [ ] `grep -c "ya29\." app/build/generated/source/buildConfig/release/**/BuildConfig.java` → ต้องได้ `0`
- [ ] dex ใน APK ต้องไม่มี `ya29.` (ไฟล์ที่ตรวจ: `android-native/app/build/outputs/apk/release/app-release.apk`) — Git Bash:
  ```
  cd android-native/app/build/outputs/apk/release
  unzip -o -q app-release.apk 'classes*.dex' -d /tmp/dexcheck && grep -Rl 'ya29\.' /tmp/dexcheck; echo "exit=$?"
  ```
  PowerShell (ไม่ต้องมี unzip):
  ```powershell
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $hits = 0; $z = [IO.Compression.ZipFile]::OpenRead((Resolve-Path .\app\build\outputs\apk\release\app-release.apk))
  foreach ($e in $z.Entries) { if ($e.Name -notmatch '^classes\d*\.dex$') { continue }
    $s = $e.Open(); $ms = New-Object IO.MemoryStream; $s.CopyTo($ms); $s.Dispose()
    if ([Text.Encoding]::GetEncoding(28591).GetString($ms.ToArray()).Contains('ya29.')) { $hits++; "HIT $($e.Name)" } }
  $z.Dispose(); "dex_hits=$hits"   # ต้องได้ dex_hits=0
  ```
- [ ] `strings app-release.apk | grep -i gdrive` ไม่เจอ token อีก
- [ ] revoke เสร็จ (§1) และบันทึกหลักฐานใน §2
- [ ] release assets เก่าถูกลบ/แทนที่ (§5) หรือ coordinator อนุมัติแล้ว

## 5. Public distribution: ต้องลบ release asset และ rotate คือสิ่งที่ปิดความเสี่ยงจริง

APK 5 เวอร์ชันที่ฝัง token ถูกเผยแพร่เป็น **GitHub Release asset** ที่ `github.com/EEMEEMMEEx/Drive-Test-5G-LTE-RF/releases` (ปุ่มดาวน์โหลดบน landing page อ้างถึงที่ `docs/index.html:71` และ `docs/index.html:113`) — ใครก็โหลดได้โดยไม่ต้องมีสิทธิ์ใน repo ดังนั้นนี่คือเหตุการณ์ token รั่วสู่สาธารณะ ไม่ใช่ปัญหาของไฟล์ในเครื่อง

ทำตามลำดับนี้:

1. **Revoke/rotate ก่อน** (§1, §3) — การ rotate คือสิ่งที่ทำให้ token ที่หลุดไปแล้วใช้งานไม่ได้จริง การลบไฟล์ไม่ทำให้ token ที่มีคนโหลดไปแล้วตาย
2. **ลบ release asset** ของ `v1.12.0`, `v1.12.1`, `v1.12.2`, `v1.13.0`, `v1.13.1`: เปิด `Releases` → เลือก release → `Edit` → ลบ asset (หรือ `Delete` ทั้ง release) → `Update release` — เป็นการจำกัดการแพร่กระจายสำหรับคนที่ยังไม่โหลด ไม่ใช่การ remediate ตัว token
3. ตรวจว่าไม่มีไฟล์แจกซ้ำที่อื่น (โดเมนตัวเอง / Worker / Drive ที่แชร์ไว้ / mirror)

ไฟล์ APK ในเครื่อง (root 3 + `android-native/` 3, ตาราง §6) เป็น build artifact ที่ถูก gitignore อยู่แล้ว (`git ls-files "*.apk"` = 0): **การลบเป็นเพียงการทำความสะอาด workspace ไม่ใช่การแก้ปัญหา** และไม่ต้อง rewrite git history เพราะไม่มี APK หรือ token อยู่ใน history — ยืนยันด้วย `git log -S "ya29." --oneline` ซึ่งต้องว่าง ถ้าเคย commit `local.properties` หรือ secret จริง การ rotate เท่านั้นที่ช่วยได้

ต้องขออนุมัติ coordinator ก่อนลบไฟล์หรือ release asset ใด ๆ — เอกสารนี้ไม่ลบให้

## 6. APK ที่พบ token (`classes2.dex`)

| ไฟล์ | ตำแหน่ง | พบ `ya29.` |
| --- | --- | --- |
| `drive-test-lte-5g-v1.12.0.apk` | `android-native/` | ใช่ |
| `drive-test-lte-5g-v1.12.1.apk` | `android-native/` | ใช่ |
| `drive-test-lte-5g-v1.12.2.apk` | root | ใช่ |
| `drive-test-lte-5g-v1.13.0.apk` | `android-native/` และ root | ใช่ (ทั้ง 2 ไฟล์) |
| `drive-test-lte-5g-v1.13.1.apk` | root | ใช่ |
| `v1.6.0`–`v1.11.2` (ทั้งหมด) | root + `android-native/` | ไม่พบ |

## 7. บันทึกผลการปิด incident

| รายการ | ผล | ผู้ทำ / เวลา |
| --- | --- | --- |
| Revoke grant (§1.2) | ยังไม่ทำ | |
| Reset client secret (§1.3) | ยังไม่ทำ | |
| Worker secret ครบ 5 ตัว + `wrangler deploy` + `CLOUD_UPLOAD_URL` (§3) | ยังไม่ทำ | |
| APK ใหม่ผ่าน checklist §4 | ยังไม่ทำ | |
| ลบ release asset + APK เก่า (§5) | รอ coordinator อนุมัติ | |