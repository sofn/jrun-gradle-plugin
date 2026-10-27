package com.archforge.jrun;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;

import org.gradle.testkit.runner.BuildResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件行为测试：在临时项目里真实执行 Gradle，断言目录约定、生成物内容与编码。
 */
class JrunPluginFunctionalTest extends AbstractJrunTest {

    @Test
    void flatLayoutIsApplied() {
        settings("flat");
        buildScript("""
                plugins { id 'com.archforge.jrun' }
                tasks.register('dump') {
                    doLast {
                        println "MAIN=" + sourceSets.main.java.srcDirs*.name
                        println "TEST=" + sourceSets.test.java.srcDirs*.name
                        println "MAIN_RES=" + sourceSets.main.resources.srcDirs*.name
                    }
                }
                """);

        BuildResult result = runner("dump").build();
        String out = result.getOutput();

        assertTrue(out.contains("MAIN=[main]"), "main java srcDirs should be [main], got:\n" + out);
        assertTrue(out.contains("TEST=[test]"), "test java srcDirs should be [test], got:\n" + out);
        assertTrue(out.contains("MAIN_RES=[resources]"), "main resources should be [resources], got:\n" + out);
    }

    @Test
    void launchersAreGeneratedWithCorrectEncodings() throws Exception {
        settings("launchers");
        minimalBuild();

        runner("help").build();

        assertTrue(exists("jrun"), "jrun should be generated at project root");
        assertTrue(exists("jrun.bat"), "jrun.bat should be generated at project root");

        // bash: LF only, no BOM
        byte[] sh = readBytes("jrun");
        assertFalse(new String(sh, StandardCharsets.UTF_8).contains("\r"),
                "jrun (bash) must use LF endings");
        assertFalse(sh.length > 3 && (sh[0] & 0xFF) == 0xEF, "jrun must not start with a BOM");

        // cmd: CRLF everywhere + pure ASCII (otherwise the GBK console code page garbles it)
        byte[] bat = readBytes("jrun.bat");
        String batText = new String(bat, StandardCharsets.US_ASCII);
        long lfTotal = batText.chars().filter(c -> c == '\n').count();
        long crlf = batText.split("\r\n", -1).length - 1;
        assertEquals(lfTotal, crlf, "jrun.bat must use CRLF on every line");
        for (byte b : bat) {
            assertTrue((b & 0xFF) < 0x80, "jrun.bat must be pure ASCII, found byte 0x"
                    + Integer.toHexString(b & 0xFF));
        }
    }

    @Test
    void launcherGenerationIsIdempotent() throws Exception {
        settings("idempotent");
        minimalBuild();

        runner("help").build();
        FileTime first = Files.getLastModifiedTime(projectDir.resolve("jrun"));

        // 稍微等一会儿，让文件系统时间戳有机会变化，从而能真正检出"被重写"
        Thread.sleep(1200);
        runner("help").build();
        FileTime second = Files.getLastModifiedTime(projectDir.resolve("jrun"));

        assertEquals(first, second,
                "re-running the build must not rewrite an unchanged launcher (breaks build caching)");
    }

    @Test
    void prepareWritesArgfilesWithEscapedClasspath() {
        settings("args");
        minimalBuild();

        runner("jrunInit").build();

        String dir = "build/jrun/" + platformDir();
        assertTrue(exists(dir + "/class.args"), "class.args should be written under " + dir);
        assertTrue(exists(dir + "/source.args"), "source.args should be written under " + dir);

        String classArgs = read(dir + "/class.args");
        assertTrue(classArgs.startsWith("--class-path\n"), "class.args must start with --class-path");
        String cp = classArgs.substring("--class-path\n".length()).trim();
        assertTrue(cp.startsWith("\"") && cp.endsWith("\""), "classpath must be quoted, got: " + cp);

        if (isWindows()) {
            // Windows 路径必须把 \ 转成 \\，否则 Java 会当成转义序列吞掉
            assertTrue(cp.contains("\\\\"), "Windows classpath must escape backslashes, got: " + cp);
        }
        assertTrue(cp.contains("classes"), "classpath should contain build/classes output, got: " + cp);

        String sourceArgs = read(dir + "/source.args");
        assertTrue(sourceArgs.contains("--source\n"),
                "source.args must pin --source, got: " + sourceArgs);
        String release = sourceArgs.substring(sourceArgs.indexOf("--source\n") + "--source\n".length()).trim();
        // 这里不断言具体数字：--source 跟随 Gradle daemon 的 JDK，测试 JVM 与 daemon 未必是同一个
        assertTrue(Integer.parseInt(release) >= 17,
                "--source should be >= 17 (plugin targets Java 17+), got: " + release);
    }

