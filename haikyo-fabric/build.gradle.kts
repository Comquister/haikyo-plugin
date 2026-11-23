plugins {
    id("net.fabricmc.fabric-loom") version "1.18.2"
    java
}

repositories {
    maven("https://maven.fabricmc.net/")
    maven("https://maven.maxhenkel.de/repository/public")
}

dependencies {
    minecraft("com.mojang:minecraft:26.3")
    implementation("net.fabricmc:fabric-loader:0.20.1")
    implementation("net.fabricmc.fabric-api:fabric-api:0.166.2+26.3")

    implementation(project(":haikyo-api"))
    implementation(project(":haikyo-core"))
    compileOnly("de.maxhenkel.voicechat:voicechat-api:2.6.0")

    testImplementation("de.maxhenkel.voicechat:voicechat-api:2.6.0")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<ProcessResources>("processResources") {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("fabric.mod.json") {
        expand(props)
    }
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("haikyo-fabric")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(":haikyo-api:classes", ":haikyo-core:classes", ":haikyo-core:processResources")
    from(project(":haikyo-api").extensions.getByType<SourceSetContainer>()["main"].output)
    from(project(":haikyo-core").extensions.getByType<SourceSetContainer>()["main"].output)
}
