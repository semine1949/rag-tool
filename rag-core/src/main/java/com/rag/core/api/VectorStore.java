package com.rag.core.api;

import com.rag.core.config.WeaviateCollectionConfig;
import com.rag.core.entity.VectorRecord;

import java.util.List;
import java.util.Map;

/**
 * 向量存储顶层接口
 */
public interface VectorStore {

    /**
     * 初始化集合/类（自动创建schema）
     * @param config 集合配置
     */
    void initCollection(WeaviateCollectionConfig config);

    /**
     * 批量写入向量记录
     * @param records 向量记录列表
     * @param config 集合配置
     * @return 成功写入的数量
     */
    int batchInsert(List<VectorRecord> records, WeaviateCollectionConfig config);

    /**
     * 按文件ID删除旧分片
     * @param fileId 文件唯一标识
     * @param className 集合名称
     * @return 删除记录数
     */
    int deleteByFileId(String fileId, String className);

    /**
     * 检查分片文本是否已存在（去重）
     * @param textHash 文本哈希
     * @param className 集合名称
     * @return 是否存在
     */
    boolean existsByTextHash(String textHash, String className);

    /**
     * 向量检索
     * @param queryVector 查询向量
     * @param limit 返回条数
     * @param filter 元数据过滤条件
     * @param className 集合名称
     * @return 检索结果
     */
    List<VectorRecord> search(List<Float> queryVector, int limit, Map<String, Object> filter, String className);

    /**
     * 删除整个集合
     * @param className 集合名称
     */
    void dropCollection(String className);

    /**
     * 清空集合数据
     * @param className 集合名称
     */
    void clearCollection(String className);
}
