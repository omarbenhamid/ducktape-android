import org.jetbrains.kotlin.gradle.plugin.PLUGIN_CLASSPATH_CONFIGURATION_NAME

plugins {
  kotlin("multiplatform")
  kotlin("plugin.serialization")
}

kotlin {
  jvm {
    withJava()
  }

  js {
    browser()
  }

  sourceSets {
    commonMain {
      dependencies {
        implementation(project(":zipline"))
      }
    }
  }
}

dependencies {
  add(PLUGIN_CLASSPATH_CONFIGURATION_NAME, project(":zipline-kotlin-plugin"))
}