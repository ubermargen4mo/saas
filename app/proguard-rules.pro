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
