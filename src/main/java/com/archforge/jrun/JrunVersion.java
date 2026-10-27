package com.archforge.jrun;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** 插件自身版本：构建时由 processResources 写入 {@code jrun/version.properties}。 */
final class JrunVersion {

    private static final String VERSION = load();

    private JrunVersion() {
    }

    static String get() {
        return VERSION;
    }

    private static String load() {
        try (InputStream in = JrunVersion.class.getResourceAsStream("/jrun/version.properties")) {
            Properties properties = new Properties();
            if (in != null) {
                properties.load(in);
            }
            return properties.getProperty("version", "0.0.0");
        } catch (IOException e) {
            return "0.0.0";
        }
    }
}
