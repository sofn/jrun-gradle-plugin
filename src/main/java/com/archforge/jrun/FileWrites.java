package com.archforge.jrun;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** 内容不变就不写：不触碰 mtime，避免破坏构建缓存或触发无谓的重编译。 */
final class FileWrites {

    private FileWrites() {
    }

    static void writeIfChanged(Path path, String content) throws IOException {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        if (Files.exists(path) && Arrays.equals(Files.readAllBytes(path), data)) {
            return;
        }
        Files.createDirectories(path.getParent());
        Files.write(path, data);
    }
}
