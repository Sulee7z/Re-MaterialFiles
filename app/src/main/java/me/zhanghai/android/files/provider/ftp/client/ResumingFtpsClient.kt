/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.ftp.client

import org.apache.commons.net.ftp.FTPSClient
import java.net.Socket
import java.util.LinkedHashSet
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket

/**
 * FTPS client that resumes the control connection's TLS session on the data connection.
 *
 * Servers like vsftpd (require_ssl_reuse=YES, the default) and FileZilla Server ("Require
 * TLS session resumption on data connection") reject data connections that do not resume
 * the control session; stock commons-net never resumes (NET-744) and silently returns an
 * empty listing, which makes encrypted FTP appear broken. TLS 1.3 sessions are single-use,
 * so TLS 1.2 is pinned and Conscrypt's client session cache is seeded for the data
 * connection's host and port with the exact value it already stored for the control
 * session.
 */
class ResumingFtpsClient(implicit: Boolean) : FTPSClient("TLS", implicit) {

    init {
        // TLS 1.3 sessions cannot be resumed on another connection.
        setEnabledProtocols(arrayOf("TLSv1.2"))
    }

    // Called after the data socket has connected but before its TLS handshake, so the
    // socket's address/port identify the cache entry the handshake will look up.
    override fun _prepareDataSocket_(socket: Socket) {
        if (socket !is SSLSocket) {
            return
        }
        val controlSocket = _socket_ as? SSLSocket ?: return
        val controlSession = try {
            controlSocket.session
        } catch (e: Throwable) {
            null
        } ?: return
        if (!controlSession.isValid) {
            return
        }
        try {
            seedConscryptSessionCache(controlSession, socket)
        } catch (e: Throwable) {
            // Best effort: servers that don't require reuse still work without this.
        }
    }

    private fun seedConscryptSessionCache(controlSession: SSLSession, dataSocket: SSLSocket) {
        val context = controlSession.sessionContext ?: return
        val contextClass = context.javaClass
        val cacheField = try {
            contextClass.getDeclaredField("sessionsByHostAndPort")
        } catch (e: NoSuchFieldException) {
            return
        }
        cacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(context) as? MutableMap<Any, Any> ?: return
        val keyClass = Class.forName("${contextClass.name}\$HostAndPort")
        val keyConstructor = keyClass
            .getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val controlHost = controlSession.peerHost ?: return
        val cachedValue = synchronized(cache) {
            cache[keyConstructor.newInstance(controlHost, controlSession.peerPort)]
                ?: findByPort(cache, controlSession.peerPort)
        } ?: return
        val reusableValue = pickReusable(cachedValue)
        // Seed every host string the data handshake could look up: the connected socket's
        // address (the usual one), the passive host, and the control session's host.
        val dataHosts = LinkedHashSet<String>(4)
        dataSocket.inetAddress?.hostAddress?.let(dataHosts::add)
        passiveHost?.let(dataHosts::add)
        controlHost.let(dataHosts::add)
        val dataPort = dataSocket.port
        synchronized(cache) {
            for (host in dataHosts) {
                cache[keyConstructor.newInstance(host, dataPort)] = reusableValue
            }
        }
    }

    /** On API 29+ the cache value is a list; keep only entries that can be resumed. */
    private fun pickReusable(value: Any): Any {
        if (value !is List<*>) {
            return value
        }
        for (element in value) {
            if (element == null) {
                continue
            }
            try {
                val isSingleUse = element.javaClass.getDeclaredMethod("isSingleUse")
                    .apply { isAccessible = true }
                if (isSingleUse.invoke(element) == false) {
                    return listOf(element)
                }
            } catch (e: Throwable) {
            }
        }
        return value
    }

    private fun findByPort(cache: Map<Any, Any>, port: Int): Any? {
        for ((key, candidate) in cache) {
            try {
                val portField = key.javaClass.getDeclaredField("port")
                    .apply { isAccessible = true }
                if (portField.getInt(key) == port) {
                    return candidate
                }
            } catch (e: Throwable) {
            }
        }
        return null
    }
}
