# RhythmicTouch keep rules
-keep class com.lyon.rhythmictouch.MainHook { *; }
-keep class com.lyon.rhythmictouch.systemui.** { *; }
-dontwarn de.robv.android.xposed.**
-dontwarn org.jetbrains.annotations.**

# RichTap SDK (accessed via reflection)
-keep class com.apprichtap.haptic.** { *; }
-dontwarn com.apprichtap.haptic.**
