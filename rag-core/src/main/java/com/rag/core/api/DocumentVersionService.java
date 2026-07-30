package com.rag.core.api;

import com.rag.core.entity.DocumentVersion;

import java.util.List;

/**
 * 文档版本服务接口（v2：documentId 改为 docId(Long)）
 */
public interface DocumentVersionService {

    /**
     * 生成下一个版本号
     * @param docId 文档ID
     * @param changeType 变更类型：INITIAL, MAJOR, MINOR, PATCH
     * @return 新版本号字符串
     */
    String generateNextVersion(Long docId, String changeType);

    /**
     * 创建版本记录
     */
    void createVersion(DocumentVersion version);

    /**
     * 获取文档当前生效版本
     */
    DocumentVersion getCurrentVersion(Long docId);

    /**
     * 获取文档所有历史版本
     */
    List<DocumentVersion> getVersionHistory(Long docId);

    /**
     * 版本回滚：将指定版本设为当前生效版本
     */
    void rollbackToVersion(Long docId, String targetVersion);

    /**
     * 版本对比：统计两个版本间分片差异
     * @return 差异统计 map（added, modified, deleted）
     */
    java.util.Map<String, Integer> compareVersions(Long docId, String versionA, String versionB);

    /**
     * 清理过期版本（保留最近N个）
     */
    int cleanExpiredVersions(Long docId, int keepCount);
}
