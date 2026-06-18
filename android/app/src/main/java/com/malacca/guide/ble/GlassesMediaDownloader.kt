package com.malacca.guide.ble

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads media from the glasses' on-device HTTP server once the phone has
 * joined the WiFi Direct group and learned the glasses' IP. The glasses expose
 * a file list at /files/media.config and the files themselves under /files/.
 */
object GlassesMediaDownloader {

    private const val TAG = "GlassesMediaDownloader"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Returns the list of image file names the glasses have available. */
    suspend fun fetchImageList(deviceIp: String): List<String> = withContext(Dispatchers.IO) {
        val url = "http://$deviceIp/files/media.config"
        Log.d(TAG, "fetchImageList: $url")
        try {
            val request = Request.Builder().url(url).build()
            val body = client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                resp.body?.string() ?: ""
            }
            val files = body.lineSequence()
                .map { it.trim() }
                .filter { it.endsWith(".jpg", ignoreCase = true) || it.endsWith(".jpeg", ignoreCase = true) }
                .toList()
            Log.d(TAG, "fetchImageList: ${files.size} image(s) -> $files")
            files
        } catch (e: Exception) {
            Log.e(TAG, "fetchImageList failed: ${e.message}")
            emptyList()
        }
    }

    /** Downloads a single file from the glasses, returning its raw bytes. */
    suspend fun downloadFile(deviceIp: String, fileName: String): ByteArray? = withContext(Dispatchers.IO) {
        val url = "http://$deviceIp/files/$fileName"
        Log.d(TAG, "downloadFile: $url")
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val bytes = resp.body?.bytes()
                Log.d(TAG, "downloadFile: $fileName -> ${bytes?.size ?: 0} bytes")
                bytes
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadFile failed: ${e.message}")
            null
        }
    }

    /**
     * Finds the glasses' HTTP server on the WiFi Direct subnet. Tries the
     * supplied candidate IPs (in priority order) first, then probes
     * 192.168.49.2..120 concurrently as a last resort.
     */
    suspend fun discoverGlassesIp(preferred: List<String>): String? = withContext(Dispatchers.IO) {
        val probeClient = OkHttpClient.Builder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .build()
        for (ip in preferred) {
            if (ip.isNotBlank() && probe(probeClient, ip)) {
                Log.d(TAG, "discoverGlassesIp: found at candidate $ip")
                return@withContext ip
            }
        }
        val found = coroutineScope {
            (2..120).map { i ->
                async {
                    val ip = "192.168.49.$i"
                    if (probe(probeClient, ip)) ip else null
                }
            }.awaitAll().firstOrNull { it != null }
        }
        Log.d(TAG, "discoverGlassesIp: probe-sweep result=$found")
        found
    }

    private fun probe(client: OkHttpClient, ip: String): Boolean = try {
        val request = Request.Builder().url("http://$ip/files/media.config").build()
        client.newCall(request).execute().use { it.isSuccessful }
    } catch (e: Exception) {
        false
    }
}
