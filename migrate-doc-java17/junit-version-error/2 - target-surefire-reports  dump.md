# target/surefire-reports/*.dump

```
# Created at 2026-03-12T16:27:49.687
org.junit.platform.commons.JUnitException: TestEngine with ID 'junit-jupiter' failed to discover tests
	at org.junit.platform.launcher.core.EngineDiscoveryOrchestrator.discoverEngineRoot(EngineDiscoveryOrchestrator.java:160)
	at org.junit.platform.launcher.core.EngineDiscoveryOrchestrator.discoverSafely(EngineDiscoveryOrchestrator.java:134)
	at org.junit.platform.launcher.core.EngineDiscoveryOrchestrator.discover(EngineDiscoveryOrchestrator.java:108)
	at org.junit.platform.launcher.core.EngineDiscoveryOrchestrator.discover(EngineDiscoveryOrchestrator.java:80)
	at org.junit.platform.launcher.core.DefaultLauncher.discover(DefaultLauncher.java:110)
	at org.junit.platform.launcher.core.DefaultLauncher.discover(DefaultLauncher.java:78)
	at org.junit.platform.launcher.core.DefaultLauncherSession$DelegatingLauncher.discover(DefaultLauncherSession.java:81)
	at org.apache.maven.surefire.junitplatform.LazyLauncher.discover(LazyLauncher.java:50)
	at org.apache.maven.surefire.junitplatform.TestPlanScannerFilter.accept(TestPlanScannerFilter.java:52)
	at org.apache.maven.surefire.api.util.DefaultScanResult.applyFilter(DefaultScanResult.java:87)
	at org.apache.maven.surefire.junitplatform.JUnitPlatformProvider.scanClasspath(JUnitPlatformProvider.java:142)
	at org.apache.maven.surefire.junitplatform.JUnitPlatformProvider.invoke(JUnitPlatformProvider.java:122)
	at org.apache.maven.surefire.booter.ForkedBooter.runSuitesInProcess(ForkedBooter.java:385)
	at org.apache.maven.surefire.booter.ForkedBooter.execute(ForkedBooter.java:162)
	at org.apache.maven.surefire.booter.ForkedBooter.run(ForkedBooter.java:507)
	at org.apache.maven.surefire.booter.ForkedBooter.main(ForkedBooter.java:495)
Caused by: org.junit.platform.commons.JUnitException: ClassSelector [className = 'exchange.core2.tests.perf.PerfLatency', classLoader = null] resolution failed
	at org.junit.platform.launcher.listeners.discovery.AbortOnFailureLauncherDiscoveryListener.selectorProcessed(AbortOnFailureLauncherDiscoveryListener.java:39)
	at org.junit.platform.engine.support.discovery.EngineDiscoveryRequestResolution.resolveCompletely(EngineDiscoveryRequestResolution.java:103)
	at org.junit.platform.engine.support.discovery.EngineDiscoveryRequestResolution.run(EngineDiscoveryRequestResolution.java:83)
	at org.junit.platform.engine.support.discovery.EngineDiscoveryRequestResolver.resolve(EngineDiscoveryRequestResolver.java:113)
	at org.junit.jupiter.engine.discovery.DiscoverySelectorResolver.resolveSelectors(DiscoverySelectorResolver.java:46)
	at org.junit.jupiter.engine.JupiterTestEngine.discover(JupiterTestEngine.java:69)
	at org.junit.platform.launcher.core.EngineDiscoveryOrchestrator.discoverEngineRoot(EngineDiscoveryOrchestrator.java:152)
	... 15 more
Caused by: java.lang.NoSuchMethodError: 'boolean org.junit.platform.commons.util.ReflectionUtils.returnsVoid(java.lang.reflect.Method)'
	at org.junit.jupiter.engine.discovery.predicates.IsTestableMethod.test(IsTestableMethod.java:48)
	at org.junit.jupiter.engine.discovery.predicates.IsTestMethod.test(IsTestMethod.java:23)
	at org.junit.jupiter.engine.discovery.predicates.IsTestableMethod.test(IsTestableMethod.java:26)
	at java.base/java.util.function.Predicate.lambda$or$2(Predicate.java:101)
	at java.base/java.util.function.Predicate.lambda$or$2(Predicate.java:101)
	at org.junit.platform.commons.util.ReflectionUtils.findMethod(ReflectionUtils.java:1559)
	at org.junit.platform.commons.util.ReflectionUtils.isMethodPresent(ReflectionUtils.java:1408)
	at org.junit.jupiter.engine.discovery.predicates.IsTestClassWithTests.hasTestOrTestFactoryOrTestTemplateMethods(IsTestClassWithTests.java:50)
	at org.junit.jupiter.engine.discovery.predicates.IsTestClassWithTests.test(IsTestClassWithTests.java:46)
	at org.junit.jupiter.engine.discovery.ClassSelectorResolver.resolve(ClassSelectorResolver.java:67)
	at org.junit.platform.engine.support.discovery.EngineDiscoveryRequestResolution.lambda$resolve$2(EngineDiscoveryRequestResolution.java:135)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
	at java.base/java.util.ArrayList$ArrayListSpliterator.tryAdvance(ArrayList.java:1602)
	at java.base/java.util.stream.ReferencePipeline.forEachWithCancel(ReferencePipeline.java:129)
	at java.base/java.util.stream.AbstractPipeline.copyIntoWithCancel(AbstractPipeline.java:527)
	at java.base/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:513)
	at java.base/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:499)
	at java.base/java.util.stream.FindOps$FindOp.evaluateSequential(FindOps.java:150)
	at java.base/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:234)
	at java.base/java.util.stream.ReferencePipeline.findFirst(ReferencePipeline.java:647)
	at org.junit.platform.engine.support.discovery.EngineDiscoveryRequestResolution.resolve(EngineDiscoveryRequestResolution.java:189)
	at org.junit.platform.engine.support.discovery.EngineDiscoveryRequestResolution.resolve(EngineDiscoveryRequestResolution.java:126)
	at org.junit.platform.engine.support.discovery.EngineDiscoveryRequestResolution.resolveCompletely(EngineDiscoveryRequestResolution.java:92)
	... 20 more

```


