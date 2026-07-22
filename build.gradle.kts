plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = "org.synergyst"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    // --- Test dependencies -------------------------------------------------
    // The automaton suite exercises engine internals with no live server, but
    // several touched classes carry Bukkit types in their signatures and the
    // kernel logger is an SLF4J facade. Both are compileOnly for the plugin
    // itself and therefore absent from the test classpath; they are restated
    // here so the tests compile and run.
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // SLF4J binding for the kernel logger during tests; keeps output quiet
    // unless a test opts into a higher level.
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
        // Paper's Brigadier command registrar is annotated @ApiStatus.Experimental.
        options.compilerArgs.add("-Xlint:all,-processing")
    }

    compileTestJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    test {
        useJUnitPlatform()
        // Surface a concise per-test outcome in the build log.
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
        }
    }

    shadowJar {
        archiveClassifier.set("")
        // Do NOT relocate org.sqlite: the driver bundles a JNI native library
        // whose exported symbol names are bound to the original package. Paper
        // provides per-plugin classloader isolation, so relocation is
        // unnecessary for collision avoidance.
        minimize {
            // The driver loads classes and native resources reflectively;
            // minimisation must not prune it.
            exclude(dependency("org.xerial:sqlite-jdbc:.*"))
        }
    }

    runServer {
        minecraftVersion("1.21.4")
        pluginJars.from(shadowJar.flatMap { it.archiveFile })

        jvmArgs(
            "-Dcom.mojang.eula.agree=true"
        )
    }

    build {
        dependsOn(shadowJar)
    }

    processResources {
        filesMatching("paper-plugin.yml") {
            expand("version" to project.version)
        }
    }
}