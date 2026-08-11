/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apkkiller

/**
 * The signature-killer Application, written directly in smali so it can be assembled
 * on-device with the smali library (d8 is a desktop JVM tool and cannot run on Android).
 *
 * Two layers of interception:
 *
 *  1. IPackageManager dynamic proxy (the classic approach): replaces
 *     ActivityThread.sPackageManager and ApplicationPackageManager.mPM, so every
 *     getPackageInfo() call is intercepted and the returned signatures are swapped
 *     for the ORIGINAL certificates embedded in this class.
 *
 *  2. PackageInfo.CREATOR replacement: every PackageInfo that crosses a Parcel (the
 *     system returns PackageInfo objects through Binder, which always goes through
 *     Parcelable.CREATOR) is intercepted at deserialization time, and BOTH the legacy
 *     signatures[] and the API 28+ signingInfo are patched. This covers signing checks
 *     the proxy cannot see.
 *
 * Placeholders (#CLASS#, #SUPERCLASS#, #SIGNATURES#) are substituted at generation time.
 * The class extends the app's real Application class so the app's own Application logic
 * still runs through the normal lifecycle.
 */
object KillerApplicationSmali {

    const val CLASS_NAME = "me.zhanghai.android.files.killer.KillerApplication"
    val CLASS_DESCRIPTOR: String = "L" + CLASS_NAME.replace('.', '/') + ";"

