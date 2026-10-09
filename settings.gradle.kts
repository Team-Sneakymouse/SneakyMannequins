pluginManagement {
	repositories {
		gradlePluginPortal()
		maven("https://repo.papermc.io/repository/maven-public/")
	}
}

rootProject.name = "SneakyMannequins"

// Prefer a local Holos checkout for workspace development; otherwise resolve from sneakyrp.
val holosDir = sequenceOf(
	file("../SneakyHolos"),
	file("SneakyHolos"),
).firstOrNull { candidate ->
	candidate.isDirectory && candidate.resolve("build.gradle.kts").isFile
}

if (holosDir != null) {
	include(":SneakyHolos")
	project(":SneakyHolos").projectDir = holosDir
}
