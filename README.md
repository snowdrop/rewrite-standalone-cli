## Instructions

Prerequisite:
- JDK 21 and maven 3.9

Compile the project and launch the Quarkus Picocli client using the command: `mvn quarkus:dev`

```shell
mvn quarkus:dev -Dquarkus.args="test-project/simple org.openrewrite.java.format.AutoFormat"
```

If the recipe can be configured using options (= java class fields), then declare them using the format `key=value`. Multiple options can be provided as a comma-separated list

```shell
mvn quarkus:dev -Dquarkus.args="test-project/demo-spring-boot-todo-app org.openrewrite.java.search.FindAnnotations annotationPattern=@org.springframework.boot.autoconfigure.SpringBootApplication,matchMetaAnnotations=false"
```

> [!NOTE]
> You can also run the application using jar file and command: `java -jar test-project/simple org.openrewrite.java.format.AutoFormat`

> [!TIP]
> Trick for the developers

```shell
set qdebug java -jar -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address="*:5005" target/quarkus-app/quarkus-run.jar
set qrun java -jar target/quarkus-app/quarkus-run.jar

$qdebug test-project/demo-spring-boot-todo-app org.openrewrite.java.search.FindAnnotations
$qrun test-project/demo-spring-boot-todo-app org.openrewrite.java.search.FindAnnotations
```

## Issue

- FIXED: There is a Java Module issue with Maven and Quarkus:dev as command works using `java -jar` - https://github.com/ch007m/rewrite-standalone-cli/issues/1