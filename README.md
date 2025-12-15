## Instructions

TODO: Review the instructions to clearly report what it works and not - 11 DEc 2025 !

Prerequisite:
- JDK 21 and maven 3.9

Compile the project and run `quarkus:dev`

```shell
mvn quarkus:dev -Dquarkus.args="test-project/simple org.openrewrite.java.format.AutoFormat"
mvn quarkus:dev -Dquarkus.args="test-project/simple dummy"

mvn quarkus:dev -Dquarkus.args="test-project/demo-spring-boot-todo-app org.openrewrite.java.search.FindAnnotations"

mvn quarkus:dev -Dquarkus.args="test-project/demo-spring-boot-todo-app org.openrewrite.java.search.FindAnnotations --jar=org.springframework.boot:spring-boot-autoconfigure:3.5.8,org.springframework.boot:spring-boot-starter-web:3.5.8"

mvn quarkus:dev -Dquarkus.args="test-project/demo-spring-boot-todo-app dev.snowdrop.openrewrite.MatchConditions --jar=org.openrewrite:rewrite-java:8.68.1,dev.snowdrop:openrewrite-recipes:1.0.0-SNAPSHOT"
```

> *WARNING*
> We don't have to pass at the moment the option `--jar` as there is an issue to load a snapshot jar using Eclipse Aether

## Issue

There is one issue as until now we don't load the jar files of the project scanned and even if the recipe is called it is not able by example to find a match. The target application to be scanned is also not compiled !

```shell
mvn quarkus:dev -Dquarkus.args="test-project/demo-spring-boot-todo-app org.openrewrite.java.search.FindAnnotations --jar=org.springframework.boot:spring-boot-starter-web:3.5.8"

Starting OpenRewrite dry-run...
Project root: /Users/cmoullia/code/application-modernisation/01_openrewrite/rewrite-standalone-cli/test-project/demo-spring-boot-todo-app
Active recipes: [org.openrewrite.java.search.FindAnnotations]
Additional JAR files: [org.springframework.boot:spring-boot-starter-web:3.5.8]
Using active recipe(s): [org.openrewrite.java.search.FindAnnotations]
Resolving Maven artifact: org.springframework.boot:spring-boot-starter-web:3.5.8
Resolved to: /Users/cmoullia/.m2/repository/org/springframework/boot/spring-boot-starter-web/3.5.8/spring-boot-starter-web-3.5.8.jar
Loaded additional JAR: /Users/cmoullia/.m2/repository/org/springframework/boot/spring-boot-starter-web/3.5.8/spring-boot-starter-web-3.5.8.jar
Running in Quarkus mode - using ClasspathScanningLoader for additional JARs:
  Using JAR from additional classpath: file:/Users/cmoullia/.m2/repository/org/springframework/boot/spring-boot-starter-web/3.5.8/spring-boot-starter-web-3.5.8.jar
Loaded recipes from additional JARs
Validating active recipes...
Parsing source files...
Parsed 7 Java files
Total source files parsed: 17
Running recipe(s)...
Applying recipes would make no changes. No patch file generated.
```

If you prefer, you can use the uber jar file with the following recipes:

## Autoformat the java code - OK

```shell
java -jar target/quarkus-app/quarkus-run.jar \
  test-project/simple \
  org.openrewrite.java.format.AutoFormat
```

## Find annotations

```shell
pushd test-project/demo-spring-boot-todo-app/
mvn package
popd

java -jar target/quarkus-app/quarkus-run.jar \
  test-project/demo-spring-boot-todo-app \
  org.openrewrite.java.search.FindAnnotations \
  --jar=org.springframework.boot:spring-boot-starter-web:3.5.8
```

  
## Test the recipes using recipes packaged in a jar - nok
```shell
java -jar "target/quarkus-app/quarkus-run.jar,/Users/cmoullia/.m2/repository/dev/snowdrop/openrewrite-recipes/1.0.0-SNAPSHOT/openrewrite-recipes-1.0.0-SNAPSHOT.jar" \
  test-project/demo-spring-boot-todo-app \
  dev.snowdrop.openrewrite.MatchConditions
```
