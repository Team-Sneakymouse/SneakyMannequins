plugins {
    kotlin("jvm") version "1.9.21"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = "com.sneakymannequins"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}


dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly(files("run/versions/1.21.4/paper-1.21.4.jar"))
    compileOnly("io.netty:netty-all:4.1.112.Final")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.21")
    implementation("com.google.code.gson:gson:2.10.1")
}

kotlin {
    jvmToolchain(21)
}

tasks {
    compileKotlin {
        kotlinOptions {
            jvmTarget = "21"
        }
    }
    
    runServer {
        minecraftVersion("1.21.4")
    }
    
    jar {
        from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}

