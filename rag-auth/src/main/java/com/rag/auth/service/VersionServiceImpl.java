package com.rag.auth.service;

import com.rag.auth.mapper.DocumentVersionMapper;
import com.rag.common.api.DocumentVersionService;
import com.rag.common.entity.DocumentVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 版本服务 MySQL 持久化实现（v2：documentId(String) → docId(Long)）
 */
public class VersionServiceImpl implements DocumentVersionService {

    private static final Logger log = LoggerFactory.getLogger(VersionServiceImpl.class);

    private final DocumentVersionMapper versionMapper;

    public VersionServiceImpl(DocumentVersionMapper versionMapper) {
        this.versionMapper = versionMapper;
    }

    @Override
    public String generateNextVersion(Long docId, String changeType) {
        DocumentVersion latest = versionMapper.findLatestVersion(docId);
        if (latest == null) {
            return "v1.0.0";
        }
        int[] parts = parseVersion(latest.getVersion());
        return switch (changeType) {
            case "MAJOR" -> String.format("v%d.0.0", parts[0] + 1);
            case "MINOR" -> String.format("v%d.%d.0", parts[0], parts[1] + 1);
            case "PATCH" -> String.format("v%d.%d.%d", parts[0], parts[1], parts[2] + 1);
            default -> String.format("v%d.%d.0", parts[0], parts[1] + 1);
        };
    }

    @Override
    public void createVersion(DocumentVersion version) {
        versionMapper.deactivateAll(version.getDocId());
        if (version.getCreateTime() == null) {
            version.setCreateTime(new Date());
        }
        version.setCurrentActive(true);
        versionMapper.insert(version);
        log.info("版本创建: doc={}, ver={}, chunks={}, type={}",
                version.getDocId(), version.getVersion(),
                version.getChunkCount(), version.getChangeType());
    }

    @Override
    public DocumentVersion getCurrentVersion(Long docId) {
        return versionMapper.findCurrentVersion(docId);
    }

    @Override
    public List<DocumentVersion> getVersionHistory(Long docId) {
        return versionMapper.findByDocumentId(docId);
    }

    @Override
    public void rollbackToVersion(Long docId, String targetVersion) {
        DocumentVersion target = versionMapper.findByDocIdAndVersion(docId, targetVersion);
        if (target == null) {
            throw new RuntimeException("文档无历史版本: " + docId);
        }
        versionMapper.deactivateAll(docId);
        versionMapper.activeVersion(docId, targetVersion);
        log.info("版本回滚: doc={}, ver={}", docId, targetVersion);
    }

    @Override
    public Map<String, Integer> compareVersions(Long docId, String versionA, String versionB) {
        log.info("版本对比: doc={}, {} vs {}", docId, versionA, versionB);
        Map<String, Integer> diff = new HashMap<>();
        diff.put("added", 0);
        diff.put("modified", 0);
        diff.put("deleted", 0);
        return diff;
    }

    @Override
    public int cleanExpiredVersions(Long docId, int keepCount) {
        return versionMapper.deleteOlderThan(docId, keepCount);
    }

    @Override
    public int deleteByDocId(Long docId) {
        int deleted = versionMapper.deleteByDocId(docId);
        log.info("版本记录已物理删除, docId={}, count={}", docId, deleted);
        return deleted;
    }

    private int[] parseVersion(String version) {
        try {
            String v = version.startsWith("v") ? version.substring(1) : version;
            String[] parts = v.split("\\.");
            return new int[]{
                    Integer.parseInt(parts[0]),
                    parts.length > 1 ? Integer.parseInt(parts[1]) : 0,
                    parts.length > 2 ? Integer.parseInt(parts[2]) : 0
            };
        } catch (Exception e) {
            return new int[]{1, 0, 0};
        }
    }
}
