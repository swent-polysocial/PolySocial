// Contributors: Claude (Hilt, KSP and Hilt testing setup, JaCoCo on Hilt-rewritten classes, #70).
plugins {
  alias(libs.plugins.androidApplication)
  alias(libs.plugins.kotlinCompose)
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.sonar)
  alias(libs.plugins.googleServices)
  alias(libs.plugins.ksp)
  alias(libs.plugins.hilt)
  id("jacoco")
}

android {
  namespace = "com.polysocial"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.polysocial"
    minSdk = 28
    targetSdk = 37
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    vectorDrawables { useSupportLibrary = true }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
          getDefaultProguardFile("proguard-android-optimize.txt"),
          "proguard-rules.pro",
      )
    }

    debug {
      enableUnitTestCoverage = true
      enableAndroidTestCoverage = true
    }
  }

  testCoverage { jacocoVersion = "0.8.15" }

  buildFeatures { compose = true }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }

  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      isReturnDefaultValues = true
    }
  }

  // Robolectric needs to be run only in debug. But its tests are placed in the shared source set
  // (test)
  // The next lines transfers the src/test/* from shared to the testDebug one
  //
  // This prevent errors from occurring during unit tests
  sourceSets.getByName("testDebug") {
    val test = sourceSets.getByName("test")

    java.directories.addAll(test.java.directories)
    res.directories.addAll(test.res.directories)
    resources.directories.addAll(test.resources.directories)
  }

  sourceSets.getByName("test") {
    java.directories.clear()
    res.directories.clear()
    resources.directories.clear()
  }
}

kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }

sonar {
  properties {
    property("sonar.projectKey", "swent-polysocial_PolySocial")
    property("sonar.projectName", "PolySocial")
    property("sonar.organization", "swent-polysocial")
    property("sonar.host.url", "https://sonarcloud.io")
    // Comma-separated paths to the various directories containing the *.xml JUnit report files.
    // Each path may be absolute or relative to the project base directory.
    property(
        "sonar.junit.reportPaths",
        "${project.layout.buildDirectory.get()}/test-results/testDebugUnitTest/",
    )
    // Paths to xml files with Android Lint issues. If the main flavor is changed, this file will
    // have to be changed too.
    property(
        "sonar.androidLint.reportPaths",
        "${project.layout.buildDirectory.get()}/reports/lint-results-debug.xml",
    )
    // Paths to JaCoCo XML coverage report files.
    property(
        "sonar.coverage.jacoco.xmlReportPaths",
        "${project.layout.buildDirectory.get()}/reports/jacoco/jacocoTestReport/jacocoTestReport.xml",
    )
  }
}

// When a library is used both by robolectric and connected tests, use this function
fun DependencyHandlerScope.globalTestImplementation(dep: Any) {
  androidTestImplementation(dep)
  testImplementation(dep)
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.material)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(platform(libs.compose.bom))
  testImplementation(libs.junit)
  globalTestImplementation(libs.androidx.junit)
  globalTestImplementation(libs.androidx.espresso.core)

  // ------------- Jetpack Compose ------------------
  val composeBom = platform(libs.compose.bom)
  implementation(composeBom)
  globalTestImplementation(composeBom)

  implementation(libs.compose.ui)
  implementation(libs.compose.ui.graphics)
  // Material Design 3
  implementation(libs.compose.material3)
  // Integration with activities
  implementation(libs.compose.activity)
  // Integration with ViewModels
  implementation(libs.compose.viewmodel)
  // Android Studio Preview support
  implementation(libs.compose.preview)
  debugImplementation(libs.compose.tooling)
  // UI Tests
  globalTestImplementation(libs.compose.test.junit)
  debugImplementation(libs.compose.test.manifest)

  // ------------- Firebase ------------------
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.auth)
  implementation(libs.firebase.firestore)

  // ------------- Hilt (dependency injection) ------------------
  implementation(libs.hilt.android)
  ksp(libs.hilt.compiler)
  implementation(libs.hilt.lifecycle.viewmodel.compose)
  testImplementation(libs.hilt.android.testing)
  kspTest(libs.hilt.compiler)

  // --------- Kaspresso test framework ----------
  globalTestImplementation(libs.kaspresso)
  globalTestImplementation(libs.kaspresso.compose)

  // ----------       Robolectric     ------------
  testImplementation(libs.robolectric)
}

tasks.withType<Test> {
  // Configure Jacoco for each tests
  configure<JacocoTaskExtension> {
    isIncludeNoLocationClasses = true
    excludes = listOf("jdk.internal.*")
  }
}

dependencyLocking { lockAllConfigurations() }

tasks.register("jacocoTestReport", JacocoReport::class) {
  mustRunAfter("testDebugUnitTest", "connectedDebugAndroidTest")

  reports {
    xml.required = true
    html.required = true
  }

  val fileFilter =
      listOf(
          "**/R.class",
          "**/R$*.class",
          "**/BuildConfig.*",
          "**/Manifest*.*",
          "**/*Test*.*",
          "android/**/*.*",
      )

  // Read the classes after Hilt's bytecode rewrite: those are the ones the tests run, so
  // @AndroidEntryPoint and @HiltAndroidApp classes match the coverage data. Hilt's generated
  // classes are excluded.
  val debugTree =
      fileTree(
          "${project.layout.buildDirectory.get()}/intermediates/classes/debug/transformDebugClassesWithAsm/dirs"
      ) {
        include("com/polysocial/**")
        exclude(fileFilter)
        exclude(
            "**/Hilt_*",
            "**/Dagger*",
            "**/*_Hilt*",
            "**/*_Factory*",
            "**/*Module_*Factory*",
            "**/*_MembersInjector*",
            "**/*_GeneratedInjector*",
            "**/*_ComponentTreeDeps*",
        )
      }

  val mainSrc = "${project.layout.projectDirectory}/src/main/java"
  sourceDirectories.setFrom(files(mainSrc))
  classDirectories.setFrom(files(debugTree))
  executionData.setFrom(
      fileTree(project.layout.buildDirectory.get()) {
        include("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
        include("outputs/code_coverage/debugAndroidTest/connected/*/coverage.ec")
      }
  )
}
