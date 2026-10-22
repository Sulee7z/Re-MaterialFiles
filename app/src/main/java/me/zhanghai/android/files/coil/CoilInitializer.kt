/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.os.Build
import coil.Coil
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import me.zhanghai.android.files.app.application

fun initializeCoil() {
    Coil.setImageLoader(
        ImageLoader.Builder(application)
            .components {
                add(AppIconApplicationInfoKeyer())
                add(AppIconApplicationInfoFetcherFactory(application))
                add(AppIconPackageNameKeyer())
                add(AppIconPackageNameFetcherFactory(application))
                add(PathAttributesKeyer())
                add(PathAttributesFetcher.Factory(application))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // Decode PNG, JPEG and WebP images with ImageDecoder, reading the encoded
                    // image from a file (or a file descriptor) instead of from a Java
                    // InputStream, so that the encoded bytes never travel through the Java
                    // heap. This is registered before Coil's own ImageDecoderDecoder, which
                    // keeps handling GIF images and animated HEIF images.
                    add(ImageDecoderFileDecoderFactory())
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(SvgDecoder.Factory(false))
            }
            .build()
    )
}
