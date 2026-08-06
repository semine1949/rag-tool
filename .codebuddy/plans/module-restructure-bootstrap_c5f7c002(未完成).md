---
name: module-restructure-bootstrap
overview: 将 rag-admin-boot 重命名为 rag-bootstrap，将 rag-parser 和 rag-chunker 作为子包文件夹合并进 rag-bootstrap 模块，删除独立 Maven 模块，更新所有依赖引用、import 路径、pom.xml 配置。
todos:
  - id: move-chunker-sources
    content: 将 rag-chunker/src/main/java/com/rag/chunker/ 下全部源码移入 rag-admin-boot/src/main/java/com/rag/chunker/
    status: pending
  - id: move-parser-sources
    content: 将 rag-parser/src/main/java/com/rag/parser/ 下全部源码移入 rag-admin-boot/src/main/java/com/rag/parser/
    status: pending
  - id: update-bootstrap-pom
    content: 更新 rag-admin-boot/pom.xml：artifactId 改为 rag-bootstrap，移除 rag-parser/rag-chunker Maven 依赖，合并两者外部依赖
    status: pending
    dependencies:
      - move-chunker-sources
      - move-parser-sources
  - id: rename-module-dir
    content: 将 rag-admin-boot 目录重命名为 rag-bootstrap
    status: pending
    dependencies:
      - update-bootstrap-pom
  - id: update-root-pom
    content: 更新根 pom.xml：modules 移除 rag-parser/rag-chunker，rag-admin-boot 改为 rag-bootstrap；dependencyManagement 移除 rag-parser/rag-chunker
    status: pending
    dependencies:
      - rename-module-dir
  - id: delete-old-modules
    content: 删除 rag-parser 和 rag-chunker 模块目录
    status: pending
    dependencies:
      - update-root-pom
  - id: verify-build
    content: 执行 mvn -o clean compile -DskipTests 验证编译无 ERROR/FAILURE，更新 work-log.md
    status: pending
    dependencies:
      - delete-old-modules
---

## 产品概述

将项目中的 `rag-admin-boot` 模块重命名为 `rag-bootstrap`，同时将 `rag-parser` 和 `rag-chunker` 两个独立 Maven 模块合并为 `rag-bootstrap` 模块内的子包（`com.rag.chunker` 和 `com.rag.parser`），不再作为独立的 Maven 模块存在。整个过程遵循设计模式（单一职责、开闭原则），不修改任何 Java 类内部逻辑，仅调整项目结构和构建配置。

## 核心功能

1. `rag-admin-boot` 模块重命名为 `rag-bootstrap`
2. `rag-parser` 模块的全部 Java 源码（`com.rag.parser` 包）移入 `rag-bootstrap` 的源码目录下
3. `rag-chunker` 模块的全部 Java 源码（`com.rag.chunker` 包）移入 `rag-bootstrap` 的源码目录下
4. 合并 `rag-parser` 和 `rag-chunker` 的外部依赖到 `rag-bootstrap/pom.xml`
5. 更新根 `pom.xml` 的模块声明和依赖管理
6. 删除空的 `rag-parser` 和 `rag-chunker` 模块目录
7. Java 类的包名 `com.rag.chunker` 和 `com.rag.parser` 保持不变，所有 import 语句无需修改

## 关键设计

- **开闭原则**：Java 类的包名和内部逻辑完全不变，仅重组物理目录结构
- **单一职责**：chunker 和 parser 作为 bootstrap 模块内的子包，保持各自职责清晰
- **最小改动**：仅修改 pom.xml 和文件目录位置，不改 Java 代码

## 技术栈

- Maven 多模块管理（根 pom.xml + 子模块 pom.xml）
- Java 17 + Spring Boot 3.4.5
- 源码目录结构重组（文件系统操作）

## 实施策略

### 1. 文件移动策略

将 rag-parser 和 rag-chunker 的源码目录整体移入 rag-bootstrap（后更名为 rag-bootstrap）的源码目录下，保持包名不变：

```
原路径:  rag-parser/src/main/java/com/rag/parser/...
新路径:  rag-bootstrap/src/main/java/com/rag/parser/...

原路径:  rag-chunker/src/main/java/com/rag/chunker/...
新路径:  rag-bootstrap/src/main/java/com/rag/chunker/...
```

### 2. pom.xml 修改策略

**根 pom.xml 变更：**

- `<modules>`：移除 `rag-parser`、`rag-chunker`，将 `rag-admin-boot` 改为 `rag-bootstrap`
- `<dependencyManagement>`：移除 `rag-parser`、`rag-chunker` 的版本管理声明

**rag-bootstrap/pom.xml 变更：**

- `artifactId`：`rag-admin-boot` → `rag-bootstrap`
- `name`：同步更新
- 移除对 `rag-parser`、`rag-chunker` 的 Maven 依赖（代码已在本地，无需 Maven 坐标依赖）
- 合并 rag-parser 的外部依赖：`okhttp`、`poi-ooxml`(5.2.5)、`spring-ai-tika-document-reader`、`spring-ai-pdf-document-reader`、`spring-ai-markdown-document-reader`
- 合并 rag-chunker 的外部依赖：`spring-ai-commons`、`opennlp-tools`、`commons-text`
- 注意去重：`hutool-all`、`slf4j-api` 已在 rag-admin-boot 或传递依赖中存在

