plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    kotlin("jvm")
    kotlin("plugin.spring")
}

group = "com.backoffice"
version = "0.1.0"

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.google.api-client:google-api-client:2.8.0")
    implementation("com.google.api-client:google-api-client-gson:2.8.0")
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.39.0")
    implementation("com.google.apis:google-api-services-gmail:v1-rev20250630-2.0.0")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
}

kotlin { compilerOptions { freeCompilerArgs.add("-Xjsr305=strict") } }

tasks.withType<Test> { useJUnitPlatform() }

// 설정(config/), 화면(frontend/static/), 데이터(data/) 경로가 모두 저장소 루트 기준이다.
// Gradle 의 기본 작업 디렉터리는 backend/ 라서 그대로 두면 dashboard.properties 를 못 읽고
// 화면도 404 가 된다. README 가 안내하는 `:backend:bootRun` 이 루트에서 실행한 것처럼 동작하게 맞춘다.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
