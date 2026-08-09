/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.ftp.client

import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Searches the Everything HTTP server JSON API, the same API used by the Everything web UI
 * and the EverythingDroid reference client:
 *
 *   GET /?s=<query>&j=1&path_column=1&count=<n>
 *
 * Returns `{"totalResults": N, "results": [{"type": "file|folder", "name": ..., "path": ...}]}`
 * where `path` is the result's parent directory (only present with `path_column=1`), so the
 * full Windows path is `<path>\<name>`.
 *
 * Everything's HTTP server is far more predictable than the ETP/FTP QUERY flow and is
 * confirmed to work against real servers, so it is used instead of SITE EVERYTHING QUERY.
 */
object EverythingHttpClient {

    private const val DEFAULT_HTTP_PORT = 80
    private const val CONNECT_TIMEOUT_MILLIS = 10_000
    private const val READ_TIMEOUT_MILLIS = 60_000

    // Matches the cap used by the local walk search (WalkFileTreeSearchable): beyond this
    // many matches the result loader becomes the bottleneck, so stop pulling more.
    private const val MAX_EVERYTHING_RESULTS = 1000

    /**
     * Searches the Everything HTTP server index. Result lines are raw Windows paths delivered
     * in batches to [listener].
     *
     * @param query the Everything search query (may include `path:"..."` scoping), sent
     *   URL-encoded as the `s` parameter.
     */
    @Throws(IOException::class)
    fun searchEverything(
        authority: Authority,
        query: String,
        intervalMillis: Long,
        listener: (List<String>) -> Unit
    ) {
        val httpPort = authority.everythingHttpPort.takeIf { it > 0 } ?: DEFAULT_HTTP_PORT
        val parameters = buildString {
            append("s=")
            append(URLEncoder.encode(query, "UTF-8").replace("+", "%20"))
            append("&j=1")
            append("&path_column=1")
            append("&count=")
            append(MAX_EVERYTHING_RESULTS)
        }
        val url = URL("http://${authority.host}:$httpPort/?$parameters")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            val isAnonymous = authority.username == Authority.ANONYMOUS_USERNAME
            if (!isAnonymous && authority.username.isNotEmpty()) {
                // The password is looked up the same way the FTP client does, so the HTTP
                // server credentials default to the FTP server ones.
                val password = Client.authenticator.getPassword(authority) ?: ""
                val credentials = Base64.getEncoder()
                    .encodeToString(
                        "${authority.username}:$password".toByteArray(StandardCharsets.UTF_8)
                    )
                connection.setRequestProperty("Authorization", "Basic $credentials")
            }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("Everything HTTP server returned $responseCode")
            }
            val responseBody = connection.inputStream.bufferedReader(StandardCharsets.UTF_8)
                .use { it.readText() }
            val root = JSONObject(responseBody)
            val results = root.optJSONArray("results") ?: return
            val batch = mutableListOf<String>()
            var resultCount = 0
            var lastProgressMillis = System.currentTimeMillis()
            for (index in 0 until results.length()) {
                val item = results.getJSONObject(index)
                val name = item.optString("name", "")
                if (name.isEmpty()) {
                    continue
                }
                val parentPath = item.optString("path", "")
                val windowsPath = if (parentPath.isEmpty()) {
                    name
                } else {
                    "${parentPath.trimEnd('\\')}\\$name"
                }
                batch.add(windowsPath)
                resultCount++
                val currentTimeMillis = System.currentTimeMillis()
                if (currentTimeMillis >= lastProgressMillis + intervalMillis) {
                    listener(batch.toList())
                    batch.clear()
                    lastProgressMillis = currentTimeMillis
                }
                if (resultCount >= MAX_EVERYTHING_RESULTS) {
                    break
                }
                if (Thread.interrupted()) {
                    throw InterruptedIOException()
                }
            }
            if (batch.isNotEmpty()) {
                listener(batch)
            }
        } finally {
            connection.disconnect()
        }
    }
}
