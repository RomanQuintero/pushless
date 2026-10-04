package dev.pushless

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.content.Intent
import android.content.ComponentName
import android.app.job.JobInfo
import android.app.job.JobScheduler
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var diagnostic: TextView
    private var ws: WsChannel? = null
    private var mqtt: MqttChannel? = null
    private val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            val p = Experiment.prefs(this@MainActivity)
            val file = File(filesDir, "results.jsonl")
            val lines = if (file.exists()) file.readLines() else emptyList()
            val receipts = lines.mapNotNull { try { JSONObject(it) } catch (_: Exception) { null } }
                .filter { it.optString("kind") == "receipt" && it.optString("run") == Experiment.run(this@MainActivity) && it.optString("technique") == p.getString("technique", "none") }
            val last = receipts.lastOrNull()
            status.text = "Technique: ${p.getString("technique", "none")} / ${Experiment.run(this@MainActivity)}\nConnection: ${Experiment.connection}\nLast message: ${last?.optString("event_id") ?: "none"}\nLatency: N/A (clock offset unknown)\nMessages: ${receipts.count { !it.optBoolean("duplicate") }} received / missed: reconcile export / ${receipts.count { it.optBoolean("duplicate") }} duplicates\nLast event: ${last?.optLong("android_received_at") ?: "none"}"
            diagnostic.text = lines.takeLast(15).joinToString("\n")
            refreshHandler.postDelayed(this, 1000)
        }
    }

    private fun stopChannels() {
        ws?.stop(); ws = null
        mqtt?.stop(); mqtt = null
        stopService(Intent(this, ForegroundChannel::class.java))
        getSystemService(JobScheduler::class.java).cancel(16)
    }
    private fun startTechnique(technique: String) {
        val server = Experiment.url(this, "")
        require(server.startsWith("http://") || server.startsWith("https://")) { "Enter an HTTP server URL before starting" }
        stopChannels()
        Experiment.prefs(this).edit().putString("technique", technique).apply()
        when (technique) {
            "WebSocket" -> ws = WsChannel(applicationContext, technique).also { it.start() }
            "MQTT" -> mqtt = MqttChannel(applicationContext).also { it.start() }
            "Polling" -> {
                val result = getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(16, ComponentName(this, PollingJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15 * 60 * 1000L).build())
                Experiment.connection = "SCHEDULED"
                Experiment.log(this, JSONObject().put("kind", "poll_scheduled").put("schedule_result", result))
            }
            "ForegroundWS" -> startForegroundService(Intent(this, ForegroundChannel::class.java))
        }
    }
    private val manager by lazy { getSystemService(NotificationManager::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager.createNotificationChannel(NotificationChannel("events", "Experiment events", NotificationManager.IMPORTANCE_DEFAULT))
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
        }
        layout.addView(TextView(this).apply { text = "PUSHLESS"; textSize = 28f })
        status = TextView(this).apply { text = "Phase 1: local notification check\nDelivery techniques pending\nLatency: N/A (local event)" }
        layout.addView(status)
        val server = EditText(this).apply { setSingleLine(); setText(Experiment.prefs(this@MainActivity).getString("server", "")); hint = "http://PC_LAN_IP:8080" }
        val run = EditText(this).apply { setSingleLine(); setText(Experiment.run(this@MainActivity)); hint = "Unique run label" }
        layout.addView(server)
        layout.addView(run)
        val condition = EditText(this).apply { setSingleLine(); hint = "Condition (T1, T2...)"; setText("T1") }
        layout.addView(condition)
        val technique = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("WebSocket", "MQTT", "Polling", "ForegroundWS")) }
        layout.addView(technique)
        layout.addView(Button(this).apply {
            text = "Start selected technique"
            setOnClickListener {
                Experiment.prefs(this@MainActivity).edit().putString("server", server.text.toString()).putString("run", run.text.toString()).putString("condition", condition.text.toString()).apply()
                try { startTechnique(technique.selectedItem.toString()) }
                catch (e: Exception) { record("start_failed", e.toString()) }
            }
        })
        layout.addView(Button(this).apply { text = "Stop channel"; setOnClickListener { stopChannels() } })
        layout.addView(Button(this).apply {
            text = "Request notification permission"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                } else record("permission", "permission already granted or not required")
            }
        })
        layout.addView(Button(this).apply { text = "Send local test notification"; setOnClickListener { notifyLocal() } })
        diagnostic = TextView(this).apply { textSize = 12f }
        layout.addView(ScrollView(this).apply { addView(diagnostic) }, LinearLayout.LayoutParams(-1, 280))
        val outer = ScrollView(this).apply { addView(layout) }
        outer.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(outer)
        record("lifecycle", "onCreate; Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT}")
        if (intent.hasExtra("technique")) {
            Experiment.prefs(this).edit().putString("server", intent.getStringExtra("server") ?: server.text.toString())
                .putString("run", intent.getStringExtra("run") ?: run.text.toString())
                .putString("condition", intent.getStringExtra("condition") ?: "T1").apply()
            server.setText(Experiment.prefs(this).getString("server", ""))
            run.setText(Experiment.run(this))
            condition.setText(Experiment.prefs(this).getString("condition", "T1"))
            technique.setSelection(listOf("WebSocket", "MQTT", "Polling", "ForegroundWS").indexOf(intent.getStringExtra("technique")).coerceAtLeast(0))
            try { startTechnique(intent.getStringExtra("technique")!!) } catch (e: Exception) { record("start_failed", e.toString()) }
        }
    }

    private fun record(kind: String, detail: String) {
        val line = JSONObject().put("at", Instant.now().toString()).put("kind", kind).put("detail", detail).toString()
        File(filesDir, "diagnostics.jsonl").appendText("$line\n")
        Log.i("Pushless", line)
        diagnostic.append("$line\n")
    }

    private fun notifyLocal() {
        val id = UUID.randomUUID().toString()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            record("notification_blocked", "event_id=$id; runtime permission denied")
            return
        }
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel("events").importance == NotificationManager.IMPORTANCE_NONE) {
            record("notification_blocked", "event_id=$id; app or channel notifications disabled")
            return
        }
        manager.notify(id, 1, Notification.Builder(this, "events")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("PUSHLESS local test")
            .setContentText(id)
            .setAutoCancel(true).build())
        status.text = "Local event: $id\nLatency: N/A (local event)\nLast event: ${Instant.now()}"
        record("notification_submitted", "event_id=$id; notify() returned; visual display must be verified manually")
    }

    override fun onStart() { super.onStart(); Experiment.foreground = true; if (::diagnostic.isInitialized) { record("lifecycle", "onStart"); refreshHandler.post(refresh) } }
    override fun onStop() { Experiment.foreground = false; refreshHandler.removeCallbacks(refresh); record("lifecycle", "onStop"); super.onStop() }
    override fun onDestroy() { ws?.stop(); mqtt?.stop(); super.onDestroy() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        record("permission", "request=$requestCode granted=${grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED}")
    }
}
