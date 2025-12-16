## Quarkus Openrewrite client

This project support to execute an Openrewrite Recipe without the need to use the maven goal `rewrite:dryrun or rewrite:run`

## Prerequisite

- JDK 21 
- Apache maven 3.9

## Instructions

Git clone this project compile the project. Next launch the Quarkus Picocli client using the command: `mvn quarkus:dev`

```shell
mvn clean install
mvn quarkus:dev -Dquarkus.args="test-project/simple org.openrewrite.java.format.AutoFormat"
```

If the recipe can be configured using options (= java class fields), then declare them using the format `key=value`. Multiple options can be provided as a comma-separated list

```shell
mvn quarkus:dev -Dquarkus.args="test-project/demo-spring-boot-todo-app org.openrewrite.java.search.FindAnnotations annotationPattern=@org.springframework.boot.autoconfigure.SpringBootApplication,matchMetaAnnotations=false"
```

> [!NOTE]
> You can also run the application using the uber jar file and command: `java -jar test-project/simple org.openrewrite.java.format.AutoFormat`

> [!TIP]
> Trick for the developers

```shell
set qdebug java -jar -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address="*:5005" target/quarkus-app/quarkus-run.jar
set qrun java -jar target/quarkus-app/quarkus-run.jar

$qdebug test-project/demo-spring-boot-todo-app org.openrewrite.java.search.FindAnnotations
$qrun test-project/demo-spring-boot-todo-app org.openrewrite.java.search.FindAnnotations
```

The command lone application also supports to load the recipes from an external jar file using the Maven GAV coordinates
```shell
mvn quarkus:dev -Dquarkus.args="--jar dev.snowdrop:openrewrite-recipes:1.0.0-SNAPSHOT test-project/demo-spring-boot-todo-app dev.snowdrop.openrewrite.java.search.FindAnnotations pattern=@org.springframework.boot.autoconfigure.SpringBootApplication,matchId=1234"
```

Enjoy :-)