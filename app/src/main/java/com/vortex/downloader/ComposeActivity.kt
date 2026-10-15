package com.vortex.downloader

import android.app.Activity
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.URL
import java.net.URLDecoder
import java.net.HttpURLConnection
import org.json.JSONObject

private val Night = Color(0xFF090914)
private val Panel = Color(0xFF1A1930)
private val Purple = Color(0xFFAA83FF)
private val Cyan = Color(0xFF5AEAFF)
private val Muted = Color(0xFFA9A8C5)
private val directExt = Regex("(?i)\\.(mp4|m4v|webm|mov|mp3|m4a|wav|aac|jpg|jpeg|png|webp|gif|pdf|zip)(?:[?#]|$)")
private val linkPattern = Regex("https://[^\\s<>]+")

class ComposeActivity : ComponentActivity() {
    private val store by lazy { getSharedPreferences("vortex_v1", MODE_PRIVATE) }
    private var incomingLink by mutableStateOf("")
    private var clipboardSuggestion by mutableStateOf("")
    private var refreshHistory by mutableIntStateOf(0)
    private var endpoint by mutableStateOf(store.getString("resolver_endpoint", "").orEmpty())
    private var quality by mutableStateOf("1080")
    private var busy by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptShare(intent)
        setContent { VortexScreen() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptShare(intent)
    }

    override fun onResume() {
        super.onResume()
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val candidate = try {
            manager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.let { extractUrl(it) }
        } catch (_: Exception) { null }
        clipboardSuggestion = candidate?.takeIf { it != incomingLink } ?: ""
        refreshHistory++
    }

