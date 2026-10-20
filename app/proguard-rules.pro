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

# ONNX Runtime : classes manipulées par le code natif (JNI).
-keep class ai.onnxruntime.** { *; }

# Classes instanciées par réflexion : le mode strict de R8 (AGP 9) ne garde plus leur
# constructeur avec les règles livrées par ces bibliothèques. Les « registrars » ML Kit
# sont nommés dans le manifeste : sans constructeur, ML Kit n'enregistre aucun composant
# et étiquettes, texte et visages échouent (vérifié en CI par tools/release).
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class * implements androidx.versionedparcelable.VersionedParcelable { <init>(); }
