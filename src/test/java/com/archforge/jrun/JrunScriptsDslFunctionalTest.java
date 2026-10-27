package com.archforge.jrun;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** script 块写法：元数据文件、Gradle 任务与依赖、配置期校验。 */
class JrunScriptsDslFunctionalTest extends AbstractJrunTest {

    private static final String APP = """
            package com.example;

            public class App {
                public static void main(String[] args) {
                    System.out.println("APP");
                    for (String a : args) {
                        System.out.println("ARG=[" + a + "]");
                    }
                }
            }
            """;

    private void groovyProject(String jrunBlock) {
        settings("dsl");
        buildScript("plugins { id 'com.archforge.jrun' }\njrun {\n" + jrunBlock + "\n}\n");
        write("main/com/example/App.java", APP);
    }

    @Test
    void blockSyntaxWritesKindsArgumentsAndDependencies() {
        groovyProject("""
                script('api', 'com.example.App') {
                    args '--greeting=hello world', 'say "hi"'
                    dependsOn 'gen'
                }
                script('gen', 'shell:echo GEN && echo DONE')
                scripts.put('verify', 'gradle:build --dry-run')
                """);

        runner("jrunInit").build();

        assertEquals("kind=class\ntarget=com.example.App\ndeps=gen\n", read("build/jrun/scripts/api.properties"));
        assertEquals("\"--greeting=hello world\"\n\"say \\\"hi\\\"\"\n", read("build/jrun/scripts/api.args"));
        assertEquals("kind=shell\ntarget=echo GEN && echo DONE\ndeps=\n", read("build/jrun/scripts/gen.properties"));
        assertEquals("kind=gradle\ntarget=build --dry-run\ndeps=\n", read("build/jrun/scripts/verify.properties"));
        assertTrue(read("build/jrun/scripts.properties")
                .contains("api=com.example.App \"--greeting=hello world\" \"say \\\"hi\\\"\""));
    }

    @Test
    void dependenciesRunFirstThroughTheGeneratedGradleTasks() {
        groovyProject("""
                script('api', 'com.example.App') {
                    args 'x y'
                    dependsOn 'gen'
                }
                script('gen', 'shell:echo GEN')
                """);

        String out = runner("jrunApi").build().getOutput();
        int gen = out.indexOf("GEN");
        assertTrue(gen >= 0 && gen < out.indexOf("APP"), "gen must run before api:\n" + out);
        assertTrue(out.contains("ARG=[x y]"), "array args must survive as one argument:\n" + out);
    }

    @Test
    void kotlinDslBlockAndTopLevelOverload() {
        write("settings.gradle.kts", "rootProject.name = \"dsl-kts\"\n");
        write("build.gradle.kts", """
                import com.archforge.jrun.script

                plugins { id("com.archforge.jrun") }

                jrun {
                    script("api", "com.example.App") { args("hello world") }
                }

                script("both", "com.example.App") { dependsOn("api") }
                """);
        write("main/com/example/App.java", APP);

        runner("jrunInit").build();

        assertEquals("\"hello world\"\n", read("build/jrun/scripts/api.args"));
        assertEquals("kind=class\ntarget=com.example.App\ndeps=api\n", read("build/jrun/scripts/both.properties"));
    }

    @Test
    void aDependencyCycleFailsAtConfigurationTime() {
        groovyProject("""
                script('a', 'shell:echo a') { dependsOn 'b' }
                script('b', 'shell:echo b') { dependsOn 'a' }
                """);

        String out = runner("help").buildAndFail().getOutput();
        assertTrue(out.contains("dependency cycle: a -> b -> a"), out);
    }
}
