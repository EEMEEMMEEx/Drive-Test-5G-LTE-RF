# Play Protect บล็อกการติดตั้ง APK ของ OTA updater

สรุป: ไดอะล็อก "Harmful app blocked — Drive Test LTE 5G — This app may be harmful" คือสถานะ **บล็อกการติดตั้งถาวรของ Google Play Protect (app verification)** ซึ่งต่างจากสถานะ "เตือนแต่ติดตั้งต่อได้" ที่มีปุ่ม `Install anyway` ซ่อนอยู่ใต้ `More details` การแก้ฝั่งผู้ใช้มี 2 เส้นทางเท่านั้น (ปิดสแกนชั่วคราว หรือปิด Auto Blocker ของ Samsung) ส่วนการแก้ถาวรคือเลิกแจก APK เอง แล้วใช้ Play internal testing + In-App Updates

วันที่ตรวจสอบเนื้อหานี้: 2026-11-12 (ตรวจ URL และข้อความบนหน้าทางการ Google ด้วยการเรียกหน้าเว็บจริง)

## 1. ข้อความนี้แมปกับสถานะไหน และทำไมบางครั้งไม่มี "Install anyway"

Google อธิบายไว้ 2 ระดับ:

- ระดับ **เตือน**: "You may get a warning from Google about an app or URL that may be unsafe." — ยังติดตั้งต่อได้
- ระดับ **บล็อก**: "The app or URL may be removed or **blocked from installation** by Google **if it is known to be harmful** to devices, data, or users." และกรณี financial fraud prevention: "For your safety, Google Play Protect **will block** these apps from installation." (แหล่งที่ 1)

หน้าความช่วยเหลือสำหรับนักพัฒนาเพิ่มว่า app verification "warn users when they install potentially harmful apps from the internet and third-party markets. **In some cases, this feature blocks app installations**." (แหล่งที่ 2)

| ข้อความบนจอ | สถานะ | มี `Install anyway` หรือไม่ |
| --- | --- | --- |
| `Harmful app blocked` + `This app may be harmful` + ปุ่ม `Got it` / `More details` | Play Protect บล็อกการติดตั้ง (known-harmful หรือ hard block ของ fraud protection) | ไม่มี (ยังไม่ยืนยัน: พฤติกรรม UI ไม่ได้ระบุในเอกสาร Google) |
| `Unsafe app blocked` / "พบ N ภัยคุกคาม" ในหน้า scan ตอนติดตั้ง | เตือน (warning) | มี ผ่าน `More details` → `Install anyway` |
| แอปถูกลบ/ปิดหลังติดตั้งแล้ว | post-install remediation: "Disable the app until you uninstall it" / "Remove the app automatically" (แหล่งที่ 1) | ไม่เกี่ยวกับ installation |

เหตุผลที่ปุ่มหาย: เมื่อ Play Protect จัดแอปเป็น known-harmful หรือเข้าเงื่อนไข hard block ระบบจะไม่เสนอทางเลือกให้ผู้ใช้เลย ปุ่มเดียวที่เหลือคือ `Got it` — ส่วน `More details` ทำหน้าที่แค่ขยายคำอธิบาย ไม่ใช่ bypass (ยังไม่ยืนยันในส่วนของข้อความที่ขยายออกมา เพราะ Google ไม่ได้เผยแพร่ string เหล่านี้)

## 2. ขั้นตอนแก้ไขบน Samsung (Android 14/15, One UI 6.x)

ทำตามลำดับ ข้อ 1–4 คือเส้นทางที่ใช้งานได้จริงบนเครื่อง Samsung:

