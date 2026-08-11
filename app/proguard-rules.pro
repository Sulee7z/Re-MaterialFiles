# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Native methods
# https://www.guardsquare.com/en/products/proguard/manual/examples#native
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# App
-keep class me.zhanghai.android.files.** implements androidx.appcompat.view.CollapsibleActionView { *; }
-keep class me.zhanghai.android.files.provider.common.ByteString { *; }
-keep class me.zhanghai.android.files.provider.linux.syscall.** { *; }
-keepnames class * extends java.lang.Exception
# For Class.getEnumConstants()
-keepclassmembers enum * {
    public static **[] values();
}
-keepnames class me.zhanghai.android.files.** implements android.os.Parcelable

# Apache FtpServer
-keepclassmembers class * implements org.apache.mina.core.service.IoProcessor {
    public <init>(java.util.concurrent.ExecutorService);
    public <init>(java.util.concurrent.Executor);
    public <init>();
}

# Bouncy Castle
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }

# SMBJ
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn sun.security.x509.X509Key

# SMBJ-RPC
-dontwarn java.rmi.UnmarshalException

# Official APK signing library (com.android.tools.build:apksig): relies on reflection and
# ASN.1 structures internally, so it must not be obfuscated or stripped in release builds.
-keep class com.android.apksig.** { *; }

# BouncyCastle classes used by apksig for CMS (v1) signing.
-keep class org.bouncycastle.asn1.** { *; }
-keep class org.bouncycastle.cert.** { *; }
-keep class org.bouncycastle.cms.** { *; }
-keep class org.bouncycastle.operator.** { *; }

# Shizuku
-keep class rikka.shizuku.** { *; }

# Stellar (Shizuku fork): the privileged API framework classes are loaded by the Stellar
# service binder, so they must not be obfuscated or stripped in release builds.
-keep class roro.stellar.** { *; }
-keep class com.stellar.** { *; }

# rikka.hidden (hidden API compat layer used by the Stellar user service process): invokes
# framework methods through reflection/Unsafe, so it must not be obfuscated in release builds.
-keep class rikka.hidden.** { *; }
-dontwarn rikka.hidden.**

# smali/baksmali/dexlib2: DEX assembly/disassembly for the signature killer and the
# dex++ editor. These libraries are invoked by name and manipulate binary structures
# internally, so they must not be obfuscated or stripped in release builds.
-keep class org.jf.dexlib2.** { *; }
-keep class org.jf.smali.** { *; }
-keep class org.jf.baksmali.** { *; }
-dontwarn org.jf.dexlib2.**

# The generated KillerApplication dex references only framework classes, but the
# libraries that build it must survive R8.
-keep class org.jf.** { *; }
