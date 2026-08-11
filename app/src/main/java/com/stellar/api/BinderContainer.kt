/*
 * Copied from the Stellar-API repository (https://github.com/roro2239/Stellar-API,
 * GPL-3.0) because the JitPack build of the provider module predates this class and the
 * Stellar service serializes it into Parcels that this app must deserialize.
 */

package com.stellar.api

import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.os.Parcelable.Creator

/**
 * A Parcelable wrapper for an [IBinder], used by the Stellar service to deliver the user
 * service binder into the client application's StellarProvider.
 */
open class BinderContainer(var binder: IBinder?) : Parcelable {

    protected constructor(parcel: Parcel) : this(parcel.readStrongBinder())

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeStrongBinder(binder)
    }

    companion object {
        @JvmField
        val CREATOR: Creator<BinderContainer?> = object : Creator<BinderContainer?> {
            override fun createFromParcel(source: Parcel): BinderContainer = BinderContainer(source)
            override fun newArray(size: Int): Array<BinderContainer?> = arrayOfNulls(size)
        }
    }
}
