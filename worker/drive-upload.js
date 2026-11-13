// Cloudflare Worker: server-side Google Drive upload proxy.
// The Google credential (refresh token) lives only in Worker secrets; the Android app never holds one.
// Secrets: GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, GOOGLE_REFRESH_TOKEN, APP_AUTH_TOKEN, GDRIVE_FOLDER_ID (optional)

const DRIVE_UPLOAD_URL = 'https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id'
const TOKEN_URL = 'https://oauth2.googleapis.com/token'
const UPLOAD_PATH = '/api/drive-upload'

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8' },
  })
}

function constantTimeEqual(a, b) {
  const enc = new TextEncoder()
  const left = enc.encode(a)
  const right = enc.encode(b)
  if (left.length !== right.length) return false
  let diff = 0
  for (let i = 0; i < left.length; i += 1) diff |= left[i] ^ right[i]
  return diff === 0
}

async function getAccessToken(env) {
  const body = new URLSearchParams({
    client_id: env.GOOGLE_CLIENT_ID,
    client_secret: env.GOOGLE_CLIENT_SECRET,
    refresh_token: env.GOOGLE_REFRESH_TOKEN,
    grant_type: 'refresh_token',
  })
  const res = await fetch(TOKEN_URL, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body,
  })
  if (!res.ok) throw new Error(`token exchange failed (HTTP ${res.status})`)
  const data = await res.json()
  if (!data.access_token) throw new Error('token exchange returned no access_token')
  return data.access_token
}

async function uploadFile(request, env) {
  const form = await request.formData()
  const file = form.get('file')
  if (!file || typeof file === 'string') return jsonResponse({ error: 'file part required' }, 400)

  const name = (form.get('name') || file.name || 'upload.bin').toString()
  const mimeType = file.type || 'application/octet-stream'
  const folderId = (form.get('folderId') || env.GDRIVE_FOLDER_ID || '').toString()

  const accessToken = await getAccessToken(env)
  const boundary = `dt_${crypto.randomUUID()}`
  const metadata = JSON.stringify({
    name,
    ...(folderId ? { parents: [folderId] } : {}),
  })
  const head =
    `--${boundary}\r\n` +
    'Content-Type: application/json; charset=UTF-8\r\n\r\n' +
    `${metadata}\r\n` +
    `--${boundary}\r\n` +
    `Content-Type: ${mimeType}\r\n\r\n`
  const tail = `\r\n--${boundary}--\r\n`
  const payload = new Blob([head, await file.arrayBuffer(), tail])

  const res = await fetch(DRIVE_UPLOAD_URL, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${accessToken}`,
      'content-type': `multipart/related; boundary=${boundary}`,
    },
    body: payload,
  })

  const text = await res.text()
  if (!res.ok) return jsonResponse({ error: 'drive upload failed', detail: text }, 502)

  let id = ''
  try {
    id = JSON.parse(text).id || ''
  } catch {
    id = ''
  }
  return jsonResponse({ id })
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url)
    if (url.pathname !== UPLOAD_PATH) return env.ASSETS.fetch(request)

    if (request.method !== 'POST') return jsonResponse({ error: 'method not allowed' }, 405)
    if (!env.APP_AUTH_TOKEN || !env.GOOGLE_REFRESH_TOKEN || !env.GOOGLE_CLIENT_ID) {
      return jsonResponse({ error: 'server not configured' }, 500)
    }

    const given = request.headers.get('x-app-auth') || ''
    if (!constantTimeEqual(given, env.APP_AUTH_TOKEN)) {
      return jsonResponse({ error: 'unauthorized' }, 401)
    }

    try {
      return await uploadFile(request, env)
    } catch (err) {
      return jsonResponse({ error: 'upload failed', detail: String(err && err.message ? err.message : err) }, 502)
    }
  },
}