plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "auto-repair-shop-execution"

include("main")
include("domain")
include("api")
include("storage")
include("worker")
include("metric")
