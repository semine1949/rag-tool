package com.rag.auth.service;

import com.rag.auth.mapper.DocumentVersionMapper;
import com.rag.core.api.DocumentVersionService;
import com.rag.core.entity.DocumentVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 版本服务 MySQL 持久化实现
 */
public class VersionServiceImpl implements DocumentVersionService {

    private static final Logger log = LoggerFactory.getLogger(VersionServiceImpl.class);

    private final DocumentVersionMapper versionMapper;

    public VersionServiceImpl(DocumentVersionMapper versionMapper) {
        this.versionMapper = versionMapper;
    }

    @Override
    public String generateNextVersion(String documentId, String changeType) {
        DocumentVersion latest = versionMapper.findLatestVersion(documentId);
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
        versionMapper.deactivateAll(version.getDocumentId());
        if (version.getCreateTime() == null) {
            version.setCreateTime(new Date());
        }
        version.setCurrentActive(true);
        versionMapper.insert(version);
        log.info("版本创建: doc={}, ver={}, chunks={}, type={}",
                version.getDocumentId(), version.getVersion(),
                version.getChunkCount(), version.getChangeType());
    }

    @Override
    public DocumentVersion getCurrentVersion(String documentId) {
        return versionMapper.findCurrentVersion(documentId);
    }

    @Override
    public List<DocumentVersion> getVersionHistory(String documentId) {
        return versionMapper.findByDocumentId(documentId);
    }

    @Override
    public void rollbackToVersion(String documentId, String targetVersion) {
        DocumentVersion target = versionMapper.findByDocIdAndVersion(documentId, targetVersion);
        if (target == null) {
            throw new RuntimeException("文档无历史版本: " + documentId);
        }
        versionMapper.deactivateAll(documentId);
        versionMapper.activeVersion(documentId, targetVersion);
        log.info("版本回滚: doc={}, ver={}", documentId, targetVersion);
    }

    @Override
    public Map<String, Integer> compareVersions(String documentId, String versionA, String versionB) {
        log.info("版本对比: doc={}, {} vs {}", documentId, versionA, versionB);
        Map<String, Integer> diff = new HashMap<>();
        diff.put("added", 0);
        diff.put("modified", 0);
        diff.put("deleted", 0);
        return diff;
    }

    @Override
    public int cleanExpiredVersions(String documentId, int keepCount) {
        return versionMapper.deleteOlderThan(documentId, keepCount);
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
