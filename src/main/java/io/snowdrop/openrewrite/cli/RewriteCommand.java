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
package io.snowdrop.openrewrite.cli;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import jakarta.inject.Inject;
import org.apache.maven.model.Model;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.config.CompositeRecipe;
import org.openrewrite.config.DeclarativeRecipe;
import org.openrewrite.config.Environment;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.internal.JavaTypeCache;
import org.openrewrite.java.marker.JavaProject;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.marker.JavaVersion;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.marker.BuildTool;
import org.openrewrite.marker.Marker;
import org.openrewrite.marker.Markers;
import org.openrewrite.marker.OperatingSystemProvenance;
import org.openrewrite.marker.ci.BuildEnvironment;
import org.openrewrite.polyglot.OmniParser;
import org.openrewrite.style.NamedStyles;
import org.openrewrite.text.PlainTextParser;
import picocli.CommandLine;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.stream.Collectors.toList;
import static org.openrewrite.Tree.randomId;

/**
 * Quarkus-based standalone CLI for OpenRewrite supporting dry-run mode
 */
@TopCommand
@CommandLine.Command(
    name = "rewrite",
    mixinStandardHelpOptions = true,
    version = "1.0.0-SNAPSHOT",
    description = "Standalone OpenRewrite CLI tool for applying recipes to codebases",
    footer = "\nExample usage:\n" +
        "  rewrite /path/to/project org.openrewrite.java.format.AutoFormat\n" +
        "  rewrite --jar custom-recipes.jar --export-datatables /path/to/project MyRecipe\n" +
        "  rewrite --jar org.openrewrite:rewrite-java:8.62.4,dev.snowdrop:openrewrite-recipes:1.0.0-SNAPSHOT /path/to/project MyRecipe\n" +
        "  rewrite --config /path/to/rewrite.yml /path/to/project MyRecipe"
)
public class RewriteCommand implements Runnable {

    @CommandLine.Parameters(
        index = "0",
        description = "The root directory of the project to analyze"
    )
    Path projectRoot;

    @CommandLine.Parameters(
        index = "1",
        description = "Active recipe to run (e.g., org.openrewrite.java.format.AutoFormat)"
    )
    //Set<String> activeRecipes = new HashSet<>();
    String activeRecipe;

    @CommandLine.Parameters(
        index = "2",
        description = "Options of the recipe to be used to set the recipe's object fields. Example: annotationPattern=@SpringBootApplication",
        split = ","
    )
    LinkedHashSet<String> recipeOptions;

    @CommandLine.Option(
        names = {"--jar"},
        description = "Additional JAR files containing recipes (file paths or Maven GAV coordinates, can be specified multiple times or comma-separated)",
        split = ","
    )
    List<String> additionalJarPaths = new ArrayList<>();

    @CommandLine.Option(
        names = {"--config", "-c"},
        description = "Path to the rewrite.yml configuration file (default: ${DEFAULT-VALUE})"
    )
    String configLocation;

    @CommandLine.Option(
        names = {"--export-datatables"},
        description = "Export datatables to CSV files"
    )
    boolean exportDatatables;

    @CommandLine.Option(
        names = {"--exclusions"},
        description = "File patterns to exclude (can be specified multiple times)",
        split = ","
    )
    Set<String> exclusions = new HashSet<>();

    @CommandLine.Option(
        names = {"--plain-text-masks"},
        description = "Plain text file masks (can be specified multiple times)",
        split = ","
    )
    Set<String> plainTextMasks = new HashSet<>();

    @CommandLine.Option(
        names = {"--size-threshold-mb"},
        description = "Size threshold in MB for large files (default: ${DEFAULT-VALUE})"
    )
    int sizeThresholdMb = 10;

    // Inject Quarkus configuration properties
    @Inject
    RewriteConfiguration config;