    @Test
    void scriptsAndDefaultTargetAreWritten() {
        settings("scripts");
        buildScript("""
                plugins { id 'com.archforge.jrun' }
                jrun {
                    mainClass = 'com.example.App'
                    scripts.put('dev', 'com.example.App')
                    scripts.put('hello', 'Hello.java')
                    scripts.put('api', 'com.example.App --server.port=8080')
                }
                """);

        runner("jrunInit").build();

        String props = read("build/jrun/scripts.properties");
        // TreeMap 排序：api < dev < hello
        assertEquals("""
                api=com.example.App --server.port=8080
                dev=com.example.App
                hello=Hello.java
                """.replace("\r\n", "\n"), props.replace("\r\n", "\n"),
                "scripts.properties must be sorted and preserve fixed arguments");

        assertEquals("com.example.App", read("build/jrun/default-target.txt").trim(),
                "mainClass should be written as the default target");
    }

    @Test
    void groovySourceSetExcludesJavaFiles() {
        settings("mixed");
        buildScript("""
                plugins {
                    id 'com.archforge.jrun'
                    id 'groovy'
                }
                tasks.register('dump') {
                    doLast {
                        println "GROOVY_SRC=" + sourceSets.main.groovy.srcDirs*.name
                        println "GROOVY_EXCLUDES=" + sourceSets.main.groovy.excludes
                    }
                }
                """);

        String out = runner("dump").build().getOutput();

        assertTrue(out.contains("GROOVY_SRC=[main]"), "groovy sources should be flattened to main, got:\n" + out);
        assertTrue(out.contains("**/*.java"),
                "groovy source set must exclude .java to avoid duplicate class output, got:\n" + out);
    }

    @Test
    void springBootPluginCoexists() {
        settings("boot");
        buildScript("""
                plugins {
                    id 'com.archforge.jrun'
                    id 'org.springframework.boot' version '4.1.2'
                }
                repositories { mavenCentral() }
                dependencies { implementation platform('org.springframework.boot:spring-boot-dependencies:4.1.2') }
                jrun { mainClass = 'com.example.App' }
                """);

        // 只跑到配置阶段（tasks），不解析业务依赖：验证两个插件互不破坏配置模型
        String out = runner("tasks").build().getOutput();

        assertTrue(out.contains("jrunInit"),
                "jrun's task should still be registered next to Spring Boot:\n" + out);
        assertFalse(out.contains("UnknownPropertyException"),
                "jrun extension should be usable next to Spring Boot:\n" + out);
    }

    @Test
    void kotlinDslConfigurationWorks() {
        write("settings.gradle.kts", "rootProject.name = \"kts\"\n");
        write("build.gradle.kts", """
                plugins {
                    id("com.archforge.jrun")
                }

                jrun {
                    mainClass = "com.example.App"
                    scripts.put("dev", "com.example.App")
                    scripts.put("hello", "Hello.java")
                }
                """);

        runner("jrunInit").build();

        String props = read("build/jrun/scripts.properties").replace("\r\n", "\n");
        assertEquals("""
                dev=com.example.App
                hello=Hello.java
                """, props, "Kotlin DSL should configure scripts the same way as Groovy DSL");
    }

