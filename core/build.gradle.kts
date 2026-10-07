apply(plugin = "org.jetbrains.kotlin.multiplatform")

configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
    jvm()
    
    sourceSets.apply {
        val commonMain = getByName("commonMain")
        val jvmMain = getByName("jvmMain")
        val jvmTest = getByName("jvmTest")
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
