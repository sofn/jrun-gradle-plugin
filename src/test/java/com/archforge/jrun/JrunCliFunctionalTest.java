package com.archforge.jrun;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 命令行背后的 Gradle 任务：jrunAdd / jrunResolve，以及多模块的模块映射。 */
class JrunCliFunctionalTest extends AbstractJrunTest {

    /** 本地 maven 仓库里只有 com.example:lib:1.0：解析结果稳定，不依赖网络上的真实坐标。 */
    private void localRepository() {
        write("repo/com/example/lib/1.0/lib-1.0.pom", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0</version>
                </project>
                """);
        write("repo/com/example/lib/1.0/lib-1.0.jar", "");
    }

    @Test
    void jrunAddEditsTheKotlinBuildScript() {
        write("settings.gradle.kts", "rootProject.name = \"add\"\n");
        write("build.gradle.kts", "plugins { id(\"com.archforge.jrun\") }\n\ndependencies {\n}\n");

        runner("jrunAdd", "--dependency=com.google.code.gson:gson:2.11",
                "--dependency=org.slf4j:slf4j-api:2.0.17").build();

        assertEquals("plugins { id(\"com.archforge.jrun\") }\n\ndependencies {\n"
                + "    implementation(\"com.google.code.gson:gson:2.11\")\n"
                + "    implementation(\"org.slf4j:slf4j-api:2.0.17\")\n}\n", read("build.gradle.kts"));
    }

    @Test
    void jrunResolveAcceptsResolvableCoordinatesAndRejectsMissingOnes() {
        settings("resolve");
        localRepository();
        buildScript("""
                plugins { id 'com.archforge.jrun' }
                repositories { maven { url = uri('repo') } }
                dependencies { testImplementation 'com.example:lib:1.0' }
                """);
        runner("jrunResolve", "--configuration=testImplementation").build();

        buildScript(read("build.gradle").replace("lib:1.0", "lib:9.9"));
        String out = runner("jrunResolve", "--configuration=testImplementation").buildAndFail().getOutput();
        assertTrue(out.contains("com.example:lib:9.9"), "the failing coordinate should be reported:\n" + out);
    }

    @Test
    void multiModuleBuildsGetLaunchersAndAModuleMap() {
        write("settings.gradle", "rootProject.name = 'multi'\ninclude 'app', 'tools:cli'\n");
        buildScript("plugins { id 'com.archforge.jrun' }\n");
        write("app/build.gradle", "plugins { id 'com.archforge.jrun' }\n");
        write("tools/cli/build.gradle", "plugins { id 'com.archforge.jrun' }\n");

        runner("jrunInit").build();

        assertTrue(exists("jrun") && exists("app/jrun") && exists("tools/cli/jrun"),
                "every project applying the plugin gets its own launcher");
        assertEquals(":app=app\napp=app\n:tools:cli=tools/cli\ncli=tools/cli\n",
                read("build/jrun/modules.properties"));
        assertTrue(read("app/build/jrun/" + platformDir() + "/inputs.sha256").contains("  ../settings.gradle\n"),
                "a module's cache must also depend on the root settings");
    }
}
