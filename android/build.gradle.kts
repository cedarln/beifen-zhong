// 根构建脚本：只声明插件，不在这里配置模块。
// 注意 buildConfig 需要显式打开（AGP 8 默认关闭），后门面板要读 BuildConfig.DEBUG。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
}
