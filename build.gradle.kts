// Top-level build file. Plugins are declared here (apply false) and applied per-module.
buildscript {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            val patched = when {
                requested.group == "io.netty" && requested.version?.startsWith("4.1.") == true -> "4.1.137.Final"
                requested.group == "com.google.guava" && requested.name == "guava" && requested.version?.endsWith("-jre") == true -> "33.7.2-jre"
                requested.group == "org.bouncycastle" && requested.name in setOf("bcprov-jdk18on", "bcpkix-jdk18on", "bcutil-jdk18on") -> "1.85"
                requested.group == "org.bitbucket.b_c" && requested.name == "jose4j" -> "0.9.6"
                requested.group == "org.jdom" && requested.name == "jdom2" -> "2.0.6.1"
                requested.group == "org.apache.commons" && requested.name == "commons-compress" -> "1.28.0"
                else -> null
            }
            if (patched != null) {
                useVersion(patched)
                because("Pin patched transitive build tools; see SECURITY.md")
            }
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

allprojects {
    // Lint resolves a detached configuration that ordinary configuration rules do not reach.
    dependencies.components.withModule("com.android.tools.lint:lint-gradle") {
        allVariants {
            withDependencyConstraints {
                listOf(
                    "com.google.guava:guava" to "33.7.2-jre",
                    "org.bouncycastle:bcprov-jdk18on" to "1.85",
                    "org.bouncycastle:bcpkix-jdk18on" to "1.85",
                    "org.bouncycastle:bcutil-jdk18on" to "1.85",
                    "org.apache.commons:commons-compress" to "1.28.0",
                    "org.apache.httpcomponents:httpclient" to "4.5.14",
                ).forEach { (module, patched) ->
                    add("$module:$patched") { version { strictly(patched) } }
                }
            }
        }
    }
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            val patched = when {
                requested.group == "io.netty" && requested.version?.startsWith("4.1.") == true -> "4.1.137.Final"
                requested.group == "com.google.guava" && requested.name == "guava" && requested.version?.endsWith("-jre") == true -> "33.7.2-jre"
                requested.group == "com.google.protobuf" && requested.name in setOf("protobuf-java", "protobuf-kotlin") && requested.version?.startsWith("3.") == true -> "3.25.8"
                else -> null
            }
            if (patched != null) {
                useVersion(patched)
                because("Pin patched Android test tooling; see SECURITY.md")
            }
        }
    }
}
