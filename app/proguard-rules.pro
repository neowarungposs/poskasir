# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.neowarungposs.poskasir.**$$serializer { *; }
-keepclassmembers class com.neowarungposs.poskasir.** { *** Companion; }
-keepclasseswithmembers class com.neowarungposs.poskasir.** { kotlinx.serialization.KSerializer serializer(...); }

# Tink
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
