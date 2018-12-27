plugins {
    val kotlinVersion = "1.3.11"

    kotlin("jvm") version kotlinVersion
    id("org.jetbrains.dokka") version "0.9.17"
    id("me.champeau.gradle.jmh") version "0.4.7"

    signing
    `maven-publish`
    id("maven-publish-auth") version "2.0.1"

    id("com.jfrog.bintray") version "1.8.4"
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

val sourcesJar by tasks.creating(Jar::class) {
    group = "build"
    classifier = "sources"
    from(main.allSource)
}

val dokkaJar by tasks.creating(Jar::class) {
    group = JavaBasePlugin.DOCUMENTATION_GROUP
    description = "Assembles Kotlin docs with Dokka"
    classifier = "javadoc"
    from(tasks["dokka"])
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
    repositories {
        maven {
            name = "maven-central-cikit"
            url = uri("https://oss.sonatype.org/service/local/staging/deploy/maven2")
        }
    }
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifact(sourcesJar)
            artifact(dokkaJar)
            pom {
                name.set("cikit-syslog")
                description.set("rfc5424 syslog implementation")
                url.set("https://github.com/b8b/cikit-syslog.git")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("b8b@cikit.org")
                        name.set("b8b@cikit.org")
                        email.set("b8b@cikit.org")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/b8b/cikit-syslog.git")
                    developerConnection.set("scm:git:ssh://github.com/b8b/cikit-syslog.git")
                    url.set("https://github.com/b8b/cikit-syslog.git")
                }
            }
        }
    }
}

signing {
    sign(publishing.publications["mavenJava"])
}

bintray {
    user = System.getenv("BINTRAY_USER")
    key = System.getenv("BINTRAY_KEY")
    setPublications("mavenJava")
    pkg.apply {
        repo = "cikit"
        name = "cikit-syslog"
        userOrg = user
        setLicenses("Apache-2.0")
        vcsUrl = "https://github.com/b8b/cikit-syslog.git"
        setLabels("kotlin")
        publicDownloadNumbers = true
        version.apply {
            name = project.version.toString()
            desc = "rfc5424 syslog implementation"
            vcsTag = "v${project.version}"
        }
    }
}
