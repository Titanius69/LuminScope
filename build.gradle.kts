plugins {
    id("java")
}

group = project.property("group") as String
version = project.property("version") as String

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
}

dependencies {
    // No shaded dependencies. Everything LuminScope needs is either in the JDK
    // (management beans, HTTP server) or already on the proxy classpath.
    compileOnly("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
    options.compilerArgs.add("-Xlint:all,-processing")
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "LuminScope",
            "Implementation-Version" to version
        )
    }
}
