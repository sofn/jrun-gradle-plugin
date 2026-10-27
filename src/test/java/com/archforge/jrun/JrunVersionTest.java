package com.archforge.jrun;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 插件版本由构建时的 processResources 写入，运行期必须读到与 build.gradle.kts 一致的值。 */
class JrunVersionTest {

    @Test
    void versionComesFromTheBuild() {
        assertEquals(System.getProperty("jrun.expectedVersion"), JrunVersion.get());
    }
}
