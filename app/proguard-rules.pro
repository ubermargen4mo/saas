# GeckoView вызывает свои классы из нативного кода и по имени, поэтому их не переименовываем и не удаляем
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }
-keep class org.mozilla.thirdparty.** { *; }
-keepclassmembers class * { @org.mozilla.gecko.annotation.WrapForJNI *; }
-dontwarn org.mozilla.**

# Сервисы, receiver'ы и activity из манифеста сохраняются правилами AGP; на всякий случай оставляем наши точки входа
-keep class com.hrips.browser.HripsApp { *; }
-keep class com.hrips.browser.MainActivity { *; }
-keep class * extends android.app.Service

# Трассировки падений читаемые: номера строк остаются
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ZXing и CameraX поставляют свои consumer-правила; предупреждения о необязательных классах не нужны
-dontwarn com.google.zxing.**
