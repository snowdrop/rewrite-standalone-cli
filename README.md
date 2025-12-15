## Instructions

Prerequisite:
- JDK 21 and maven 3.9

Compile the project and run `quarkus:dev`

```shell
mvn quarkus:dev -Dquarkus.args="test-project/simple org.openrewrite.java.format.AutoFormat"
mvn quarkus:dev -Dquarkus.args="test-project/demo-spring-boot-todo-app org.openrewrite.java.search.FindAnnotations"
```

> *TODO*
> Pass the parameters to the Recipe object initialized (class introspection)
> Load the recipe(s) from an external jar file

## Issue

- There is a Java Module issue with Maven and Quarkus:dev as command works using `java -jar`
```shell
java.lang.LinkageError: loader constraint violation: when resolving method 'void org.eclipse.aether.internal.impl.Maven2RepositoryLayoutFactory.<init>(org.eclipse.aether.spi.connector.checksum.ChecksumAlgorithmFactorySelector)' the class loader io.quarkus.bootstrap.classloading.QuarkusClassLoader @3a6bb9bf of the current class, org/eclipse/aether/supplier/RepositorySystemSupplier, and the class loader 'app' for the method's defining class, org/eclipse/aether/internal/impl/Maven2RepositoryLayoutFactory, have different Class objects for the type org/eclipse/aether/spi/connector/checksum/ChecksumAlgorithmFactorySelector used in the signature (org.eclipse.aether.supplier.RepositorySystemSupplier is in unnamed module of loader io.quarkus.bootstrap.classloading.QuarkusClassLoader @3a6bb9bf, parent loader 'app'; org.eclipse.aether.internal.impl.Maven2RepositoryLayoutFactory is in unnamed module of loader 'app') at org.eclipse.aether.supplier.RepositorySystemSupplier.getRepositoryLayoutFactories(RepositorySystemSupplier.java:223) at org.eclipse.aether.supplier.RepositorySystemSupplier.get(RepositorySystemSupplier.java:541)
```

## Trick for the developers

```shell
set qdebug java -jar -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address="*:5005" target/quarkus-app/quarkus-run.jar
set qrun java -jar target/quarkus-app/quarkus-run.jar

$qdebug test-project/demo-spring-boot-todo-app org.openrewrite.java.search.FindAnnotations
$qrun test-project/demo-spring-boot-todo-app org.openrewrite.java.search.FindAnnotations
```