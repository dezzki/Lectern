# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.gigaton.reader.**$$serializer { *; }
-keep,includedescriptorclasses class com.gigaton.reader.**$$serializer { *; }
-keepclassmembers class com.gigaton.reader.data.network.** { ** companion; }
-keepclasseswithmembers class com.gigaton.reader.data.network.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class *