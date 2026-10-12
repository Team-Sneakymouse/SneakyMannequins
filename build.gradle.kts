import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	kotlin("jvm") version "2.4.10"
	id("io.papermc.paperweight.userdev") version "2.0.0-beta.22"
	`maven-publish`
}

group = "io.github.team-sneakymouse"

version = providers.exec {
	workingDir(projectDir)
	commandLine("git", "show", "-s", "--format=%ct:%h", "--abbrev=12", "HEAD")
}.standardOutput.asText.map { commit ->
	val (timestamp, hash) = commit.trim().split(":", limit = 2)
	val date = DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneOffset.UTC)
		.format(Instant.ofEpochSecond(timestamp.toLong()))
	"$date-$hash"
}.get()

base {
	archivesName.set("SneakyMannequins")
}

repositories {
	mavenCentral()
	maven("https://repo.papermc.io/repository/maven-public/")
	maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
	maven("https://maven.sneakyrp.com/releases")
}

dependencies {
	val holos = findProject(":SneakyHolos")
	if (holos != null) {
		implementation(project(":SneakyHolos"))
	} else {
		implementation("io.github.team-sneakymouse:sneakholos:2026.10.09-02e4f81d0e24")
	}
	paperweight.paperDevBundle("26.2.build.+")
	implementation(kotlin("stdlib"))
	implementation("com.google.code.gson:gson:2.10.1")
	compileOnly("io.github.team-sneakymouse:sneakycharactermanager-paper:2026.10.09-84db8a554af1") {
		isTransitive = false
	}
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
		inputs.property("version", project.version.toString())
		filesMatching(listOf("paper-plugin.yml", "plugin.yml")) {
			expand("version" to project.version.toString())
		}
	}

	jar {
		archiveBaseName.set("SneakyMannequins")
		archiveFileName.set("SneakyMannequins-${project.version}.jar")
		from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
		duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	}
}

// Only publish when this repo is the Gradle root (not when included from HoloUX-Workspace).
if (project == rootProject) {
	publishing {
		publications {
			create<MavenPublication>("maven") {
				artifactId = "sneakymannequins"
				artifact(tasks.jar) {
					classifier = null
				}
				pom {
					name.set("SneakyMannequins")
					description.set("Paper plugin for interactive mannequin skin previews and holographic customization HUDs.")
					url.set("https://github.com/Team-Sneakymouse/SneakyMannequins")
					properties.put("sneakyrp.deployment.type", "paper-plugin")
					scm {
						url.set("https://github.com/Team-Sneakymouse/SneakyMannequins")
						connection.set("scm:git:https://github.com/Team-Sneakymouse/SneakyMannequins.git")
					}
				}
			}
		}
		repositories {
			maven {
				name = "sneakyrp"
				url = uri("https://maven.sneakyrp.com/releases")
				credentials(PasswordCredentials::class)
				authentication {
					create<org.gradle.authentication.http.BasicAuthentication>("basic")
				}
			}
		}
	}

	tasks.withType<PublishToMavenRepository>().configureEach {
		dependsOn(tasks.check)
	}
}
