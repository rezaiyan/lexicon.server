// The Spring Boot Gradle plugin brings Jackson onto the build classpath at an older version; keep it
// on the same patched release as the app (the jackson-2-bom.version override below)
buildscript {
	configurations.classpath {
		resolutionStrategy.eachDependency {
			if (requested.group == "com.fasterxml.jackson.core" && requested.name != "jackson-annotations") {
				useVersion("2.21.7")
			}
		}
	}
}

plugins {
	alias(libs.plugins.kotlin.jvm)
	alias(libs.plugins.kotlin.spring)
	alias(libs.plugins.kotlin.jpa)
	alias(libs.plugins.spring.boot)
	alias(libs.plugins.spring.dependency.management)
	alias(libs.plugins.detekt)
	jacoco
}

group = "com.alirezaiyan"
version = project.property("projectVersion") as String
description = "Lexicon Server Application"

// The Spring Boot BOM pins Kotlin libraries to its own baseline; keep them on the compiler's version
extra["kotlin.version"] = libs.versions.kotlin.asProvider().get()
// Security fixes newer than the Spring Boot BOM ships (found by the Trivy image scan in CI). Drop
// each override once the BOM catches up.
// Jackson 2 is still used by firebase-admin, jjwt and the MCP SDK (the app itself is on Jackson 3)
extra["jackson-2-bom.version"] = "2.21.7"

repositories {
	mavenCentral()
}

dependencies {
	// Spring Boot Starters
	implementation(libs.spring.boot.starter.webmvc)
	// Event publication registry: cross-feature events persisted in Postgres until their listeners complete
	implementation(libs.spring.modulith.starter.jdbc)
	// OpenAPI spec of the API, rendered in tests and committed as openapi.json (no UI, route off in prod)
	implementation(libs.springdoc.openapi.webmvc.api)
	implementation(libs.spring.boot.starter.data.jpa)
	implementation(libs.spring.boot.starter.security)
	implementation(libs.spring.boot.starter.validation)
	implementation(libs.spring.boot.starter.actuator)
	implementation(libs.spring.boot.starter.mail)
	// MCP server (/mcp): learner tools over the word services, for AI clients
	implementation(platform(libs.spring.ai.bom))
	implementation(libs.spring.ai.starter.mcp.server.webmvc)
	// OAuth 2.1 for MCP clients: we are the authorization server, /mcp is the resource server
	implementation(libs.spring.security.oauth2.authorization.server)
	implementation(libs.spring.security.oauth2.resource.server)

	// Metrics, scraped from the internal management port
	implementation(libs.micrometer.registry.prometheus)

	// Kotlin
	implementation(libs.kotlin.reflect)
	implementation(libs.jackson.module.kotlin)
	// springdoc models schemas with swagger-core, still on Jackson 2: without its Kotlin module the spec
	// loses required (non-null) fields and renames is-prefixed properties
	runtimeOnly(libs.jackson2.module.kotlin)

	// Database
	implementation(libs.postgresql)
	// H2 only for the local h2 profile (bootRun); not shipped in the production jar
	developmentOnly(libs.h2)
	// /h2-console for the h2 profile (a separate module since Boot 4)
	developmentOnly("org.springframework.boot:spring-boot-h2console")
	implementation(libs.spring.boot.starter.flyway)
	implementation(libs.flyway.core)
	implementation(libs.flyway.postgresql)

	// JWT
	implementation(libs.jjwt.api)
	runtimeOnly(libs.jjwt.impl)
	runtimeOnly(libs.jjwt.jackson)

	// Firebase Admin SDK for push notifications
	implementation(libs.firebase.admin) {
		// Pulled in by google-cloud-storage (unused: only Auth + Messaging). On the classpath it makes
		// Spring MVC answer requests without an explicit Accept header in XML instead of JSON.
		exclude(group = "com.fasterxml.jackson.dataformat", module = "jackson-dataformat-xml")
	}

	// Rate Limiting
	implementation(libs.bucket4j.core)
	// Expiring, size-bounded map of rate-limit buckets (version managed by the Spring Boot BOM)
	implementation("com.github.ben-manes.caffeine:caffeine")
	// Fetches MCP client metadata documents with an address-checking DNS resolver
	implementation(libs.httpclient5)
	// RestClient.Builder auto-configuration (RestClientBuilderConfigurer), see HttpClientConfig
	implementation(libs.spring.boot.starter.restclient)

	// BCrypt + SHA-256 refresh token hashing
	implementation(libs.spring.security.crypto)

	// GeoIP
	implementation(libs.geoip2)

	// Logging
	implementation(libs.kotlin.logging)

	// Testing
	testImplementation(libs.spring.boot.starter.test)
	testImplementation(libs.spring.boot.starter.webmvc.test)
	testImplementation(libs.spring.boot.starter.data.jpa.test)
	testImplementation(libs.spring.boot.starter.security.test)
	testImplementation(libs.spring.boot.starter.actuator.test)
	testImplementation(libs.spring.security.test)
	testImplementation(libs.kotlin.test.junit5)
	testImplementation(libs.mockk)
	// Architecture rules as tests (src/test/.../architecture)
	testImplementation(libs.konsist)
	// Feature-module boundaries (ModularityTest)
	testImplementation(libs.spring.modulith.starter.test)
	// Integration tests run on real PostgreSQL (same major as prod) with the Flyway migrations
	testImplementation(libs.testcontainers.postgresql)
	testRuntimeOnly(libs.junit.platform.launcher)
}

