pluginManagement {
	repositories {
		gradlePluginPortal()
		maven("https://repo.papermc.io/repository/maven-public/")
	}
}

rootProject.name = "SneakyMannequins"

val holosDir = sequenceOf(
	file("../SneakyHolos"),
	file("SneakyHolos"),
).firstOrNull { candidate ->
	candidate.isDirectory && candidate.resolve("build.gradle.kts").isFile
} ?: error(
	"SneakyHolos not found. Clone Team-Sneakymouse/SneakyHolos as a sibling directory " +
		"(../SneakyHolos) or under ./SneakyHolos before building standalone."
)

include(":SneakyHolos")
project(":SneakyHolos").projectDir = holosDir