    @Override
    public void run() {
        try {
            // Use injected defaults if not specified via command line
            if (configLocation == null) {
                configLocation = config.configLocation();
            }
            if (sizeThresholdMb == 0) {
                sizeThresholdMb = config.sizeThresholdMb();
            }
            if (!exportDatatables) {
                exportDatatables = config.exportDatatables();
            }
            if (plainTextMasks.isEmpty() && config.plainTextMasks().isPresent()) {
                plainTextMasks.addAll(Arrays.asList(config.plainTextMasks().get().split(",")));
            }
            if (exclusions.isEmpty() && config.exclusions().isPresent()) {
                exclusions.addAll(Arrays.asList(config.exclusions().get().split(",")));
            }

            execute();
        } catch (Exception e) {
            System.err.println("Error executing rewrite command: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public void execute() throws Exception {
        System.out.println("Starting OpenRewrite dry-run...");
        System.out.println("Project root: " + projectRoot.toAbsolutePath());
        System.out.println("Active recipe: " + activeRecipe);

        if (!additionalJarPaths.isEmpty()) {
            System.out.println("Additional JAR files: " + additionalJarPaths);
        }

        List<Throwable> throwables = new ArrayList<>();
        ExecutionContext ctx = createExecutionContext(throwables);

        ResultsContainer results = listResults(ctx);

        RuntimeException firstException = results.getFirstException();
        if (firstException != null) {
            System.err.println("The recipe produced an error. Please report this to the recipe author.");
            throw firstException;
        }

        if (!throwables.isEmpty()) {
            System.err.println("The recipe produced " + throwables.size() + " warning(s). Please report this to the recipe author.");
            for (Throwable throwable : throwables) {
                System.err.println("Warning: " + throwable.getMessage());
            }
        }

        if (results.isNotEmpty()) {
            Duration estimateTimeSaved = Duration.ZERO;

            for (Result result : results.generated) {
                assert result.getAfter() != null;
                System.err.println("These recipes would generate a new file " +
                    result.getAfter().getSourcePath() + ":");
                logRecipesThatMadeChanges(result);
                estimateTimeSaved = estimateTimeSaved.plus(result.getTimeSavings() != null ?
                    result.getTimeSavings() : Duration.ZERO);
            }

            for (Result result : results.deleted) {
                assert result.getBefore() != null;
                System.err.println("These recipes would delete a file " +
                    result.getBefore().getSourcePath() + ":");
                logRecipesThatMadeChanges(result);
                estimateTimeSaved = estimateTimeSaved.plus(result.getTimeSavings() != null ?
                    result.getTimeSavings() : Duration.ZERO);
            }

            for (Result result : results.moved) {
                assert result.getBefore() != null;
                assert result.getAfter() != null;
                System.err.println("These recipes would move a file from " +
                    result.getBefore().getSourcePath() + " to " +
                    result.getAfter().getSourcePath() + ":");
                logRecipesThatMadeChanges(result);
                estimateTimeSaved = estimateTimeSaved.plus(result.getTimeSavings() != null ?
                    result.getTimeSavings() : Duration.ZERO);
            }

            for (Result result : results.refactoredInPlace) {
                assert result.getBefore() != null;
                System.err.println("These recipes would make changes to " +
                    result.getBefore().getSourcePath() + ":");
                logRecipesThatMadeChanges(result);
                estimateTimeSaved = estimateTimeSaved.plus(result.getTimeSavings() != null ?
                    result.getTimeSavings() : Duration.ZERO);
            }

            // Create patch file
            Path outPath = projectRoot.resolve("target").resolve("rewrite");
            try {
                Files.createDirectories(outPath);
            } catch (IOException e) {
                throw new RuntimeException("Could not create the folder [" + outPath + "].", e);
            }

            Path patchFile = outPath.resolve("rewrite.patch");
            try (BufferedWriter writer = Files.newBufferedWriter(patchFile)) {
                Stream.concat(
                    Stream.concat(results.generated.stream(), results.deleted.stream()),
                    Stream.concat(results.moved.stream(), results.refactoredInPlace.stream())
                )
                .map(Result::diff)
                .forEach(diff -> {
                    try {
                        writer.write(diff + "\n");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (Exception e) {
                throw new RuntimeException("Unable to generate rewrite result.", e);
            }

            System.err.println("Patch file available:");
            System.err.println("    " + patchFile.normalize());
            System.err.println("Estimate time saved: " + formatDuration(estimateTimeSaved));
            System.err.println("Run 'mvn rewrite:run' to apply the recipes.");
        } else {
            System.out.println("Applying recipes would make no changes. No patch file generated.");
        }
    }

    private ExecutionContext createExecutionContext(List<Throwable> throwables) {
        return new InMemoryExecutionContext(t -> {
            System.err.println("Debug: " + t.getMessage());
            throwables.add(t);
        });
    }

    private ResultsContainer listResults(ExecutionContext ctx) throws Exception {
        System.out.println("Using active recipe(s): " + activeRecipe);

        if (activeRecipe.isEmpty()) {
            return new ResultsContainer(projectRoot, emptyList());
        }

        Environment env = createEnvironment();

        // This code works when we instantiate directly the Recipe class as the jar packaging it is loaded by the application
        // Recipe recipe = new FindAnnotations("@org.springframework.boot.autoconfigure.SpringBootApplication",false);

        // The recipe class is created using the Resource classloader using the FQName
        Recipe recipe = env.activateRecipes(activeRecipe);

        // The Recipe class has been instantiated from the FQName string but the fields/parameters still need to be set
        //Set<String> options = Collections.singleton("annotationPattern=@org.springframework.boot.autoconfigure.SpringBootApplication");
        configureRecipeOptions(recipe, recipeOptions);

        if ("org.openrewrite.Recipe$Noop".equals(recipe.getName())) {
            System.err.println("No recipes were activated. " +
                "Activate a recipe by providing it as a command line argument.");
            return new ResultsContainer(projectRoot, emptyList());
        }

        System.out.println("Validating active recipes...");
        List<Validated<Object>> validations = new ArrayList<>();
        recipe.validateAll(ctx, validations);
        List<Validated.Invalid<Object>> failedValidations = validations.stream()
            .map(Validated::failures)
            .flatMap(Collection::stream)
            .collect(toList());

        if (!failedValidations.isEmpty()) {
            failedValidations.forEach(failedValidation ->
                System.err.println("Recipe validation error in " + failedValidation.getProperty() +
                    ": " + failedValidation.getMessage()));
            System.err.println("Recipe validation errors detected as part of one or more activeRecipe(s). " +
                "Execution will continue regardless.");
        }

        LargeSourceSet sourceSet = loadSourceSet(env, ctx);
        List<Result> results = runRecipe(recipe, sourceSet, ctx);

        return new ResultsContainer(projectRoot, results);
    }

    /**
     * Creates a URLClassLoader from the additional JAR paths or Maven GAV coordinates.
     * @return URLClassLoader containing the additional Rewrite JARs, or null if no additional JARs are specified
     */
    private URLClassLoader loadAdditionalJars() {
        if (additionalJarPaths.isEmpty()) {
            return null;
        }

        List<URL> jarUrls = new ArrayList<>();
        MavenArtifactResolver resolver = new MavenArtifactResolver();

        try {
            // Resolve all jar paths/coordinates to actual file paths
            List<Path> resolvedPaths = resolver.resolveArtifacts(additionalJarPaths);

            for (Path jarPath : resolvedPaths) {
                try {
                    if (!Files.exists(jarPath)) {
                        System.err.println("Warning: JAR file does not exist: " + jarPath);
                        continue;
                    }
                    if (!jarPath.toString().toLowerCase().endsWith(".jar")) {
                        System.err.println("Warning: File is not a JAR: " + jarPath);
                        continue;
                    }
                    jarUrls.add(jarPath.toUri().toURL());
                    System.out.println("Loaded additional JAR: " + jarPath);
                } catch (MalformedURLException e) {
                    System.err.println("Could not load JAR: " + jarPath + " - " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Error resolving Maven artifacts: " + e.getMessage());
            e.printStackTrace();
            return null;
        }

        return jarUrls.isEmpty() ? null :
            URLClassLoader.newInstance(jarUrls.toArray(new URL[0]), this.getClass().getClassLoader());
    }

    /**
     * Merges URLs from the source classloader into the target classloader.
     * This is similar to the merge functionality in the Maven plugin.
     * Handles both URLClassLoader and Quarkus classloaders gracefully.
     */
    private void merge(ClassLoader targetClassLoader, URLClassLoader sourceClassLoader) {
        // In Quarkus dev mode, the classloader is typically a QuarkusClassLoader,
        // not a URLClassLoader. Since recipe discovery is already handled by the
        // ClasspathScanningLoader with the additional classloader, we don't need
        // to merge URLs into the runtime classloader. Just log the additional JARs.

        if (!(targetClassLoader instanceof URLClassLoader)) {
            System.out.println("Running in Quarkus mode - using ClasspathScanningLoader for additional JARs:");
            for (URL newUrl : sourceClassLoader.getURLs()) {
                System.out.println("  Using JAR from additional classpath: " + newUrl);
            }
            return;
        }

        URLClassLoader targetUrlClassLoader = (URLClassLoader) targetClassLoader;
        Set<String> existingVersionlessJars = new HashSet<>();

        for (URL existingUrl : targetUrlClassLoader.getURLs()) {
            existingVersionlessJars.add(stripVersion(existingUrl));
        }

        for (URL newUrl : sourceClassLoader.getURLs()) {
            if (!existingVersionlessJars.contains(stripVersion(newUrl))) {
                // Note: This is a simplified version. In a real implementation,
                // you might need to use reflection to add URLs to the URLClassLoader
                System.out.println("Would add JAR to classpath: " + newUrl);
            }
        }
    }

    /**
     * Strips version information from JAR URLs for comparison.
     */
    private String stripVersion(URL jarUrl) {
        return jarUrl.toString().replaceAll("/[^/]+/[^/]+\\.jar", "");
    }

    private Environment createEnvironment() throws Exception {
        Environment.Builder env = Environment.builder();

        // Load additional JARs if specified
        URLClassLoader additionalJarsClassloader = loadAdditionalJars();

        if (additionalJarsClassloader != null) {
            // Load recipes using the ClasspathScanningLoader with the additional classloader
            // This is the key fix - we use the additionalJarsClassloader for recipe discovery
            env.load(new org.openrewrite.config.ClasspathScanningLoader(new Properties(), additionalJarsClassloader));
            merge(getClass().getClassLoader(), additionalJarsClassloader);
            System.out.println("Loaded recipes from additional JARs");
        }

        // Scan runtime classpath and user home
        env.scanRuntimeClasspath().scanUserHome();

        // Load the YAML configuration file if it exists from the project to scan
        Path configPath;
        if (Paths.get(configLocation).isAbsolute()) {
            // Use absolute path directly
            configPath = Paths.get(configLocation);
        } else {
            // Check for APP_PROJECT environment variable first
            String appProject = System.getenv("APP_PROJECT");
            if (appProject != null && !appProject.isEmpty()) {
                configPath = Paths.get(appProject);
            } else {
                // Fall back to resolving against project root
                configPath = projectRoot.resolve(configLocation);
            }
        }

        if (Files.exists(configPath)) {
            try (InputStream is = Files.newInputStream(configPath)) {
                env.load(new YamlResourceLoader(is, configPath.toUri(), new Properties()));
            }
        }

        return env.build();
    }

    private LargeSourceSet loadSourceSet(Environment env, ExecutionContext ctx) throws Exception {
        List<NamedStyles> styles = env.activateStyles(emptySet());

        System.out.println("Parsing source files...");
        List<SourceFile> sourceFiles = new ArrayList<>();

        // Parse Java files
        List<Path> javaFiles = findFiles(projectRoot, ".java");
        if (!javaFiles.isEmpty()) {
            // If we have java files, then we assume that we have a pom and dependencies
            MavenUtils mavenUtils = new MavenUtils();
            Model model = mavenUtils.setupProject(Paths.get(projectRoot.toString(), "pom.xml").toFile());

            // Collect the GAVs and their transitive dependencies
            MavenArtifactResolver mar = new MavenArtifactResolver();
            List<Path> classpaths = mar.resolveArtifactsWithDependencies(mavenUtils.convertModelDependenciesToAetherDependencies(model.getDependencies()));

            // Create the JavaParser and set the classpaths
            JavaParser.Builder<? extends JavaParser, ?> javaParserBuilder = JavaParser.fromJavaVersion()
                .styles(styles).logCompilationWarningsAndErrors(false);
            JavaTypeCache typeCache = new JavaTypeCache();
            javaParserBuilder.classpath(classpaths).typeCache(typeCache);

            // Load the Java source files
            JavaParser jp = javaParserBuilder.build();
            sourceFiles.addAll(jp.parse(javaFiles, projectRoot, ctx).toList());
            System.out.println("Parsed " + javaFiles.size() + " Java files");
        }

        // Parse Kotlin files
        List<Path> kotlinFiles = findFiles(projectRoot, ".kt");
        if (!kotlinFiles.isEmpty()) {
            KotlinParser kotlinParser = KotlinParser.builder().build();
            sourceFiles.addAll(kotlinParser.parse(kotlinFiles, projectRoot, ctx).toList());
            System.out.println("Parsed " + kotlinFiles.size() + " Kotlin files");
        }

        // Parse other files (XML, YAML, properties, etc.)
        Set<String> masks = plainTextMasks.isEmpty() ? getDefaultPlainTextMasks() : plainTextMasks;
        OmniParser omniParser = OmniParser.builder(
            OmniParser.defaultResourceParsers(),
            PlainTextParser.builder()
                .plainTextMasks(projectRoot, masks)
                .build()
        )
        .sizeThresholdMb(sizeThresholdMb)
        .build();

        List<Path> otherFiles = omniParser.acceptedPaths(projectRoot, projectRoot);
        sourceFiles.addAll(omniParser.parse(otherFiles, projectRoot, ctx).toList());

        // Add provenance markers
        List<Marker> provenance = generateProvenance();
        sourceFiles = sourceFiles.stream()
            .map(sf -> addProvenance(sf, provenance))
            .collect(toList());

        System.out.println("Total source files parsed: " + sourceFiles.size());
        return new InMemoryLargeSourceSet(sourceFiles);
    }

    private List<Result> runRecipe(Recipe recipe, LargeSourceSet sourceSet, ExecutionContext ctx) {
        System.out.println("Running recipe(s)...");
        RecipeRun recipeRun = recipe.run(sourceSet, ctx);

        if (exportDatatables) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"));
            Path datatableDirectoryPath = projectRoot.resolve("target").resolve("rewrite").resolve("datatables").resolve(timestamp);
            System.out.println("Printing available datatables to: " + datatableDirectoryPath);
            recipeRun.exportDatatablesToCsv(datatableDirectoryPath, ctx);
        }

        return recipeRun.getChangeset().getAllResults();
    }

    private List<Path> findFiles(Path root, String extension) throws IOException {
        List<Path> files = new ArrayList<>();

        Collection<PathMatcher> exclusionMatchers = exclusions.stream()
            .map(pattern -> root.getFileSystem().getPathMatcher("glob:" + pattern))
            .toList();

        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.toString().endsWith(extension)) {
                    // Check if file is excluded
                    boolean excluded = false;
                    Path relativePath = root.relativize(file);
                    for (PathMatcher matcher : exclusionMatchers) {
                        if (matcher.matches(relativePath)) {
                            excluded = true;
                            break;
                        }
                    }

                    if (!excluded) {
                        files.add(file);
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                // Skip target directories and .git directories
                String dirName = dir.getFileName().toString();
                if (dirName.equals("target") || dirName.equals(".git") || dirName.startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return files;
    }

    private List<Marker> generateProvenance() {
        BuildEnvironment buildEnvironment = BuildEnvironment.build(System::getenv);
        String javaRuntimeVersion = System.getProperty("java.specification.version");
        String javaVendor = System.getProperty("java.vm.vendor");

        return Arrays.asList(
            buildEnvironment,
            OperatingSystemProvenance.current(),
            new BuildTool(randomId(), BuildTool.Type.Gradle, "standalone"), // Generic build tool
            new JavaProject(randomId(), projectRoot.getFileName().toString(),
                new JavaProject.Publication("standalone", "standalone", "1.0.0")),
            new JavaVersion(randomId(), javaRuntimeVersion, javaVendor, javaRuntimeVersion, javaRuntimeVersion),
            JavaSourceSet.build("main", emptyList())
        );
    }

    private <T extends SourceFile> T addProvenance(T sourceFile, List<Marker> provenance) {
        Markers markers = sourceFile.getMarkers();
        for (Marker marker : provenance) {
            markers = markers.addIfAbsent(marker);
        }
        return sourceFile.withMarkers(markers);
    }

    private void logRecipesThatMadeChanges(Result result) {
        String indent = "    ";
        String prefix = "    ";
        for (org.openrewrite.config.RecipeDescriptor recipeDescriptor : result.getRecipeDescriptorsThatMadeChanges()) {
            System.err.println(prefix + recipeDescriptor.getName());
            prefix = prefix + indent;
        }
    }

    private String formatDuration(Duration duration) {
        return duration.toString()
            .substring(2)
            .replaceAll("(\\d[HMS])(?!$)", "$1 ")
            .toLowerCase()
            .trim();
    }

    private Set<String> getDefaultPlainTextMasks() {
        return new HashSet<>(Arrays.asList(
            "**/*.adoc",
            "**/*.bash",
            "**/*.bat",
            "**/CODEOWNERS",
            "**/*.css",
            "**/*.config",
            "**/[dD]ockerfile*",
            "**/*.[dD]ockerfile",
            "**/*.env",
            "**/.gitattributes",
            "**/.gitignore",
            "**/*.htm*",
            "**/gradlew",
            "**/.java-version",
            "**/*.jelly",
            "**/*.jsp",
            "**/*.ksh",
            "**/*.lock",
            "**/lombok.config",
            "**/[mM]akefile",
            "**/*.md",
            "**/*.mf",
            "**/META-INF/services/**",
            "**/META-INF/spring/**",
            "**/META-INF/spring.factories",
            "**/mvnw",
            "**/mvnw.cmd",
            "**/*.qute.java",
            "**/.sdkmanrc",
            "**/*.sh",
            "**/*.sql",
            "**/*.svg",
            "**/*.tsx",
            "**/*.txt",
            "**/*.py"
        ));
    }

    // Inner class to represent results container (simplified version)
    public static class ResultsContainer {
        final Path projectRoot;
        final List<Result> generated = new ArrayList<>();
        final List<Result> deleted = new ArrayList<>();
        final List<Result> moved = new ArrayList<>();
        final List<Result> refactoredInPlace = new ArrayList<>();

        public ResultsContainer(Path projectRoot, Collection<Result> results) {
            this.projectRoot = projectRoot;
            for (Result result : results) {
                if (result.getBefore() == null && result.getAfter() == null) {
                    continue;
                }
                if (result.getBefore() == null && result.getAfter() != null) {
                    generated.add(result);
                } else if (result.getBefore() != null && result.getAfter() == null) {
                    deleted.add(result);
                } else if (result.getBefore() != null && result.getAfter() != null &&
                    !result.getBefore().getSourcePath().equals(result.getAfter().getSourcePath())) {
                    moved.add(result);
                } else {
                    if (!result.diff(Paths.get("")).isEmpty()) {
                        refactoredInPlace.add(result);
                    }
                }
            }
        }

        public @Nullable RuntimeException getFirstException() {
            // Simplified version - in the full implementation this would check for recipe errors
            return null;
        }

        public boolean isNotEmpty() {
            return !generated.isEmpty() || !deleted.isEmpty() || !moved.isEmpty() || !refactoredInPlace.isEmpty();
        }
    }

    public static Recipe createRecipeInstance(String fqn)
        throws ClassNotFoundException, NoSuchMethodException,
        InvocationTargetException, InstantiationException, IllegalAccessException {

        Class<?> clazz = Class.forName(fqn);
        if (!Recipe.class.isAssignableFrom(clazz)) {
            throw new IllegalArgumentException(fqn + " does not extend or implement org.openrewrite.Recipe.");
        }

        Constructor<?> constructor = clazz.getConstructor(fqn.getClass());
        constructor.setAccessible(true);
        Object instance = constructor.newInstance();
        return (Recipe) instance;
    }

    private static void configureRecipeOptions(Recipe recipe, Set<String> options) throws RuntimeException {
        if (recipe instanceof CompositeRecipe ||
            recipe instanceof DeclarativeRecipe ||
            recipe instanceof Recipe.DelegatingRecipe ||
            !recipe.getRecipeList().isEmpty()) {
            // We don't (yet) support configuring potentially nested recipes, as recipes might occur more than once,
            // and setting the same value twice might lead to unexpected behavior.
            throw new RuntimeException(
                "Recipes containing other recipes can not be configured from the command line: " + recipe);
        }

        Map<String, String> optionValues = new HashMap<>();
        for (String option : options) {
            String[] parts = option.split("=", 2);
            if (parts.length == 2) {
                optionValues.put(parts[0], parts[1]);
            }
        }
        for (Field field : recipe.getClass().getDeclaredFields()) {
            String removed = optionValues.remove(field.getName());
            updateOption(recipe, field, removed);
        }
        if (!optionValues.isEmpty()) {
            throw new RuntimeException(
                String.format("Unknown recipe options: %s", String.join(", ", optionValues.keySet())));
        }
    }

    private static void updateOption(Recipe recipe, Field field, @Nullable String optionValue) throws RuntimeException {
        Object convertedOptionValue = convertOptionValue(field.getName(), optionValue, field.getType());
        if (convertedOptionValue == null) {
            return;
        }
        try {
            field.setAccessible(true);
            field.set(recipe, convertedOptionValue);
            field.setAccessible(false);
        } catch (IllegalArgumentException | IllegalAccessException e) {
            throw new RuntimeException(
                String.format("Unable to configure recipe '%s' option '%s' with value '%s'",
                    recipe.getClass().getSimpleName(), field.getName(), optionValue));
        }
    }

    private static @Nullable Object convertOptionValue(String name, @Nullable String optionValue, Class<?> type)
        throws RuntimeException {
        if (optionValue == null) {
            return null;
        }
        if (type.isAssignableFrom(String.class)) {
            return optionValue;
        }
        if (type.isAssignableFrom(boolean.class) || type.isAssignableFrom(Boolean.class)) {
            return Boolean.parseBoolean(optionValue);
        }
        if (type.isAssignableFrom(int.class) || type.isAssignableFrom(Integer.class)) {
            return Integer.parseInt(optionValue);
        }
        if (type.isAssignableFrom(long.class) || type.isAssignableFrom(Long.class)) {
            return Long.parseLong(optionValue);
        }

        throw new RuntimeException(
            String.format("Unable to convert option: %s value: %s to type: %s", name, optionValue, type));
    }
}