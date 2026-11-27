# Live Fleet NOC Dashboard — Drive Test LTE/5G (`v1.15.0`)

Web Console สำหรับศูนย์ปฏิบัติการโครงข่าย (Network Operations Center — NOC) แสดงตำแหน่งรถทดสอบสัญญาณภาคสนามแบบ Real-Time พร้อมวิเคราะห์ค่า RF Telemetry 5G NR / LTE แบบ Dual-SIM, เล่นข้อมูลย้อนหลัง (Historical Session Playback) และสร้างแผนที่ความครอบคลุมสัญญาณ (Multi-Vehicle Coverage Heatmap) อัตโนมัติโดยไม่ต้องมีขั้นตอน build

---

## ฟีเจอร์หลัก (Key Features)

1. **Live Fleet Map (Leaflet 1.9.4 + Dark NOC OpenStreetMap):**
   - แผนที่ธีมมืด (Dark NOC) ไม่มี watermark และไม่ต้องใช้ API Key แสดงตำแหน่งรถที่กำลังสตรีมสด (`isRecording === true` และ heartbeat ภายใน 2 นาที) พร้อมทิศทางหัวรถ (Bearing)
   - แถบ KPI ด้านบนแสดง **ACTIVE FLEET**, **TOTAL SAMPLES**, **ANOMALIES** และสถานะ **SYNC SOURCE** (`FIREBASE LIVE` / `FIREBASE ERROR`)
   - เส้นทางวิ่ง (Breadcrumb Trail) แยกสีตามเกณฑ์ RSRP มาตรฐาน 3GPP / ITU-R พร้อม Legend แบบพับเก็บได้:

| Tier | ช่วงค่า RSRP | ช่วงค่า SINR | ระดับคุณภาพ |
| --- | --- | --- | --- |
| T1 | `>= -80 dBm` | `>= 20 dB` | Excellent |
| T2 | `-80` ถึง `-90 dBm` | `13` ถึง `20 dB` | Good |
| T3 | `-90` ถึง `-100 dBm` | `5` ถึง `13 dB` | Fair |
| T4 | `-100` ถึง `-110 dBm` | `0` ถึง `5 dB` | Poor |
| T5 | `< -110 dBm` | `< 0 dB` | Coverage Hole / Interference |

2. **Historical Session Playback (Timeline Scrubber & Archive):**
   - สลับโหมด **LIVE FLEET** และ **HISTORICAL PLAYBACK** จากแถบด้านบนได้ทันที
   - ดึงรายการและเส้นทางย้อนหลังจาก Firebase RTDB (`/fleet/sessions` และ `/fleet/trails`) พร้อมระบบค้นหา (Vehicle / Session ID / SIM), กรองตามรถ (`All Vehicles`), เรียงลำดับ (`Date`, `RSRP`, `Points`) และปุ่มรีเฟรช
   - แถบควบคุม Timeline Scrubber: Play/Pause, Step Back/Forward, ปรับความเร็ว `1x` / `2x` / `5x` พร้อมแอนิเมชันหัวรถ เส้นทางที่เล่นแล้ว และเกจ Dual-SIM RF Inspector แบบเรียลไทม์
   - คีย์ลัดขณะอยู่ในโหมด Playback: `Space` (Play/Pause), `ArrowLeft` / `ArrowRight` (เลื่อนทีละ sample), `Esc` (ปิดหน้าต่างยืนยัน)
3. **Multi-Vehicle Coverage Heatmap (`rf-heatmap.js` — IDW Interpolation):**
   - เอนจินคำนวณการกระจายสัญญาณเชิงพื้นที่แบบ **Inverse Distance Weighting (IDW)** บน HTML5 Canvas ซิงก์กับ Leaflet โดยไม่มี dependency ภายนอกเพิ่ม
   - สลับมิติได้ระหว่าง **RSRP** (`-135` ถึง `-55 dBm`) และ **SINR** (`-10` ถึง `30 dB`) พร้อมสไลเดอร์ปรับความโปร่งใส (`15%–95%`, ค่าเริ่มต้น `52%`) และปรับ Legend อัตโนมัติ
   - ใช้งานได้ทั้งข้อมูลจากรถทุกคันในโหมด Live Fleet และข้อมูลเส้นทางในโหมด Historical Playback
4. **Dual-SIM RF Inspector:**
   - เปรียบเทียบสัญญาณสดหรือข้อมูลย้อนหลังของ **SIM 1 (Primary)** vs **SIM 2 (Benchmark)**:
     - Operator, RAT / Band (`5G SA` / `NSA` / `LTE`), PCI / NCI / Cell ID
     - เกจ RSRP และ SINR พร้อมหลอดแสดงผลตามระดับสี
     - คำนวณ **RSRP Advantage (`Δ RSRP`)** แบบ Real-Time
