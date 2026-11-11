# Live Fleet NOC Dashboard — Drive Test LTE/5G

Web Console สำหรับศูนย์ปฏิบัติการโครงข่าย (Network Operations Center — NOC) แสดงตำแหน่งรถทดสอบสัญญาณภาคสนาม (Drive Test Fleet) แบบ Real-Time พร้อมวิเคราะห์ค่า RF Telemetry 5G NR / LTE (Dual-SIM) และตรวจจับพื้นที่สัญญาณบกพร่อง (RF Anomalies) อัตโนมัติ

---

## 🌟 ฟีเจอร์หลัก (Key Features)

1. **Live Fleet Map (Leaflet.js + CartoDB Dark Matter):**
   - แผนที่ธีมมืด (Dark NOC) แสดงตำแหน่งรถทดสอบทุกคันพร้อมทิศทางหัวรถ (Bearing) และแถบแสงเรืองรองตามสถานะ
   - เส้นทางวิ่ง (Breadcrumb Trail) แยกสีตามเกณฑ์ RSRP มาตรฐาน 3GPP / ITU-R:
     - 🟢 **≥ -80 dBm** (Excellent)
     - 🔵 **-80 ถึง -90 dBm** (Good)
     - 🟡 **-90 ถึง -100 dBm** (Fair)
     - 🟠 **-100 ถึง -110 dBm** (Poor)
     - 🔴 **< -110 dBm** (Coverage Hole)
2. **Dual-SIM Telemetry Inspector:**
   - ตรวจสอบค่าสัญญาณสดของรถที่เลือกเปรียบเทียบ **SIM 1 (Primary)** vs **SIM 2 (Benchmark)**:
     - Operator, RAT (5G SA / NSA / LTE), Band, PCI, Cell ID
     - RSRP และ SINR พร้อมหลอดแสดงผลแบบไดนามิก
     - คำนวณค่าความต่าง **Δ RSRP (RSRP Advantage)** แบบ Real-Time
3. **Real-Time RF Anomaly Alert Feed:**
   - ตรวจจับจุดอับสัญญาณ (`COVERAGE_HOLE`: RSRP < -110 dBm)
   - ตรวจจับพื้นที่สัญญาณรบกวนสูง (`INTERFERENCE`: RSRP ≥ -95 dBm แต่ SINR < 0 dB)
   - คลิกที่การแจ้งเตือนเพื่อบิน (Fly-to) ไปยังพิกัดที่มีปัญหาบนแผนที่ได้ทันที
4. **Built-in Demo Fleet Simulator:**
   - โหมดจำลองรถวิ่ง 3 คันในกรุงเทพฯ (ดอนเมือง / สยาม / บางนา) พร้อมค่า RF จริง
   - เปิดทดสอบหน้าจอได้ทันทีโดยไม่ต้องต่อ Backend
5. **Real-Time Firebase Sync:**
   - รองรับการเชื่อมต่อ **Firebase Realtime Database** (`/fleet/vehicles` และ `/fleet/trails`)
   - รองรับทั้งการเปิดผ่าน Browser ธรรมดา (file://) หรือผ่าน Web Server (Nginx / Vercel / GitHub Pages / Vite)

---

## 🚀 วิธีเปิดใช้งาน (How to Run)

### วิธีที่ 1: เปิดไฟล์ตรงๆ (Zero Setup)
ดับเบิลคลิกเปิดไฟล์ `noc-dashboard/index.html` บนเบราว์เซอร์ (Chrome / Edge / Firefox) ได้ทันที

### วิธีที่ 2: รันผ่าน Local Web Server (แนะนำ)
```bash
# ใช้ npx serve
npx serve noc-dashboard -p 3000

# หรือใช้ Python
python -m http.server 3000 --directory noc-dashboard
```
เปิดเบราว์เซอร์ไปที่ `http://localhost:3000`

---

## 📡 วิธีเชื่อมต่อกับแอป Android (Drive Test LTE/5G v1.12.0)

1. เปิดแอป **Drive Test LTE/5G** บนมือถือ
2. ไปที่แท็บ **Settings** > หมวด **LIVE CLOUD FLEET TELEMETRY & NOC**
3. กำหนด **Vehicle ID** (เช่น `DT-01`) และ **ชื่อรถ** (เช่น `Unit 01`)
4. ใส่ **Firebase Realtime DB URL** (เช่น `https://<project-id>-default-rtdb.asia-southeast1.firebasedatabase.app`)
5. เปิดสวิตช์ **สตรีม Telemetry สดเข้า Firebase**
6. บนหน้าเว็บ **NOC Dashboard** คลิกปุ่ม **"FIREBASE SYNC"** ที่มุมขวาบน แล้วใส่ URL เดียวกัน
7. เมื่อรถเริ่มบันทึกสัญญาณ (กด Start Drive Test) พิกัดและค่า RF จะปรากฏบนหน้าจอ NOC ทันที!