// Hibernate proxies subclass entities; final classes made every LAZY to-one association load eagerly
allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

kotlin {
	jvmToolchain(21)
	compilerOptions {
		freeCompilerArgs.addAll(
			"-Xjsr305=strict",
			// Annotations on constructor properties (@JsonProperty, @NotBlank, ...) also land on the
			// property/field — the Kotlin 2.x future default; opting in now silences the migration warning
			"-Xannotation-default-target=param-property",
			// Writes annotations on type arguments to bytecode, so Bean Validation sees element
			// constraints such as List<@Size(max = 40) String>; without it they are silently dropped
			"-Xemit-jvm-type-annotations",
		)
	}
}

// META-INF/build-info.properties: lets /api/v1/health report the real version instead of "development"
springBoot {
	buildInfo()
}

// Same ruleset as the client (Lexicon/detekt.yml), minus the Compose/Android rules. Runs in `check`,
// so CI fails on new findings; existing ones are recorded in detekt-baseline.xml.
detekt {
	buildUponDefaultConfig = true
	config.setFrom(files("$rootDir/detekt.yml"))
	baseline = file("$rootDir/detekt-baseline.xml")
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
	jvmTarget = "21"
	reports {
		sarif.required = true
	}
}

// detekt 1.23 embeds the Kotlin 1.9 compiler; keep the Spring BOM from lifting its classpath to ours
configurations.matching { it.name == "detekt" }.configureEach {
	resolutionStrategy.eachDependency {
		if (requested.group == "org.jetbrains.kotlin") {
			useVersion(io.gitlab.arturbosch.detekt.getSupportedKotlinVersion())
		}
	}
}

// One stable artifact name for the Dockerfile and scripts; the plain (non-boot) jar isn't used
tasks.bootJar {
	archiveFileName = "app.jar"
}

tasks.jar {
	enabled = false
}

tasks.test {
	useJUnitPlatform()
	// Spring caches one context per test configuration; the default 512m worker heap ran out (2026-10-15)
	maxHeapSize = "1g"
	finalizedBy(tasks.jacocoTestReport)
	// `./gradlew test -PupdateOpenApi=true` rewrites openapi.json instead of failing on a diff
	systemProperty("openapi.update", providers.gradleProperty("updateOpenApi").getOrElse("false"))
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
		val report = layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml").get().asFile
		
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
