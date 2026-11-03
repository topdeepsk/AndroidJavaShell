plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

// JitPack 的坐标规则：单模块项目是 com.github.<用户>:<仓库>，多模块项目是
// com.github.<用户>.<仓库>:<模块名>。本仓库发布 :core 与 :service 两个 AAR，
// 属于后者，所以统一 group；这样 :service 里 api(project(":core")) 生成的依赖
// 会自动写成 com.github.topdeepsk.AndroidJavaShell:core:<版本>，与 JitPack 对得上。
allprojects {
    group = "com.github.topdeepsk.AndroidJavaShell"
    // 构建时可用 -Pversion=<tag> 覆盖（jitpack.yml 会传 JitPack 注入的版本），
    // 兜底值只为避免出现 "unspecified" 这种无法引用的版本号
    if (version == Project.DEFAULT_VERSION) {
        version = "1.0.0"
    }
}
