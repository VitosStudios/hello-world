# Tippli is built without minification. If minification is ever enabled, keep
# the kotlinx.serialization models used to parse the shared JSON files.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class ch.vitosstudios.tippli.core.data.**$$serializer { *; }
-keepclassmembers class ch.vitosstudios.tippli.core.data.** {
    *** Companion;
}
