plugins {
    `kotlin-dsl`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "hatchslide.android.application"
            implementationClass = "com.hatchslide.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "hatchslide.android.library"
            implementationClass = "com.hatchslide.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "hatchslide.android.compose"
            implementationClass = "com.hatchslide.buildlogic.AndroidComposeConventionPlugin"
        }
        register("rootGates") {
            id = "hatchslide.root.gates"
            implementationClass = "com.hatchslide.buildlogic.RootGatesPlugin"
        }
        register("jvmLibrary") {
            id = "hatchslide.jvm.library"
            implementationClass = "com.hatchslide.buildlogic.JvmLibraryConventionPlugin"
        }
    }
}