1. **ปิด Samsung Auto Blocker ก่อน** (One UI 6 ขึ้นไป, เปิดเป็นค่าเริ่มต้นตั้งแต่ One UI 6.1.1): `Settings` → `Security and privacy` → `Auto Blocker` → ปิดสวิตช์ → ยืนยันด้วย PIN/ลายนิ้วมือ (แหล่งที่ 5, 6) — Auto Blocker บล็อกการติดตั้งจากแหล่งนอก Play/Galaxy Store แยกจาก Play Protect ถ้าเปิดอยู่จะบล็อกซ้ำถึงแม้ปิด Play Protect แล้ว (ยังไม่ยืนยันว่าไดอะล็อกในเคสนี้มาจาก Auto Blocker หรือ Play Protect — ข้อความบนจอตรงกับ Play Protect)
2. **ปิดการสแกนของ Play Protect ชั่วคราว**: เปิดแอป `Google Play Store` → แตะ `โปรไฟล์` (มุมขวาบน) → `Play Protect` → แตะไอคอนเฟือง `Settings` (มุมขวาบน) → ปิด `Scan apps with Play Protect` (แหล่งที่ 1 ระบุเส้นทางนี้ตรงตัว) — ในหน้าเดียวกันมีสวิตช์ `Improve harmful app detection` ซึ่งปิดเพิ่มได้แต่ไม่จำเป็น
3. **อนุญาตให้แอปนี้ติดตั้ง APK**: `Settings` → `Apps` → `Drive Test LTE 5G` → `Install unknown apps` → `Allowed` (ชื่อเมนูบน Samsung บางรุ่นคือ `Settings` → `Apps` → `Special access` → `Install unknown apps`) — ยังไม่ยืนยันชื่อเมนูทุก variants
4. **ถ้าไดอะล็อกมีปุ่ม `More details`**: แตะแล้วดูว่ามี `Install anyway (unsafe)` โผล่มาหรือไม่ ถ้ามีให้ใช้เส้นทางนี้แทนการปิดสแกนทั้งเครื่อง (ยังไม่ยืนยัน: ไม่มีเอกสาร Google ระบุว่าปุ่มนี้ปรากฏเมื่อไร)
5. **ติดตั้งซ้ำ** แล้ว **เปิด Play Protect กลับทันที** (`Scan apps with Play Protect` = on)
6. ถ้าสวิตช์ใน Play Protect ถูกทำให้จาง (greyed out) ให้ตรวจ `Advanced Protection` ใน `Settings` → `Security and privacy` และตรวจว่ามี work profile / MDM profile ครอบอยู่หรือไม่ (ยังไม่ยืนยันชื่อเมนู: แตกต่างตามรุ่น)
7. ทางสำรองสำหรับ tester ที่มีคอมพิวเตอร์: `adb install -r drive-test-lte-5g-vX.Y.Z.apk` (เลี่ยงไดอะล็อกบนเครื่องได้ในทางปฏิบัติ แต่ยังไม่ยืนยันว่า Play Protect ฝั่ง cloud จะสแกน APK ที่ติดตั้งผ่าน adb หรือไม่)

หมายเหตุสำคัญ: Play Protect **ไม่มี allowlist รายแอปสำหรับผู้ใช้ทั่วไป** — หน้าความช่วยเหลือมีเพียงสวิตช์เปิด/ปิดการสแกน และการส่ง unknown app ให้ Google (แหล่งที่ 1) ดังนั้น "per-app unblock" ในความหมายถาวรไม่มี ทางเดียวคือปิดสแกนชั่วคราวหรือให้ Google allowlist ผ่านการ appeal (ข้อ 4)

## 3. อะไรเป็นสาเหตุจริง / อะไรเป็นสมมติฐาน

| ปัจจัย | สถานะ |
| --- | --- |
| APK ถูกสแกนตอนติดตั้งไม่ว่าจะมาจากแหล่งใด | **จริง** — "Google Play Protect checks apps when you install them" (แหล่งที่ 1) |
| `targetSdk` ต่ำ (≤ 23) ทำให้ติดตั้งไม่ได้ | **จริงแต่ไม่ใช่เคสนี้** — Android 15 ขึ้นไปติดตั้งไม่ได้และขึ้น `INSTALL_FAILED_DEPRECATED_SDK_VERSION` ซึ่งเป็น error ของ OS ไม่ใช่ไดอะล็อก Play Protect (แหล่งที่ 4) และ APK ที่ ship อยู่ targetSdk 37 |
| debug-signed ทำให้ Play Protect บล็อก | **ยังไม่ยืนยัน** — Google ไม่ได้ประกาศว่าใบรับรอง debug เป็น trigger; และ APK ที่ ship ไม่ได้ debug-signed (ใบรับรอง release `C=TH ... CN=WATCHRAR MANADEE`, SHA-256 `0016cd35...9744`, scheme v2 เท่านั้น) |
| self-signed certificate เป็นสาเหตุ | **ยังไม่ยืนยัน** — การเซ็นเองเป็นเรื่องปกติของแอปนอก Play; ที่น่าสงสัยคือ "ผู้พัฒนาไม่เป็นที่รู้จัก + ไม่มีประวัติการติดตั้ง" ไม่ใช่ตัวใบรับรอง |
| `REQUEST_INSTALL_PACKAGES` + ดาวน์โหลด APK แล้วสั่งติดตั้งทันที (dropper pattern) ทำให้คะแนนเสี่ยงสูงขึ้น | **สมมติฐานที่มีน้ำหนัก** — Google ระบุว่าการจัดประเภทอิง "harmful behavior" และตรวจแอปจาก "higher risk sources" แต่ไม่ได้เปิดเผย scoring; นโยบาย Play เองก็จัด `REQUEST_INSTALL_PACKAGES` เป็น permission ที่ถูกจำกัด (แหล่งที่ 3) ลำดับ ดาวน์โหลด → cache → `ACTION_INSTALL_PACKAGE`/`ACTION_VIEW` ในแตะเดียว ตรงกับรูปแบบ dropper ที่ classifier จับ |
| ทุกเวอร์ชันเป็น hash ใหม่ที่ Google ยังไม่เคยเห็น | **ยังไม่ยืนยัน** (อธิบายได้ว่าทำไมบล็อกทุกครั้งที่อัปเดต) |

