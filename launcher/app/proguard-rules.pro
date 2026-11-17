# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class de.lumen.launcher.** {
    *** Companion;
}
-keepclasseswithmembers class de.lumen.launcher.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class de.lumen.launcher.**$$serializer { *; }
