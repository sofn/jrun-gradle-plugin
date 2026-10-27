package com.archforge.jrun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** install.sh 走一遍真实安装：资产来自 jrunDist 的产物（file:// 地址），HOME 指向临时目录。 */
@DisabledOnOs(OS.WINDOWS)
class JrunInstallerTest extends AbstractJrunTest {

    private Path dist;

    @BeforeEach
    void requireDist() {
        String dir = System.getProperty("jrun.dist.dir");
        Assumptions.assumeTrue(dir != null && Files.isDirectory(Path.of(dir)), "run through gradle test (needs jrunDist)");
        Assumptions.assumeTrue(findBash() != null, "bash not available");
        dist = Path.of(dir);
    }

    private ProcessResult install(Path assets) {
        String base = assets.toUri().toString().replaceAll("/$", "");
        return runBash(Path.of("installer/install.sh").toAbsolutePath(), projectDir, Map.of(
                "HOME", projectDir.resolve("home").toString(),
                "JRUN_DOWNLOAD_BASE", base,
                "JRUN_INSTALL_DIR", "", "JRUN_HOME", "", "XDG_DATA_HOME", "", "JRUN_VERSION", ""));
    }

    @Test
    void releaseAssetsAreCompleteAndStamped() throws IOException {
        for (String asset : List.of("jrun", "jrun.bat", "install.sh", "install.ps1", "gradlew", "gradlew.bat",
                "gradle-wrapper.jar", "gradle-wrapper.properties", "SHA256SUMS")) {
            assertTrue(Files.isRegularFile(dist.resolve(asset)), "missing release asset " + asset);
        }
        assertFalse(Files.readString(dist.resolve("jrun")).contains("@JRUN_VERSION@"));
        String bat = Files.readString(dist.resolve("jrun.bat"));
        assertEquals(bat.split("\n", -1).length, bat.split("\r\n", -1).length, "jrun.bat must use CRLF");
    }

    @Test
    void installsTheLauncherAndTheWrapperTemplateIntoTheUserHome() {
        ProcessResult r = install(dist);
        assertEquals(0, r.exitCode(), r.output());

        Path home = projectDir.resolve("home");
        Path jrun = home.resolve(".local/bin/jrun");
        assertTrue(Files.isExecutable(jrun), "jrun should land in ~/.local/bin");
        assertTrue(Files.isRegularFile(home.resolve(".local/share/jrun/wrapper/gradle/wrapper/gradle-wrapper.jar")));
        assertTrue(r.output().contains("jrun " + JrunVersion.get()), r.output());
    }

    @Test
    void refusesAssetsThatDoNotMatchTheChecksums() throws IOException {
        Path tampered = Files.createDirectories(projectDir.resolve("tampered"));
        try (Stream<Path> files = Files.list(dist)) {
            for (Path file : files.toList()) {
                Files.copy(file, tampered.resolve(file.getFileName()));
            }
        }
        Files.writeString(tampered.resolve("jrun"), "#!/bin/sh\necho pwned\n");

        ProcessResult r = install(tampered);
        assertNotEquals(0, r.exitCode(), r.output());
        assertTrue(r.output().contains("checksum mismatch for jrun"), r.output());
        assertFalse(Files.exists(projectDir.resolve("home/.local/bin/jrun")), "nothing may be installed");
    }
}
