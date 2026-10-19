# Règles R8 de Focale.

# Routes de navigation et modèles sérialisés (kotlinx.serialization).
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keepclassmembers @kotlinx.serialization.Serializable class app.focale.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class app.focale.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Tink : génération de code protobuf.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}

# osmdroid
-dontwarn org.osmdroid.**

# ONNX Runtime : le code natif appelle les classes Java par leur nom.
-keep class ai.onnxruntime.** { *; }
