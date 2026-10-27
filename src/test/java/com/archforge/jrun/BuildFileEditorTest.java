package com.archforge.jrun;

import org.gradle.api.InvalidUserDataException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ./jrun add 的文本改写：只动顶层 dependencies 块，保持原有缩进与换行符。 */
class BuildFileEditorTest {

    private static String kts(String script, String coordinate) {
        return BuildFileEditor.addDependency(script, true, "implementation", coordinate);
    }

    @Test
    void insertsIntoTheTopLevelKotlinBlockWithItsIndentation() {
        String script = """
                plugins { id("com.archforge.jrun") }

                dependencies {
                  implementation("org.slf4j:slf4j-api:2.0.17")
                }
                """;
        assertEquals("""
                plugins { id("com.archforge.jrun") }

                dependencies {
                  implementation("org.slf4j:slf4j-api:2.0.17")
                  implementation("com.google.code.gson:gson:2.11")
                }
                """, kts(script, "com.google.code.gson:gson:2.11"));
    }

    @Test
    void usesGroovySyntaxForBuildGradle() {
        assertEquals("dependencies {\n    implementation 'a:b:1'\n    testImplementation 'org.junit.jupiter:junit-jupiter:5.14.4'\n}\n",
                BuildFileEditor.addDependency("dependencies {\n    implementation 'a:b:1'\n}\n", false,
                        "testImplementation", "org.junit.jupiter:junit-jupiter:5.14.4"));
    }

    @Test
    void createsTheBlockWhenMissing() {
        assertEquals("plugins { id(\"com.archforge.jrun\") }\n\ndependencies {\n"
                        + "    implementation(\"com.google.code.gson:gson:2.11\")\n}\n",
                kts("plugins { id(\"com.archforge.jrun\") }\n", "com.google.code.gson:gson:2.11"));
    }

    @Test
    void leavesBuildscriptAndNestedDependenciesBlocksAlone() {
        String result = kts("""
                buildscript {
                    dependencies {
                        classpath("x:y:1")
                    }
                }
                kotlin {
                    sourceSets { main { dependencies { } } }
                }
                dependencies {
                }
                """, "a:b:1");
        assertTrue(result.endsWith("dependencies {\n    implementation(\"a:b:1\")\n}\n"), result);
        assertEquals(result.indexOf("a:b:1"), result.lastIndexOf("a:b:1"), "inserted exactly once:\n" + result);
    }

    @Test
    void ignoresBracesAndKeywordsInStringsAndComments() {
        String result = kts("""
                // dependencies { not this one }
                val text = "dependencies { nor this }"
                /* dependencies {
                   } */
                dependencies {
                    implementation("x:y:1") // { stray brace in a comment
                }
                """, "a:b:1");
        assertTrue(result.contains("    implementation(\"x:y:1\") // { stray brace in a comment\n"
                + "    implementation(\"a:b:1\")\n}\n"), result);
    }

    @Test
    void upgradesAnExistingCoordinateInPlace() {
        assertEquals("dependencies {\n    api(\"com.google.code.gson:gson:2.11\")\n}\n",
                kts("dependencies {\n    api(\"com.google.code.gson:gson:2.10\")\n}\n", "com.google.code.gson:gson:2.11"));
    }

    @Test
    void expandsAnInlineEmptyBlock() {
        assertEquals("dependencies {\n    implementation(\"a:b:1\")\n}\n", kts("dependencies {}\n", "a:b:1"));
    }

    @Test
    void keepsWindowsLineEndings() {
        assertEquals("dependencies {\r\n    implementation(\"x:y:1\")\r\n    implementation(\"a:b:1\")\r\n}\r\n",
                kts("dependencies {\r\n    implementation(\"x:y:1\")\r\n}\r\n", "a:b:1"));
    }

    @Test
    void acceptsVersionlessCoordinatesManagedByAPlatform() {
        assertTrue(kts("dependencies {\n}\n", "org.springframework.boot:spring-boot-starter-web")
                .contains("    implementation(\"org.springframework.boot:spring-boot-starter-web\")\n"));
    }

    /** 坐标与配置名会被原样写进构建脚本，必须在入口处严格校验（防止注入任意 Kotlin/Groovy 代码）。 */
    @Test
    void rejectsMalformedCoordinatesAndConfigurations() {
        assertThrows(InvalidUserDataException.class, () -> kts("", "gson"));
        assertThrows(InvalidUserDataException.class, () -> kts("", "a:b:1\") ; evil(\""));
        assertThrows(InvalidUserDataException.class,
                () -> BuildFileEditor.addDependency("", true, "impl(\"x\")", "a:b:1"));
    }
}
