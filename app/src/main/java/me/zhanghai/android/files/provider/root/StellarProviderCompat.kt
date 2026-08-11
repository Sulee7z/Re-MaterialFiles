/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import android.os.Bundle
import android.util.Log
import com.stellar.api.BinderContainer
import roro.stellar.Stellar

/**
 * StellarProvider that handles the user-service binder delivery without calling
 * StellarService.attachUserService(): the current Stellar manager build cannot unmarshal
 * the com.stellar.api.BinderContainer from the attach extras and fails the whole user
 * service startup. The user service binder has already reached this provider by then, so
 * it is handed directly to the waiting StellarUserServiceCompat connection instead.
 *
 * Everything else (Stellar service binder delivery, binder queries) is delegated to the
 * official provider.
 */
class StellarProviderCompat : roro.stellar.StellarProvider() {

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_SEND_USER_SERVICE || extras == null) {
            return super.call(method, arg, extras)
        }
        Log.i(TAG, "Received user service binder (direct delivery)")
        extras.classLoader = BinderContainer::class.java.classLoader
        val container = extras.getParcelable<BinderContainer>(EXTRA_BINDER)
        if (container?.binder != null) {
            StellarUserServiceCompat.onUserServiceBinder(container.binder!!)
        }
        // Reply with the same payload the official provider would return, so the caller
        // (the user service process, via the manager) treats the delivery as successful.
        val reply = Bundle()
        reply.classLoader = BinderContainer::class.java.classLoader
        Stellar.binder?.let {
            reply.putParcelable(EXTRA_BINDER, BinderContainer(it))
        }
        Stellar.getClientBinder()?.let {
            reply.putParcelable(EXTRA_CLIENT_BINDER, BinderContainer(it))
        }
        return reply
    }

    companion object {
        private const val TAG = "StellarProviderCompat"
        private const val METHOD_SEND_USER_SERVICE = "sendUserService"
        private const val EXTRA_BINDER = "roro.stellar.manager.intent.extra.BINDER"
        private const val EXTRA_CLIENT_BINDER = "roro.stellar.manager.intent.extra.CLIENT_BINDER"
    }
}
