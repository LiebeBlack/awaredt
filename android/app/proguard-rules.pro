# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# SQLCipher (net.zetetic)
-keep class net.sqlcipher.** { *; }
-keep class net.zetetic.** { *; }

# WorkManager crea los Worker por reflexion a partir del nombre de clase
# (SyncWorker, TamperWorker): sin estas reglas, la version release con R8 no
# encuentra la clase y el trabajo falla solo en produccion, no en debug.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
