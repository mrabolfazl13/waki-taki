# Retrofit + kotlinx.serialization
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep,includedescriptorclasses class com.securetalk.**$$serializer { *; }
-keepclassmembers class com.securetalk.** { *** Companion; }
# BouncyCastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
# Tink
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
# Ktor
-dontwarn io.ktor.**
