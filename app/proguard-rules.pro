-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }
-keep class org.mozilla.thirdparty.** { *; }
-keepclassmembers class * { @org.mozilla.gecko.annotation.WrapForJNI *; }
-dontwarn org.mozilla.**

-keep class com.hrips.browser.HripsApp { *; }
-keep class com.hrips.browser.MainActivity { *; }
-keep class * extends android.app.Service

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-dontwarn com.google.zxing.**

# В релизе отладочный вывод в logcat не нужен: AppLog пишет свой файл журнала, а Log.v/d/i только тратят время
# на форматирование и IPC с logd. Предупреждения и ошибки остаются.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
