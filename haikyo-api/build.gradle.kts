plugins {
    `java-library`
}

tasks.named<JavaCompile>("compileJava") {
    options.release.set(8)
    options.compilerArgs.add("-Xlint:-options")
}