สรุปสั้น: ในเคสนี้ตัวที่ "แก้ได้และวัดผลได้" คือการเลิกใช้เส้นทาง self-update ในแอป ไม่ใช่การเซ็น APK หรือ targetSdk

## 4. การ appeal false positive

ช่องทางทางการ (ทั้งคู่เปิดจริง ตรวจเมื่อ 2026-11-12):

- บทความ "App verification" (Play Console Help) → ปุ่ม `File an appeal` → แบบฟอร์ม → <https://support.google.com/googleplay/android-developer/answer/2992033> (แหล่งที่ 2)
- แบบฟอร์มโดยตรง: Play Protect Appeals Submission Form → <https://support.google.com/googleplay/android-developer/contact/protectappeals> (แหล่งที่ 3)

ฟิลด์ที่แบบฟอร์มบังคับ (คัดจากหน้าจริง):

- `Email address`, `Developer name`, `Application package name`
- `SHA256 Hash of the apk of the uploaded file on Virus Total` — ต้องอัป APK ขึ้น VirusTotal ก่อน แล้วใส่ SHA-256 ของไฟล์นั้น
- `Additional information to support your appeal`

หลักฐานที่ควรใส่ในช่องสุดท้าย (ประกอบจากข้อมูล artifact จริงของโปรเจกต์):

- package name `com.drivetest.lte5g`, versionName/versionCode ที่บล็อก, targetSdk
- SHA-256 ของ APK ทุกไฟล์ที่ปล่อย (ปัจจุบันยังไม่มี checksum เผยแพร่ — ควรสร้างก่อนยื่น)
- signing certificate SHA-256: `0016cd359756774efe2d3a2c553ee034e77309130218e757a6b8c457cf5a9744` (DN `C=TH ... CN=WATCHRAR MANADEE`) และระบุว่าใช้คีย์เดิมทุกเวอร์ชัน
- URL ที่ Google โหลดได้โดยตรง: release asset บน GitHub (ต้องไม่มีหน้า landing, ไม่มี Cloudflare/CAPTCHA, ไม่ต้องล็อกอิน)
- คำอธิบายฟังก์ชันแอป (เครื่องมือวัดสัญญาณ เก็บ telemetry ลง Firebase/Drive ของผู้ใช้เอง), ลิงก์ repo สาธารณะ, และระบุชัดว่าไม่ใช่แอปที่ติดตั้งแอปอื่นให้ผู้ใช้แบบเงียบ
- เขียนเป็นภาษาอังกฤษ (หน้าความช่วยเหลือระบุ "we can only respond to appeals in English at this time") (แหล่งที่ 2)

เวลาพิจารณา: แบบฟอร์มระบุตรงตัวว่า **"All appeal decisions are final and you will not receive a response."** (แหล่งที่ 3) — จึงไม่มี SLA และไม่มีอีเมลตอบกลับ ระยะเวลาจริงที่ผู้ใช้รายงาน (หลักวันถึงสัปดาห์) เป็น **ยังไม่ยืนยัน**

หมายเหตุ: การ appeal เปลี่ยนได้แค่การจัดประเภทของ Google ไม่ได้แก้ behavior ของในแอป ถ้ายังใช้ dropper pattern อยู่ เวอร์ชันถัดไปมีโอกาสถูกบล็อกซ้ำ

## 5. เส้นทางแจกจ่ายที่ไม่เจอปัญหานี้ซ้ำ