    @Test
    void kotlinPluginFlattensKotlinSources() {
        settings("kotlin");
        buildScript("""
                plugins {
                    id 'com.archforge.jrun'
                    id 'org.jetbrains.kotlin.jvm' version '2.4.21'
                }
                tasks.register('dump') {
                    doLast {
                        println "KOTLIN_MAIN=" + sourceSets.main.kotlin.srcDirs*.name
                        println "KOTLIN_TEST=" + sourceSets.test.kotlin.srcDirs*.name
                        println "JAVA_MAIN=" + sourceSets.main.java.srcDirs*.name
                    }
                }
                """);

        String out = runner("dump").build().getOutput();

        assertTrue(out.contains("KOTLIN_MAIN=[main]"),
                "kotlin sources should be flattened to main, got:\n" + out);
        assertTrue(out.contains("KOTLIN_TEST=[test]"),
                "kotlin test sources should be flattened to test, got:\n" + out);
        assertTrue(out.contains("JAVA_MAIN=[main]"),
                "java sources should stay flattened next to kotlin, got:\n" + out);
    }

    @Test
    void emptyProjectStillPreparesArgfiles() {
        settings("empty");
        minimalBuild();

        // 完全没有 main/ 目录，jrunInit 依赖的 classes 会是 NO-SOURCE，但不应该失败
        runner("jrunInit").build();

        assertTrue(exists("build/jrun/" + platformDir() + "/source.args"),
                "argfiles should still be produced for an empty project");
    }

    /**
     * jrunInit 被挂到 wrapper 任务的末尾：`gradle wrapper` 之后运行器应当已经就绪。
     */    @Test
    void wrapperTaskAlsoRunsJrunInit() {
        settings("wrapper-hook");
        minimalBuild();

        BuildResult result = runner("wrapper").build();
        String out = result.getOutput();

        assertTrue(out.contains(":jrunInit"),
                "jrunInit should be finalized by the wrapper task, got:\n" + out);
        assertTrue(exists("build/jrun/" + platformDir() + "/source.args"),
                "gradle wrapper should leave the argfile cache ready for ./jrun");
    }

    @Test
    void intellijRunUsesGradleTypeSoClasspathComesFromGradle() {
        // 默认就生成，不需要任何配置
        settings("escape");
        buildScript("""
                plugins { id 'com.archforge.jrun' }
                jrun {
                    scripts.put('quote', 'a.b.C --msg="hi & bye"')
                }
                """);

        runner("help").build();

        String xml = read(".run/jrun - quote.run.xml");
        assertTrue(xml.contains("type=\"GradleRunConfiguration\""),
                "the run configuration must be a Gradle type so classpath comes from Gradle, got: " + xml);
        assertTrue(xml.contains("<option value=\"jrunQuote\" />"),
                "it should point at the generated jrunQuote task, got: " + xml);
        assertFalse(xml.contains("MAIN_CLASS_NAME"),
                "a Gradle run configuration must not carry MAIN_CLASS_NAME (that is the Application layout)");
    }

    @Test
    void defaultLaunchersAreBashAndCmdOnly() {
        settings("default-launchers");
        minimalBuild();

        runner("help").build();

        assertTrue(exists("jrun"), "bash launcher should be generated by default");
        assertTrue(exists("jrun.bat"), "cmd launcher should be generated by default");
        assertFalse(exists("jrun.ps1"),
                "powershell launcher must be OFF by default (one Windows entry point is enough); "
                        + "enable it explicitly with launchers = ['bash','cmd','powershell']");
    }

    @Test
    void launchersCanBeNarrowedToBashOnly() {
        settings("launcher-toggle");

        // 1) 默认：bash + cmd
        minimalBuild();
        runner("help").build();
        assertTrue(exists("jrun"), "bash launcher should be generated by default");
        assertTrue(exists("jrun.bat"), "cmd launcher should be generated by default");

        // 2) 收窄到只剩 bash：bat 必须被删除
        buildScript("""
                plugins { id 'com.archforge.jrun' }
                jrun { launchers = ['bash'] }
                """);
        runner("help").build();
        assertTrue(exists("jrun"), "bash launcher should remain");
        assertFalse(exists("jrun.bat"), "removing cmd from launchers must delete jrun.bat");
    }

