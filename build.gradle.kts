import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("io.papermc.paperweight.userdev")
}

repositories {
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

dependencies {
    implementation(project(":SneakyHolos"))
    paperweight.paperDevBundle("26.2.build.+")
    implementation(kotlin("stdlib"))
    implementation("com.google.code.gson:gson:2.10.1")
    compileOnly(files("../../SneakyCharacterManager-Paper/build/libs/SneakyCharacterManager.jar"))
    compileOnly("me.clip:placeholderapi:2.11.6")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

tasks {
    test {
        useJUnitPlatform()
    }

    processResources {
        filesMatching("paper-plugin.yml") {
            expand("version" to version)
        }
    }

    jar {
        from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}

