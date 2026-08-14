/*
 * Copyright (c) 2022 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.ftp.client

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.provider.common.UriAuthority
import me.zhanghai.android.files.util.takeIfNotEmpty
import java.nio.charset.StandardCharsets

@Parcelize
data class Authority(
    val protocol: Protocol,
    val host: String,
    val port: Int,
    val username: String,
    val mode: Mode,
    val encoding: String,
    val everythingWindowsRoot: String = "",
    // Port of the Everything HTTP server used for search (JSON API); 0 means
    // unset, in which case the default of 80 is used.
    val everythingHttpPort: Int = 0
) : Parcelable {
    fun toUriAuthority(): UriAuthority {
        val userInfo = username.takeIfNotEmpty()
        val uriPort = port.takeIf { it != protocol.defaultPort }
        return UriAuthority(userInfo, host, uriPort)
    }

    /**
     * Compares the connection-relevant fields of this authority with [other], ignoring the
     * Everything search configuration. This is used as a fallback for password lookup, since a
     * path parsed back from an URI may have lost the Everything fields (e.g. [everythingHttpPort]
     * defaulting to 0 while a saved server stores 80).
     */
    fun sameConnection(other: Authority): Boolean =
        protocol == other.protocol
            && host == other.host
            && port == other.port
            && username == other.username
            && mode == other.mode
            && encoding == other.encoding

    override fun toString(): String = toUriAuthority().toString()

    companion object {
        // @see https://www.rfc-editor.org/rfc/rfc1635
        const val ANONYMOUS_USERNAME = "anonymous"
        const val ANONYMOUS_PASSWORD = "guest"
        val DEFAULT_MODE = Mode.PASSIVE
        val DEFAULT_ENCODING = StandardCharsets.UTF_8.name()!!
    }
}