    private const val TEMPLATE = """.class public #CLASS#
.super #SUPERCLASS#
.implements Ljava/lang/reflect/InvocationHandler;

.field private base:Ljava/lang/Object;
.field private sign:[[B
.field private appPkgName:Ljava/lang/String;
.field private originalCreator:Ljava/lang/Object;

.method public constructor <init>()V
    .registers 1

    invoke-direct {p0}, #SUPERCLASS#-><init>()V

    return-void
.end method

.method protected attachBaseContext(Landroid/content/Context;)V
    .registers 2

    invoke-direct {p0, p1}, #CLASS#->hook(Landroid/content/Context;)V
    invoke-super {p0, p1}, #SUPERCLASS#->attachBaseContext(Landroid/content/Context;)V

    return-void
.end method

# Intercepts both the IPackageManager proxy and the Parcelable.Creator proxy:
#  - createFromParcel: deserialize a PackageInfo with the ORIGINAL creator, patch its
#    signatures (legacy + signingInfo), return the patched object,
#  - newArray: forward to the original creator,
#  - getPackageInfo: call the real binder and patch the returned PackageInfo.
.method public invoke(Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;
    .registers 11

    invoke-virtual {p2}, Ljava/lang/reflect/Method;->getName()Ljava/lang/String;
    move-result-object v0
    const-string v1, "createFromParcel"
    invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v0
    if-nez v0, :cond_create_from_parcel

    invoke-virtual {p2}, Ljava/lang/reflect/Method;->getName()Ljava/lang/String;
    move-result-object v0
    const-string v1, "newArray"
    invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v0
    if-nez v0, :cond_new_array

    invoke-virtual {p2}, Ljava/lang/reflect/Method;->getName()Ljava/lang/String;
    move-result-object v0
    const-string v1, "getPackageInfo"
    invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :cond_default

    const/4 v0, 0x0
    aget-object v2, p3, v0
    const/4 v0, 0x1
    aget-object v3, p3, v0

    # The flags parameter is an Integer on older APIs and a Long on API 33+.
    instance-of v0, v3, Ljava/lang/Integer;
    if-eqz v0, :cond_flag_long
    invoke-virtual {v3}, Ljava/lang/Integer;->intValue()I
    move-result v3
    goto :cond_flag_ok
    :cond_flag_long
    invoke-virtual {v3}, Ljava/lang/Long;->intValue()I
    move-result v3
    :cond_flag_ok

    and-int/lit8 v0, v3, 0x40
    if-eqz v0, :cond_default

    iget-object v0, p0, #CLASS#->appPkgName:Ljava/lang/String;
    invoke-virtual {v0, v2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :cond_default

    iget-object v0, p0, #CLASS#->base:Ljava/lang/Object;
    invoke-virtual {p2, v0, p3}, Ljava/lang/reflect/Method;->invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;
    move-result-object v4
    check-cast v4, Landroid/content/pm/PackageInfo;
    invoke-direct {p0, v4}, #CLASS#->patchPackageInfo(Landroid/content/pm/PackageInfo;)V

    return-object v4

    :cond_create_from_parcel
    const/4 v0, 0x0
    aget-object v1, p3, v0
    iget-object v0, p0, #CLASS#->originalCreator:Ljava/lang/Object;
    invoke-interface {v0, v1}, Landroid/os/Parcelable${'$'}Creator;->createFromParcel(Landroid/os/Parcel;)Ljava/lang/Object;
    move-result-object v0
    check-cast v0, Landroid/content/pm/PackageInfo;
    invoke-direct {p0, v0}, #CLASS#->patchPackageInfo(Landroid/content/pm/PackageInfo;)V

    return-object v0

    :cond_new_array
    const/4 v0, 0x0
    aget-object v1, p3, v0
    invoke-virtual {v1}, Ljava/lang/Integer;->intValue()I
    move-result v1
    iget-object v0, p0, #CLASS#->originalCreator:Ljava/lang/Object;
    invoke-interface {v0, v1}, Landroid/os/Parcelable${'$'}Creator;->newArray(I)[Ljava/lang/Object;
    move-result-object v0

    return-object v0

    :cond_default
    iget-object v0, p0, #CLASS#->base:Ljava/lang/Object;
    invoke-virtual {p2, v0, p3}, Ljava/lang/reflect/Method;->invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;
    move-result-object v0

    return-object v0
.end method

# Swaps the first signature in PackageInfo.signatures (legacy) and, on API 28+,
# in signingInfo.getApkContentsSigners(), with the embedded ORIGINAL certificate.
.method private patchPackageInfo(Landroid/content/pm/PackageInfo;)V
    .registers 6

    iget-object v0, p1, Landroid/content/pm/PackageInfo;->signatures:[Landroid/content/pm/Signature;
    if-eqz v0, :cond_signing_info
    array-length v1, v0
    if-lez v1, :cond_signing_info
    iget-object v1, p0, #CLASS#->sign:[[B
    const/4 v2, 0x0
    aget-object v1, v1, v2
    new-instance v2, Landroid/content/pm/Signature;
    invoke-direct {v2, v1}, Landroid/content/pm/Signature;-><init>([B)V
    const/4 v3, 0x0
    aput-object v2, v0, v3

    :cond_signing_info
    sget v0, Landroid/os/Build${'$'}VERSION;->SDK_INT:I
    const/16 v1, 0x1c
    if-lt v0, v1, :cond_done
    iget-object v0, p1, Landroid/content/pm/PackageInfo;->signingInfo:Landroid/content/pm/SigningInfo;
    if-eqz v0, :cond_done
    invoke-virtual {v0}, Landroid/content/pm/SigningInfo;->getApkContentsSigners()[Landroid/content/pm/Signature;
    move-result-object v0
    if-eqz v0, :cond_done
    array-length v1, v0
    if-lez v1, :cond_done
    iget-object v1, p0, #CLASS#->sign:[[B
    const/4 v2, 0x0
    aget-object v1, v1, v2
    new-instance v2, Landroid/content/pm/Signature;
    invoke-direct {v2, v1}, Landroid/content/pm/Signature;-><init>([B)V
    const/4 v3, 0x0
    aput-object v2, v0, v3

    :cond_done
    return-void
.end method

# Decodes the embedded signatures, installs the IPackageManager proxy, replaces
# PackageInfo.CREATOR (so every Parcel-deserialized PackageInfo is patched) and clears
# the package-info caches.
.method private hook(Landroid/content/Context;)V
    .registers 14
    .catch Ljava/lang/Throwable; {:try_start .. :try_end} :cond_catch

    :try_start
    const-string v0, "#SIGNATURES#"
    const/4 v1, 0x0
    invoke-static {v0, v1}, Landroid/util/Base64;->decode(Ljava/lang/String;I)[B
    move-result-object v0
    new-instance v1, Ljava/io/ByteArrayInputStream;
    invoke-direct {v1, v0}, Ljava/io/ByteArrayInputStream;-><init>([B)V
    new-instance v0, Ljava/io/DataInputStream;
    invoke-direct {v0, v1}, Ljava/io/DataInputStream;-><init>(Ljava/io/InputStream;)V

    invoke-virtual {v0}, Ljava/io/DataInputStream;->read()I
    move-result v1
    and-int/lit16 v1, v1, 0xff
    new-array v2, v1, [[B
    const/4 v3, 0x0
    :cond_decode_loop
    array-length v4, v2
    if-ge v3, v4, :cond_decode_loop_end
    invoke-virtual {v0}, Ljava/io/DataInputStream;->readInt()I
    move-result v4
    new-array v4, v4, [B
    aput-object v4, v2, v3
    aget-object v4, v2, v3
    invoke-virtual {v0, v4}, Ljava/io/DataInputStream;->readFully([B)V
    add-int/lit8 v3, v3, 0x1
    goto :cond_decode_loop
    :cond_decode_loop_end

    const-string v5, "android.app.ActivityThread"
    invoke-static {v5}, Ljava/lang/Class;->forName(Ljava/lang/String;)Ljava/lang/Class;
    move-result-object v5
    const-string v6, "currentActivityThread"
    const/4 v4, 0x0
    new-array v4, v4, [Ljava/lang/Class;
    invoke-virtual {v5, v6, v4}, Ljava/lang/Class;->getDeclaredMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;
    move-result-object v6
    const/4 v4, 0x0
    new-array v7, v4, [Ljava/lang/Object;
    const/4 v4, 0x0
    invoke-virtual {v6, v4, v7}, Ljava/lang/reflect/Method;->invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;
    move-result-object v7
    const-string v8, "sPackageManager"
    invoke-virtual {v5, v8}, Ljava/lang/Class;->getDeclaredField(Ljava/lang/String;)Ljava/lang/reflect/Field;
    move-result-object v8
    const/4 v9, 0x1
    invoke-virtual {v8, v9}, Ljava/lang/reflect/Field;->setAccessible(Z)V
    invoke-virtual {v8, v7}, Ljava/lang/reflect/Field;->get(Ljava/lang/Object;)Ljava/lang/Object;
    move-result-object v9
    const-string v10, "android.content.pm.IPackageManager"
    invoke-static {v10}, Ljava/lang/Class;->forName(Ljava/lang/String;)Ljava/lang/Class;
    move-result-object v10

    iput-object v9, p0, #CLASS#->base:Ljava/lang/Object;
    iput-object v2, p0, #CLASS#->sign:[[B
    invoke-virtual {p1}, Landroid/content/Context;->getPackageName()Ljava/lang/String;
    move-result-object v4
    iput-object v4, p0, #CLASS#->appPkgName:Ljava/lang/String;

    invoke-virtual {v10}, Ljava/lang/Class;->getClassLoader()Ljava/lang/ClassLoader;
    move-result-object v4
    const/4 v5, 0x1
    new-array v5, v5, [Ljava/lang/Class;
    const/4 v6, 0x0
    aput-object v10, v5, v6
    invoke-static {v4, v5, p0}, Ljava/lang/reflect/Proxy;->newProxyInstance(Ljava/lang/ClassLoader;[Ljava/lang/Class;Ljava/lang/reflect/InvocationHandler;)Ljava/lang/Object;
    move-result-object v11

    invoke-virtual {v8, v7, v11}, Ljava/lang/reflect/Field;->set(Ljava/lang/Object;Ljava/lang/Object;)V
    invoke-virtual {p1}, Landroid/content/Context;->getPackageManager()Landroid/content/pm/PackageManager;
    move-result-object v4
    invoke-virtual {v4}, Ljava/lang/Object;->getClass()Ljava/lang/Class;
    move-result-object v5
    const-string v6, "mPM"
    invoke-virtual {v5, v6}, Ljava/lang/Class;->getDeclaredField(Ljava/lang/String;)Ljava/lang/reflect/Field;
    move-result-object v5
    const/4 v6, 0x1
    invoke-virtual {v5, v6}, Ljava/lang/reflect/Field;->setAccessible(Z)V
    invoke-virtual {v5, v4, v11}, Ljava/lang/reflect/Field;->set(Ljava/lang/Object;Ljava/lang/Object;)V

    # === PackageInfo.CREATOR replacement ===
    const-string v5, "android.content.pm.PackageInfo"
    invoke-static {v5}, Ljava/lang/Class;->forName(Ljava/lang/String;)Ljava/lang/Class;
    move-result-object v5
    const-string v6, "CREATOR"
    invoke-virtual {v5, v6}, Ljava/lang/Class;->getDeclaredField(Ljava/lang/String;)Ljava/lang/reflect/Field;
    move-result-object v6
    const/4 v7, 0x1
    invoke-virtual {v6, v7}, Ljava/lang/reflect/Field;->setAccessible(Z)V
    const/4 v7, 0x0
    invoke-virtual {v6, v7}, Ljava/lang/reflect/Field;->get(Ljava/lang/Object;)Ljava/lang/Object;
    move-result-object v7
    iput-object v7, p0, #CLASS#->originalCreator:Ljava/lang/Object;

    const-string v8, "android.os.Parcelable${'$'}Creator"
    invoke-static {v8}, Ljava/lang/Class;->forName(Ljava/lang/String;)Ljava/lang/Class;
    move-result-object v8
    invoke-virtual {v8}, Ljava/lang/Class;->getClassLoader()Ljava/lang/ClassLoader;
    move-result-object v9
    const/4 v10, 0x1
    new-array v10, v10, [Ljava/lang/Class;
    const/4 v11, 0x0
    aput-object v8, v10, v11
    invoke-static {v9, v10, p0}, Ljava/lang/reflect/Proxy;->newProxyInstance(Ljava/lang/ClassLoader;[Ljava/lang/Class;Ljava/lang/reflect/InvocationHandler;)Ljava/lang/Object;
    move-result-object v10
    const/4 v11, 0x0
    invoke-virtual {v6, v11, v10}, Ljava/lang/reflect/Field;->set(Ljava/lang/Object;Ljava/lang/Object;)V

    # Clear the package-info caches (best effort; failures are swallowed by the try).
    const-string v5, "android.app.ApplicationPackageManager"
    invoke-static {v5}, Ljava/lang/Class;->forName(Ljava/lang/String;)Ljava/lang/Class;
    move-result-object v5
    const-string v6, "sPackageInfoCache"
    invoke-virtual {v5, v6}, Ljava/lang/Class;->getDeclaredField(Ljava/lang/String;)Ljava/lang/reflect/Field;
    move-result-object v6
    const/4 v7, 0x1
    invoke-virtual {v6, v7}, Ljava/lang/reflect/Field;->setAccessible(Z)V
    const/4 v7, 0x0
    invoke-virtual {v6, v7}, Ljava/lang/reflect/Field;->get(Ljava/lang/Object;)Ljava/lang/Object;
    move-result-object v6
    invoke-virtual {v6}, Ljava/lang/Object;->getClass()Ljava/lang/Class;
    move-result-object v7
    const-string v8, "clear"
    const/4 v9, 0x0
    new-array v9, v9, [Ljava/lang/Class;
    invoke-virtual {v7, v8, v9}, Ljava/lang/Class;->getMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;
    move-result-object v7
    const/4 v8, 0x0
    new-array v8, v8, [Ljava/lang/Object;
    const/4 v9, 0x0
    invoke-virtual {v7, v9, v8}, Ljava/lang/reflect/Method;->invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;

    const-string v5, "android.os.Parcel"
    invoke-static {v5}, Ljava/lang/Class;->forName(Ljava/lang/String;)Ljava/lang/Class;
    move-result-object v5
    const-string v6, "mCreators"
    invoke-virtual {v5, v6}, Ljava/lang/Class;->getDeclaredField(Ljava/lang/String;)Ljava/lang/reflect/Field;
    move-result-object v6
    const/4 v7, 0x1
    invoke-virtual {v6, v7}, Ljava/lang/reflect/Field;->setAccessible(Z)V
    const/4 v7, 0x0
    invoke-virtual {v6, v7}, Ljava/lang/reflect/Field;->get(Ljava/lang/Object;)Ljava/lang/Object;
    move-result-object v6
    invoke-virtual {v6}, Ljava/lang/Object;->getClass()Ljava/lang/Class;
    move-result-object v7
    const-string v8, "clear"
    const/4 v9, 0x0
    new-array v9, v9, [Ljava/lang/Class;
    invoke-virtual {v7, v8, v9}, Ljava/lang/Class;->getMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;
    move-result-object v7
    const/4 v8, 0x0
    new-array v8, v8, [Ljava/lang/Object;
    const/4 v9, 0x0
    invoke-virtual {v7, v9, v8}, Ljava/lang/reflect/Method;->invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;

    const-string v5, "android.os.Parcel"
    invoke-static {v5}, Ljava/lang/Class;->forName(Ljava/lang/String;)Ljava/lang/Class;
    move-result-object v5
    const-string v6, "sPairedCreators"
    invoke-virtual {v5, v6}, Ljava/lang/Class;->getDeclaredField(Ljava/lang/String;)Ljava/lang/reflect/Field;
    move-result-object v6
    const/4 v7, 0x1
    invoke-virtual {v6, v7}, Ljava/lang/reflect/Field;->setAccessible(Z)V
    const/4 v7, 0x0
    invoke-virtual {v6, v7}, Ljava/lang/reflect/Field;->get(Ljava/lang/Object;)Ljava/lang/Object;
    move-result-object v6
    invoke-virtual {v6}, Ljava/lang/Object;->getClass()Ljava/lang/Class;
    move-result-object v7
    const-string v8, "clear"
    const/4 v9, 0x0
    new-array v9, v9, [Ljava/lang/Class;
    invoke-virtual {v7, v8, v9}, Ljava/lang/Class;->getMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;
    move-result-object v7
    const/4 v8, 0x0
    new-array v8, v8, [Ljava/lang/Object;
    const/4 v9, 0x0
    invoke-virtual {v7, v9, v8}, Ljava/lang/reflect/Method;->invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;
    :try_end

    return-void
    :cond_catch
    move-exception v0
    return-void
.end method
"""

    /**
     * @param superclassDescriptor the app's Application class descriptor
     * (e.g. "Lcom/example/App;"), or "Landroid/app/Application;" when the manifest has no
     * custom Application.
     * @param signaturesBase64 base64 of [1 byte count][4-byte length][DER cert]... payload
     */
    fun build(superclassDescriptor: String, signaturesBase64: String): String =
        TEMPLATE
            .replace("#CLASS#", CLASS_DESCRIPTOR)
            .replace("#SUPERCLASS#", superclassDescriptor)
            .replace("#SIGNATURES#", signaturesBase64)
}
