
description = "Kotlin Android Compat Replacer Compiler Plugin"

apply { plugin("kotlin") }

plugins { java }

dependencies {
    compile(ideaSdkCoreDeps("intellij-core"))
    compile(project(":compiler:util"))
    compile(project(":compiler:plugin-api"))
    compile(project(":compiler:frontend"))
    compile(project(":compiler:frontend.java"))
    compile(project(":compiler:backend"))

    testCompile(project(":compiler:util"))
    testCompile(project(":compiler:backend"))
    testCompile(project(":compiler:cli"))
    testCompile(project(":compiler:tests-common"))
    testCompile(projectTests(":compiler:tests-common"))
    testCompile(projectDist(":kotlin-test:kotlin-test-jvm"))
    testCompile(commonDep("junit:junit"))
}

sourceSets {
    "main" { projectDefault() }
    "test" { projectDefault() }
}

dist(targetName = the<BasePluginConvention>().archivesBaseName.removePrefix("kotlin-") + ".jar")

val jar = runtimeJar {
    from(fileTree("$projectDir/src")) { include("META-INF/**") }
}

testsJar {}

ideaPlugin()

projectTest { workingDir = rootDir }