    /**
     * PowerShell 启动器已被彻底移除：PowerShell 可以直接执行 jrun.bat。
     * 旧配置里写了 powershell 时，只告警忽略，不生成文件、也不让构建失败。
     */
    @Test
    void powershellLauncherIsGoneAndIgnoredWithWarning() {
        settings("no-ps");
        buildScript("""
                plugins { id 'com.archforge.jrun' }
                jrun { launchers = ['bash', 'cmd', 'powershell'] }
                """);

        // 先在项目里放一个历史遗留的 jrun.ps1，验证升级后会被清理
        write("jrun.ps1", "# stale launcher from an older plugin version\n");

        String out = runner("help").build().getOutput();

        assertFalse(exists("jrun.ps1"),
                "jrun.ps1 must not exist: the launcher is removed and stale copies are cleaned up");
        assertTrue(exists("jrun"), "bash launcher should still be generated");
        assertTrue(exists("jrun.bat"), "cmd launcher should still be generated");
        assertTrue(out.contains("PowerShell launcher has been removed"),
                "configuring powershell should warn instead of silently doing nothing, got:\n" + out);
    }


    /**
     * 为 classpath 模式的 script 生成 Gradle 任务：classpath 由 Gradle 解析，
     * 不依赖 IDE 的 module 同步状态。
     */
    @Test
    void scriptTasksRunWithGradleClasspath() {
        settings("script-tasks");
        buildScript("""
                plugins { id 'com.archforge.jrun' }
                jrun {
                    scripts.put('dev', 'com.example.App --server.port=8080')
                    scripts.put('hello', 'Hello.java')
                }
                """);
        write("main/com/example/App.java", """
                package com.example;

                public class App {
                    public static void main(String[] args) {
                        System.out.println("APP_ARGS=" + String.join(",", args));
                    }
                }
                """);
        write("main/Hello.java", """
                public class Hello {
                    public static void main(String[] args) {
                        System.out.println("HELLO_ARGS=" + String.join(",", args));
                    }
                }
                """);

        BuildResult result = runner("jrunDev").build();
        assertTrue(result.getOutput().contains("APP_ARGS=--server.port=8080"),
                "the generated Gradle task should run the script with its fixed args, got:\n"
                        + result.getOutput());

        String listed = runner("tasks", "--group", "jrun").build().getOutput();
        assertTrue(listed.contains("jrunDev"), "jrunDev should be registered under the jrun group");
        assertTrue(listed.contains("jrunHello"),
                "source-file scripts should also get a task, with the main class derived "
                        + "from the package declaration");
    }

    /**
     * 默认包下的简单类名（无点号）也要能生成任务；{@code gradle:} 型不生成。
     */
    @Test
    void scriptTasksCoverSimpleClassNames() {
        settings("script-tasks-simple");
        buildScript("""
                plugins { id 'com.archforge.jrun' }
                jrun {
                    scripts.put('hello', 'Hello.java')
                    scripts.put('hello2', 'Hello')
                    scripts.put('verify', 'gradle:build')
                }
                """);
        write("main/Hello.java", """
                public class Hello {
                    public static void main(String[] args) {
                        System.out.println("HELLO_ARGS=" + String.join(",", args));
                    }
                }
                """);

        String listed = runner("tasks", "--group", "jrun").build().getOutput();
        assertTrue(listed.contains("jrunHello"), "Hello.java should map to a jrunHello task");
        assertTrue(listed.contains("jrunHello2"),
                "'Hello' (default-package class name) should map to a jrunHello2 task");
        assertFalse(listed.contains("jrunVerify"),
                "gradle: scripts should not get a JavaExec task - the user runs that task directly");

        assertTrue(runner("jrunHello").build().getOutput().contains("HELLO_ARGS="),
                "jrunHello should actually run the class");
        assertTrue(runner("jrunHello2").build().getOutput().contains("HELLO_ARGS="),
                "jrunHello2 should actually run the class");
    }

