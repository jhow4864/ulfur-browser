plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9 has built-in Kotlin; this only bumps the Kotlin compiler (GeckoView 157 is built with Kotlin 2.4).
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
}
