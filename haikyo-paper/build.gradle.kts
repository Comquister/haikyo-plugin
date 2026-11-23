plugins {
    java
}

dependencies {
    implementation(project(":haikyo-api"))
    implementation(project(":haikyo-core"))

    compileOnly("io.papermc.paper:paper-api:26.3.build.258-stable")
    compileOnly("ac.grim.grimac:GrimAPI:1.6.0.13")

    testImplementation("io.papermc.paper:paper-api:26.3.build.258-stable")
    testImplementation("ac.grim.grimac:GrimAPI:1.6.0.13")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<ProcessResources>("processResources") {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching(listOf("plugin.yml", "paper-plugin.yml")) {
        expand(props)
    }
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("haikyo-paper")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(":haikyo-api:classes", ":haikyo-core:classes", ":haikyo-core:processResources")
    from(project(":haikyo-api").extensions.getByType<SourceSetContainer>()["main"].output)
    from(project(":haikyo-core").extensions.getByType<SourceSetContainer>()["main"].output)
}
