plugins {
    val kotlinVersion = "1.3.11"

    kotlin("jvm") version kotlinVersion
    `maven-publish`
    id("me.champeau.gradle.jmh") version "0.4.7"
}

group = "org.cikit.syslog"
version = project.properties["version"]
        ?.takeUnless { it == "unspecified" } ?: "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_1_7
    targetCompatibility = JavaVersion.VERSION_1_7
}

repositories {
    jcenter()
    maven {
        url = uri("https://dl.bintray.com/kotlin/kotlinx")
    }
}

dependencies {
    val jacksonVersion = "2.9.7"

    compile(kotlin("stdlib"))
    compile("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.0.1")

    testCompile("junit:junit:4.12")
    testCompile("de.erichseifert.vectorgraphics2d:VectorGraphics2D:0.13")
}

val main by sourceSets

val sourcesJar by tasks.registering(Jar::class) {
    group = "build"
    classifier = "sources"
    from(main.allSource)
}

tasks.named<Jar>("jar") {
    dependsOn("generatePomFileForMavenJavaPublication")
    into("META-INF/maven/${project.group}/${project.name}") {
        from(File(buildDir, "publications/mavenJava"))
        rename(".*", "pom.xml")
    }
}

jmh {
    jmhVersion = "1.21"
    jvmArgs = listOf("-Djmh.separateClasspathJAR=true")
}

publishing {
    publications {
        register("mavenJava", MavenPublication::class) {
            from(components["java"])
            artifact(sourcesJar.get())
        }
    }
}
