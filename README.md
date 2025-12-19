[![GitHub Actions Status](<https://img.shields.io/github/actions/workflow/status/snowdrop/rewrite-standalone-cli/build-test.yml?branch=main&logo=GitHub&style=for-the-badge>)](https://github.com/snowdrop/rewrite-standalone-cli/actions/workflows/build-test.yml)
[![License](https://img.shields.io/github/license/snowdrop/rewrite-standalone-cli?style=for-the-badge&logo=apache)](https://www.apache.org/licenses/LICENSE-2.0)


## Quarkus Openrewrite client

This project support to execute Openrewrite Recipe(s) without the need to use the maven goal `rewrite:dryRun` or `rewrite:run` according to the following scenario.

- Use the FQName of the recipe as parameter: `-r or --recipe <FQName_recipe>`. Example: `-r org.openrewrite.java.format.AutoFormat`. The tool will try to find the class of the recipe from the classes loaded using the runtime classpath
- The fields of the Recipe can be defined using the parameter `-o or --options "k=v,k=v,...`. Example: `-o annotationPattern=@org.springframework.boot.autoconfigure.SpringBootApplication`
- If Recipe is packaged in another JAR file, then provide its PATH or Maven GAV using `--jar <PATH_OR_GAV>`. Example: `--jar dev.snowdrop:openrewrite-recipes:1.0.0-SNAPSHOT test-project/demo-spring-boot-todo-app -r dev.snowdrop.mtool.openrewrite.java.search.FindAnnotations`
- The recipes can also be configured using a YAML recipe file and parameter `-c or --config <REWRITE_YAML_NAME>`. Example: `-c rewrite.yml`

## Prerequisite

- JDK 21 
- Apache maven 3.9

## Instructions 

### To use the Rewrite scanner

Git clone this project and compile the project. When done, the project can be now be used in your own java project if you import the following dependency
```xml
    <groupId>io.snowdrop.openrewrite</groupId>
    <artifactId>rewrite-standalone-cli</artifactId>
    <version>1.0.0-SNAPSHOT</version>
```
Next configure the `RewriteScanner` to issue a scan of a java application as described hereafter
```java
Config cfg = new Config();
cfg.setAppPath(Paths.get("<PATH_TO_JAVA_PROJECT>"));
cfg.setActiveRecipes(List.of("FQNAME_OF_THE_RECIPE"));
cfg.setRecipeOptions(Set.of("annotationPattern=@org.springframework.boot.autoconfigure.SpringBootApplication,matchMetaAnnotations=false"));

RewriteScanner scanner = new RewriteScanner(cfg);
scanner.init();
ResultsContainer results = scanner.run();

// Use the results object to access the Datatables, Changeset, etc
RecipeRun run = results.getRecipeRuns().get("FQNAME_OF_THE_RECIPE");
Optional<Map.Entry<DataTable<?>, List<?>>> resultMap = run.getDataTables().entrySet().stream()
    .filter(entry -> entry.getKey().getName().contains("SearchResults"))
    .findFirst();
assertTrue(resultMap.isPresent());

List<?> rows = resultMap.get().getValue();
assertEquals(1, rows.size());

SearchResults.Row record = (SearchResults.Row)rows.getFirst();
assertEquals("src/main/java/com/todo/app/AppApplication.java",record.getSourcePath());
assertEquals("@SpringBootApplication",record.getResult());
assertEquals("Find annotations `@org.springframework.boot.autoconfigure.SpringBootApplication,matchMetaAnnotations=false`",record.getRecipe());


```

### To use the client

Git clone this project and compile the project. Next launch the Quarkus Picocli client using the command: `mvn quarkus:dev`

```shell
mvn clean install
mvn quarkus:dev -Dquarkus.args="test-project/simple -r org.openrewrite.java.format.AutoFormat"
```

If the recipe can be configured using options (= java class fields), then declare them using the format `key=value`. Multiple options can be provided as a comma-separated list

```shell
mvn quarkus:dev -Dquarkus.args="test-project/demo-spring-boot-todo-app -r org.openrewrite.java.search.FindAnnotations -o annotationPattern=@org.springframework.boot.autoconfigure.SpringBootApplication,matchMetaAnnotations=false"
```

Alternatively, you can use a YAML recipes file and pass it using the parameter `-c`:
```shell
mvn quarkus:dev -Dquarkus.args="test-project/demo-spring-boot-todo-app -c rewrite.yml"
```

> [!NOTE]
> You can also run the application using the uber jar file and command: `java -jar test-project/simple -r org.openrewrite.java.format.AutoFormat`

> [!TIP]
> Trick for the developers

```shell
set qdebug java -jar -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address="*:5005" target/quarkus-app/quarkus-run.jar
set qrun java -jar target/quarkus-app/quarkus-run.jar

$qdebug test-project/demo-spring-boot-todo-app -r org.openrewrite.java.search.FindAnnotations
$qrun test-project/demo-spring-boot-todo-app -r org.openrewrite.java.search.FindAnnotations
$qrun test-project/demo-spring-boot-todo-app -r org.openrewrite.java.search.FindAnnotations -o annotationPattern=@org.springframework.boot.autoconfigure.SpringBootApplication,matchMetaAnnotations=false
$qrun test-project/demo-spring-boot-todo-app -r org.openrewrite.maven.search.FindDependency -o groupId=org.springframework.boot,artifactId=spring-boot-starter-data-jpa,version=3.5.3
```

The command lone application also supports to load the recipes from an external jar file using the Maven GAV coordinates
```shell
mvn quarkus:dev -Dquarkus.args="--jar dev.snowdrop:openrewrite-recipes:1.0.0-SNAPSHOT test-project/demo-spring-boot-todo-app -r dev.snowdrop.mtool.openrewrite.java.search.FindAnnotations -o pattern=@org.springframework.boot.autoconfigure.SpringBootApplication,matchId=1234"
```

Enjoy :-)
