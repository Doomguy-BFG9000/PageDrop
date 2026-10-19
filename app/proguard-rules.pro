# R8 rules for the release build. Most dependencies (AndroidX, Room, Hilt, WorkManager, OkHttp,
# Coil, kotlinx.serialization, jsoup) ship their own consumer rules; this file only covers what
# they don't.

# Keep line numbers so crash reports from users map back to source. The file name is hidden.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# JSch picks ciphers, MACs, key exchanges, signatures and key types by class name from its config
# (JSch.getConfig("aes128-ctr") -> "com.jcraft.jsch.jce.AESCTR" etc.) and creates them with
# Class.forName(...).newInstance(), so none of them can be removed or renamed.
-keep class com.jcraft.jsch.** { *; }
# Optional JSch backends that aren't on the classpath (Bouncy Castle, JNA agents, unix sockets,
# logging bridges). JSch checks for them at runtime and falls back.
-dontwarn com.jcraft.jsch.**
-dontwarn org.bouncycastle.**
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.ietf.jgss.**

# PdfBox-Android: fonts, glyph lists and CMaps are looked up by name, and parts of the object model
# are instantiated reflectively. Its own consumer rules only cover security handlers, so keep it whole.
-keep class com.tom_roush.** { *; }
-dontwarn com.gemalto.jp2.**

# epublib (core only; its xmlpull dependency is provided by Android) and the slf4j binding it logs to.
-keep class nl.siegmann.epublib.** { *; }
-dontwarn nl.siegmann.epublib.**
-keep class org.slf4j.impl.** { *; }
-dontwarn org.slf4j.**

# commons-compress: only tar/bzip2/gzip are used; optional codecs (xz, zstd, brotli, asm) are absent.
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**

# kotlinx.serialization: keep the generated serializers of the app's own @Serializable classes
# (navigation keys are saved and restored by serializer; API models are decoded from JSON).
-keepclassmembers @kotlinx.serialization.Serializable class app.pagedrop.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.pagedrop.**$$serializer { *; }
