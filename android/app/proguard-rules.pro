# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# SQLCipher (net.zetetic)
-keep class net.sqlcipher.** { *; }
-keep class net.zetetic.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
