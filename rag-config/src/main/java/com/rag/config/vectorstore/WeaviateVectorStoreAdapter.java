package com.rag.config.vectorstore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.common.entity.config.SearchConfig;
import com.rag.common.enums.SearchMode;
import io.weaviate.client.Config;
import io.weaviate.client.WeaviateClient;
import io.weaviate.client.base.Result;
import io.weaviate.client.v1.filters.WhereFilter;
import io.weaviate.client.v1.graphql.model.GraphQLResponse;
import io.weaviate.client.v1.graphql.query.Get;
import io.weaviate.client.v1.graphql.query.argument.Bm25Argument;
import io.weaviate.client.v1.graphql.query.argument.NearVectorArgument;
import io.weaviate.client.v1.graphql.query.argument.WhereArgument;
import io.weaviate.client.v1.graphql.query.fields.Field;
import io.weaviate.client.v1.misc.model.VectorIndexConfig;
import io.weaviate.client.v1.schema.model.DataType;
import io.weaviate.client.v1.schema.model.Property;
import io.weaviate.client.v1.schema.model.WeaviateClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 {@code io.weaviate:client} 4.6.0 的 Spring AI {@link VectorStore} 适配实现。
 * <p>
 * 框架自带的 {@code spring-ai-vector-store-weaviate} 连接器在本项目的 Maven 镜像中不可用，
 * 故此处直接实现 Spring AI 的 {@link VectorStore} 接口，复用既有的 Weaviate 直连逻辑。
 * 集合按 {@code className(=T{tenantId}_Kb{kbId})} 物理隔离；向量化由绑定 {@link EmbeddingModel} 在 {@link #add(List)} 内完成。
 */
public class WeaviateVectorStoreAdapter implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(WeaviateVectorStoreAdapter.class);

    private final WeaviateClient client;
    private final EmbeddingModel embeddingModel;
    private final String className;
    private final int vectorDim;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Set<String> existingClasses = ConcurrentHashMap.newKeySet();
    private final Set<String> initializedClasses = ConcurrentHashMap.newKeySet();

    public WeaviateVectorStoreAdapter(WeaviateClient client, EmbeddingModel embeddingModel,
                                      String className, int vectorDim) {
        this.client = client;
        this.embeddingModel = embeddingModel;
        this.className = className;
        this.vectorDim = vectorDim;
    }

    // ==================== 集合初始化 ====================

    private void initCollection() {
        if (initializedClasses.contains(className)) {
            return;
        }
        if (!classExistsInSchema(className)) {
            WeaviateClass clazz = WeaviateClass.builder()
                    .className(className)
                    .vectorIndexConfig(VectorIndexConfig.builder().distance("cosine").build())
                    .properties(buildSchemaProperties())
                    .build();
            Result<Boolean> createResult = client.schema().classCreator().withClass(clazz).run();
            if (Boolean.FALSE.equals(createResult.getResult()) && createResult.hasErrors()) {
                log.error("创建集合失败: {}", createResult.getError());
                throw new IllegalStateException("创建集合失败: " + className);
            }
            existingClasses.add(className);
            log.info("集合创建成功: {}", className);
        }
        initializedClasses.add(className);
    }

    private boolean classExistsInSchema(String name) {
        if (existingClasses.contains(name)) {
            return true;
        }
        Result<Boolean> result = client.schema().exists().withClassName(name).run();
        boolean exists = Boolean.TRUE.equals(result.getResult());
        if (exists) {
            existingClasses.add(name);
        }
        return exists;
    }

    private List<Property> buildSchemaProperties() {
        List<Property> properties = new ArrayList<>();
        properties.add(Property.builder().name("text").dataType(List.of("text")).build());
        properties.add(Property.builder().name("fileName").dataType(List.of("text")).build());
        properties.add(Property.builder().name("fileType").dataType(List.of("text")).build());
        properties.add(Property.builder().name("chunkType").dataType(List.of("text")).build());
        properties.add(Property.builder().name("parentChunkId").dataType(List.of("text")).build());
        properties.add(Property.builder().name("pageNo").dataType(List.of("int")).build());
        properties.add(Property.builder().name("tableFlag").dataType(List.of("boolean")).build());
        properties.add(Property.builder().name("codeFlag").dataType(List.of("boolean")).build());
        properties.add(Property.builder().name("sourcePath").dataType(List.of("text")).build());
        properties.add(Property.builder().name("fileId").dataType(List.of("text")).build());
        properties.add(Property.builder().name("textHash").dataType(List.of("text")).build());
        properties.add(Property.builder().name("recordId").dataType(List.of("text")).build());
        properties.add(Property.builder().name("documentId").dataType(List.of("text")).build());
        properties.add(Property.builder().name("documentVersion").dataType(List.of("text")).build());
        properties.add(Property.builder().name("ownerId").dataType(List.of("int")).build());
        properties.add(Property.builder().name("roleIds").dataType(List.of("text")).build());
        properties.add(Property.builder().name("permissionTags").dataType(List.of("text")).build());
        properties.add(Property.builder().name("tenantId").dataType(List.of("int")).build());
        properties.add(Property.builder().name("kbId").dataType(List.of("int")).build());
        properties.add(Property.builder().name("metadata").dataType(List.of("text")).build());
        return properties;
    }

    // ==================== VectorStore 接口实现 ====================

    @Override
    public void add(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        initCollection();
        for (Document doc : documents) {
            String text = doc.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            Object textHash = doc.getMetadata().get("textHash");
            if (textHash != null && existsByTextHash(String.valueOf(textHash))) {
                continue;
            }
            float[] vector = embeddingModel.embed(text);
            Float[] vectorArray = new Float[vector.length];
            for (int i = 0; i < vector.length; i++) {
                vectorArray[i] = vector[i];
            }

            Map<String, Object> props = buildProperties(doc);
            Result<?> result = client.data().creator()
                    .withClassName(className)
                    .withID(doc.getId())
                    .withProperties(props)
                    .withVector(vectorArray)
                    .run();
            if (result.hasErrors()) {
                log.error("插入向量记录失败: {}", result.getError());
            }
        }
    }

    @Override
    public void delete(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        initCollection();
        for (String id : ids) {
            Result<Boolean> result = client.data().deleter().withClassName(className).withID(id).run();
            if (result.hasErrors()) {
                log.warn("按ID删除失败: {}, {}", id, result.getError());
            }
        }
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        initCollection();
        WhereFilter where = toWhere(filterExpression);
        Result<?> result = client.batch().objectsBatchDeleter()
                .withClassName(className)
                .withWhere(where)
                .withOutput("minimal")
                .run();
        if (result.hasErrors()) {
            log.error("按条件删除失败: {}", result.getError());
        }
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        initCollection();
        String query = request.getQuery();
        if (query == null || query.isBlank()) {
            return List.of();
        }
        float[] queryVector = embeddingModel.embed(query);
        Float[] vectorArray = new Float[queryVector.length];
        for (int i = 0; i < queryVector.length; i++) {
            vectorArray[i] = queryVector[i];
        }

        int limit = request.getTopK() <= 0 ? 5 : request.getTopK();
        Field[] fields = buildSearchFields();

        Get get = client.graphQL().get()
                .withClassName(className)
                .withFields(fields)
                .withNearVector(NearVectorArgument.builder().vector(vectorArray).build())
                .withLimit(limit);

        if (request.hasFilterExpression()) {
            get = get.withWhere(WhereArgument.builder()
                    .filter(toWhere(request.getFilterExpression())).build());
        }

        Result<GraphQLResponse> result = get.run();
        if (result.hasErrors()) {
            log.error("向量检索失败: {}", result.getError());
            return List.of();
        }
        return parseSearchResult(result.getResult());
    }

    // ==================== BM25 关键词检索 ====================

    /**
     * BM25 关键词检索，基于 Weaviate 原生 {@code bm25} 查询算子。
     * <p>
     * 使用 Weaviate client 4.6.0 的 {@link Bm25Argument} 构造 BM25 查询，
     * 对 {@code text} 字段进行关键词匹配，返回按 BM25 相关性得分排序的结果。
     * 元数据过滤、返回字段结构与向量检索完全对齐。
     * </p>
     *
     * @param query        检索关键词（空或 null 时返回空列表）
     * @param topK         返回条数（≤0 时回退默认值 5）
     * @param filterExpr   可选元数据过滤表达式（如按 fileId 过滤），null 表示不过滤
     * @return BM25 检索结果列表
     */
    public List<Document> bm25Search(String query, int topK, Filter.Expression filterExpr) {
        initCollection();
        if (query == null || query.isBlank()) {
            log.debug("BM25 检索查询为空，返回空列表");
            return List.of();
        }
        int limit = topK <= 0 ? 5 : topK;

        // 构建返回字段（与向量检索完全一致）
        Field[] fields = buildSearchFields();

        // 构建 BM25 查询：对 text 属性进行关键词匹配
        Bm25Argument bm25Arg = Bm25Argument.builder()
                .query(query)
                .properties(new String[]{"text"})
                .build();

        // 构建 GraphQL Get 查询
        Get get = client.graphQL().get()
                .withClassName(className)
                .withFields(fields)
                .withBm25(bm25Arg)
                .withLimit(limit);

        // 元数据过滤（复用现有的 toWhere 转换逻辑）
        if (filterExpr != null) {
            get = get.withWhere(WhereArgument.builder()
                    .filter(toWhere(filterExpr)).build());
        }

        Result<GraphQLResponse> result = get.run();
        if (result.hasErrors()) {
            log.error("BM25 检索失败: {}", result.getError());
            return List.of();
        }
        log.debug("BM25 检索完成，查询: {}, 返回 {} 条", query,
                parseSearchResult(result.getResult()).size());
        return parseSearchResult(result.getResult());
    }

    // ==================== 混合多路召回 ====================

    /**
     * 混合多路召回：向量检索 + BM25 检索，经融合策略排序后返回。
     * <p>
     * 两路各取 topK * 2 条候选，通过 {@link SearchFusion#fuse} 执行 RRF 或加权求和融合，
     * 最终截取 topK 条返回。支持降级：单路失败时自动回退为另一路结果。
     * </p>
     *
     * @param query        检索内容
     * @param topK         最终返回条数
     * @param filterExpr   可选元数据过滤表达式，null 表示不过滤
     * @param searchConfig 融合配置（指定融合模式、RRF-k、权重等）
     * @return 融合排序后的检索结果
     */
    public List<Document> hybridSearch(String query, int topK, Filter.Expression filterExpr,
                                       SearchConfig searchConfig) {
        initCollection();
        if (query == null || query.isBlank()) {
            log.debug("混合检索查询为空，降级为纯向量检索");
            return similaritySearch(SearchRequest.builder().query(query).topK(topK).build());
        }
        int limit = topK <= 0 ? 5 : topK;

        // 并行执行两路检索
        List<Document> vectorResults = null;
        List<Document> bm25Results = null;

        // 向量检索
        try {
            SearchRequest vecReq = SearchRequest.builder().query(query).topK(limit * 2).build();
            if (filterExpr != null) {
                vecReq = SearchRequest.builder().query(query).topK(limit * 2)
                        .filterExpression(filterExpr).build();
            }
            vectorResults = similaritySearch(vecReq);
        } catch (Exception e) {
            log.warn("混合检索：向量路失败，将降级使用 BM25 路结果: {}", e.getMessage());
        }

        // BM25 检索
        try {
            bm25Results = bm25Search(query, limit * 2, filterExpr);
        } catch (Exception e) {
            log.warn("混合检索：BM25 路失败，将降级使用向量路结果: {}", e.getMessage());
        }

        // 降级处理：单路失败时回退另一路
        if (vectorResults == null && bm25Results == null) {
            log.error("混合检索：两路均失败，返回空列表");
            return List.of();
        }
        if (vectorResults == null || vectorResults.isEmpty()) {
            log.info("混合检索：向量路无结果，回退 BM25 路结果");
            return limitResults(bm25Results, limit);
        }
        if (bm25Results == null || bm25Results.isEmpty()) {
            log.info("混合检索：BM25 路无结果，回退向量路结果");
            return limitResults(vectorResults, limit);
        }

        // 执行融合
        List<Document> fused = SearchFusion.fuse(vectorResults, bm25Results, limit, searchConfig);
        log.info("混合检索完成：向量路 {} 条, BM25 路 {} 条, 融合后 {} 条",
                vectorResults.size(), bm25Results.size(), fused.size());
        return fused;
    }

    /**
     * 多模式检索统一入口，根据 {@link SearchMode} 分发执行。
     * <p>
     * 这是适配器对外暴露的高层检索方法，Service 层可直接调用。
     * 兼容原有纯向量检索行为。
     * </p>
     *
     * @param query        检索内容
     * @param topK         返回条数
     * @param filterExpr   可选过滤表达式
     * @param searchConfig 检索配置（指定模式与参数）
     * @return 检索结果
     */
    public List<Document> searchByMode(String query, int topK, Filter.Expression filterExpr,
                                       SearchConfig searchConfig) {
        if (searchConfig == null || searchConfig.getSearchMode() == null) {
            searchConfig = SearchConfig.vectorOnly();
        }

        SearchMode mode = searchConfig.getSearchMode();
        log.debug("执行检索, mode={}, query={}, topK={}", mode, query, topK);

        return switch (mode) {
            case BM25_ONLY -> bm25Search(query, topK, filterExpr);
            case HYBRID -> hybridSearch(query, topK, filterExpr, searchConfig);
            default -> {
                // VECTOR_ONLY 或其他未知模式，走原有向量检索
                SearchRequest request = SearchRequest.builder()
                        .query(query)
                        .topK(topK <= 0 ? 5 : topK)
                        .build();
                if (filterExpr != null) {
                    request = SearchRequest.builder()
                            .query(query)
                            .topK(topK <= 0 ? 5 : topK)
                            .filterExpression(filterExpr)
                            .build();
                }
                yield similaritySearch(request);
            }
        };
    }

    // ==================== 去重工具 ====================

    /**
     * 构建检索返回字段数组，BM25 / 向量检索复用。
     * <p>包含文本内容、元数据、文件信息及 _additional 得分字段。</p>
     */
    private Field[] buildSearchFields() {
        return new Field[]{
                Field.builder().name("text").build(),
                Field.builder().name("fileName").build(),
                Field.builder().name("fileType").build(),
                Field.builder().name("chunkType").build(),
                Field.builder().name("fileId").build(),
                Field.builder().name("textHash").build(),
                Field.builder().name("recordId").build(),
                Field.builder().name("documentId").build(),
                Field.builder().name("documentVersion").build(),
                Field.builder().name("tenantId").build(),
                Field.builder().name("kbId").build(),
                Field.builder().name("metadata").build(),
                Field.builder().name("_additional")
                        .fields(Field.builder().name("id").build(),
                                Field.builder().name("certainty").build(),
                                Field.builder().name("distance").build())
                        .build()
        };
    }

    /**
     * 截取列表前 limit 条，不足时返回原列表。
     */
    private static List<Document> limitResults(List<Document> results, int limit) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        return results.size() <= limit ? results : results.subList(0, limit);
    }

    private boolean existsByTextHash(String textHash) {
        if (textHash == null || textHash.isBlank()) {
            return false;
        }
        WhereFilter where = WhereFilter.builder()
                .path(new String[]{"textHash"})
                .operator("Equal")
                .valueText(textHash)
                .build();
        WhereArgument whereArg = WhereArgument.builder().filter(where).build();
        Result<GraphQLResponse> result = client.graphQL().get()
                .withClassName(className)
                .withFields(Field.builder().name("textHash").build())
                .withWhere(whereArg)
                .withLimit(1)
                .run();
        if (result.hasErrors()) {
            return false;
        }
        return !parseSearchResult(result.getResult()).isEmpty();
    }

    // ==================== 属性 / 结果映射 ====================

    private Map<String, Object> buildProperties(Document doc) {
        Map<String, Object> props = new HashMap<>(doc.getMetadata());
        props.put("recordId", doc.getId());
        props.put("text", doc.getText());
        try {
            props.put("metadata", objectMapper.writeValueAsString(doc.getMetadata()));
        } catch (Exception e) {
            props.put("metadata", String.valueOf(doc.getMetadata()));
        }
        return props;
    }

    private List<Document> parseSearchResult(GraphQLResponse response) {
        List<Document> documents = new ArrayList<>();
        if (response == null) {
            return documents;
        }
        Object data = response.getData();
        if (!(data instanceof Map)) {
            return documents;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> dataMap = (Map<String, Object>) data;
        Object getSection = dataMap.get("Get");
        @SuppressWarnings("unchecked")
        Map<String, Object> getMap = (getSection instanceof Map) ? (Map<String, Object>) getSection : dataMap;
        Object itemsObj = getMap.get(className);
        if (!(itemsObj instanceof List)) {
            return documents;
        }
        for (Object itemObj : (List<?>) itemsObj) {
            if (!(itemObj instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) itemObj;
            String text = getStr(item, "text");
            String recordId = getStr(item, "recordId");
            String id = recordId;
            Double score = null;
            Object additional = item.get("_additional");
            if (additional instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> addl = (Map<String, Object>) additional;
                if (addl.get("id") != null) {
                    id = String.valueOf(addl.get("id"));
                }
                Double cert = getDouble(addl, "certainty");
                Double dist = getDouble(addl, "distance");
                if (cert != null) {
                    score = cert;
                } else if (dist != null) {
                    score = 1 - dist;
                }
            }
            Map<String, Object> metadata = new HashMap<>();
            for (Map.Entry<String, Object> e : item.entrySet()) {
                if ("_additional".equals(e.getKey()) || "text".equals(e.getKey())) {
                    continue;
                }
                metadata.put(e.getKey(), e.getValue());
            }
            String metaJson = getStr(item, "metadata");
            if (!metaJson.isBlank()) {
                try {
                    Map<String, Object> extra = objectMapper.readValue(metaJson,
                            new TypeReference<Map<String, Object>>() {});
                    metadata.putAll(extra);
                } catch (Exception ignored) {
                    // 忽略无法解析的元数据
                }
            }
            Document.Builder builder = Document.builder().id(id).text(text).metadata(metadata);
            if (score != null) {
                builder.score(score);
            }
            documents.add(builder.build());
        }
        return documents;
    }

    private static String getStr(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static Double getDouble(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return null;
    }

    // ==================== Filter 表达式转换 ====================

    private WhereFilter toWhere(Filter.Expression e) {
        Filter.ExpressionType type = e.type();
        var b = WhereFilter.builder();
        if (type == Filter.ExpressionType.AND || type == Filter.ExpressionType.OR
                || type == Filter.ExpressionType.NOT) {
            if (type == Filter.ExpressionType.NOT) {
                return b.operands(toWhere((Filter.Expression) e.left()))
                        .operator("Not").build();
            }
            String op = type == Filter.ExpressionType.AND ? "And" : "Or";
            return b.operands(toWhere((Filter.Expression) e.left()),
                            toWhere((Filter.Expression) e.right()))
                    .operator(op).build();
        }
        String key = ((Filter.Key) e.left()).key();
        Object value = ((Filter.Value) e.right()).value();
        b.path(new String[]{key});
        applyLeaf(b, type, value);
        return b.build();
    }

    private static void applyLeaf(WhereFilter.WhereFilterBuilder b,
                                  Filter.ExpressionType type, Object value) {
        switch (type) {
            case NE -> b.operator("NotEqual");
            case GT -> b.operator("GreaterThan");
            case GTE -> b.operator("GreaterThanEqual");
            case LT -> b.operator("LessThan");
            case LTE -> b.operator("LessThanEqual");
            default -> b.operator("Equal");
        }
        if (value instanceof Boolean bool) {
            b.valueBoolean(bool);
        } else if (value instanceof Number n) {
            if (n.doubleValue() == n.longValue() && Math.abs(n.longValue()) <= Integer.MAX_VALUE) {
                b.valueInt(n.intValue());
            } else {
                b.valueNumber(n.doubleValue());
            }
        } else {
            b.valueText(String.valueOf(value));
        }
    }
}
