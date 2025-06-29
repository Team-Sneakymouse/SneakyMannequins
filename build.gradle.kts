plugins {
	kotlin("jvm") version "1.9.22"
	id("io.papermc.paperweight.userdev") version "2.0.0-beta.17"
	id("xyz.jpenilla.run-paper") version "2.2.3"
	id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "net.sneakymannequins"
version = "1.0.0"

repositories {
	mavenCentral()
	maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
	paperweight.paperDevBundle("1.21.4-R0.1-SNAPSHOT")
	implementation(kotlin("stdlib"))
}

tasks {
	assemble {
		dependsOn(reobfJar)
	}
	
	compileKotlin {
		kotlinOptions.jvmTarget = "21"
	}
	
	compileJava {
		options.encoding = Charsets.UTF_8.name()
		options.release.set(21)
	}
	
	runServer {
		minecraftVersion("1.21.4")
	}

	shadowJar {
		dependencies {
			include(dependency("org.jetbrains.kotlin:.*"))
		}
	}

	reobfJar {
		dependsOn(shadowJar)
	}
}

java {
	toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

kotlin {
	jvmToolchain(21)
}

paperweight.reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION
