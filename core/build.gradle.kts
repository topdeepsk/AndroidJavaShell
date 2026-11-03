plugins {
    alias(libs.plugins.android.library)
    // 生成 Maven 坐标（POM + AAR + sources），JitPack 构建时正是读取 install 到本地仓库的产物
    `maven-publish`
}

android {
    namespace = "com.github.topdeepsk.androidjavashell.core"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.keep")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // 只把 release 变体发布出去，并附带源码 jar，使用方在 IDE 里可以直接看源码
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                // AGP 的 release 组件要到配置阶段末尾才创建，因此整块延后到 afterEvaluate
                from(components["release"])

                pom {
                    name.set("AndroidJavaShell-core")
                    description.set("Run a Java entry point in a separate Android process, optionally with root privileges")
                    url.set("https://github.com/topdeepsk/AndroidJavaShell")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    scm {
                        url.set("https://github.com/topdeepsk/AndroidJavaShell")
                        connection.set("scm:git:https://github.com/topdeepsk/AndroidJavaShell.git")
                    }
                }
            }
        }
    }
}

dependencies {
    compileOnly(libs.annotation)
}
