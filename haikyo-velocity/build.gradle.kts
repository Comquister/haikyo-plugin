plugins {
    java
}

dependencies {
    implementation(project(":haikyo-api"))
    implementation(project(":haikyo-core"))

    compileOnly("com.velocitypowered:velocity-api:4.3.1")
    annotationProcessor("com.velocitypowered:velocity-api:4.3.1")

    testImplementation("com.velocitypowered:velocity-api:4.3.1")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("haikyo-velocity")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(":haikyo-api:classes", ":haikyo-core:classes", ":haikyo-core:processResources")
    from(project(":haikyo-api").extensions.getByType<SourceSetContainer>()["main"].output)
    from(project(":haikyo-core").extensions.getByType<SourceSetContainer>()["main"].output)
}
