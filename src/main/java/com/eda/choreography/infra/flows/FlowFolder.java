package com.eda.choreography.infra.flows;

import com.eda.choreography.domain.plan.InvalidPlanException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The manual path into the store: every {@code <name>.bpmn} in a folder is defined as the flow
 * {@code <name>}. A file that cannot run is logged and left out; the others still load.
 */
public final class FlowFolder {

    private static final Logger LOG = LoggerFactory.getLogger(FlowFolder.class);

    private FlowFolder() {
    }

    /** @return how many flows were defined */
    public static int load(Path folder, Flows flows) {
        if (!Files.isDirectory(folder)) {
            LOG.info("no flow folder at {}", folder.toAbsolutePath());
            return 0;
        }
        var loaded = 0;
        try (var files = Files.list(folder)) {
            for (var file : files.filter(path -> path.toString().endsWith(".bpmn")).sorted().toList()) {
                var name = file.getFileName().toString().replaceFirst("\\.bpmn$", "");
                try {
                    var defined = flows.define(name, Files.readString(file, StandardCharsets.UTF_8));
                    LOG.info("defined flow {} from {}; late-bound services: {}", name, file, defined.bindings().lateBound());
                    loaded++;
                } catch (InvalidPlanException | IllegalArgumentException e) {
                    LOG.error("left out {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the flow folder " + folder, e);
        }
        return loaded;
    }
}
