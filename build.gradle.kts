plugins {
    id("zenithproxy.plugin.dev") version "1.0.0-SNAPSHOT"
}

group = properties["maven_group"] as String
version = properties["plugin_version"] as String
val mc = properties["mc"] as String
val pluginId = properties["plugin_id"] as String

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

zenithProxyPlugin {
    templateProperties = mapOf(
        // variables in the BuildConstants.java template class
        "version" to project.version,
        "mc_version" to mc,
        "plugin_id" to pluginId,
        "maven_group" to group as String,
    )
    // the minimum supported java version for users of the plugin
    javaReleaseVersion = JavaLanguageVersion.of(21)
}

repositories {
    maven("https://maven.2b2t.vc/releases") {
        description = "ZenithProxy Releases"
    }
    maven("https://maven.2b2t.vc/remote") {
        description = "Dependencies used by ZenithProxy"
    }
}

dependencies {
    zenithProxy("com.zenith:ZenithProxy:$mc-SNAPSHOT")
}

tasks {
    shadowJar {
        // no shaded dependencies yet; relocate here if any are added later
    }
}
