# run-test-java17.sh output errors:

```
Checkout this error when running maven test

[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.2:test (default-test) on project exchange-core: 
[ERROR] 
[ERROR] See /Users/ericyeung/private/github/exchange-core/target/surefire-reports for the individual test results.
[ERROR] See dump files (if any exist) [date].dump, [date]-jvmRun[N].dump and [date].dumpstream.
[ERROR] There was an error in the forked process
[ERROR] TestEngine with ID 'junit-jupiter' failed to discover tests
[ERROR] org.apache.maven.surefire.booter.SurefireBooterForkException: There was an error in the forked process
[ERROR] TestEngine with ID 'junit-jupiter' failed to discover tests
[ERROR]         at org.apache.maven.plugin.surefire.booterclient.ForkStarter.fork(ForkStarter.java:628)
[ERROR]         at org.apache.maven.plugin.surefire.booterclient.ForkStarter.run(ForkStarter.java:285)
[ERROR]         at org.apache.maven.plugin.surefire.booterclient.ForkStarter.run(ForkStarter.java:250)
[ERROR]         at org.apache.maven.plugin.surefire.AbstractSurefireMojo.executeProvider(AbstractSurefireMojo.java:1336)
[ERROR]         at org.apache.maven.plugin.surefire.AbstractSurefireMojo.executeAfterPreconditionsChecked(AbstractSurefireMojo.java:1134)
[ERROR]         at org.apache.maven.plugin.surefire.AbstractSurefireMojo.execute(AbstractSurefireMojo.java:968)
[ERROR]         at org.apache.maven.plugin.DefaultBuildPluginManager.executeMojo(DefaultBuildPluginManager.java:126)
[ERROR]         at org.apache.maven.lifecycle.internal.MojoExecutor.doExecute2(MojoExecutor.java:328)
[ERROR]         at org.apache.maven.lifecycle.internal.MojoExecutor.doExecute(MojoExecutor.java:316)
[ERROR]         at org.apache.maven.lifecycle.internal.MojoExecutor.execute(MojoExecutor.java:212)
[ERROR]         at org.apache.maven.lifecycle.internal.MojoExecutor.execute(MojoExecutor.java:174)
```


This error comes from the Maven test runner **Apache Maven Surefire Plugin** while trying to run tests with **JUnit Jupiter**.

The key line is:

```
TestEngine with ID 'junit-jupiter' failed to discover tests
```

This means **JUnit started but failed during test discovery**, before executing any test. It usually happens due to **classpath, dependency, or incompatible JUnit configuration**.

Below are the most common causes and how to debug them.

---

# 1. Check the real root error (most important)

The stack trace you pasted is only the **wrapper error**.

Surefire already told you where the **real error** is:

```
target/surefire-reports
```

Look inside:

```
target/surefire-reports/*.txt
target/surefire-reports/*.dump
```

Example:

```bash
cat target/surefire-reports/*.txt
```

or

```bash
less target/surefire-reports/*.txt
```

That file will contain something like:

```
java.lang.NoClassDefFoundError
java.lang.ClassNotFoundException
java.lang.UnsupportedClassVersionError
```

Those messages reveal the real cause.

---

# 2. Most common causes

## Cause 1 — Missing JUnit Jupiter dependency

JUnit 5 requires the Jupiter engine.

Check your `pom.xml` includes:

```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.10.2</version>
    <scope>test</scope>
</dependency>
```

Then ensure Surefire supports JUnit5.

Your version `3.5.2` is fine.

---

## Cause 2 — Mixing JUnit4 and JUnit5 incorrectly

If your project has both:

```
junit:junit
org.junit.jupiter
```

test discovery may fail.

Fix options:

**Option A — Pure JUnit5**

Remove:

```xml
<dependency>
  <groupId>junit</groupId>
  <artifactId>junit</artifactId>
</dependency>
```

**Option B — Add Vintage engine**

```xml
<dependency>
  <groupId>org.junit.vintage</groupId>
  <artifactId>junit-vintage-engine</artifactId>
  <version>5.10.2</version>
  <scope>test</scope>
</dependency>
```

---

## Cause 3 — Java version mismatch

Check Java version:

```bash
java -version
mvn -version
```

If compiled with **Java 21** but tests run with **Java 17**, discovery may fail.

---

## Cause 4 — Static initializer crash

JUnit fails discovery if a test class throws an exception during loading:

```java
static {
    throw new RuntimeException("boom");
}
```

or

```java
static SomeService s = new SomeService(); // fails
```

Then the error appears exactly like yours.

---

## Cause 5 — Corrupted Maven cache

Sometimes dependencies are broken.

Fix:

```bash
mvn -U clean test
```

or delete cache:

```bash
rm -rf ~/.m2/repository/org/junit
```

---

# 3. Run Maven with debug to see the real error

Run:

```bash
mvn -X test
```

or

```bash
mvn -e test
```

The stack trace will show the exact class causing the failure.

---

# 4. Quick checklist

Run these:

```bash
mvn clean
mvn dependency:tree | grep junit
java -version
mvn test -e
```

Look for:

* duplicated junit versions
* missing `junit-jupiter-engine`
* test class initialization errors

---

# 5. If you want, paste these and I can pinpoint the exact problem

Please paste:

1️⃣ `pom.xml` dependencies section
2️⃣ Output of

```bash
mvn dependency:tree | grep junit
```

3️⃣ One file from

```
target/surefire-reports/*.txt
```

Then I can identify the exact cause in a few seconds.
