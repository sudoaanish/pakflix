import org.gradle.api.GradleException
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest

plugins {
	alias(libs.plugins.aboutlibraries)
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.kotlin.serialization)
}

android {
	namespace = "org.jellyfin.androidtv"
	compileSdk = libs.versions.android.compileSdk.get().toInt()

	defaultConfig {
		minSdk = libs.versions.android.minSdk.get().toInt()
		targetSdk = libs.versions.android.targetSdk.get().toInt()

		// Release version
		applicationId = "com.pakflix.tv"
		versionName = "0.2.7"
		versionCode = 207
	}

	buildFeatures {
		buildConfig = true
		viewBinding = true
		compose = true
		resValues = true
	}

	compileOptions {
		isCoreLibraryDesugaringEnabled = true
	}

	signingConfigs {
		val canonicalReleaseSignerSha256 = "359dde3161d91c47a020d2ed40a2b2a223272ac5cfc23cba8d35636759830c51"
		val projectKeystore = rootProject.file("app/pakflix.keystore")
		val configuredKeystoreFile = getProperty("keystore.file")?.let(::file)
		val keystoreFile = configuredKeystoreFile ?: projectKeystore.takeIf { it.exists() }
		val usingLocalFallback = configuredKeystoreFile == null && keystoreFile == projectKeystore
		val keystorePassword = getProperty("keystore.password") ?: if (usingLocalFallback) "pakflix123" else null
		val signingKeyAlias = getProperty("signing.key.alias") ?: if (usingLocalFallback) "pakflix" else null
		val signingKeyPassword = getProperty("signing.key.password") ?: if (usingLocalFallback) "pakflix123" else null

		fun File.certificateSha256(storePassword: String, alias: String): String {
			val password = storePassword.toCharArray()
			val keyStore = listOf("PKCS12", "JKS")
				.firstNotNullOfOrNull { type ->
					runCatching {
						KeyStore.getInstance(type).apply {
							FileInputStream(this@certificateSha256).use { load(it, password) }
						}
					}.getOrNull()
				} ?: throw GradleException("Unable to read release keystore")

			val certificate = keyStore.getCertificate(alias)
				?: throw GradleException("Release signing key alias was not found in keystore")

			return MessageDigest.getInstance("SHA-256")
				.digest(certificate.encoded)
				.joinToString("") { "%02x".format(it) }
		}

		if (keystoreFile != null && keystorePassword != null && signingKeyAlias != null && signingKeyPassword != null) {
			val signerSha256 = keystoreFile.certificateSha256(keystorePassword, signingKeyAlias)
			if (signerSha256 != canonicalReleaseSignerSha256) {
				throw GradleException("Release keystore signer does not match the canonical PAKFLIX signer")
			}
			create("release") {
				storeFile = keystoreFile
				storePassword = keystorePassword
				keyAlias = signingKeyAlias
				keyPassword = signingKeyPassword
			}
		}
	}

	dependenciesInfo {
		includeInBundle = false
		includeInApk = false
	}

	buildTypes {
		release {
			isMinifyEnabled = true
			isShrinkResources = true
			proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

			// Set package names used in various XML files
			resValue("string", "app_id", "com.pakflix.tv")
			resValue("string", "app_search_suggest_authority", "com.pakflix.tv.content")
			resValue("string", "app_search_suggest_intent_data", "content://com.pakflix.tv.content/intent")

			// Set flavored application name
			resValue("string", "app_name", "@string/app_name_release")

			buildConfigField("boolean", "DEVELOPMENT", "false")

			signingConfig = signingConfigs.findByName("release")
		}

		debug {
			applicationIdSuffix = ".debug"
			versionNameSuffix = "-debug"

			// Set package names used in various XML files
			resValue("string", "app_id", "com.pakflix.tv.debug")
			resValue("string", "app_search_suggest_authority", "com.pakflix.tv.debug.content")
			resValue("string", "app_search_suggest_intent_data", "content://com.pakflix.tv.debug.content/intent")

			// Set flavored application name
			resValue("string", "app_name", "@string/app_name_release")

			buildConfigField("boolean", "DEVELOPMENT", "false")
		}
	}

	gradle.taskGraph.whenReady {
		if (allTasks.any { it.name.contains("Release") } && signingConfigs.findByName("release") == null) {
			throw GradleException("Release signing is not configured. Provide keystore.file, keystore.password, signing.key.alias, and signing.key.password.")
		}
	}


	lint {
		lintConfig = file("$rootDir/android-lint.xml")
		abortOnError = false
		sarifReport = true
		checkDependencies = true
	}

	testOptions.unitTests.all {
		it.useJUnitPlatform()
	}}