5. **Live RF Anomaly Feed & Fleet Controls:**
   - ตรวจจับจุดอับสัญญาณ (`COVERAGE_HOLE`: `RSRP < -110 dBm`) และพื้นที่สัญญาณรบกวน (`INTERFERENCE`: `RSRP >= -95 dBm` แต่ `SINR < 0 dB`) คลิกที่รายการเพื่อบินไปยังพิกัดบนแผนที่ได้ทันที
   - ปุ่มควบคุมบนแผนที่: **FIT ALL** (จัดกรอบแผนที่ให้เห็นทั้ง fleet/เส้นทาง), **CLEAR TRAILS** และ **PURGE OFFLINE** (มีหน้าต่างยืนยันก่อนลบรถที่ขาด heartbeat เกิน 2 นาทีออกจาก `/fleet/vehicles` โดยไม่กระทบข้อมูล historical sessions)
6. **Responsive & Collapsible Layout:**
   - **Desktop:** พับ/กางแผงด้านซ้าย (Fleet Roster / Sessions) และด้านขวา (Dual-SIM Inspector / Anomalies) เพื่อขยายพื้นที่แผนที่ได้เต็มจอ
   - **Mobile / Tablet:** แถบแท็บด้านล่าง (`MAP`, `FLEET`, `RF INSPECTOR`), Floating Bottom Mini Drawer สำหรับรถที่เลือก และพับ Legend อัตโนมัติเมื่อหน้าจอแคบ (`<= 768px`)

---

## การตั้งค่า (Configuration)

กำหนดค่าเชื่อมต่อ Firebase Realtime Database ที่ไฟล์ `noc-dashboard/env.js`:

```js
window.ENV = {
  FIREBASE_DB_URL: "https://<your-project>-default-rtdb.<region>.firebasedatabase.app",
  FIREBASE_AUTH_TOKEN: ""
};
```

- โหมด Live Fleet จะ poll `/fleet/vehicles.json` ทุก `1.5` วินาที
- กฎและ index ของฐานข้อมูลอยู่ใน [firebase/database.rules.json](../firebase/database.rules.json) (paths: `/fleet/vehicleClaims`, `/fleet/vehicles`, `/fleet/sessions`, `/fleet/trails`)
- ห้ามใส่ credential ลับลงในไฟล์ที่ถูกเสิร์ฟให้เบราว์เซอร์ (ดู `.assetsignore` สำหรับไฟล์ที่ถูกกันออกจาก Cloudflare Workers static assets)

---

## วิธีเปิดใช้งาน (How to Run)

### วิธีที่ 1: เปิดไฟล์ตรงๆ (Zero Setup)
เปิดไฟล์ `noc-dashboard/index.html` ด้วยเบราว์เซอร์ (Chrome / Edge / Firefox) ได้ทันที

### วิธีที่ 2: รันผ่าน Local Web Server (แนะนำ)

```powershell
# 1. ใช้ Go Local Server (จากโฟลเดอร์ noc-dashboard; ปิด cache ด้วย no-store และบล็อก dotfiles/.go/go.mod)
cd noc-dashboard
go run .
# ตัวเลือกเสริม: -host (default 127.0.0.1), -port (default 3000 หรือ env PORT), -dir, -open
go run . -port 3000 -open

# 2. หรือใช้ Node / Python จาก root ของ repo
npx serve noc-dashboard -p 3000
python -m http.server 3000 --directory noc-dashboard
```

เปิดเบราว์เซอร์ไปที่ `http://127.0.0.1:3000`

### วิธีที่ 3: Cloudflare Workers (Dev & Production)

อิงการตั้งค่าจาก `wrangler.jsonc` ที่ root ของ repo (เสิร์ฟ `noc-dashboard/` เป็น static assets ร่วมกับ Worker `worker/drive-upload.js`):

```powershell
npx wrangler dev
npx wrangler deploy
```

---

## วิธีเชื่อมต่อกับแอป Android (`Drive Test LTE/5G v1.13.16`)

1. เปิดแอป **Drive Test LTE/5G** บนมือถือ (ตั้งค่า Firebase RTDB และการสตรีม Telemetry ไว้พร้อมใช้งาน)
2. ระบบจะจอง **Vehicle ID** ที่ไม่ซ้ำกันอัตโนมัติผ่าน `/fleet/vehicleClaims` (สามารถแก้ไขชื่อ/รหัสรถได้ในแท็บ **Settings**)
3. กดปุ่ม **START** บนหน้าหลักเพื่อเริ่มบันทึกและสตรีมข้อมูลภาคสนาม
4. เมื่ออุปกรณ์ส่งพิกัดและค่า RF Dual-SIM รถจะปรากฏบนหน้าจอ NOC Dashboard แบบสดทันที และบันทึกเข้า Historical Playback เมื่อจบ session
