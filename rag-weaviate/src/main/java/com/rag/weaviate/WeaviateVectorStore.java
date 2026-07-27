package com.rag.weaviate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.core.api.VectorStore;
import com.rag.core.config.WeaviateCollectionConfig;
import com.rag.core.entity.VectorRecord;
import com.rag.core.exception.RagException;
import io.weaviate.client.Config;
import io.weaviate.client.WeaviateClient;
import io.weaviate.client.base.Result;
import io.weaviate.client.v1.batch.model.BatchDeleteOutput;
import io.weaviate.client.v1.batch.model.BatchDeleteResponse;
import io.weaviate.client.v1.data.model.WeaviateObject;
import io.weaviate.client.v1.filters.Operator;
import io.weaviate.client.v1.filters.WhereFilter;
import io.weaviate.client.v1.graphql.model.GraphQLResponse;
import io.weaviate.client.v1.graphql.query.argument.NearVectorArgument;
import io.weaviate.client.v1.graphql.query.argument.WhereArgument;
import io.weaviate.client.v1.graphql.query.fields.Field;
import io.weaviate.client.v1.misc.model.VectorIndexConfig;
import io.weaviate.client.v1.schema.model.DataType;
import io.weaviate.client.v1.schema.model.Property;
import io.weaviate.client.v1.schema.model.WeaviateClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Weaviate向量存储实现 —— Multi-Tenant v2（物理隔离 + 元数据审计）
 *
 * <p>每个知识库一个独立集合，集合名由 KbConfigService 派生（T{tenantId}_Kb{kbId}）。
 * tenantId/kbId 作为属性写入，用于审计与防误删兜底。</p>
 */
