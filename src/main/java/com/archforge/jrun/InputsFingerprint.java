package com.archforge.jrun;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 运行缓存的内容指纹，格式与 sha256sum 相同："{@code <hash>  <相对路径>}"，每行一个文件。
 *
 * <p>这是插件与启动器之间的协议：jrun.sh 按同样的文件集合、顺序和格式重新计算后逐字比较，
 * jrun.bat 则逐行重新计算 inputs 清单里每个文件的哈希。改动这里必须同步修改两个启动器。
 */
final class InputsFingerprint {

    /** 影响 classpath 的构建输入（字典序）；先查项目目录，再查构建根目录（多模块时以 ../ 记录）。 */
    static final List<String> BUILD_FILES = List.of(
            "build.gradle", "build.gradle.kts", "gradle.properties",
            "gradle/libs.versions.toml", "settings.gradle", "settings.gradle.kts");

    /** 编译进 build/classes 与 build/resources 的目录；test/ 不在运行 classpath 上。 */
    static final List<String> SOURCE_DIRS = List.of("main", "resources");

    /** UTF-8 字节序，与启动器里的 {@code LC_ALL=C sort} 一致。 */
    private static final Comparator<String> BYTEWISE = (a, b) -> Arrays.compareUnsigned(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));

    private InputsFingerprint() {
    }

    static String inputs(Path projectDir, Path rootDir) throws IOException {
        return inputs(projectDir, rootDir, List.of());
    }

    static String sources(Path projectDir) throws IOException {
        return sources(projectDir, List.of());
    }

    /** 项目目录、构建根目录，再加上 classpath 上其他模块（project 依赖）的目录。 */
    static String inputs(Path projectDir, Path rootDir, List<Path> dependencyDirs) throws IOException {
        Set<Path> bases = new LinkedHashSet<>(List.of(projectDir.normalize(), rootDir.normalize()));
        dependencyDirs.forEach(d -> bases.add(d.normalize()));
        List<String> files = new ArrayList<>();
        for (Path base : bases) {
            for (String name : BUILD_FILES) {
                Path file = base.resolve(name);
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    files.add(relative(projectDir, file));
                }
            }
        }
        return manifest(projectDir, files);
    }

    /** main/、resources/，再加上 project 依赖的源码目录（相对项目目录，启动器从 source-roots 读到同一份列表）。 */
    static String sources(Path projectDir, List<String> dependencyRoots) throws IOException {
        List<String> files = new ArrayList<>();
        List<String> roots = new ArrayList<>(SOURCE_DIRS);
        roots.addAll(dependencyRoots);
        for (String dir : roots) {
            Path root = projectDir.resolve(dir);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        .forEach(p -> files.add(relative(projectDir, p)));
            }
        }
        files.sort(BYTEWISE);
        return manifest(projectDir, files);
    }

    private static String manifest(Path base, List<String> files) throws IOException {
        StringBuilder out = new StringBuilder();
        for (String file : files) {
            out.append(sha256(base.resolve(file))).append("  ").append(file).append('\n');
        }
        return out.toString();
    }

    static String relative(Path base, Path file) {
        return base.normalize().relativize(file.normalize()).toString().replace(File.separatorChar, '/');
    }

    static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JDK", e);
        }
    }
}
