# R8 / ProGuard rules for the release build.
#
# `isMinifyEnabled` is currently **false** (app/build.gradle.kts), so this file
# is inert: it exists so that enabling shrinking is a one-line change rather
# than a debugging session. The rules below are the ones this app is known to
# need; they were written from the dependency list, not by running R8.
#
# Before enabling shrinking, verify on a real device that dictation, model
# download, the phone-server WebSocket and the accessibility insertion all
# still work — JNI and reflection boundaries are exactly what R8 breaks.

# --- Kotlin / serialization -------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class io.github.alexeikramm.sovereign.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.alexeikramm.sovereign.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.alexeikramm.sovereign.**$$serializer { *; }

# --- Native engines (JNI entry points are resolved by name) -----------------
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }

# --- Security / TLS ---------------------------------------------------------
# BouncyCastle registers providers by class name; bcpkix signs the persisted
# self-signed server certificate.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# --- WebSocket transport ----------------------------------------------------
-keep class org.java_websocket.** { *; }
-dontwarn org.slf4j.**

# --- Android components -----------------------------------------------------
# Declared in AndroidManifest.xml and instantiated by the framework, so their
# names must survive; the default rules usually cover this, but the
# accessibility and foreground services are load-bearing for the whole product.
-keep class io.github.alexeikramm.sovereign.floating.VoiceAccessibilityService { *; }
-keep class io.github.alexeikramm.sovereign.floating.FloatingOverlayService { *; }
-keep class io.github.alexeikramm.sovereign.server.SovereignServerService { *; }
-keep class io.github.alexeikramm.sovereign.MainActivity { *; }

# Keep the stack traces readable in a release crash report.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
