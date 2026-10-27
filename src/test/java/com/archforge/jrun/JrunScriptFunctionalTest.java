package com.archforge.jrun;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脚本级端到端测试：真实执行项目根目录生成的 {@code jrun} 启动器。
 *
 * <p>为了让脚本不去调用 {@code ./gradlew}（TestKit 生成的临时项目没有 wrapper），
 * 每个用例都先用 TestKit 跑一次 {@code jrunInit} 把 argfile 缓存准备好，
 * 之后脚本会走「缓存命中」路径，直接 exec java。
 */
class JrunScriptFunctionalTest extends AbstractJrunTest {

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(findBash() != null, "bash not available on this machine, skipping launcher tests");

        settings("script-test");
        buildScript("""
                plugins { id 'com.archforge.jrun' }
                jrun {
                    scripts.put('plain', 'Hello')
                    scripts.put('fixed', 'Hello a=1 --server.port=18071')
                }
                """);
        write("main/Hello.java", """
                public class Hello {
                    public static void main(String[] args) {
                        System.out.println("Hello, World!");
                        System.out.println("ARGC=" + args.length);
                        System.out.println("ARGS=" + String.join(",", args));
                    }
                }
                """);

        // 先生成缓存，脚本之后就不会再触发 Gradle
        runner("jrunInit").build();
    }

    @Test
    void runsSingleJavaFile() {
        ProcessResult r = jrun("Hello.java");
        assertEquals(0, r.exitCode(), "jrun should exit 0, stderr+stdout:\n" + r.output());
        assertTrue(r.output().contains("Hello, World!"), "expected greeting, got:\n" + r.output());
        assertTrue(r.output().contains("ARGC=0"), "no args expected, got:\n" + r.output());
    }

    @Test
    void passesArgumentsThrough() {
        ProcessResult r = jrun("Hello.java", "alpha", "beta");
        assertEquals(0, r.exitCode(), "jrun should exit 0, output:\n" + r.output());
        assertTrue(r.output().contains("ARGC=2"), "expected 2 args, got:\n" + r.output());
        assertTrue(r.output().contains("ARGS=alpha,beta"), "args must be forwarded verbatim, got:\n" + r.output());
    }

    /**
     * 回归用例：曾经因为 bash 数组切片的写法有误（把 {@code 1:+} 当成了算术切片），
     * 命名 scripts 里的固定参数被静默丢弃。
     */
    @Test
    void namedScriptKeepsFixedArguments() {
        ProcessResult r = jrun("plain");
        assertEquals(0, r.exitCode(), "script 'plain' should exit 0, output:\n" + r.output());
        assertTrue(r.output().contains("Hello, World!"), "script 'plain' should run Hello, got:\n" + r.output());

        ProcessResult fixed = jrun("fixed");
        assertEquals(0, fixed.exitCode(), "script 'fixed' should exit 0, output:\n" + fixed.output());
        assertTrue(fixed.output().contains("ARGC=2"),
                "fixed arguments must not be dropped or split, got:\n" + fixed.output());
        // 关键：含 "=" 的参数不能被拆成两段
        assertTrue(fixed.output().contains("ARGS=a=1,--server.port=18071"),
                "arguments containing '=' must survive intact, got:\n" + fixed.output());
    }

    @Test
    void missingSourceFailsWithClearMessage() {
        ProcessResult r = jrun("Nope.java");
        assertNotEquals(0, r.exitCode(), "missing source should exit non-zero, output:\n" + r.output());
        assertTrue(r.output().contains("找不到源文件") || r.output().contains("Source file not found"),
                "should explain the missing file, got:\n" + r.output());
    }
}
