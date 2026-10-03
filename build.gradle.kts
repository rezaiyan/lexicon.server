plugins {
	kotlin("jvm") version "1.9.25"
	kotlin("plugin.spring") version "1.9.25"
	kotlin("plugin.jpa") version "1.9.25"
	id("org.springframework.boot") version "3.5.6"
	id("io.spring.dependency-management") version "1.1.7"
	jacoco
}

group = "com.alirezaiyan"
version = project.property("projectVersion") as String
description = "Lexicon Server Application"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	// Spring Boot Starters
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-mail")
	
	// Kotlin
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	
	// Database
	implementation("org.postgresql:postgresql")
	// H2 only for the local h2 profile (bootRun); not shipped in the production jar
	developmentOnly("com.h2database:h2")
	implementation("org.flywaydb:flyway-core:11.8.0")
	implementation("org.flywaydb:flyway-database-postgresql:11.8.0")
	
	// JWT
	implementation("io.jsonwebtoken:jjwt-api:0.12.3")
	runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.3")
	runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.3")
	
	// Firebase Admin SDK for push notifications
	implementation("com.google.firebase:firebase-admin:9.2.0")
	
	// Google API Client for OAuth
	implementation("com.google.api-client:google-api-client:2.2.0")
	
	// Rate Limiting
	implementation("com.bucket4j:bucket4j-core:8.10.1")
	
	// Password Hashing (for refresh tokens)
	implementation("org.springframework.security:spring-security-crypto")
	
	// Argon2 for refresh token hashing
	implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
	
	// GeoIP
	implementation("com.maxmind.geoip2:geoip2:4.2.0")

	// Logging
	implementation("io.github.oshai:kotlin-logging-jvm:5.1.0")
	
	// Testing
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.security:spring-security-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("io.mockk:mockk:1.13.8")
	// Integration tests run on real PostgreSQL (same major as prod) with the Flyway migrations
	testImplementation("org.testcontainers:postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
	finalizedBy(tasks.jacocoTestReport)
}

// JaCoCo configuration
tasks.jacocoTestReport {
	dependsOn(tasks.test)
	reports {
		xml.required = true
		html.required = true
		csv.required = false
	}

	classDirectories.setFrom(
		files(classDirectories.files.map {
			fileTree(it) {
				exclude(
					"**/domain/entity/**",
					"**/domain/repository/**",
					"**/presentation/dto/**",
					"**/config/**",
					"**/*Application*",
					"**/scheduler/**",
				)
			}
		})
	)

	// Print coverage after report is generated
	doLast {
		val report = file("$buildDir/reports/jacoco/test/jacocoTestReport.xml")
		
		if (report.exists()) {
			val content = report.readText()
			
			// Parse line coverage from XML — use last match which is the report-level total
			val lineRegex = """<counter type="LINE" missed="(\d+)" covered="(\d+)"/>""".toRegex()
			val match = lineRegex.findAll(content).lastOrNull()

			if (match != null) {
				val missed = match.groupValues[1].toInt()
				val covered = match.groupValues[2].toInt()
				val total = missed + covered
				val percentage = if (total > 0) (covered * 100) / total else 0
				
				println("")
				println("============================================")
				println("Code Coverage Report (Line Coverage)")
				println("============================================")
				println("Covered:    $covered lines")
				println("Missed:     $missed lines")
				println("Total:      $total lines")
				println("Coverage:   $percentage%")
				println("Threshold:  80%")
				println("============================================")
				println("")
				
				if (percentage < 80) {
					throw GradleException("Code coverage is below 80% threshold (${percentage}%)")
				}

				// Generate coverage badge SVG
				val color = when {
					percentage >= 80 -> "#4c1"
					percentage >= 70 -> "#dfb317"
					else -> "#e05d44"
				}
				val labelW = 72
				val valueText = "$percentage%"
				val valueW = (valueText.length * 7 + 12).coerceAtLeast(32)
				val totalW = labelW + valueW
				val labelMidX = labelW / 2
				val valueMidX = labelW + valueW / 2
				val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="$totalW" height="20" role="img" aria-label="coverage: $valueText">
  <title>coverage: $valueText</title>
  <linearGradient id="s" x2="0" y2="100%">
    <stop offset="0" stop-color="#bbb" stop-opacity=".1"/>
    <stop offset="1" stop-opacity=".1"/>
  </linearGradient>
  <rect width="$totalW" height="20" rx="3" fill="#555"/>
  <rect x="$labelW" width="$valueW" height="20" rx="3" fill="$color"/>
  <rect x="$labelW" width="4" height="20" fill="$color"/>
  <rect width="$totalW" height="20" rx="3" fill="url(#s)"/>
  <g fill="#fff" font-family="DejaVu Sans,Verdana,Geneva,sans-serif" font-size="11" text-anchor="middle">
    <text x="$labelMidX" y="14" fill="#010101" fill-opacity=".3">coverage</text>
    <text x="$labelMidX" y="13">coverage</text>
    <text x="$valueMidX" y="14" fill="#010101" fill-opacity=".3">$valueText</text>
    <text x="$valueMidX" y="13">$valueText</text>
  </g>
</svg>"""
				val badgeDir = file("$rootDir/.github/badges")
				badgeDir.mkdirs()
				file("$badgeDir/coverage.svg").writeText(svg)
			}
		}
	}
}

// Run report after tests
tasks.test {
	finalizedBy(tasks.jacocoTestReport)
}
