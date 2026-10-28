plugins {
    id("suite.jvm-library")
}

dependencies {
    api(project(":core:opc"))
    testImplementation(project(":testing:corpus"))
}