### 3. 依赖分析（去重）

| 依赖 | rag-parser | rag-chunker | rag-admin-boot | 合并后处理 |
| --- | --- | --- | --- | --- |
| okhttp | ✅ | - | - | 新增 |
| poi-ooxml 5.2.5 | ✅ | - | - | 新增 |
| spring-ai-tika-document-reader | ✅ | - | - | 新增 |
| spring-ai-pdf-document-reader | ✅ | - | - | 新增 |
| spring-ai-markdown-document-reader | ✅ | - | - | 新增 |
| spring-ai-commons | ✅ | ✅ | 传递 | 已通过 rag-core 传递引入 |
| opennlp-tools | - | ✅ | - | 新增 |
| commons-text | - | ✅ | - | 新增 |
| hutool-all | ✅ | ✅ | ✅ | 已有，无需重复 |
| slf4j-api | ✅ | ✅ | 传递 | 已有 |


### 4. 模块重命名

- 目录：`rag-admin-boot/` → `rag-bootstrap/`
- 这影响根 pom.xml 的 `<module>` 声明和 IDE 的项目识别

## 实施说明

- **包名不变**：`com.rag.chunker` 和 `com.rag.parser` 保持原样，Java 文件中无任何 import 需要修改
- **编译验证**：`mvn -o clean compile -DskipTests` 确保无 ERROR/FAILURE
- **工作日志**：完成后在 work-log.md 记录

## 架构设计

```
重组前:
rag-tool/
├── pom.xml (modules: rag-core, rag-parser, rag-chunker, rag-auth, rag-admin-boot)
├── rag-core/          (独立模块)
├── rag-parser/        (独立模块 → 将合并)
├── rag-chunker/       (独立模块 → 将合并)
├── rag-auth/          (独立模块)
└── rag-admin-boot/    (启动模块 → 重命名为 rag-bootstrap)

重组后:
rag-tool/
├── pom.xml (modules: rag-core, rag-bootstrap, rag-auth)
├── rag-core/          (独立模块，不变)
├── rag-auth/          (独立模块，不变)
└── rag-bootstrap/     (启动模块，内含 chunker + parser 子包)
    └── src/main/java/com/rag/
        ├── boot/          (原 admin-boot 代码)
        ├── chunker/       (原 rag-chunker 代码)
        └── parser/        (原 rag-parser 代码)
```

## 目录结构

```
rag-tool/
├── pom.xml                              # [MODIFY] modules 移除 rag-parser/rag-chunker，rag-admin-boot→rag-bootstrap；dependencyManagement 移除 rag-parser/rag-chunker
├── rag-core/                            # 不变
├── rag-auth/                            # 不变
├── rag-bootstrap/                       # [RENAME] 原 rag-admin-boot
│   ├── pom.xml                          # [MODIFY] artifactId→rag-bootstrap，移除 rag-parser/rag-chunker Maven 依赖，合并两者外部依赖
│   └── src/main/java/com/rag/
│       ├── boot/                        # 原 admin-boot 源码（不变）
│       │   ├── RagBootstrapApplication.java
│       │   ├── config/RagCoreConfig.java
│       │   ├── controller/RagController.java
│       │   ├── controller/AdminController.java
│       │   ├── controller/BatchUploadRequest.java
│       │   ├── service/RagToolService.java
│       │   ├── service/FileProcessResult.java
│       │   └── interceptor/JwtAuthInterceptor.java
│       ├── chunker/                     # [NEW] 原 rag-chunker 源码移入
│       │   ├── ChunkStrategyFactory.java
│       │   ├── SplitterConfig.java
│       │   ├── SizeTextSplitter.java
│       │   ├── ParentChildTextSplitter.java
│       │   └── util/ChunkDocuments.java
│       └── parser/                      # [NEW] 原 rag-parser 源码移入
│           ├── DocumentParseFactory.java
│           └── impl/
│               ├── DeepSeekOcrClient.java
│               ├── ExcelParser.java
│               ├── MixedDocumentParser.java
│               └── OcrDocumentReader.java
├── rag-parser/                          # [DELETE] 整体删除
└── rag-chunker/                         # [DELETE] 整体删除
```

## 关键代码结构

无 Java 代码变更。仅 pom.xml 调整，关键 diff：

**根 pom.xml `<modules>` 变更：**

```xml
<modules>
    <module>rag-core</module>
    <module>rag-bootstrap</module>
    <module>rag-auth</module>
</modules>
```

**rag-bootstrap/pom.xml 新增依赖（合并自 rag-parser + rag-chunker）：**

```xml
<!-- 原 rag-parser 依赖 -->
<dependency>
    <groupId>com.squareup.okhttp3</groupId>
    <artifactId>okhttp</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
    <version>5.2.5</version>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-tika-document-reader</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-pdf-document-reader</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-markdown-document-reader</artifactId>
</dependency>
<!-- 原 rag-chunker 依赖 -->
<dependency>
    <groupId>org.apache.opennlp</groupId>
    <artifactId>opennlp-tools</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-text</artifactId>
</dependency>
```