package dev.pushless

import android.app.*
import android.app.job.*
import android.content.*
import android.os.Build
import android.os.IBinder
import okhttp3.Request
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

class MqttChannel(private val c: Context) {
    private var client: MqttAsyncClient? = null
    fun start() {
        val host = URI(Experiment.url(c, "")).host
        val mqtt = MqttAsyncClient("tcp://$host:1883", "pushless-${Experiment.run(c).hashCode()}", MemoryPersistence())
        client = mqtt
        mqtt.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String) {
                state("CONNECTED", "reconnect=$reconnect; clean session; QoS1")
                mqtt.subscribe("pushless/events", 1)
            }
            override fun connectionLost(cause: Throwable?) { state("DISCONNECTED", cause.toString()) }
            override fun messageArrived(topic: String, message: MqttMessage) {
                try { Experiment.receive(c, "MQTT", JSONObject(message.toString())) }
                catch (e: Exception) { Experiment.log(c, JSONObject().put("kind", "parse_failure").put("detail", e.toString())) }
            }
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        state("CONNECTING")
        mqtt.connect(MqttConnectOptions().apply { isCleanSession = true; isAutomaticReconnect = true; keepAliveInterval = 30 }, null, object : IMqttActionListener {
            override fun onSuccess(token: IMqttToken?) {}
            override fun onFailure(token: IMqttToken?, exception: Throwable?) { state("DISCONNECTED", "initial connect failed: $exception; press Start to retry") }
        })
    }
    private fun state(value: String, detail: String = "") {
        Experiment.connection = value
        Experiment.log(c, JSONObject().put("kind", "connection").put("technique", "MQTT").put("run", Experiment.run(c)).put("connection_state", value).put("detail", detail))
    }
    fun stop() {
        client?.let { mqtt ->
            Thread { try { mqtt.disconnectForcibly(); mqtt.close() } catch (_: Exception) {} }.start()
        }
        client = null
        state("DISCONNECTED", "user stop")
    }
}

class PollingJob : JobService() {
    @Volatile private var cancelled = false
    override fun onStartJob(params: JobParameters): Boolean {
        cancelled = false
        val run = Experiment.run(this)
        val key = "cursor:$run"
        val after = Experiment.prefs(this).getLong(key, 0)
        Experiment.log(this, JSONObject().put("kind", "poll_started").put("technique", "Polling").put("run", run))
        Thread {
            var retry = false
            try {
                Experiment.http.newCall(Request.Builder().url(Experiment.url(this, "/events?run=${Experiment.encoded(run)}&after=$after")).build()).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    val events = JSONArray(response.body!!.string())
                    for (i in 0 until events.length()) {
                        if (cancelled || run != Experiment.run(this)) break
                        val event = events.getJSONObject(i)
                        Experiment.connection = "POLL_RESPONSE"
                        Experiment.receive(this, "Polling", event)
                        Experiment.prefs(this).edit().putLong(key, event.getLong("seq")).commit()
                    }
                }
            } catch (e: Exception) {
                retry = true
                Experiment.log(this, JSONObject().put("kind", "poll_failed").put("run", run).put("detail", e.toString()))
            }
            if (!cancelled) jobFinished(params, retry)
        }.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        cancelled = true
        Experiment.log(this, JSONObject().put("kind", "poll_stopped").put("detail", "scheduler stopped job"))
        return true
    }
}

class ForegroundChannel : Service() {
    private var channel: WsChannel? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("service", "Foreground experiment channel", NotificationManager.IMPORTANCE_LOW))
        startForeground(100, Notification.Builder(this, "service").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("PUSHLESS foreground channel active").setContentText("Data sync experiment; stop in the app").setOngoing(true).build())
        channel?.stop()
        channel = WsChannel(applicationContext, "ForegroundWS").also { it.start() }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        Experiment.log(this, JSONObject().put("kind", "service_timeout").put("detail", "Android foreground dataSync time budget exhausted"))
        stopSelf()
    }
    override fun onDestroy() { channel?.stop(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