Your stack trace actually shows the **exact root cause** now.

The critical line is:

```
Caused by: java.lang.NoSuchMethodError:
'boolean org.junit.platform.commons.util.ReflectionUtils.returnsVoid(java.lang.reflect.Method)'
```

This almost always means **JUnit dependencies are incompatible versions** on the classpath.

In other words:

⚠️ **JUnit Jupiter engine version ≠ JUnit Platform Commons version**

So when **JUnit Jupiter** calls a method that exists in a newer **JUnit Platform**, the older library doesn't have it → `NoSuchMethodError`.

---

# Why this happens

Your Maven dependency tree probably contains **mixed JUnit versions**, e.g.:

```
junit-jupiter-engine 5.10.x
junit-platform-commons 1.7.x   ❌
```

or

```
junit-jupiter 5.8.x
junit-platform 1.10.x
```

This mismatch breaks test discovery in **Apache Maven Surefire Plugin**.

---

# Confirm the issue

Run:

```bash
mvn dependency:tree | grep junit
```

You will likely see something like:

```
org.junit.jupiter:junit-jupiter-engine:5.10.2
org.junit.platform:junit-platform-commons:1.8.0
```

Versions should **always align**.

---

# The correct fix (recommended)

Use **one single dependency**:

```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.10.2</version>
    <scope>test</scope>
</dependency>
```

Remove **all of these if present**:

```
junit-jupiter-api
junit-jupiter-engine
junit-platform-commons
junit-platform-launcher
```

because `junit-jupiter` already imports them with correct versions.

---

# Then run

```
mvn clean test
```

---

# If the problem persists (very common in large projects)

Another dependency may **pull an old JUnit platform transitively**.

Check with:

```
mvn dependency:tree | grep platform
```

If you see something like:

```
junit-platform-commons:1.7.x
```

force the correct version:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.junit</groupId>
      <artifactId>junit-bom</artifactId>
      <version>5.10.2</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

This guarantees **all JUnit artifacts match**.

---

# One more clue from your trace

This class failed:

```
exchange.core2.tests.perf.PerfLatency
```

So test discovery reached that class and then crashed due to the JUnit mismatch.

The test itself is likely **fine**.

---

# Fastest fix (2 lines)

In your `pom.xml`:

```xml
<dependency>
 <groupId>org.junit.jupiter</groupId>
 <artifactId>junit-jupiter</artifactId>
 <version>5.10.2</version>
 <scope>test</scope>
</dependency>
```

Then:

```
mvn clean test
```

---

✅ If you want, paste:

```
mvn dependency:tree | grep junit
```

I can immediately identify **which dependency is introducing the broken JUnit version** (this happens a lot in performance testing frameworks like JMH, Spring, etc.).


