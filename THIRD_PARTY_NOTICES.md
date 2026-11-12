# Third-party software

Duck Notes uses the libraries below. Their licenses apply independently of Duck Notes' [MIT License](LICENSE). Version declarations are in `gradle/libs.versions.toml`; resolved versions and transitive dependencies must also be reviewed from the exported dependency inventory before distribution.

| Software | Declared version | Upstream license / source |
|---|---|---|
| Kotlin Gradle/Compose plugins; Kotlin runtime libraries | Compiler/plugins 2.0.21; runtime versions come from the resolved graph | [Apache-2.0](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt) |
| Kotlin Symbol Processing | 2.0.21-1.0.28 | [Apache-2.0](https://github.com/google/ksp/blob/main/LICENSE) |
| Android Gradle Plugin | 8.13.2 | [Android tools source and license](https://android.googlesource.com/platform/tools/base/) |
| AndroidX Core, AppCompat, Lifecycle, Activity, DataStore, SplashScreen, Hilt navigation, Compose, Room, SQLite, and AndroidX Test | See version catalog; Compose BOM 2025.10.01 | [AndroidX Apache-2.0 and module-specific notices](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt) |
| Bundled SQLite engine | Supplied by `androidx.sqlite:sqlite-bundled:2.5.2` | [SQLite public-domain dedication](https://sqlite.org/copyright.html); audit the bundled engine version separately |
| Dagger / Hilt | 2.52 | [Apache-2.0](https://github.com/google/dagger/blob/master/LICENSE.txt) |
| Coil | 3.2.0 | [Apache-2.0](https://github.com/coil-kt/coil/blob/main/LICENSE.txt) |
| kotlinx.coroutines | 1.9.0 | [Apache-2.0](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt) |
| commonmark-java and Markdown extensions | 0.29.0 | [BSD-2-Clause](https://github.com/commonmark/commonmark-java/blob/main/LICENSE.txt) |
| JUnit 4 (tests) | 4.13.2 | [EPL-1.0](https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt) |
| Gradle wrapper/build tooling | 8.13 | [Bundled Gradle license and notices](third-party/GRADLE-LICENSE.txt) |
| Gitleaks (external security tool; not included in the APK) | 8.30.1 | [MIT](https://github.com/gitleaks/gitleaks/blob/master/LICENSE) |

## Additional runtime dependency families

The resolved runtime graph also contains the following families. Coordinates and versions below were checked against the candidate dependency inventory; the final export remains authoritative if resolution changes. License labels are verified from the published POMs or upstream source headers, not inferred from the package name.

| Software | Observed runtime version | Upstream license / metadata |
|---|---|---|
| Accompanist DrawablePainter | 0.37.3 | [Apache-2.0; published metadata](https://repo.maven.apache.org/maven2/com/google/accompanist/accompanist-drawablepainter/0.37.3/accompanist-drawablepainter-0.37.3.pom) |
| FindBugs JSR-305 annotations | 3.0.2 | [Apache-2.0; published metadata](https://repo.maven.apache.org/maven2/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.pom) |
| Guava ListenableFuture | 1.0 | [Apache-2.0; inherited parent metadata](https://repo.maven.apache.org/maven2/com/google/guava/guava-parent/26.0-android/guava-parent-26.0-android.pom) |
| Okio | 3.11.0 | [Apache-2.0; published metadata](https://repo.maven.apache.org/maven2/com/squareup/okio/okio-jvm/3.11.0/okio-jvm-3.11.0.pom) |
| Jakarta Dependency Injection API | 2.0.1 | [Apache-2.0; published metadata](https://repo.maven.apache.org/maven2/jakarta/inject/jakarta.inject-api/2.0.1/jakarta.inject-api-2.0.1.pom) |
| javax.inject / JSR-330 | 1 | Apache-2.0; copyright 2009 The JSR-330 Expert Group, as retained in the [published source archive](https://repo.maven.apache.org/maven2/javax/inject/javax.inject/1/javax.inject-1-sources.jar) |
| JetBrains annotations | 23.0.0 | [Apache-2.0; published metadata](https://repo.maven.apache.org/maven2/org/jetbrains/annotations/23.0.0/annotations-23.0.0.pom) |
| JSpecify annotations | 1.0.0 | [Apache-2.0](https://github.com/google/jspecify/blob/v1.0.0/LICENSE) |
| autolink-java | 0.12.0 | [MIT; tagged upstream license](https://github.com/robinst/autolink-java/blob/autolink-0.12.0/LICENSE) |
| kotlinx.serialization core | 1.7.3 | [Apache-2.0; published metadata](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.7.3/kotlinx-serialization-core-jvm-1.7.3.pom) |
| JetBrains Compose and AndroidX lifecycle ports | Compose 1.8.0; lifecycle 2.8.4 | [Compose Apache-2.0 metadata](https://repo.maven.apache.org/maven2/org/jetbrains/compose/ui/ui/1.8.0/ui-1.8.0.pom), [lifecycle Apache-2.0 metadata](https://repo.maven.apache.org/maven2/org/jetbrains/androidx/lifecycle/lifecycle-runtime/2.8.4/lifecycle-runtime-2.8.4.pom) |

The [runtime license and notice bundle](app/src/main/assets/THIRD_PARTY_LICENSES.txt) is packaged in the APK. It records the reviewed Release dependency coordinates, license texts, and upstream notices, including the additional protobuf, LLVM runtime, and SQLite attribution. The [Duck Notes MIT license](app/src/main/assets/DUCK_NOTES_LICENSE.txt) is packaged alongside it.

A version catalog is not a complete software bill of materials. Export the actual resolved graph using the command in [SETUP.md](SETUP.md), review changed dependency metadata, refresh the bundle, and verify its bytes in the final APK before distribution. Build-tool licenses are documented separately above.
