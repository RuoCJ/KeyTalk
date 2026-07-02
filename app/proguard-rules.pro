-keep class com.keytalk.app.KeyTalkApp { *; }

-keepattributes *Annotation*, InnerClasses
-keep class com.keytalk.app.backup.**$$serializer { *; }
-keepclassmembers class com.keytalk.app.backup.** {
    public static ** Companion;
}

-keep class com.keytalk.app.data.db.KeyTalkDatabase { *; }
-keep interface com.keytalk.app.data.db.dao.** { *; }

-keep class net.sqlcipher.** { *; }

-dontwarn com.google.errorprone.annotations.**