- **Play internal testing track + Play Core In-App Updates API** — Google Play เป็นผู้ติดตั้งและอัปเดตเอง จึงไม่ผ่าน Play Protect installer สำหรับ APK นอก Play; internal testing ไม่ต้องผ่าน review เต็มรูปแบบและจำกัดจำนวน tester (แหล่งที่ 7, 8) ข้อแลกเปลี่ยน: ต้องมี Play Console account, ต้องทำ permissons declaration, และต้องลบ `REQUEST_INSTALL_PACKAGES` + โค้ดติดตั้งออกจาก manifest/โค้ด (ไม่ใช่แค่ไม่เรียกใช้) ไม่งั้นจะถูกปัดตอน review
- **Firebase App Distribution** — แจก build ให้ tester ผ่านอีเมล/กลุ่ม ไม่ต้อง review (แหล่งที่ 9) ข้อแลกเปลี่ยน: ยังเป็นการติดตั้ง APK บนเครื่อง จึงยังถูก Play Protect สแกนและบล็อกได้ (ยังไม่ยืนยันว่า App Tester app หลีกเลี่ยง app verification ได้หรือไม่) เหมาะกับ tester ภายใน ไม่ใช่ผู้ใช้จริง
- **APK บนโดเมนของตัวเอง + เผยแพร่ SHA-256/ใบรับรอง** — ควบคุมเวอร์ชันและ metadata ได้เต็มที่ ไม่พึ่ง GitHub API rate limit ข้อแลกเปลี่ยน: ยังเป็น sideload จึงยังเสี่ยงบล็อก แต่มี checksum ให้ผู้ใช้ตรวจและใช้ยื่น appeal ได้ง่ายขึ้น
- **ระยะยาว: Android developer verification** — Google เริ่มบังคับตั้งแต่ 30 กันยายน 2026 สำหรับแอปที่ติดตั้งจาก store ที่เข้าร่วมในบราซิล/อินโดนีเซีย/สิงคโปร์/**ไทย** บนเครื่อง certified (Android 7+) และขยายทั่วโลกปี 2027 สำหรับแอปทั้งหมด (แหล่งที่ 10) แปลว่าเส้นทาง sideload แบบไม่ยืนยันตัวตนจะถูกบล็อกเชิงระบบในอนาคตอันใกล้ ควรลงทะเบียนใน Android Developer Console หรือ Play Console ล่วงหน้า

## 6. รายการที่ยังไม่ยืนยัน

- การแมป string ต่อ string ระหว่าง `Harmful app blocked` / `Unsafe app blocked` กับเงื่อนไขภายในของ Play Protect (Google ไม่เผยแพร่)
- เงื่อนไขที่ทำให้ปุ่ม `Install anyway` ปรากฏ/หาย
- น้ำหนักของ `REQUEST_INSTALL_PACKAGES` + dropper pattern ต่อคะแนน classifier
- ระยะเวลาพิจารณา appeal และผลของ appeal ในเคสนี้
- ชื่อเมนู `Install unknown apps` / `Advanced Protection` ที่ต่างกันตามรุ่น Samsung
- พฤติกรรมของ app verification เมื่อติดตั้งผ่าน adb

## Sources

1. Google Play Help — Use Google Play Protect to help keep your device safe: <https://support.google.com/googleplay/answer/2812853>
2. Play Console Help — App verification: <https://support.google.com/googleplay/android-developer/answer/2992033>
3. Play Protect Appeals Submission Form: <https://support.google.com/googleplay/android-developer/contact/protectappeals>
4. Android Developers — Behavior changes: Android 15 (apps targeting API 23 or lower cannot install): <https://developer.android.com/about/versions/15/behavior-changes-all>
5. Samsung Support (ค้นหา "Auto Blocker"): <https://www.samsung.com/th/support/>
6. Samsung Knox — Auto Blocker: <https://www.samsungknox.com/>
7. Play Console Help — Set up an open, closed, or internal test: <https://support.google.com/googleplay/android-developer/answer/9845334>
8. Android Developers — In-app updates (Play Core): <https://developer.android.com/guide/playcore/in-app-updates>
9. Firebase App Distribution: <https://firebase.google.com/docs/app-distribution>
10. Android Developers — Android developer verification: <https://developer.android.com/developer-verification>
11. Play Console Help — Use of the REQUEST_INSTALL_PACKAGES permission: <https://support.google.com/googleplay/android-developer/answer/12085295>
12. Android Developers — Behavior changes: Android 16: <https://developer.android.com/about/versions/16/behavior-changes-all>