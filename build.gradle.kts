import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.13.1"
}

group = "com.yii2storm"
version = "1.0.0"

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        phpstorm("2026.1")
        bundledPlugin("com.jetbrains.php")
    }
    
    // Test dependencies - use IntelliJ Platform test framework
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testImplementation("org.junit.platform:junit-platform-launcher:1.10.0")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks {
    patchPluginXml {
        sinceBuild.set("241")  // PhpStorm 2024.1+
        untilBuild.set("")     // No upper limit
    }

    buildSearchableOptions {
        enabled = false
    }

    prepareJarSearchableOptions {
        enabled = false
    }

    runIde {
        jvmArgs = listOf("-Xmx2048m")
    }

    test {
        useJUnitPlatform()
    }
}