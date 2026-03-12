```

I am using Java 17

mvn dependency:tree | grep junit

mvn dependency:tree | grep junit                                                     1 ✘  9s  16:35:23 
[INFO] +- org.mockito:mockito-junit-jupiter:jar:5.13.0:test
[INFO] |  \- org.junit.jupiter:junit-jupiter-api:jar:5.11.0:test
[INFO] |     \- org.junit.platform:junit-platform-commons:jar:1.11.0:test
[INFO] +- org.junit.jupiter:junit-jupiter-engine:jar:5.10.3:test
[INFO] |  +- org.junit.platform:junit-platform-engine:jar:1.10.3:test
[INFO] |  |  +- io.cucumber:junit-xml-formatter:jar:0.5.0:test
[INFO] +- io.cucumber:cucumber-junit-platform-engine:jar:7.18.1:test
[INFO] +- org.junit.platform:junit-platform-suite:jar:1.10.3:test
[INFO] |  +- org.junit.platform:junit-platform-suite-api:jar:1.10.3:test
[INFO] |  \- org.junit.platform:junit-platform-suite-engine:jar:1.10.3:test
[INFO] |     \- org.junit.platform:junit-platform-suite-commons:jar:1.10.3:test
[INFO] |        \- org.junit.platform:junit-platform-launcher:jar:1.10.3:test


```


Your dependency tree clearly shows the problem: **mixed JUnit versions**. ⚠️

You currently have:

| Component                | Version    |
| ------------------------ | ---------- |
| `junit-jupiter-api`      | **5.11.0** |
| `junit-platform-commons` | **1.11.0** |
| `junit-jupiter-engine`   | **5.10.3** |
| `junit-platform-engine`  | **1.10.3** |

So the runtime becomes something like:

```
JUnit Jupiter Engine 5.10.3
     ↓
JUnit Platform 1.10.3
     ↓
JUnit Platform Commons 1.11.0   ❌
```

But **JUnit Jupiter 5.10.3** expects the **JUnit Platform 1.10.x API**, not 1.11.x.

That’s why the engine calls:

```
ReflectionUtils.returnsVoid()
```

which **exists in 1.11 but not in 1.10**, causing the `NoSuchMethodError`.

---

# Why this happened

This dependency pulled a **newer JUnit API**:

```
org.mockito:mockito-junit-jupiter:5.13.0
    └── junit-jupiter-api 5.11.0
```

But your project still uses:

```
junit-jupiter-engine 5.10.3
```

So the **API and engine are mismatched**.

---

# Correct fix (best practice)

Use the **JUnit BOM** so every component is aligned.

Add this to your `pom.xml`:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.junit</groupId>
      <artifactId>junit-bom</artifactId>
      <version>5.11.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

Then define JUnit without versions:

```xml
<dependency>
  <groupId>org.junit.jupiter</groupId>
  <artifactId>junit-jupiter</artifactId>
  <scope>test</scope>
</dependency>
```

This ensures **all JUnit components match automatically**.

---

# Alternative quick fix

Upgrade the engine to match the API:

```xml
<dependency>
  <groupId>org.junit.jupiter</groupId>
  <artifactId>junit-jupiter-engine</artifactId>
  <version>5.11.0</version>
  <scope>test</scope>
</dependency>
```

Then run:

```bash
mvn clean test
```

---

# After fixing, your dependency tree should look like

```
junit-jupiter-api 5.11.0
junit-jupiter-engine 5.11.0
junit-platform-engine 1.11.0
junit-platform-commons 1.11.0
```

All **aligned**.

---

# Small side note

You're also using:

```
io.cucumber:cucumber-junit-platform-engine
```

which integrates **Cucumber** with JUnit Platform. That dependency can also drag older platform versions, so the **BOM approach is safest**.

---

✅ **Most stable setup for Java 17**

```
JUnit 5.11.x
Mockito 5.13.x
Surefire 3.5.x
```

which is exactly what you already have except for the version mismatch.

---

💡 If you'd like, I can also show a **clean minimal Maven testing stack used in high-performance Java projects** (JUnit + Mockito + AssertJ + JMH) that avoids these dependency conflicts entirely.