    /**
     * 误用防护：把 jrun 的 script 语法（gradle:xxx）传给 jrunMain() 时，
     * 应在**配置阶段**就报清晰错误，而不是运行期抛 ClassNotFoundException。
     */
    @Test
    void jrunMainRejectsScriptSyntaxInsteadOfClassNames() {
        write("settings.gradle.kts", "rootProject.name = \"reject\"\n");
        write("build.gradle.kts", """
                import com.archforge.jrun.jrunMain

                plugins { id("com.archforge.jrun") }

                tasks.register<JavaExec>("verify2") {
                    jrunMain("gradle:build")
                }
                """);

        BuildResult result = runner("verify2").buildAndFail();
        String out = result.getOutput();

        assertTrue(out.contains("expects a main class name"),
                "should explain that a class name is required, got:\n" + out);
        assertTrue(out.contains("jrun { script("),
                "should point at the script DSL as the correct alternative, got:\n" + out);
    }

    /**
     * `jrun { }` 块内的 script(...) 简写（等价于 scripts.put）。
     */
    @Test
    void scriptShorthandWorksInsideJrunBlock() {
        write("settings.gradle.kts", "rootProject.name = \"dsl-block\"\n");
        write("build.gradle.kts", """
                plugins { id("com.archforge.jrun") }

                jrun {
                    script("inside", "com.example.App")
                }
                """);
        write("main/com/example/App.java", """
                package com.example;

                public class App {
                    public static void main(String[] args) {
                        System.out.println("BLOCK_OK");
                    }
                }
                """);

        String listed = runner("tasks", "--group", "jrun").build().getOutput();
        assertTrue(listed.contains("jrunInside"),
                "script(...) inside the jrun block should register the task, got:\n" + listed);
        assertTrue(runner("jrunInside").build().getOutput().contains("BLOCK_OK"),
                "the task should actually run");
    }

    /**
     * 顶层 `script(name, target)` DSL：一行声明 script，走完整的 jrun 机制
     * （注册任务 + scripts.properties + .run 配置）。
     */
    @Test
    void topLevelScriptDslDeclaresAScript() {        write("settings.gradle.kts", "rootProject.name = \"dsl\"\n");
        write("build.gradle.kts", """
                import com.archforge.jrun.script

                plugins { id("com.archforge.jrun") }

                script("hello3", "com.example.App")
                """);
        write("main/com/example/App.java", """
                package com.example;

                public class App {
                    public static void main(String[] args) {
                        System.out.println("DSL_ARGV=" + String.join(",", args));
                    }
                }
                """);

        // 走完整机制：任务、脚本元数据、IDE 运行配置都应该有
        runner("jrunInit").build();

        String listed = runner("tasks", "--group", "jrun").build().getOutput();
        assertTrue(listed.contains("jrunHello3"),
                "script(...) should register the task, got:\n" + listed);
        assertTrue(exists(".run/jrun - hello3.run.xml"),
                "script(...) should also produce an IntelliJ run configuration");
        assertTrue(read("build/jrun/scripts.properties").contains("hello3=com.example.App"),
                "script(...) should be visible to ./jrun via scripts.properties");

        assertTrue(runner("jrunHello3").build().getOutput().contains("DSL_ARGV="),
                "the generated task should actually run the class");
    }

    /**
     * Kotlin DSL 扩展函数：在 build.gradle.kts 里手写 tasks.register 时，
     * 用 jrunMain(...) 一行搞定 classpath/mainClass。
     * （这种写法在 IDEA 里会显示 gutter 运行图标，scripts.put 不会。）
     */
    @Test
    void jrunMainExtensionConfiguresJavaExecTask() {
        write("settings.gradle.kts", "rootProject.name = \"ext\"\n");
        write("build.gradle.kts", """
                import com.archforge.jrun.jrunMain

                plugins { id("com.archforge.jrun") }

                tasks.register<JavaExec>("hello") {
                    jrunMain("com.example.App")
                    args("--x=1")
                }
                """);
        write("main/com/example/App.java", """
                package com.example;

                public class App {
                    public static void main(String[] args) {
                        System.out.println("EXT_ARGS=" + String.join(",", args));
                    }
                }
                """);

        BuildResult result = runner("hello").build();
        assertTrue(result.getOutput().contains("EXT_ARGS=--x=1"),
                "jrunMain() should wire classpath and main class, got:\n" + result.getOutput());
    }

