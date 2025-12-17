package dev.snowdrop.openrewrite.cli;

import dev.snowdrop.openrewrite.cli.model.Config;
import dev.snowdrop.openrewrite.cli.model.ResultsContainer;
import dev.snowdrop.openrewrite.cli.toolbox.MavenArtifactResolver;
import dev.snowdrop.openrewrite.cli.toolbox.MavenUtils;
import org.apache.maven.model.Model;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.config.*;
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

public class Scanner {

    private ExecutionContext ctx;
    private List<Throwable> throwables;
    private Environment env;
    private LargeSourceSet sourceSet;
    private Config config;

    public Scanner(Config cfg) {
        this.config = cfg;
        init();
    }

    private void init() {
        throwables = new ArrayList<>();
        ctx = createExecutionContext(throwables);

        try {
            env = createEnvironment();
            sourceSet = loadSourceSet(env, ctx);
        } catch (Exception ex) {
            System.err.println("Error while initializing");
            ex.printStackTrace(System.err);
        }
    }

    public void run() throws Exception {
        ResultsContainer results = listResults();

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

            for (Result result : results.getGenerated()) {
                assert result.getAfter() != null;
                System.err.println("These recipes would generate a new file " +
                    result.getAfter().getSourcePath() + ":");
                logRecipesThatMadeChanges(result);
                estimateTimeSaved = estimateTimeSaved.plus(result.getTimeSavings() != null ?
                    result.getTimeSavings() : Duration.ZERO);
            }

            for (Result result : results.getDeleted()) {
                assert result.getBefore() != null;
                System.err.println("These recipes would delete a file " +
                    result.getBefore().getSourcePath() + ":");
                logRecipesThatMadeChanges(result);
                estimateTimeSaved = estimateTimeSaved.plus(result.getTimeSavings() != null ?
                    result.getTimeSavings() : Duration.ZERO);
            }

            for (Result result : results.getMoved()) {
                assert result.getBefore() != null;
                assert result.getAfter() != null;
                System.err.println("These recipes would move a file from " +
                    result.getBefore().getSourcePath() + " to " +
                    result.getAfter().getSourcePath() + ":");
                logRecipesThatMadeChanges(result);
                estimateTimeSaved = estimateTimeSaved.plus(result.getTimeSavings() != null ?
                    result.getTimeSavings() : Duration.ZERO);
            }

            for (Result result : results.getRefactoredInPlace()) {
                assert result.getBefore() != null;
                System.err.println("These recipes would make changes to " +
                    result.getBefore().getSourcePath() + ":");
                logRecipesThatMadeChanges(result);
                estimateTimeSaved = estimateTimeSaved.plus(result.getTimeSavings() != null ?
                    result.getTimeSavings() : Duration.ZERO);
            }

            // Create patch file
            Path outPath = config.getAppPath().resolve("target").resolve("rewrite");
            try {
                Files.createDirectories(outPath);
            } catch (IOException e) {
                throw new RuntimeException("Could not create the folder [" + outPath + "].", e);
            }

            Path patchFile = outPath.resolve("rewrite.patch");
            try (BufferedWriter writer = Files.newBufferedWriter(patchFile)) {
                Stream.concat(
                        Stream.concat(results.getGenerated().stream(), results.getDeleted().stream()),
                        Stream.concat(results.getMoved().stream(), results.getRefactoredInPlace().stream())
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
            System.out.println("Applying recipe would make no changes. No patch file generated.");
        }
    }

    private ExecutionContext createExecutionContext(List<Throwable> throwables) {
        return new InMemoryExecutionContext(t -> {
            System.err.println("Debug: " + t.getMessage());
            throwables.add(t);
        });
    }

    private ResultsContainer listResults() {
        // 1. Process YAML recipes if they exist
        if (!config.getYamlRecipes().isEmpty()) {
            env = loadRecipesFromYAML(env);
        }

        // TODO: To be reviewed to process a list of recipes
        String activeRecipe = config.getActiveRecipes().getFirst();

        // 2. Check if we have at least one source of recipes
        boolean hasActiveRecipe = activeRecipe != null && !activeRecipe.isEmpty();
        boolean hasYamlRecipes = !config.getYamlRecipes().isEmpty();

        // 3. Early return only if BOTH sources are empty
        if (!hasActiveRecipe && !hasYamlRecipes) {
            System.out.printf("No recipes found in active selection or YAML configuration for path: %s\n", config.getAppPath());
            return new ResultsContainer(Collections.emptyList());
        }

        System.out.println("Using active recipe(s): " + activeRecipe);

        // This code works when we instantiate directly the Recipe class as the jar packaging it is loaded by the application
        // Recipe recipe = new FindAnnotations("@org.springframework.boot.autoconfigure.SpringBootApplication",false);

        // The recipe class is created using the Resource classloader using the FQName
        Recipe recipe = env.activateRecipes(getActiveRecipes());

        // The Recipe class has been instantiated from the FQName string but the fields/parameters still need to be set
        // Set<String> options = Collections.singleton("annotationPattern=@org.springframework.boot.autoconfigure.SpringBootApplication");
        if (config.getRecipeOptions() != null && !config.getRecipeOptions().isEmpty()) {
            configureRecipeOptions(recipe, config.getRecipeOptions());
        }

        if ("org.openrewrite.Recipe$Noop".equals(recipe.getName())) {
            System.err.println("No recipes were activated. " +
                "Activate a recipe by providing it as a command line argument.");
            return new ResultsContainer(emptyList());
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

        RecipeRun recipeRun = runRecipe(recipe);

        // The DataTable<SearchResult> will be available starting from: 8.69.0 !
        /*
        Map<DataTable<?>, List<?>> searchResults = recipeRun.getDataTables();
        if (searchResults != null) {
            searchResults.forEach((result, list) -> {
                if (result.getClass().getSimpleName().startsWith("SearchResults")) {
                    System.out.println("# Found " + list.size() + " search results.");
                    list.stream().forEach(r -> {
                        var row = (SearchResults.Row)r;
                        System.out.println("# SourcePath: " + row.getSourcePath());
                        System.out.println("# Result: " + row.getResult());
                        System.out.println("# Recipe: " + row.getRecipe());
                        System.out.println("==============================================");
                    });
                }
            });
        }
        */

        return new ResultsContainer(recipeRun.getChangeset().getAllResults());
    }

    private Iterable<String> getActiveRecipes() {
        return env.listRecipes()
            .stream()
            .map(Recipe::getName)
            .toList();
    }

    private Environment createEnvironment() throws Exception {
        Environment.Builder env = Environment.builder();

        // Load additional JARs if specified
        URLClassLoader additionalJarsClassloader = loadAdditionalJars();

        if (additionalJarsClassloader != null) {
            // Load recipes using the ClasspathScanningLoader with the additional classloader
            // This is the key fix - we use the additionalJarsClassloader for recipe discovery
            env.load(new ClasspathScanningLoader(new Properties(), additionalJarsClassloader));
            merge(getClass().getClassLoader(), additionalJarsClassloader);
            System.out.println("Loaded recipes from additional JARs");
        }

        return env.build();
    }

    private Environment loadRecipesFromYAML(Environment env) {
        Environment.Builder envBuilder = env.builder();
        Path configPath;
        if (Paths.get(config.getYamlRecipes()).isAbsolute()) {
            configPath = Paths.get(config.getYamlRecipes());
        } else {
            String appProject = System.getenv("APP_PROJECT");
            if (appProject != null && !appProject.isEmpty()) {
                configPath = Paths.get(appProject);
            } else {
                // Fall back to resolving against project root
                configPath = config.getAppPath().resolve(config.getYamlRecipes());
            }
        }

        if (Files.exists(configPath)) {
            try (InputStream is = Files.newInputStream(configPath)) {
                envBuilder.load(new YamlResourceLoader(is, configPath.toUri(), new Properties()));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        return envBuilder.build();
    }

    private LargeSourceSet loadSourceSet(Environment env, ExecutionContext ctx) throws Exception {
        List<NamedStyles> styles = env.activateStyles(emptySet());

        System.out.println("Parsing source files...");
        List<SourceFile> sourceFiles = new ArrayList<>();

        // Parse Java files
        List<Path> javaFiles = findFiles(config.getAppPath(), ".java");
        if (!javaFiles.isEmpty()) {
            // If we have java files, then we assume that we have a pom and dependencies
            MavenUtils mavenUtils = new MavenUtils();
            Model model = mavenUtils.setupProject(Paths.get(config.getAppPath().toString(), "pom.xml").toFile());

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
            sourceFiles.addAll(jp.parse(javaFiles, config.getAppPath(), ctx).toList());
            System.out.println("Parsed " + javaFiles.size() + " Java files");
        }

        // Parse Kotlin files
        List<Path> kotlinFiles = findFiles(config.getAppPath(), ".kt");
        if (!kotlinFiles.isEmpty()) {
            KotlinParser kotlinParser = KotlinParser.builder().build();
            sourceFiles.addAll(kotlinParser.parse(kotlinFiles, config.getAppPath(), ctx).toList());
            System.out.println("Parsed " + kotlinFiles.size() + " Kotlin files");
        }

        // Parse other files (XML, YAML, properties, etc.)
        Set<String> masks = config.getPlainTextMasks().isEmpty() ? getDefaultPlainTextMasks() : config.getPlainTextMasks();
        OmniParser omniParser = OmniParser.builder(
                OmniParser.defaultResourceParsers(),
                PlainTextParser.builder()
                    .plainTextMasks(config.getAppPath(), masks)
                    .build()
            )
            .sizeThresholdMb(config.getSizeThresholdMb())
            .build();

        List<Path> otherFiles = omniParser.acceptedPaths(config.getAppPath(), config.getAppPath());
        sourceFiles.addAll(omniParser.parse(otherFiles, config.getAppPath(), ctx).toList());

        // Add provenance markers
        List<Marker> provenance = generateProvenance();
        sourceFiles = sourceFiles.stream()
            .map(sf -> addProvenance(sf, provenance))
            .collect(toList());

        System.out.println("Total source files parsed: " + sourceFiles.size());
        return new InMemoryLargeSourceSet(sourceFiles);
    }

    private RecipeRun runRecipe(Recipe recipe) {
        System.out.println("Running recipe(s)...");
        RecipeRun rr = recipe.run(sourceSet, ctx);

        if (config.canExportDatatables()) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"));
            Path datatableDirectoryPath = config.getAppPath().resolve("target").resolve("rewrite").resolve("datatables").resolve(timestamp);
            System.out.println("Printing available datatables to: " + datatableDirectoryPath);
            rr.exportDatatablesToCsv(datatableDirectoryPath, ctx);
        }

        return rr;
    }

    private List<Path> findFiles(Path root, String extension) throws IOException {
        List<Path> files = new ArrayList<>();

        Collection<PathMatcher> exclusionMatchers = config.getExclusions().stream()
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
            new JavaProject(randomId(), config.getAppPath().getFileName().toString(),
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

    /**
     * Creates a URLClassLoader from the additional JAR paths or Maven GAV coordinates.
     *
     * @return URLClassLoader containing the additional Rewrite JARs, or null if no additional JARs are specified
     */
    private URLClassLoader loadAdditionalJars() {
        if (config.getAdditionalJarPaths().isEmpty()) {
            return null;
        }

        List<URL> jarUrls = new ArrayList<>();
        MavenArtifactResolver resolver = new MavenArtifactResolver();

        try {
            // Resolve all jar paths/coordinates to actual file paths
            List<Path> resolvedPaths = resolver.resolveArtifacts(config.getAdditionalJarPaths());

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

        if (!(targetClassLoader instanceof URLClassLoader targetUrlClassLoader)) {
            System.out.println("Running in Quarkus mode - using ClasspathScanningLoader for additional JARs:");
            for (URL newUrl : sourceClassLoader.getURLs()) {
                System.out.println("  Using JAR from additional classpath: " + newUrl);
            }
            return;
        }

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


}