public class WeaviateVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(WeaviateVectorStore.class);

    private final String weaviateUrl;
    private final String weaviateToken;

    private final ObjectMapper objectMapper;

    private volatile WeaviateClient client;
    private final Set<String> initializedClasses = ConcurrentHashMap.newKeySet();
    private final Set<String> existingClasses = ConcurrentHashMap.newKeySet();

    // ==================== GraphQL查询字段定义 ====================

    private static final Field[] ALL_FIELDS = {
            Field.builder().name("text").build(),
            Field.builder().name("fileName").build(),
            Field.builder().name("fileType").build(),
            Field.builder().name("chunkType").build(),
            Field.builder().name("parentChunkId").build(),
            Field.builder().name("pageNo").build(),
            Field.builder().name("tableFlag").build(),
            Field.builder().name("codeFlag").build(),
            Field.builder().name("sourcePath").build(),
            Field.builder().name("fileId").build(),
            Field.builder().name("textHash").build(),
            Field.builder().name("recordId").build(),
            Field.builder().name("documentId").build(),
            Field.builder().name("documentVersion").build(),
            Field.builder().name("ownerId").build(),
            Field.builder().name("roleIds").build(),
            Field.builder().name("permissionTags").build(),
            Field.builder().name("tenantId").build(),
            Field.builder().name("kbId").build(),
            Field.builder().name("_additional").fields(
                    Field.builder().name("id").build(),
                    Field.builder().name("certainty").build(),
                    Field.builder().name("distance").build()
            ).build()
    };

    private static final Field[] EXIST_CHECK_FIELDS = {
            Field.builder().name("_additional").fields(
                    Field.builder().name("id").build()
            ).build()
    };

    public WeaviateVectorStore(String weaviateUrl, String weaviateToken) {
        this.weaviateUrl = weaviateUrl;
        this.weaviateToken = weaviateToken;
        this.objectMapper = new ObjectMapper();
    }

    // ==================== 客户端管理 ====================

    private WeaviateClient getClient() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = buildClient();
                }
            }
        }
        return client;
    }

    private WeaviateClient buildClient() {
        String scheme = weaviateUrl.startsWith("https") ? "https" : "http";
        String hostPort = weaviateUrl.replaceFirst("^https?://", "").replaceAll("/+$", "");

        Map<String, String> headers = new HashMap<>();
        if (weaviateToken != null && !weaviateToken.isBlank()) {
            headers.put("Authorization", "Bearer " + weaviateToken);
            log.info("Weaviate客户端已启用认证: url={}", weaviateUrl);
        } else {
            log.info("Weaviate客户端已创建（无认证）: url={}", weaviateUrl);
        }

        Config config = new Config(scheme, hostPort, headers);
        return new WeaviateClient(config);
    }

    private boolean classExistsInSchema(String className) {
        if (existingClasses.contains(className)) {
            return true;
        }
        try {
            Result<WeaviateClass> result = getClient().schema().classGetter()
                    .withClassName(className)
                    .run();
            if (!result.hasErrors() && result.getResult() != null) {
                existingClasses.add(className);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("查询Schema异常: class={}, error={}", className, e.getMessage());
            return false;
        }
    }

    // ==================== VectorStore 接口实现 ====================

    @Override
    public void initCollection(WeaviateCollectionConfig config) {
        String className = config.getClassName();
        if (initializedClasses.contains(className)) {
            log.debug("集合已初始化，跳过: class={}", className);
            return;
        }

        if (classExistsInSchema(className)) {
            log.info("集合已存在于Weaviate Schema中: class={}", className);
            initializedClasses.add(className);
            return;
        }

        try {
            VectorIndexConfig vectorIndexConfig = VectorIndexConfig.builder()
                    .distance(config.getDistanceMetric() != null ? config.getDistanceMetric() : "cosine")
                    .build();

            List<Property> properties = buildSchemaProperties();

            WeaviateClass weaviateClass = WeaviateClass.builder()
                    .className(className)
                    .vectorIndexConfig(vectorIndexConfig)
                    .vectorizer("none")
                    .properties(properties)
                    .build();

            Result<Boolean> result = getClient().schema().classCreator()
                    .withClass(weaviateClass)
                    .run();

            if (result.hasErrors()) {
                throw new RagException("WEAVIATE_001",
                        "创建Weaviate集合失败: class=" + className + ", error=" + result.getError());
            }

            initializedClasses.add(className);
            existingClasses.add(className);
            log.info("Weaviate集合创建成功: class={}, dim={}, metric={}",
                    className, config.getVectorDim(), config.getDistanceMetric());
        } catch (RagException e) {
            throw e;
        } catch (Exception e) {
            throw new RagException("WEAVIATE_001",
                    "创建Weaviate集合异常: class=" + className + ", error=" + e.getMessage(), e);
        }
    }

    @Override
    public int batchInsert(List<VectorRecord> records, WeaviateCollectionConfig config) {
        if (records == null || records.isEmpty()) return 0;

        String className = config.getClassName();
        int success = 0;
        int skipped = 0;
        int failed = 0;

        int batchSize = config.getBatchSize() != null ? config.getBatchSize() : 500;
        for (int i = 0; i < records.size(); i += batchSize) {
            int end = Math.min(i + batchSize, records.size());
            for (int j = i; j < end; j++) {
                VectorRecord record = records.get(j);
                try {
                    if ("skip".equals(config.getDedupStrategy()) && record.getTextHash() != null) {
                        if (existsByTextHash(record.getTextHash(), className)) {
                            skipped++;
                            log.debug("跳过重复记录: recordId={}, textHash={}", record.getRecordId(), record.getTextHash());
                            continue;
                        }
                    }

                    Map<String, Object> properties = buildPropertyMap(record);

                    Float[] vectorArray = record.getVector().toArray(new Float[0]);

                    Result<WeaviateObject> result = getClient().data().creator()
                            .withClassName(className)
                            .withProperties(properties)
                            .withVector(vectorArray)
                            .run();

                    if (result.hasErrors()) {
                        log.error("Weaviate写入失败: recordId={}, error={}", record.getRecordId(), result.getError());
                        failed++;
                    } else {
                        success++;
                        log.debug("Weaviate写入成功: recordId={}", record.getRecordId());
                    }
                } catch (Exception e) {
                    log.error("Weaviate写入异常: recordId={}, error={}", record.getRecordId(), e.getMessage());
                    failed++;
                }
            }
        }

        log.info("Weaviate批量写入完成: class={}, success={}, skipped={}, failed={}, total={}",
                className, success, skipped, failed, records.size());
        return success;
    }

    @Override
    public int deleteByFileId(String fileId, String className) {
        try {
            Result<BatchDeleteResponse> result = getClient().batch().objectsBatchDeleter()
                    .withClassName(className)
                    .withOutput(BatchDeleteOutput.MINIMAL)
                    .withWhere(WhereFilter.builder()
                            .path(new String[]{"fileId"})
                            .operator(Operator.Equal)
                            .valueText(fileId)
                            .build())
                    .run();

            if (result.hasErrors()) {
                log.error("Weaviate按fileId删除失败: fileId={}, class={}, error={}",
                        fileId, className, result.getError());
                return 0;
            }

            BatchDeleteResponse response = result.getResult();
            long deleted = response != null && response.getResults() != null
                    ? response.getResults().getMatches() : 0;
            log.info("Weaviate按fileId删除: fileId={}, class={}, deleted={}", fileId, className, deleted);
            return (int) deleted;
        } catch (Exception e) {
            log.error("Weaviate按fileId删除异常: fileId={}, class={}, error={}", fileId, className, e.getMessage());
            return 0;
        }
    }

    @Override
    public boolean existsByTextHash(String textHash, String className) {
        if (textHash == null) return false;
        try {
            Result<GraphQLResponse> result = getClient().graphQL().get()
                    .withClassName(className)
                    .withWhere(WhereArgument.builder()
                            .filter(WhereFilter.builder()
                                    .path(new String[]{"textHash"})
                                    .operator(Operator.Equal)
                                    .valueText(textHash)
                                    .build())
                            .build())
                    .withLimit(1)
                    .withFields(EXIST_CHECK_FIELDS)
                    .run();

            if (result.hasErrors()) {
                log.warn("Weaviate查重查询失败: textHash={}, error={}", textHash, result.getError());
                return false;
            }

            GraphQLResponse response = result.getResult();
            if (response == null || response.getData() == null) return false;

            Map<String, Object> data = objectMapper.convertValue(
                    response.getData(), new TypeReference<>() {});
            Map<String, Object> getData = (Map<String, Object>) data.get("Get");
            if (getData == null) return false;
            List<?> items = (List<?>) getData.get(className);
            return items != null && !items.isEmpty();
        } catch (Exception e) {
            log.error("Weaviate查重异常: textHash={}, error={}", textHash, e.getMessage());
            return false;
        }
    }

    @Override
    public List<VectorRecord> search(List<Float> queryVector, int limit,
                                      Map<String, Object> filter, String className) {
        if (queryVector == null || queryVector.isEmpty()) return List.of();

        try {
            Float[] vectorArray = queryVector.toArray(new Float[0]);
            NearVectorArgument nearVector = NearVectorArgument.builder()
                    .vector(vectorArray)
                    .build();

            var queryBuilder = getClient().graphQL().get()
                    .withClassName(className)
                    .withNearVector(nearVector)
                    .withLimit(limit)
                    .withFields(ALL_FIELDS);

            if (filter != null && !filter.isEmpty()) {
                WhereArgument whereArg = buildWhereFromFilter(filter);
                if (whereArg != null) {
                    queryBuilder.withWhere(whereArg);
                }
            }

            Result<GraphQLResponse> result = queryBuilder.run();

            if (result.hasErrors()) {
                log.error("Weaviate检索失败: class={}, error={}", className, result.getError());
                return List.of();
            }

            return parseSearchResult(result.getResult(), className);
        } catch (Exception e) {
            log.error("Weaviate检索异常: class={}, error={}", className, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void dropCollection(String className) {
        try {
            Result<Boolean> result = getClient().schema().classDeleter()
                    .withClassName(className)
                    .run();

            if (result.hasErrors()) {
                log.error("Weaviate删除集合失败: class={}, error={}", className, result.getError());
            } else {
                initializedClasses.remove(className);
                existingClasses.remove(className);
                log.info("Weaviate集合已删除: {}", className);
            }
        } catch (Exception e) {
            log.error("Weaviate删除集合异常: class={}, error={}", className, e.getMessage());
        }
    }

    @Override
    public void clearCollection(String className) {
        try {
            getClient().schema().classDeleter()
                    .withClassName(className)
                    .run();

            initializedClasses.remove(className);
            existingClasses.remove(className);
            log.info("Weaviate集合已清空（已删除）: {}，下次入库时将自动重建", className);
        } catch (Exception e) {
            log.error("Weaviate清空集合异常: class={}, error={}", className, e.getMessage());
        }
    }

    // ==================== 内部工具方法 ====================

    private List<Property> buildSchemaProperties() {
        return List.of(
                Property.builder().name("text").dataType(List.of(DataType.TEXT)).build(),
                Property.builder().name("fileName").dataType(List.of(DataType.TEXT)).build(),
                Property.builder().name("fileType").dataType(List.of(DataType.TEXT)).build(),
                Property.builder().name("chunkType").dataType(List.of(DataType.TEXT)).build(),
                Property.builder().name("parentChunkId").dataType(List.of(DataType.TEXT)).build(),
                Property.builder().name("pageNo").dataType(List.of(DataType.INT)).build(),
                Property.builder().name("tableFlag").dataType(List.of(DataType.BOOLEAN)).build(),
                Property.builder().name("codeFlag").dataType(List.of(DataType.BOOLEAN)).build(),
                Property.builder().name("sourcePath").dataType(List.of(DataType.TEXT)).build(),
                Property.builder().name("fileId").dataType(List.of(DataType.TEXT)).build(),
                Property.builder().name("textHash").dataType(List.of(DataType.TEXT)).build(),
                Property.builder().name("recordId").dataType(List.of(DataType.TEXT)).build(),
                Property.builder().name("documentId").dataType(List.of(DataType.TEXT)).build(),
                Property.builder().name("documentVersion").dataType(List.of(DataType.TEXT)).build(),
                Property.builder().name("ownerId").dataType(List.of(DataType.INT)).build(),
                Property.builder().name("roleIds").dataType(List.of(DataType.INT_ARRAY)).build(),
                Property.builder().name("permissionTags").dataType(List.of(DataType.TEXT_ARRAY)).build(),
                // Multi-Tenant 审计字段
                Property.builder().name("tenantId").dataType(List.of(DataType.INT)).build(),
                Property.builder().name("kbId").dataType(List.of(DataType.INT)).build()
        );
    }

    private Map<String, Object> buildPropertyMap(VectorRecord record) {
        Map<String, Object> props = new HashMap<>();
        props.put("text", record.getText());
        props.put("fileName", record.getFileName());
        props.put("fileType", record.getFileType());
        props.put("chunkType", record.getChunkType());
        props.put("parentChunkId", record.getParentChunkId());
        props.put("pageNo", record.getPageNo());
        props.put("tableFlag", record.getTableFlag() != null ? record.getTableFlag() : false);
        props.put("codeFlag", record.getCodeFlag() != null ? record.getCodeFlag() : false);
        props.put("sourcePath", record.getSourcePath());
        props.put("fileId", record.getFileId());
        props.put("textHash", record.getTextHash());
        props.put("recordId", record.getRecordId());
        props.put("documentId", record.getDocumentId());
        props.put("documentVersion", record.getDocumentVersion());
        props.put("ownerId", record.getOwnerId());
        props.put("tenantId", record.getTenantId());
        props.put("kbId", record.getKbId());
        return props;
    }

    private WhereArgument buildWhereFromFilter(Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) return null;

        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            String field = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof String strVal) {
                return WhereArgument.builder()
                        .filter(WhereFilter.builder()
                                .path(new String[]{field})
                                .operator(Operator.Equal)
                                .valueText(strVal)
                                .build())
                        .build();
            } else if (value instanceof Integer intVal) {
                return WhereArgument.builder()
                        .filter(WhereFilter.builder()
                                .path(new String[]{field})
                                .operator(Operator.Equal)
                                .valueInt(intVal)
                                .build())
                        .build();
            } else if (value instanceof Long longVal) {
                return WhereArgument.builder()
                        .filter(WhereFilter.builder()
                                .path(new String[]{field})
                                .operator(Operator.Equal)
                                .valueInt(longVal.intValue())
                                .build())
                        .build();
            } else if (value instanceof Boolean boolVal) {
                return WhereArgument.builder()
                        .filter(WhereFilter.builder()
                                .path(new String[]{field})
                                .operator(Operator.Equal)
                                .valueBoolean(boolVal)
                                .build())
                        .build();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<VectorRecord> parseSearchResult(GraphQLResponse response, String className) {
        if (response == null || response.getData() == null) return List.of();

        try {
            Map<String, Object> data = objectMapper.convertValue(
                    response.getData(), new TypeReference<>() {});

            Map<String, Object> getData = (Map<String, Object>) data.get("Get");
            if (getData == null) return List.of();

            List<Map<String, Object>> items = (List<Map<String, Object>>) getData.get(className);
            if (items == null || items.isEmpty()) return List.of();

            List<VectorRecord> records = new ArrayList<>();
            for (Map<String, Object> item : items) {
                records.add(convertToVectorRecord(item));
            }
            return records;
        } catch (Exception e) {
            log.error("解析Weaviate检索结果失败: class={}, error={}", className, e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private VectorRecord convertToVectorRecord(Map<String, Object> item) {
        return VectorRecord.builder()
                .recordId(getStr(item, "recordId"))
                .text(getStr(item, "text"))
                .fileName(getStr(item, "fileName"))
                .fileType(getStr(item, "fileType"))
                .chunkType(getStr(item, "chunkType"))
                .parentChunkId(getStr(item, "parentChunkId"))
                .pageNo(getInt(item, "pageNo"))
                .tableFlag(getBool(item, "tableFlag"))
                .codeFlag(getBool(item, "codeFlag"))
                .sourcePath(getStr(item, "sourcePath"))
                .fileId(getStr(item, "fileId"))
                .textHash(getStr(item, "textHash"))
                .documentId(getStr(item, "documentId"))
                .documentVersion(getStr(item, "documentVersion"))
                .ownerId(getLong(item, "ownerId"))
                .tenantId(getLong(item, "tenantId"))
                .kbId(getLong(item, "kbId"))
                .build();
    }

    // ==================== 安全取值辅助方法 ====================

    private String getStr(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    private Integer getInt(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number num) return num.intValue();
        return null;
    }

    private Long getLong(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number num) return num.longValue();
        return null;
    }

    private Boolean getBool(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Boolean b) return b;
        return null;
    }
}
