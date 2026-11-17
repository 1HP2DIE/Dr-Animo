// Root build file — declares plugin classpaths only. No jvmToolchain(): this machine has JDK 21
// only, and every module pins Java 17 bytecode via compileOptions + compilerOptions (STACK.md rule 1).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
