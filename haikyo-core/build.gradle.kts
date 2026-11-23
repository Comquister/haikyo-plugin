plugins {
    `java-library`
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

dependencies {
    api(project(":haikyo-api"))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
