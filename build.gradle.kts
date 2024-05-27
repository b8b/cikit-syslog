plugins {
    kotlin("jvm") version "2.0.0"
    id("org.jetbrains.dokka") version "1.9.20"
    `maven-publish`

    id("me.champeau.jmh") version "0.7.2"
}

group = "org.cikit.syslog"
version = project.properties["version"]
        ?.takeUnless { it == "unspecified" } ?: "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

repositories {
    mavenCentral()
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    api(kotlin("stdlib"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0")


    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")
    testImplementation("de.erichseifert.vectorgraphics2d:VectorGraphics2D:0.13")
}

dependencyLocking {
    lockAllConfigurations()
}

val kotlinSourcesJar by tasks

val dokkaJar by tasks.creating(Jar::class) {
    group = JavaBasePlugin.DOCUMENTATION_GROUP
    description = "Assembles Kotlin docs with Dokka"
    archiveClassifier = "javadoc"
    from(tasks["dokkaJavadoc"])
}

val mainJar by tasks.named<Jar>("jar")

tasks.jar {
    dependsOn("generatePomFileForMavenJavaPublication")
    into("META-INF/maven/${project.group}/${project.name}") {
        from(layout.buildDirectory.dir("publications/mavenJava")) {
            include("pom-default.xml")
        }
        rename(".*", "pom.xml")
    }
}

jmh {
    jmhVersion = "1.37"
    jvmArgs = listOf("-Djmh.separateClasspathJAR=true")
}

publishing {
    repositories {
        maven {
            name = "maven-central-cikit"
            url = uri("https://oss.sonatype.org/service/local/staging/deploy/maven2")
        }
    }
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifact(kotlinSourcesJar)
            artifact(dokkaJar)
            versionMapping {
                usage("java-runtime") {
                    fromResolutionResult()
                }
            }
            pom {
                name = "cikit-syslog"
                description = "rfc5424 syslog implementation"
                url = "https://github.com/b8b/cikit-syslog.git"
                licenses {
                    license {
                        name = "The Apache License, Version 2.0"
                        url = "http://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }
                developers {
                    developer {
                        id = "b8b@cikit.org"
                        name = "b8b@cikit.org"
                        email = "b8b@cikit.org"
                    }
                }
                scm {
                    connection = "scm:git:https://github.com/b8b/cikit-syslog.git"
                    developerConnection = "scm:git:ssh://github.com/b8b/cikit-syslog.git"
                    url = "https://github.com/b8b/cikit-syslog.git"
                }
            }
        }
    }
}