base.archivesName.set("pakflix")




tasks.register("versionTxt") {
	val path = layout.buildDirectory.asFile.get().resolve("version.txt")

	doLast {
		val versionString = "v${android.defaultConfig.versionName}=${android.defaultConfig.versionCode}"
		logger.info("Writing [$versionString] to $path")
		path.writeText("$versionString\n")
	}
}

dependencies {
	// Jellyfin
	implementation(projects.design)
	implementation(projects.playback.core)
	implementation(projects.playback.jellyfin)
	implementation(projects.playback.media3.exoplayer)
	implementation(projects.playback.media3.session)
	implementation(projects.preference)
	implementation(libs.jellyfin.sdk) {
		// Change version if desired
		val sdkVersion = findProperty("sdk.version")?.toString()
		when (sdkVersion) {
			"local" -> version { strictly("latest-SNAPSHOT") }
			"snapshot" -> version { strictly("master-SNAPSHOT") }
			"unstable-snapshot" -> version { strictly("openapi-unstable-SNAPSHOT") }
		}
	}

	// Kotlin
	implementation(libs.kotlinx.coroutines)
	implementation(libs.kotlinx.serialization.json)

	// Android(x)
	implementation(libs.androidx.core)
	implementation(libs.androidx.activity)
	implementation(libs.androidx.activity.compose)
	implementation(libs.androidx.fragment)
	implementation(libs.androidx.fragment.compose)
	implementation(libs.androidx.leanback.core)
	implementation(libs.androidx.leanback.preference)
	implementation(libs.androidx.navigation3.ui)
	implementation(libs.androidx.preference)
	implementation(libs.androidx.appcompat)
	implementation(libs.androidx.tvprovider)
	implementation(libs.androidx.constraintlayout)
	implementation(libs.androidx.recyclerview)
	implementation(libs.androidx.work.runtime)
	implementation(libs.bundles.androidx.lifecycle)
	implementation(libs.androidx.window)
	implementation(libs.androidx.cardview)
	implementation(libs.androidx.startup)
	implementation(libs.bundles.androidx.compose)
	implementation(libs.accompanist.permissions)

	// Dependency Injection
	implementation(libs.bundles.koin)

	// Media players
	implementation(libs.androidx.media3.exoplayer)
	implementation(libs.androidx.media3.datasource.okhttp)
	implementation(libs.androidx.media3.exoplayer.hls)
	implementation(libs.androidx.media3.ui)
	implementation(libs.jellyfin.androidx.media3.ffmpeg.decoder)
	implementation(libs.libass.media3)

	// Markdown
	implementation(libs.bundles.markwon)

	// Image utility
	implementation(libs.bundles.coil)

	// Crash Reporting
	implementation(libs.bundles.acra)

	// Licenses
	implementation(libs.aboutlibraries)

	// Logging
	implementation(libs.timber)
	implementation(libs.slf4j.timber)

	// Compatibility (desugaring)
	coreLibraryDesugaring(libs.android.desugar)

	// Testing
	testImplementation(libs.kotest.runner.junit5)
	testImplementation(libs.kotest.assertions)
	testImplementation(libs.mockk)
}
