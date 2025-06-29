plugins {
	kotlin("jvm") version "1.9.22"
	id("io.papermc.paperweight.userdev") version "2.0.0-beta.17"
	id("xyz.jpenilla.run-paper") version "2.2.3"
}

group = "net.sneakymannequin"
version = "1.0.0"

repositories {
	mavenCentral()
	maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
	paperweight.paperDevBundle("1.21.4-R0.1-SNAPSHOT")
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
}

java {
	toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

kotlin {
	jvmToolchain(21)
}

paperweight.reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION
