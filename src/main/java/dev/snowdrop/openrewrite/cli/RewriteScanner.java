package dev.snowdrop.openrewrite.cli;

import dev.snowdrop.openrewrite.cli.model.Config;
import dev.snowdrop.openrewrite.cli.model.ResultsContainer;
import dev.snowdrop.openrewrite.cli.toolbox.ClassLoaderUtils;
import dev.snowdrop.openrewrite.cli.toolbox.MavenArtifactResolver;
import dev.snowdrop.openrewrite.cli.toolbox.MavenUtils;
import dev.snowdrop.openrewrite.cli.toolbox.SanitizedMarkerPrinter;
import org.apache.maven.model.Model;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.binary.Binary;
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
import org.openrewrite.maven.MavenParser;
import org.openrewrite.polyglot.OmniParser;
import org.openrewrite.quark.Quark;
import org.openrewrite.remote.Remote;
import org.openrewrite.text.PlainTextParser;

import java.io.*;
import java.lang.reflect.Field;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;
import static org.openrewrite.Tree.randomId;

public class RewriteScanner {

    private ExecutionContext ctx;
    private List<Throwable> throwables;
    private Environment env;
    private LargeSourceSet sourceSet;
    private Config config;

    public RewriteScanner(Config cfg) {
        this.config = cfg;
    }

    protected void init() {
        throwables = new ArrayList<>();
        ctx = createExecutionContext(throwables);

        try {
            // Instantiate the resource and classloader including also the external one provided
            env = createEnvironment();
            sourceSet = loadSourceSet(env, ctx);
        } catch (Exception ex) {
            System.err.println("Error while initializing");
            ex.printStackTrace(System.err);
        }
    }

    public ResultsContainer run() throws Exception {
        ResultsContainer results = processRecipes();
        if(config.isDryRun()) {
            createPatchFile(results);
        }
        return results;
    }

    private ExecutionContext createExecutionContext(List<Throwable> throwables) {
        return new InMemoryExecutionContext(t -> {
            System.err.println("Debug: " + t.getMessage());
            throwables.add(t);
        });
    }

    private void createPatchFile(ResultsContainer results) {
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

        // TODO: Review this code with maven plugin: AbstractRewriteRunMojo & AbstractRewriteDryRunMojo !
        if (results.isNotEmpty()) {
            Duration estimateTimeSaved = Duration.ZERO;

            for (Result result : results.getGenerated()) {
                assert result.getAfter() != null;
                if (!config.isDryRun()) {writeAfter(config.getAppPath(), result, ctx);}
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
                if (!config.isDryRun()) {writeAfter(config.getAppPath(), result, ctx);}
            }

            for (Result result : results.getRefactoredInPlace()) {
                assert result.getBefore() != null;
                System.err.println("These recipes would make changes to " +
                    result.getBefore().getSourcePath() + ":");
                logRecipesThatMadeChanges(result);
                estimateTimeSaved = estimateTimeSaved.plus(result.getTimeSavings() != null ?
                    result.getTimeSavings() : Duration.ZERO);
                if (!config.isDryRun()) {writeAfter(config.getAppPath(), result, ctx);}
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
        } else {
            System.out.println("Applying recipe would make no changes. No patch file generated.");
        }
    }

    private ResultsContainer processRecipes() {
        RecipeRun recipeRun = null;
        Recipe recipe = null;
        boolean yamlRecipes = false;
        Map<String, RecipeRun> allResults = new HashMap<>();

        // Process YAML recipes if it has been defined
        if (config.getYamlRecipes() != null && !config.getYamlRecipes().isEmpty()) {
            env = loadRecipesFromYAML(env);
            yamlRecipes = true;
        } else {
            // Check if we got a recipe FQName string instead and load it
            if (config.getActiveRecipes() != null && !config.getActiveRecipes().isEmpty()) {
                // TODO: To be improved to iterate in a list
                recipe = env.activateRecipes(config.getActiveRecipes().getFirst());

                // When we use `activeRecipe` parameter, we can also optionally configure the parameters of the recipe where the fields will be set
                // using the parameter "options"
                // Set<String> options = Collections.singleton("annotationPattern=@org.springframework.boot.autoconfigure.SpringBootApplication");
                if (config.getRecipeOptions() != null && !config.getRecipeOptions().isEmpty()) {
                    configureRecipeOptions(recipe, config.getRecipeOptions());
                }
            }
        }

        if (env.listRecipes().isEmpty()) {
            System.out.printf("No recipes found in active selection or YAML configuration for path: %s\n", config.getAppPath());
            return new ResultsContainer(Collections.emptyMap());
        }

        // Run the recipe loaded
        if (!yamlRecipes) {
            System.out.println("Using active recipe(s): " + recipe.getName());

            if ("org.openrewrite.Recipe$Noop".equals(recipe.getName())) {
                System.err.println("No recipes were activated. " +
                    "Activate a recipe by providing it as a command line argument.");
                return new ResultsContainer(Collections.emptyMap());
            }

            validatingRecipe(recipe);
            recipeRun = runRecipe(recipe);
            allResults.put(recipe.getName(),recipeRun);

        } else {
            System.out.println("Using recipes from YAML configuration");
            env.listRecipes().forEach(r -> {
                System.out.println("Running recipe: " + r.getName());
                validatingRecipe(r);
                RecipeRun currentRun = runRecipe(r);
                allResults.put(r.getName(),currentRun);
            });
        }

        return new ResultsContainer(allResults);
    }

    private Environment createEnvironment() throws Exception {
        ClassLoaderUtils classLoaderUtils = new ClassLoaderUtils();
        Environment.Builder env = Environment.builder();

        // Construct a ClasspathScanningLoader scans the runtime classpath of the current java process for recipes
        env.scanRuntimeClasspath();

        // Load additional JARs if specified
        URLClassLoader additionalJarsClassloader = classLoaderUtils.loadAdditionalJars(config.getAdditionalJarPaths());

        if (additionalJarsClassloader != null) {
            // Load recipes using the ClasspathScanningLoader with the additional classloader
            env.load(new ClasspathScanningLoader(new Properties(), additionalJarsClassloader));
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

    private void validatingRecipe(Recipe recipe) {
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
    }

    private LargeSourceSet loadSourceSet(Environment env, ExecutionContext ctx) throws Exception {
        // TODO: Do we need such Styles for the Java parser. To be investigated !
        // List<NamedStyles> styles = env.activateStyles(emptySet());

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
            JavaParser.Builder<? extends JavaParser, ?> javaParserBuilder = JavaParser.fromJavaVersion().logCompilationWarningsAndErrors(false);
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

        List<Path> poms = findFiles(config.getAppPath(), ".xml");
        MavenParser.Builder mavenParserBuilder = MavenParser.builder();
        List<SourceFile> mavens = mavenParserBuilder.build()
            .parse(poms, config.getAppPath(), ctx)
            .toList();
        sourceFiles.addAll(mavens);

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
        for (RecipeDescriptor recipeDescriptor : result.getRecipeDescriptorsThatMadeChanges()) {
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

    private static void writeAfter(Path root, Result result, ExecutionContext ctx) {
        if (result.getAfter() == null || result.getAfter() instanceof Quark) {
            return;
        }
        Path targetPath = root.resolve(result.getAfter().getSourcePath());
        File targetFile = targetPath.toFile();
        if (!targetFile.getParentFile().exists()) {
            //noinspection ResultOfMethodCallIgnored
            targetFile.getParentFile().mkdirs();
        }
        if (result.getAfter() instanceof Binary) {
            try (FileOutputStream sourceFileWriter = new FileOutputStream(targetFile)) {
                sourceFileWriter.write(((Binary) result.getAfter()).getBytes());
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to rewrite source files", e);
            }
        } else if (result.getAfter() instanceof Remote) {
            Remote remote = (Remote) result.getAfter();
            try (FileOutputStream sourceFileWriter = new FileOutputStream(targetFile)) {
                InputStream source = remote.getInputStream(ctx);
                byte[] buf = new byte[4096];
                int length;
                while ((length = source.read(buf)) > 0) {
                    sourceFileWriter.write(buf, 0, length);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to rewrite source files", e);
            }
        } else if (!(result.getAfter() instanceof Quark)) {
            // Don't attempt to write to a Quark; it has already been logged as change that has been made
            Charset charset = result.getAfter().getCharset() == null ? StandardCharsets.UTF_8 : result.getAfter().getCharset();
            try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(targetPath, charset)) {
                sourceFileWriter.write(result.getAfter().printAll(new PrintOutputCapture<>(0, new SanitizedMarkerPrinter())));
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to rewrite source files", e);
            }
        }
        if (result.getAfter().getFileAttributes() != null) {
            FileAttributes fileAttributes = result.getAfter().getFileAttributes();
            if (targetFile.canRead() != fileAttributes.isReadable()) {
                //noinspection ResultOfMethodCallIgnored
                targetFile.setReadable(fileAttributes.isReadable());
            }
            if (targetFile.canWrite() != fileAttributes.isWritable()) {
                //noinspection ResultOfMethodCallIgnored
                targetFile.setWritable(fileAttributes.isWritable());
            }
            if (targetFile.canExecute() != fileAttributes.isExecutable()) {
                //noinspection ResultOfMethodCallIgnored
                targetFile.setExecutable(fileAttributes.isExecutable());
            }
        }
    }

}
