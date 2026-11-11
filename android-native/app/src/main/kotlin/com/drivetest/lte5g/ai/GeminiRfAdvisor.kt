package com.drivetest.lte5g.ai

import android.util.Log
import com.drivetest.lte5g.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

sealed interface GeminiAdvisorState {
    object Idle : GeminiAdvisorState
    object Analyzing : GeminiAdvisorState
    data class Success(val report: String, val timestamp: Long = System.currentTimeMillis()) : GeminiAdvisorState
    data class Error(val message: String) : GeminiAdvisorState
}

object GeminiRfAdvisor {
    private const val TAG = "GeminiRfAdvisor"
    private const val MODEL_NAME = "gemini-3.8-flash"
    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent"

    private val _state = MutableStateFlow<GeminiAdvisorState>(GeminiAdvisorState.Idle)
    val state: StateFlow<GeminiAdvisorState> = _state.asStateFlow()

    fun reset() {
        _state.value = GeminiAdvisorState.Idle
    }

    suspend fun analyzeDriveTest(telemetryJson: String): Result<String> {
        val apiKey = BuildConfig.GEMINI_API_KEY.trim()
        if (apiKey.isBlank()) {
            val errorMsg = "ไม่พบ GEMINI_API_KEY ใน local.properties (กรุณาระบุ GEMINI_API_KEY=xxx ในไฟล์ local.properties แล้ว Rebuild)"
            _state.value = GeminiAdvisorState.Error(errorMsg)
            return Result.failure(IllegalStateException(errorMsg))
        }

        _state.value = GeminiAdvisorState.Analyzing

        return withContext(Dispatchers.IO) {
            try {
                val url = URL(ENDPOINT)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    doInput = true
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("x-goog-api-key", apiKey)
                }

                val payload = buildRequestPayload(telemetryJson)
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(payload)
                    writer.flush()
                }

                val responseCode = conn.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val responseText = conn.inputStream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
                    val reportText = parseGeminiResponse(responseText)
                    _state.value = GeminiAdvisorState.Success(reportText)
                    Result.success(reportText)
                } else {
                    val errorStream = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText)
                    val errorMessage = parseErrorMessage(errorStream, responseCode)
                    Log.e(TAG, "Gemini API HTTP $responseCode: $errorMessage")
                    _state.value = GeminiAdvisorState.Error(errorMessage)
                    Result.failure(RuntimeException(errorMessage))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gemini request failed", e)
                val msg = "การเชื่อมต่อล้มเหลว: ${e.localizedMessage ?: e.message}"
                _state.value = GeminiAdvisorState.Error(msg)
                Result.failure(e)
            }
        }
    }

    private fun buildRequestPayload(telemetryJson: String): String {
        val root = JSONObject()

        val systemInstruction = JSONObject()
        val sysParts = JSONArray()
        val sysText = JSONObject()
        sysText.put(
            "text",
            """
            คุณคือ Senior 5G/LTE Radio Frequency (RF) Optimization Specialist ประจำโครงข่ายโทรศัพท์เคลื่อนที่
            หน้าที่ของคุณคือวิเคราะห์ข้อมูล Drive Test Telemetry, CDF Percentiles, สถิติความแรงสัญญาณ RSRP/RSRQ/SINR, ปัญหา Coverage Holes, Interference, และ Handover Ping-Pong ที่ได้รับ
            
            กรุณาจัดทำรายงานวิเคราะห์ฉบับกระชับ ตรงประเด็น เป็นภาษาไทย (ทับศัพท์เทคนิควิศวกรรม RF มาตรฐานสากลได้) โดยมีโครงสร้างดังนี้:
            1. 📊 สรุปผลการประเมินภาพรวม (Executive Summary & Health Verdict)
            2. 🔍 วิเคราะห์สาเหตุรากเหง้า (Root Cause Analysis ของจุดอับสัญญาณ, สัญญาณรบกวน หรือ Ping-Pong)
            3. 🛠️ ข้อเสนอแนะการจูนโครงข่าย (Actionable Network Tuning Recommendations เช่น ปรับ Electrical/Mechanical Antenna Tilt, ปรับ TX Power, ปรับ Handover A3-Offset / Time-To-Trigger, หรือ PCI Re-planning)
            4. ⚡ สิ่งที่ทีมควรเร่งดำเนินการแก้ไข 3 อันดับแรก (Top 3 Priority Actions)
            """.trimIndent()
        )
        sysParts.put(sysText)
        systemInstruction.put("parts", sysParts)
        root.put("system_instruction", systemInstruction)

        val contents = JSONArray()
        val contentObj = JSONObject()
        contentObj.put("role", "user")
        val userParts = JSONArray()
        val userText = JSONObject()
        userText.put(
            "text",
            """
            นี่คือชุดข้อมูล Telemetry สรุปผลจากเส้นทาง Drive Test ที่บันทึกได้จริง:
            
            ```json
            $telemetryJson
            ```
            
            โปรดวิเคราะห์และให้คำแนะนำทางวิศวกรรม RF อย่างละเอียดและตรงจุด
            """.trimIndent()
        )
        userParts.put(userText)
        contentObj.put("parts", userParts)
        contents.put(contentObj)
        root.put("contents", contents)

        val genConfig = JSONObject()
        genConfig.put("temperature", 0.2)
        genConfig.put("maxOutputTokens", 2048)
        root.put("generationConfig", genConfig)

        return root.toString()
    }

    private fun parseGeminiResponse(jsonText: String): String {
        val root = JSONObject(jsonText)
        val candidates = root.optJSONArray("candidates")
        if (candidates != null && candidates.length() > 0) {
            val firstCandidate = candidates.getJSONObject(0)
            val content = firstCandidate.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            if (parts != null && parts.length() > 0) {
                val sb = StringBuilder()
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    sb.append(part.optString("text", ""))
                }
                return sb.toString().trim()
            }
        }
        return "ไม่สามารถประมวลผลคำตอบจาก Gemini ได้"
    }

    private fun parseErrorMessage(errorJson: String?, responseCode: Int): String {
        if (errorJson.isNullOrBlank()) {
            return "Gemini API เกิดข้อผิดพลาด HTTP $responseCode"
        }
        return try {
            val root = JSONObject(errorJson)
            val errorObj = root.optJSONObject("error")
            val msg = errorObj?.optString("message") ?: errorJson
            "HTTP $responseCode: $msg"
        } catch (_: Exception) {
            "HTTP $responseCode: $errorJson"
        }
    }
}
