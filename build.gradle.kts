plugins {
    id("com.android.application") version "9.1.1" apply false
    // Kotlin 2.4.20：Miuix 0.9.4（Kotlin 2.4 metadata）所需；两个插件的版本必须一致
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
