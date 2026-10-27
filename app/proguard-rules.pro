# R8 rules for the release build.
#
# Most of what this app uses ships its own consumer rules (Compose, Media3, OkHttp, Coil,
# DataStore), so only the pieces R8 cannot infer are listed here.

# kotlinx.serialization: the generated serializers are referenced from the companion object, and
# R8 used to drop those fields on the floor. The rules below are the ones from the library docs and
# only keep the companion of @Serializable classes, so nothing else is retained.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static kotlinx.serialization.KSerializer serializer(...);
}

# Keep the annotations the serializer reads back at runtime.
-keepattributes *Annotation*, InnerClasses

# WorkManager stores the worker class name in its database and instantiates it reflectively after
# a process restart. Keep the constructor explicitly: relying on the library's consumer rules
# alone would let R8 rename it and every queued progress retry would fail with a class lookup.
-keep class com.audiobookshelf.nativeapp.data.ProgressSyncWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# WorkManager creates its Room database from androidx.startup before the first Activity. With
# this project's R8 configuration the generated *_Impl class was renamed, so the release app
# crashed at process start: "Failed to create an instance of class
# androidx.work.impl.WorkDatabase.canonicalName" (reproduced on device, 2026-11-11). Keep the
# database implementations explicitly; the library's consumer rules were not sufficient here.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class **_Impl extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }

# Release builds must not emit any logs. App code logs through AppLog, where BuildConfig.DEBUG
# is a compile-time false in release, so the calls (and message construction) are already gone.
# This rule is the second layer: it strips every android.util.Log invocation that survives in
# third-party libraries (Media3, Compose, Coil, ...). Verified by dexdump: without this rule the
# release dex keeps ~195 Log.d/e/w/i calls from libraries; with it, zero printing calls remain.
# It only applies to the minified release variant - debug is unminified and keeps every log.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
    public static *** println(...);
}

# A few libraries (OkHttp, coroutines, ...) log through java.util.logging or stdout instead of
# android.util.Log. On Android, JUL forwards to logcat and System.out/System.err end up there too,
# so release strips those output methods as well. Only void output methods are listed: methods
# with meaningful return values (getLogger, isLoggable, PrintStream.write) are deliberately left
# alone so library control flow is unaffected.
-assumenosideeffects class java.util.logging.Logger {
    public void log(...);
    public void logp(...);
    public void logrb(...);
    public void entering(...);
    public void exiting(...);
    public void throwing(...);
    public void fine(...);
    public void finer(...);
    public void finest(...);
    public void config(...);
    public void info(...);
    public void warning(...);
    public void severe(...);
}
-assumenosideeffects class java.io.PrintStream {
    public void print(...);
    public void println(...);
}
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace(...);
}
