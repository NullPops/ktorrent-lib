plugins {
    id("java")
    kotlin("jvm") version "2.2.0"
}

group = "nullpops"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.nullpops:logger:1.0.1")
    implementation("io.github.nullpops:eventbus:1.0.0")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}