plugins {
    id("suite.jvm-library")
}

dependencies {
    api(project(":core:xml"))
    api(project(":core:common"))
    testImplementation(project(":testing:corpus"))
}
