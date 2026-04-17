plugins {
    kotlin("jvm")
}

repositories {
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

dependencies {
    implementation(project(":SneakyHolos"))
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly(files("../run/versions/1.21.4/paper-1.21.4.jar"))
    compileOnly("io.netty:netty-all:4.1.112.Final")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.21")
    implementation("com.google.code.gson:gson:2.10.1")
    compileOnly("io.github.team-sneakymouse:sneakycharactermanager-paper:1.4.0")
    compileOnly("me.clip:placeholderapi:2.11.6")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

kotlin {
    jvmToolchain(21)
}

tasks {
    test {
        useJUnitPlatform()
    }

    compileKotlin {
        kotlinOptions {
            jvmTarget = "21"
        }
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

