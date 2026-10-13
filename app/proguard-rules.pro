# Beast Browser R8 rules
# Our own classes are small; keep them whole so reflection/XML-referenced classes
# (Preferences, activities, view binding) can never be stripped by mistake.
-keep class com.jamhowman.beastbrowser.** { *; }
# GeckoView classes are reached from native code; the AAR's consumer rules cover this,
# this is a belt-and-braces keep for the crash helper service started by name.
-keep class org.mozilla.gecko.crashhelper.** { *; }
-keep class org.mozilla.gecko.process.** { *; }
-keep class org.mozilla.gecko.media.** { *; }
-keep class org.mozilla.gecko.gfx.** { *; }
-dontwarn org.mozilla.**
-dontwarn javax.annotation.**
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 2.11.2 launch fix: Room (used by WorkManager since 2.11.0) creates its generated database by reflection
# (Class.getDeclaredConstructor().newInstance()). R8 removed WorkDatabase_Impl.<init>(), so WorkManager's
# androidx.startup initializer threw NoSuchMethodException in a ContentProvider, before Application.onCreate
# (and before safe start could count the launch). Keep every generated database's no-arg constructor, and the
# classes WorkManager instantiates by name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
-keep class * extends androidx.work.ListenableWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }
-keep class * extends androidx.work.InputMerger { public <init>(); }