    private fun acceptShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())?.let { incomingLink = it }
        }
    }

    private fun extractUrl(text: String): String? =
        linkPattern.find(text)?.value?.trimEnd('.', ',', ')', ']', '>')

    private fun isDirectMedia(url: String): Boolean = try {
        val parsed = URL(url)
        parsed.protocol.equals("https", true) &&
            !parsed.userInfo.isNullOrBlank().not() &&
            directExt.containsMatchIn(url) &&
            parsed.host.isNotBlank()
    } catch (_: Exception) { false }

    private fun filename(url: String): String {
        val raw = Uri.parse(url).lastPathSegment ?: "download.mp4"
        val decoded = try { URLDecoder.decode(raw, "UTF-8") } catch (_: Exception) { raw }
        return decoded.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .trimStart('.').take(90).ifBlank { "vortex-download" }
    }

    private fun enqueue(url: String): String {
        if (!isDirectMedia(url)) return "Unsupported link. Use a direct HTTPS media file URL."
        return try {
            val name = filename(url)
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setDescription("VORTEX download")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "VORTEX/$name")
            val id = (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            val ids = store.getString("ids", "").orEmpty().split(',').filter { it.isNotBlank() }
            store.edit().putString("ids", (listOf(id.toString()) + ids).distinct().take(100).joinToString(","))
                .putString("name_$id", name).apply()
            refreshHistory++
            "Queued $name"
        } catch (e: Exception) { "Download failed: ${e.localizedMessage}" }
    }

    private fun resolveAndQueue(source: String, report: (String) -> Unit) {
        val base = endpoint.trim().trimEnd('/')
        if (!base.startsWith("https://") || Uri.parse(base).host.isNullOrBlank()) {
            report("Configure your authorized HTTPS Cobalt API endpoint first.")
            return
        }
        if (!source.startsWith("https://", ignoreCase = true)) {
            report("A valid HTTPS media link is required.")
            return
        }
        if (busy) return
        busy = true
        Thread {
            val message = try {
                val connection = URL("$base/").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 12000
                connection.readTimeout = 25000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                val payload = JSONObject()
                    .put("url", source)
                    .put("videoQuality", quality)
                    .put("downloadMode", "auto").toString()
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val response = (if(status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                if (status !in 200..299) "Resolver HTTP $status: ${response.take(160)}"
                else {
                    val obj = JSONObject(response)
                    when (obj.optString("status")) {
                        "redirect", "tunnel" -> {
                            val target = obj.optString("url")
                            if (!target.startsWith("https://")) "Resolver returned an unsafe download URL"
                            else enqueueResolved(target, obj.optString("filename"))
                        }
                        "picker" -> {
                            val choices = obj.optJSONArray("picker")
                            var selected = ""
                            if (choices != null) {
                                for (i in 0 until choices.length()) {
                                    val item = choices.optJSONObject(i)
                                    if (item?.optString("type") == "video" && item.optString("url").startsWith("https://")) {
                                        selected = item.optString("url")
                                        break
                                    }
                                }
                            }
                            if (selected.isBlank()) "No downloadable video in media picker"
                            else enqueueResolved(selected, "vortex-video.mp4")
                        }
                        "error" -> "Resolver: ${obj.optJSONObject("error")?.optString("code") ?: "Unknown error"}"
                        "local-processing" -> "This source needs local FFmpeg processing (not included in this version)."
                        else -> "Unexpected resolver response"
                    }
                }
            } catch (e: Exception) { "Resolver error: ${e.localizedMessage}" }
            runOnUiThread { busy = false; refreshHistory++; report(message) }
        }.start()
    }

    private fun enqueueResolved(link: String, suggestedName: String): String {
        return try {
            val safeName = suggestedName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .trimStart('.').take(90).ifBlank { "vortex-video.mp4" }
            val request = DownloadManager.Request(Uri.parse(link))
                .setTitle(safeName)
                .setDescription("VORTEX resolved download")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "VORTEX/$safeName")
            val id = (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            val old = store.getString("ids", "").orEmpty()
            store.edit().putString("ids", (listOf(id.toString()) + old.split(',').filter { it.isNotBlank() }).distinct().take(100).joinToString(","))
                .putString("name_$id", safeName).apply()
            "Queued $safeName"
        } catch (e: Exception) { "Queue error: ${e.localizedMessage}" }
    }

    private fun downloads(): List<Pair<String, String>> {
        refreshHistory // compose state read to refresh after resume / enqueue
        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        return store.getString("ids", "").orEmpty().split(',')
            .mapNotNull { it.toLongOrNull() }.take(20).map { id ->
                val name = store.getString("name_$id", "Download").orEmpty()
                val status = try {
                    manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                        if (!cursor.moveToFirst()) "Removed"
                        else when(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                            DownloadManager.STATUS_SUCCESSFUL -> "Completed"
                            DownloadManager.STATUS_FAILED -> "Failed"
                            DownloadManager.STATUS_PAUSED -> "Paused"
                            DownloadManager.STATUS_RUNNING -> "Downloading"
                            else -> "Queued"
                        }
                    } ?: "Unknown"
                } catch (_: Exception) { "Unknown" }
                name to status
            }
    }

    @Composable
    private fun VortexScreen() {
        var url by remember { mutableStateOf(incomingLink) }
        var result by remember { mutableStateOf("Ready to download direct media") }
        LaunchedEffect(incomingLink) { if(incomingLink.isNotBlank()) url = incomingLink }
        val valid = remember(url) { isDirectMedia(url.trim()) }
        val name = remember(url) { if (valid) filename(url.trim()) else "" }
        MaterialTheme(colorScheme = darkColorScheme(
            background = Night, surface = Panel, primary = Purple, secondary = Cyan,
            onBackground = Color.White, onSurface = Color.White
        )) {
            Column(
                modifier = Modifier.fillMaxSize().background(Night)
                    .verticalScroll(rememberScrollState()).padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(20.dp))
                Text("VORTEX", fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 5.sp)
                Text("Paste. Pull. Save.", color = Muted, fontSize = 14.sp)
                Spacer(Modifier.height(26.dp))
                VortexAnimation { if (valid) result = enqueue(url.trim()) else resolveAndQueue(url.trim()) { result = it } }
                Text("TAP THE VORTEX TO DOWNLOAD", color = Purple, fontSize = 12.sp)
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Direct HTTPS media URL") },
                    placeholder = { Text("https://example.com/video.mp4") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                if (clipboardSuggestion.isNotBlank() && clipboardSuggestion != url) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { url = clipboardSuggestion }) {
                        Text("Use link from clipboard", color = Cyan)
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (valid) {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("MEDIA PREVIEW", color = Cyan, fontSize = 12.sp)
                            Spacer(Modifier.height(8.dp))
                            Text(name, fontWeight = FontWeight.SemiBold)
                            Text("Direct file · ${name.substringAfterLast('.', "file").uppercase()}", color = Muted)
                        }
                    }
                } else if(url.isNotBlank()) {
                    Text("Social link detected. Configure your own Cobalt-compatible resolver below to fetch the video.", color = Muted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it.trim(); store.edit().putString("resolver_endpoint", endpoint).apply() },
                    label = { Text("Cobalt API endpoint (your server)") },
                    placeholder = { Text("https://your-cobalt-instance.example") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("720", "1080", "2160").forEach { option ->
                        TextButton(onClick = { quality = option }) {
                            Text("${option}p", color = if(quality == option) Cyan else Muted)
                        }
                    }
                }
                Button(
                    onClick = {
                        if(valid) result = enqueue(url.trim())
                        else resolveAndQueue(url.trim()) { result = it }
                    },
                    enabled = url.startsWith("https://") && !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if(busy) "RESOLVING..." else if(valid) "DOWNLOAD FILE" else "FETCH VIDEO & DOWNLOAD")
                }
                Text(result, color = Muted, modifier = Modifier.padding(12.dp), textAlign = TextAlign.Center)
                OutlinedButton(onClick = {
                    startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                }, modifier = Modifier.fillMaxWidth()) { Text("OPEN ANDROID DOWNLOADS") }
                Spacer(Modifier.height(22.dp))
                Text("RECENT DOWNLOADS", modifier = Modifier.fillMaxWidth(), color = Cyan,
                    fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(10.dp))
                val items = downloads()
                if(items.isEmpty()) Text("No downloads yet", color = Muted)
                items.forEach { (file, status) ->
                    Card(colors = CardDefaults.cardColors(containerColor = Panel),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Text(file, fontWeight = FontWeight.Medium)
                            Text(status, color = Muted, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("Social links need a configured, authorized Cobalt instance. DRM and private content are unsupported. Only save media you have permission to download.",
                    color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun VortexAnimation(onTap: () -> Unit) {
    val loop = rememberInfiniteTransition(label = "vortex")
    val angle by loop.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "rotation"
    )
    Box(Modifier.fillMaxWidth().height(230.dp).clickable(onClick = onTap),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(210.dp)) {
            val d = size.minDimension
            for(i in 0..6) {
                val inset = (i * 9 + 7).dp.toPx()
                val color = if(i % 2 == 0) Purple else Cyan
                drawArc(
                    color = color.copy(alpha = 0.95f - i * 0.09f),
                    startAngle = angle + i * 37f,
                    sweepAngle = 220f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(d - inset * 2, d - inset * 2),
                    style = Stroke(width = (5 - i * 0.35f).dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
        Text("↓", color = Color.White, fontSize = 45.sp, fontWeight = FontWeight.Bold)
    }
}
