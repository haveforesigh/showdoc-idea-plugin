import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform")
}

group = "com.showdoc.plugin"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3")
        bundledPlugin("com.intellij.java")
        // instrumentationTools()
        testFramework(TestFrameworkType.Plugin.Java)
    }
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    instrumentCode.set(false)
    
    pluginConfiguration {
        id.set("com.myteam.free.showdoc.integration")
        name.set("MyTeam ShowDoc Integration")
        version.set("1.0.1")
        vendor {
            name.set("MyTeam Internal Dev")
        }
        description.set("Exports Spring Project API documentation to a local ShowDoc server.")
        
        ideaVersion {
            sinceBuild.set("232")
            untilBuild.set("253.*")
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
