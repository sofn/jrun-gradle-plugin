package com.archforge.jrun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.gradle.api.DefaultTask;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.api.tasks.options.Option;

/** {@code ./jrun add} 的改写步骤：把坐标写进构建脚本的顶层 dependencies 块。 */
@UntrackedTask(because = "edits the build script in place")
public abstract class JrunAddTask extends DefaultTask {

    @Option(option = "dependency", description = "Coordinate group:artifact[:version]; repeat for several")
    @Input
    public abstract ListProperty<String> getDependencies();

    @Option(option = "configuration", description = "Configuration to add to (default: implementation)")
    @Input
    public abstract Property<String> getConfiguration();

    @Internal
    public abstract RegularFileProperty getBuildFile();

    @TaskAction
    public void add() throws IOException {
        List<String> coordinates = getDependencies().get();
        if (coordinates.isEmpty()) {
            throw new InvalidUserDataException(
                    "Usage: ./jrun add [--test | --config <name>] <group:artifact[:version]>...");
        }
        Path file = getBuildFile().get().getAsFile().toPath();
        boolean kotlinDsl = file.getFileName().toString().endsWith(".kts");
        String script = Files.readString(file);
        for (String coordinate : coordinates) {
            script = BuildFileEditor.addDependency(script, kotlinDsl, getConfiguration().get(), coordinate);
        }
        FileWrites.writeIfChanged(file, script);
    }
}
