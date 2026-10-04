package dev.pushless

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object Experiment {
    val http = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()
    @Volatile var foreground = false
    @Volatile var connection = "DISCONNECTED"
    fun prefs(c: Context) = c.getSharedPreferences("experiment", Context.MODE_PRIVATE)
    fun url(c: Context, path: String): String = prefs(c).getString("server", "")!!.trimEnd('/') + path
    fun run(c: Context) = prefs(c).getString("run", "first-ws")!!
    fun encoded(value: String) = URLEncoder.encode(value, "UTF-8")
    @Synchronized fun log(c: Context, row: JSONObject) {
        row.put("logged_at", System.currentTimeMillis()).put("app_state", if (foreground) "foreground" else "background").put("experiment_state", prefs(c).getString("condition", "T1"))
        File(c.filesDir, "results.jsonl").appendText("$row\n")
        Log.i("Pushless", row.toString())
    }
    @Synchronized fun receive(c: Context, technique: String, event: JSONObject) {
        if (event.getString("run") != run(c)) return
        val received = System.currentTimeMillis()
        val p = prefs(c)
        val key = "seen:${run(c)}:$technique:${event.getString("id")}"
        val duplicate = p.contains(key)
        var notificationAt: Long? = null
        var outcome = if (duplicate) "duplicate_suppressed" else "notification_blocked"
        if (!duplicate) {
            p.edit().putBoolean(key, true).commit()
            val manager = c.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("events", "Experiment events", NotificationManager.IMPORTANCE_DEFAULT))
            val granted = Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (granted && manager.areNotificationsEnabled() && manager.getNotificationChannel("events").importance != NotificationManager.IMPORTANCE_NONE) {
                try {
                    manager.notify(event.getString("id"), 1, Notification.Builder(c, "events").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("PUSHLESS $technique").setContentText(event.getString("payload")).build())
                    notificationAt = System.currentTimeMillis()
                    outcome = "notification_submitted"
                } catch (e: Exception) { outcome = "notification_failed:${e.javaClass.simpleName}" }
            }
        }
        val row = JSONObject().put("kind", "receipt").put("technique", technique).put("run", run(c))
            .put("event_id", event.getString("id")).put("seq", event.getLong("seq"))
            .put("server_sent_at", event.getLong("sent_at")).put("android_received_at", received)
            .put("notification_created_at", notificationAt ?: JSONObject.NULL)
            .put("delivery_latency_ms", JSONObject.NULL).put("latency_valid", false)
            .put("raw_clock_delta_ms", received - event.getLong("sent_at"))
            .put("clock_note", "PC/device offset not calibrated; no valid one-way latency")
            .put("connection_state", connection).put("duplicate", duplicate).put("success", true)
            .put("notification_outcome", outcome)
        log(c, row)
        p.edit().putString("last", row.toString()).apply()
    }
}

class WsChannel(private val c: Context, private val technique: String) {
    private var socket: WebSocket? = null
    private var active = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val reconnect = Runnable { if (active) connect() }
    fun start() { active = true; connect() }
    private fun state(value: String, detail: String = "") {
        Experiment.connection = value
        Experiment.log(c, JSONObject().put("kind", "connection").put("technique", technique).put("run", Experiment.run(c)).put("connection_state", value).put("detail", detail))
    }
    private fun connect() {
        state("CONNECTING")
        val url = Experiment.url(c, "/ws?run=${Experiment.encoded(Experiment.run(c))}").replaceFirst("http", "ws")
        socket = Experiment.http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { if (active) state("CONNECTED") }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try { Experiment.receive(c, technique, JSONObject(text)) }
                catch (e: Exception) { Experiment.log(c, JSONObject().put("kind", "parse_failure").put("detail", e.toString())) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { failed(t.toString()) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { failed("close $code $reason") }
        })
    }
    private fun failed(detail: String) { if (active) { state("DISCONNECTED", detail); handler.removeCallbacks(reconnect); handler.postDelayed(reconnect, 5000) } }
    fun stop() { active = false; handler.removeCallbacks(reconnect); socket?.cancel(); state("DISCONNECTED", "user stop") }
}