    /**
     * taskPrefix 置空后，任务名就是 script 名（与手写 tasks.register("hello") 一致），
     * 且 IntelliJ 运行配置也会指向它。
     */
    @Test
    void taskPrefixCanBeCleared() {
        settings("prefix");
        buildScript("""
                plugins { id 'com.archforge.jrun' }
                jrun {
                    taskPrefix = ''
                    scripts.put('hello', 'com.example.App')
                }
                """);

        String listed = runner("tasks", "--group", "jrun").build().getOutput();
        assertTrue(listed.contains("hello - "),
                "task should be named 'hello' when taskPrefix is empty, got:\n" + listed);
        assertFalse(listed.contains("jrunHello"),
                "the jrun prefix should be gone entirely, got:\n" + listed);

        String xml = read(".run/jrun - hello.run.xml");
        assertTrue(xml.contains("<option value=\"hello\" />"),
                "the run configuration should point at the unprefixed task, got: " + xml);
    }

    /**
     * 项目一个仓库都没配时，插件应自动补上 mavenCentral。
     */
    @Test
    void mavenCentralIsAddedWhenNoRepositoryDeclared() {
        settings("repos");
        buildScript("""
                plugins { id 'com.archforge.jrun' }
                tasks.register('dumpRepos') {
                    doLast {
                        println "REPOS=" + repositories.collect { it.name }
                    }
                }
                """);

        String out = runner("dumpRepos").build().getOutput();
        assertTrue(out.contains("MavenRepo"),
                "mavenCentral() should be registered automatically, got:\n" + out);
    }

    /**
     * IntelliJ 运行配置：默认就生成，指向对应的 jrun 任务；关闭后会被清理。
     */
    @Test
    void intellijRunsMatchGeneratedTasksAndArePrunedWhenDisabled() {
        settings("idea-runs");
        buildScript("""
                plugins { id 'com.archforge.jrun' }
                jrun {
                    scripts.put('dev', 'com.example.App')
                    scripts.put('api', 'com.example.App --server.port=8080')
                    scripts.put('hello', 'Hello.java')
                    scripts.put('verify', 'gradle:build')
                }
                """);

        runner("help").build();

        assertTrue(exists(".run/jrun - dev.run.xml"),
                "runnable scripts should get a run configuration by default (no config needed)");
        assertTrue(exists(".run/jrun - api.run.xml"), "each runnable script gets its own configuration");
        assertFalse(exists(".run/jrun - hello.run.xml"),
                "Hello.java does not exist in this project, so no task/run-config can be derived");
        assertFalse(exists(".run/jrun - verify.run.xml"),
                "gradle: scripts have no jrun task, so no run configuration");

        String api = read(".run/jrun - api.run.xml");
        assertTrue(api.contains("<option value=\"jrunApi\" />"),
                "the run configuration should point at the generated task, got: " + api);

        // 显式关闭后，之前生成的文件必须被清理
        buildScript("""
                plugins { id 'com.archforge.jrun' }
                jrun {
                    generateIntellijRuns = false
                    scripts.put('dev', 'com.example.App')
                }
                """);
        runner("help").build();
        assertFalse(exists(".run/jrun - dev.run.xml"),
                "disabling generateIntellijRuns must remove previously generated run configurations");
    }

    /** 启动器里的 @JRUN_VERSION@ 占位符必须被替换成插件版本（jrun --version、jrun init 都要用）。 */
    @Test
    void launchersCarryThePluginVersion() {
        settings("version");
        minimalBuild();

        runner("help").build();

        String version = JrunVersion.get();
        String sh = read("jrun");
        String bat = read("jrun.bat");
        assertFalse(sh.contains("@JRUN_VERSION@"), "placeholder must be replaced in jrun");
        assertFalse(bat.contains("@JRUN_VERSION@"), "placeholder must be replaced in jrun.bat");
        assertTrue(sh.contains("JRUN_VERSION=\"" + version + "\""), "jrun should carry the plugin version");
        assertTrue(bat.contains("set \"JRUN_VERSION=" + version + "\""), "jrun.bat should carry the plugin version");
    }
}
