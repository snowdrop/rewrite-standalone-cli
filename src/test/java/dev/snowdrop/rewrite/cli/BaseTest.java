package dev.snowdrop.rewrite.cli;

import dev.snowdrop.openrewrite.cli.model.Config;
import org.junit.jupiter.api.BeforeEach;

import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

public class BaseTest {

    Config cfg;

    @BeforeEach
    public void beforeEach() {
        cfg = new Config();
        cfg.setExportDatatables(true);
        cfg.setExclusions(Set.of());
        cfg.setPlainTextMasks(Set.of());
        cfg.setAdditionalJarPaths(List.of());
        cfg.setDryRun(true);
    }
}
