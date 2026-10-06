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
