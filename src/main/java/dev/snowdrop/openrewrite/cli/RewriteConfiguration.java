/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.snowdrop.openrewrite.cli;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

/**
 * Configuration properties for the Rewrite CLI application.
 * These properties can be set via environment variables or application.properties.
 *
 * Environment variable examples:
 * - REWRITE_CONFIG_LOCATION=/path/to/rewrite.yml
 * - REWRITE_SIZE_THRESHOLD_MB=20
 * - REWRITE_EXPORT_DATATABLES=true
 */
@ConfigMapping(prefix = "rewrite")
public interface RewriteConfiguration {

    /**
     * Location of the rewrite.yml configuration file.
     * Can be overridden with REWRITE_CONFIG_LOCATION environment variable.
     */
    @WithDefault("rewrite.yml")
    String configLocation();

    /**
     * Size threshold in MB for large files.
     * Can be overridden with REWRITE_SIZE_THRESHOLD_MB environment variable.
     */
    @WithDefault("10")
    int sizeThresholdMb();

    /**
     * Whether to export datatables to CSV files.
     * Can be overridden with REWRITE_EXPORT_DATATABLES environment variable.
     */
    @WithDefault("true")
    boolean exportDatatables();

    /**
     * Whether to fail on invalid active recipes.
     * Can be overridden with REWRITE_FAIL_ON_INVALID_ACTIVE_RECIPES environment variable.
     */
    @WithDefault("false")
    boolean failOnInvalidActiveRecipes();

    /**
     * Comma-separated list of plain text file masks.
     * Can be overridden with REWRITE_PLAIN_TEXT_MASKS environment variable.
     */
    Optional<String> plainTextMasks();

    /**
     * Comma-separated list of file patterns to exclude.
     * Can be overridden with REWRITE_EXCLUSIONS environment variable.
     */
    Optional<String> exclusions();
}