# 内部 Maven 发布

## 配置与坐标

在仓库根目录执行命令。构建使用 JDK 25；macOS 可先执行：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
```

`gradle.properties` 的 `flowVersion=1.0.1` 统一三个模块版本，`repoUrl` 同时用于
内部依赖下载和发布。可以用 `-PflowVersion=1.0.2` 覆盖本次发布版本。

| 模块 | 发布坐标 |
| --- | --- |
| server | org.cses.flow:flow:1.0.1 |
| core | org.cses.flow:core:1.0.1 |
| gen | org.cses.flow:gen:1.0.1 |

## 发布

发布读取 Gradle 属性 `repoUser`、`repoPassword`，沿用已有的 properties 配置。先完成测试，再发布三个模块：

```bash
./gradlew test
./gradlew publish
```

`publish` 不会自动运行测试。只执行 `:server:publish` 不会上传 Core 或 Gen；应从根目录执行 `publish`。产物包含普通 JAR、源码 JAR、POM 和
Gradle Module Metadata，不上传 Shadow JAR。

## 本地检查

无需发布凭据，生成元数据并检查坐标和传递依赖：

```bash
./gradlew generatePomFileForMavenJavaPublication generateMetadataFileForMavenJavaPublication
python3 - <<'PY'
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
versions = set()
for module, artifact in [('server', 'flow'), ('core', 'core'), ('gen', 'gen')]:
    base = Path(module) / 'build/publications/mavenJava'
    pom = ET.parse(base / 'pom-default.xml').getroot()
    value = lambda name: pom.findtext('m:' + name, namespaces=ns)
    assert value('groupId') == 'org.cses.flow'
    assert value('artifactId') == artifact
    version = value('version')
    assert version and version != 'unspecified'
    versions.add(version)
    metadata = json.loads((base / 'module.json').read_text())
    assert metadata['component']['module'] == artifact
    assert metadata['component']['version'] == version
    assert all('shadow' not in v['name'].lower() for v in metadata['variants'])
    dependencies = pom.findall('m:dependencies/m:dependency', ns)
    flow_dependencies = [(d.findtext('m:artifactId', namespaces=ns), d.findtext('m:version', namespaces=ns))
                         for d in dependencies if d.findtext('m:groupId', namespaces=ns) == 'org.cses.flow']
    expected = {'server': [('core', version)], 'core': [('gen', version)]}.get(module, [])
    assert flow_dependencies == expected, (module, flow_dependencies)
assert len(versions) == 1, versions
print('三个模块坐标、统一版本、传递依赖和普通 JAR 变体验证通过')
PY
```

## 宿主接入

```groovy
dependencies {
    implementation('org.cses.flow:flow:1.0.1')
}
```

宿主配置同一 Maven 仓库并使用实际发布版本。Core 和 Gen 由 Flow 传递引入。
运行配置与数据库准备见 [CSES 嵌入手册](cses-embedding.md)。
