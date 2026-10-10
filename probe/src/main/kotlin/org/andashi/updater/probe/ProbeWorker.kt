package org.andashi.updater.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant

/**
 * One probe run: promote to a dataSync foreground service, fetch one URL,
 * record exactly what happened. The record is the measurement the design
 * waits for (§16): whether this shape of background work gets network in a
 * zone whose VPN is in lockdown.
 *
 * Every field of the record is something the system reported, never an
 * inference. "ok" means the body arrived in full with an HTTP 200 and its
 * SHA-256 is recorded, so a stale cache or a captive portal cannot pass as
 * success.
 */
class ProbeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(applicationContext)

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: DEFAULT_URL
        val trigger = inputData.getString(KEY_TRIGGER) ?: "unknown"
        val record = ProbeRecord(
            startedAt = Instant.now().toString(),
            trigger = trigger,
            url = url,
            attempt = runAttemptCount,
        )
        // The whole point: the fetch runs inside a foreground service, not a
        // plain job. If promotion itself fails, that is a finding too.
        try {
            setForeground(foregroundInfo(applicationContext))
            record.foreground = true
        } catch (e: Exception) {
            record.foreground = false
            record.error = "setForeground: ${e.javaClass.simpleName}: ${e.message}"
            ProbeStore.append(applicationContext, record.finish())
            return Result.failure(workDataOf("error" to record.error))
        }
        record.network = describeNetwork(applicationContext)
        withContext(Dispatchers.IO) { fetch(url, record) }
        // A cancelled run (CANCEL) writes nothing: the store was just cleared.
        if (isStopped) return Result.failure()
        ProbeStore.append(applicationContext, record.finish())
        return if (record.ok) Result.success() else Result.failure(workDataOf("error" to record.error))
    }

    private fun fetch(url: String, record: ProbeRecord) {
        val t0 = System.nanoTime()
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                // No conditional request: the probe wants bytes, not a 304.
                setRequestProperty("Cache-Control", "no-cache")
            }
            record.httpStatus = conn.responseCode
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            // A 4xx/5xx has no inputStream (it throws); the error stream is
            // read instead so the record says "http 404, n bytes", not
            // "FileNotFoundException".
            val stream = if (conn.responseCode >= 400) (conn.errorStream ?: java.io.ByteArrayInputStream(ByteArray(0))) else conn.inputStream
            stream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    bytes += n
                }
            }
            record.bytes = bytes
            record.sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            record.ok = record.httpStatus == 200 && bytes > 0
            if (!record.ok) record.error = "http ${record.httpStatus}, $bytes bytes"
        } catch (e: Exception) {
            record.ok = false
            record.error = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            conn?.disconnect()
            record.durationMs = (System.nanoTime() - t0) / 1_000_000
        }
    }

    /** What ConnectivityManager says about the active network at fetch time, so a "no network" failure can be told from a "network, but no route" one. */
    private fun describeNetwork(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return "none"
        val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
        val flags = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cell")
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) add("internet")
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) add("validated")
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) add("unmetered")
        }
        return flags.joinToString(",")
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_TRIGGER = "trigger"
        const val DEFAULT_URL = "https://raw.githubusercontent.com/andashi/provisioning/main/apks/lock.json"
        private const val CHANNEL = "probe"
        private const val NOTIFICATION_ID = 1

        fun foregroundInfo(context: Context): ForegroundInfo {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Probe", NotificationManager.IMPORTANCE_LOW),
            )
            val notification = Notification.Builder(context, CHANNEL)
                .setContentTitle("Updater probe")
                .setContentText("Fetching one URL inside a foreground service")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build()
            return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
    }
}
