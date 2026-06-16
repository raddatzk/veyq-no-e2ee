// Umbrella build. Plugin versions are declared once here (apply false) and applied per module.
plugins {
    kotlin("jvm") version "2.3.21" apply false
    kotlin("plugin.compose") version "2.3.21" apply false
    kotlin("plugin.serialization") version "2.3.21" apply false
    id("com.android.application") version "9.0.0" apply false
}
