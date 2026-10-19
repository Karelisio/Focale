// Fichier de build racine : déclare les plugins sans les appliquer.
// AGP 9 intègre Kotlin ; déclarer le plugin Kotlin ici fixe simplement sa version.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}
