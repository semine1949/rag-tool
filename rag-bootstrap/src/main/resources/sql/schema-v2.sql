-- ============================================================================
-- RAG Tool - MySQL 建表脚本 v2（重构版）
-- 重构时间：2026-07-30
--
-- 重构要点：
--   1. 分片策略配置从知识库下移到文档维度，支持同一知识库不同文档采用不同切分策略
--   2. 向量化模型配置归属知识库维度，保障向量空间一致性
--   3. 新增 doc_chunk 分片数据表，持久化分片原文及向量元数据映射
--   4. 修复 doc_version.document_id 字段类型与 kb_document.doc_id 不一致的问题
--   5. 在文档维度补充全流程处理状态机（process_status）
--   6. 完整保留原有多租户架构、用户体系、角色字典与 RBAC 权限体系
-- ============================================================================

CREATE DATABASE IF NOT EXISTS rag_tool DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE rag_tool;

-- ==================== 租户表 ====================
DROP TABLE IF EXISTS sys_tenant;
CREATE TABLE sys_tenant (
    tenant_id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '租户ID',
    tenant_name           VARCHAR(128) NOT NULL COMMENT '租户名称',
    default_embedding_model VARCHAR(128) DEFAULT NULL COMMENT '租户默认 Embedding 模型名',
    status                TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1=正常, 0=禁用',
    create_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户表';

-- ==================== 用户表 ====================
-- 用户为全局主体，不含 tenant_id 外键；租户归属由 user_tenant_role 关联表承载
DROP TABLE IF EXISTS sys_user;
CREATE TABLE sys_user (
    user_id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    username              VARCHAR(64)  NOT NULL COMMENT '用户名（全局唯一）',
    password_hash         VARCHAR(255) NOT NULL COMMENT 'BCrypt密码哈希',
    nickname              VARCHAR(64)  DEFAULT NULL COMMENT '昵称',
    status                TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1=正常, 0=禁用, -1=锁定',
    login_fail_count      INT          NOT NULL DEFAULT 0 COMMENT '登录失败次数',
    last_login_time       DATETIME     DEFAULT NULL COMMENT '最后登录时间',
    create_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

-- ==================== 角色字典表 ====================
DROP TABLE IF EXISTS sys_role;
CREATE TABLE sys_role (
    role_id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '角色ID',
    role_code             VARCHAR(64)  NOT NULL COMMENT '角色编码：TENANT_ADMIN/KB_ADMIN/CONTRIBUTOR/VIEWER',
    role_name             VARCHAR(64)  NOT NULL COMMENT '角色名称',
    description           VARCHAR(255) DEFAULT NULL COMMENT '角色描述',
    status                TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1=启用, 0=禁用',
    create_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (role_id),
    UNIQUE KEY uk_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色字典表';

-- ==================== 用户-租户-角色关联表 ====================
DROP TABLE IF EXISTS user_tenant_role;
CREATE TABLE user_tenant_role (
    id                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id               BIGINT       NOT NULL COMMENT '用户ID',
    tenant_id             BIGINT       NOT NULL COMMENT '租户ID',
    role_id               BIGINT       NOT NULL COMMENT '角色ID（sys_role）',
    create_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_tenant (user_id, tenant_id),
    KEY idx_tenant_id (tenant_id),
    KEY idx_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户租户角色关联表';

-- ==================== 知识库表 ====================
-- [v2 变更] 移除了 chunk_strategy / chunk_size / chunk_overlap 字段，
--           分片策略配置下沉到文档维度（kb_document），支持单知识库内差异化的文档分片策略。
-- [v2 保留] embedding_model 仍归属知识库，同一知识库内所有文档使用相同向量化模型。
DROP TABLE IF EXISTS kb_knowledge_base;
CREATE TABLE kb_knowledge_base (
    kb_id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '知识库ID',
    tenant_id             BIGINT       NOT NULL COMMENT '所属租户ID',
    kb_name               VARCHAR(128) NOT NULL COMMENT '知识库名称',
    description           VARCHAR(512) DEFAULT NULL COMMENT '知识库描述',
    embedding_model       VARCHAR(128) DEFAULT NULL COMMENT 'Embedding模型名（如 text-embedding-3-small），同一知识库内统一',
    status                TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1=正常, 0=禁用',
    create_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (kb_id),
    KEY idx_tenant_id (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库表（v2：分片策略已下沉至文档）';

-- ==================== 知识库角色权限表 ====================
DROP TABLE IF EXISTS kb_role_permission;
CREATE TABLE kb_role_permission (
    id                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    kb_id                 BIGINT       NOT NULL COMMENT '知识库ID',
    role_id               BIGINT       NOT NULL COMMENT '角色ID（sys_role）',
    create_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_role (kb_id, role_id),
    KEY idx_kb_id (kb_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库角色权限表';

-- ==================== 知识库文档登记表（v2 重构，v3 分片策略下沉） ====================
-- [v3 变更] 分片策略进一步下沉到分片维度（doc_chunk）：
--           删除 chunk_strategy / chunk_size / chunk_overlap 三列，
--           每个分片的分片策略与参数快照记录在 doc_chunk（chunk_mode + params_snapshot）。
-- [v2 新增] process_status：文档全流程处理状态机，
--           覆盖上传→解析→分块→向量化→入库/失败的完整生命周期。
DROP TABLE IF EXISTS kb_document;
CREATE TABLE kb_document (
    doc_id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '文档ID',
    kb_id                 BIGINT       NOT NULL COMMENT '所属知识库ID',
    tenant_id             BIGINT       NOT NULL COMMENT '所属租户ID',
    file_name             VARCHAR(512) NOT NULL COMMENT '文件名',
    file_type             VARCHAR(32)  DEFAULT NULL COMMENT '文件类型（扩展名，如 pdf/docx/txt）',
    file_size             BIGINT       DEFAULT 0 COMMENT '文件大小（字节）',

    -- 文档处理状态机（v2 新增）
    process_status        VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT '处理状态：PENDING=待处理, PARSING=解析中, PARSED=解析完成, CHUNKING=分块中, CHUNKED=分块完成, VECTORIZING=向量化中, COMPLETED=已完成, FAILED=处理失败',

    -- 版本与元数据
    chunk_count           INT          NOT NULL DEFAULT 0 COMMENT '分片总数',
    version               VARCHAR(32)  DEFAULT NULL COMMENT '当前生效版本号',
    owner_id              BIGINT       NOT NULL COMMENT '上传者/所有者ID',
    collection_name       VARCHAR(128) DEFAULT NULL COMMENT 'Weaviate集合名 T{tenantId}_Kb{kbId}',
    upload_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    create_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (doc_id),
    KEY idx_kb_id (kb_id),
    KEY idx_tenant_id (tenant_id),
    KEY idx_process_status (process_status),
    KEY idx_owner_id (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库文档登记表（v3：分片策略已下沉至分片维度 doc_chunk）';

-- ==================== 文档版本表（v2 修复关联一致性） ====================
-- [v2 修复] 将 document_id（VARCHAR(128)）改为 doc_id（BIGINT），
--           与 kb_document.doc_id 字段类型一致，消除因类型不匹配导致的
--           索引失效与关联查询性能下降问题。
-- [v2 新增] idx_doc_id 索引，支持按文档ID高效查询版本列表。
DROP TABLE IF EXISTS doc_version;
CREATE TABLE doc_version (
    id                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    doc_id                BIGINT       NOT NULL COMMENT '文档ID（关联 kb_document.doc_id）',
    tenant_id             BIGINT       NOT NULL DEFAULT 1 COMMENT '所属租户ID',
    kb_id                 BIGINT       NOT NULL DEFAULT 0 COMMENT '所属知识库ID',
    version               VARCHAR(32)  NOT NULL COMMENT '语义化版本号（v1.0.0）',
    chunk_count           INT          NOT NULL DEFAULT 0 COMMENT '本版本的分片总数',
    operator_id           BIGINT       DEFAULT NULL COMMENT '操作人ID',
    change_type           VARCHAR(32)  NOT NULL COMMENT '变更类型：INITIAL/MAJOR/MINOR/PATCH',
    change_remark         VARCHAR(512) DEFAULT NULL COMMENT '变更说明',
    collection_name       VARCHAR(128) DEFAULT NULL COMMENT '本版本对应的 Weaviate 集合名',
    current_active        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否当前生效版本（1=是, 0=否）',
    create_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_doc_id (doc_id),
    KEY idx_document_version (doc_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档版本表（v2：doc_id 改为 BIGINT，与 kb_document 保持一致）';

-- ==================== 文档分片表（v2 新增，v3 分片策略下沉） ====================
-- 作为业务库与向量数据库之间的元数据映射桥梁，持久化全部分片原文。
-- 核心职责：
--   1. 持久化分片原文，支撑内容检索、溯源与上下文补全
--   2. 记录分片在文档内的顺序与层级关系（parent_chunk_id）
--   3. 承载向量数据库对象 ID（vector_id），实现业务ID→向量ID的双向映射
--   4. 记录分片的父子类型（chunk_type），区分层级分片与扁平分片
-- [v3 新增] 分片策略下沉到分片维度：
--   chunk_mode        记录该分片实际采用的分片策略名（如 FIXED_SIZE / TEXT_MODEL / HIERARCHICAL_MODEL）
--   params_snapshot   以 JSON 形式记录该分片实际生效的分块参数快照
DROP TABLE IF EXISTS doc_chunk;
CREATE TABLE doc_chunk (
    chunk_id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '分片ID（主键）',
    doc_id                BIGINT       NOT NULL COMMENT '所属文档ID（关联 kb_document.doc_id）',
    tenant_id             BIGINT       NOT NULL COMMENT '所属租户ID',
    kb_id                 BIGINT       NOT NULL COMMENT '所属知识库ID',
    version_id            BIGINT       DEFAULT NULL COMMENT '关联版本ID（doc_version.id），可为空以支持未版本化场景',

    -- 分片定位
    chunk_index           INT          NOT NULL COMMENT '分片序号（从0开始，记录分片在文档中的顺序）',
    chunk_type            VARCHAR(32)  NOT NULL DEFAULT 'flat' COMMENT '父子分片类型：parent=父分片（可包含子分片）, child=子分片（从属于某父分片）, flat=扁平分片（非层级策略下的独立分片）',
    parent_chunk_id       BIGINT       DEFAULT NULL COMMENT '父分片ID（自引用，用于 PARENT_CHILD 分片策略的层级关系）。值为 NULL 时：chunk_type=flat 表示独立分片，chunk_type=parent 表示顶级父分片',

    -- 分片内容（v2 核心：完整持久化分片原文）
    content               LONGTEXT     NOT NULL COMMENT '分片原文（完整持久化，不依赖向量数据库存储原文）',

    -- 向量映射
    vector_id             VARCHAR(128) DEFAULT NULL COMMENT '向量数据库中的对象 ID（如 Weaviate object ID），用于业务与向量间的双向映射',

    -- 分片策略与参数快照（v3 新增：分片策略下沉到分片维度）
    chunk_mode            VARCHAR(64)  NOT NULL DEFAULT 'FIXED_SIZE' COMMENT '分片策略名：FIXED_SIZE/SEMANTIC/TABLE/CODE_FUNCTION/TITLE_HIERARCHY/PARENT_CHILD/TEXT_MODEL/HIERARCHICAL_MODEL',
    params_snapshot       TEXT         DEFAULT NULL COMMENT '分片参数快照（JSON）：记录该分片实际生效的分块参数，如固定分片大小/重叠窗口、text_model 的 delimiter/maxTokens/chunkOverlap、hierarchical_model 的父子块参数',

    -- 时间戳
    create_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (chunk_id),
    KEY idx_doc_id (doc_id),
    KEY idx_kb_id (kb_id),
    KEY idx_tenant_id (tenant_id),
    KEY idx_version_id (version_id),
    KEY idx_doc_chunk_order (doc_id, chunk_index),
    KEY idx_vector_id (vector_id),
    KEY idx_parent_chunk_id (parent_chunk_id),
    KEY idx_chunk_mode (chunk_mode)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档分片表（v3：分片策略与参数快照下沉至分片维度）';

-- ==================== 角色字典种子数据 ====================
INSERT INTO sys_role (role_id, role_code, role_name, description, status)
VALUES (1, 'TENANT_ADMIN', '租户管理员', '管理本租户知识库、任命知识库管理员、查看全部资源', 1),
       (2, 'KB_ADMIN', '知识库管理员', '管理指定知识库配置与成员', 1),
       (3, 'CONTRIBUTOR', '知识库编辑者', '可上传文档，不可修改配置', 1),
       (4, 'VIEWER', '知识库查看者', '仅查看文档与检索', 1)
ON DUPLICATE KEY UPDATE role_name = VALUES(role_name), description = VALUES(description);

-- ==================== v2 核心变更说明 ====================
-- 1. 【分片策略按文档独立配置】
--    kb_document 新增 chunk_strategy / chunk_size / chunk_overlap 字段，
--    kb_knowledge_base 移除对应字段。同一知识库下不同文档可配置不同切分策略。
--
-- 2. 【向量化模型按知识库统一】
--    kb_knowledge_base.embedding_model 保留不变，同一知识库内所有文档使用相同 Embedding 模型。
--
-- 3. 【分片原文持久化存储（doc_chunk 新增）】
--    新建 doc_chunk 表，持久化所有分片的完整原文（content LONGTEXT），
--    不依赖向量数据库存储原文。同时承载排序（chunk_index）、层级关系（parent_chunk_id）、
--    向量映射（vector_id）等核心元数据。
--
-- 4. 【版本表关联一致性修复】
--    doc_version.document_id（VARCHAR(128)）→ doc_id（BIGINT），
--    与 kb_document.doc_id 类型一致，消除关联鸿沟。
--
-- 5. 【文档处理状态机】
--    kb_document 新增 process_status，覆盖文档从上传到入库的完整生命周期：
--    PENDING → PARSING → PARSED → CHUNKING → CHUNKED → VECTORIZING → COMPLETED
--                                                                    → FAILED（任意阶段可进入）
--
-- 6. 默认租户（tenant_id=1）+ 管理员 admin 由 AuthServiceImpl.initDefaultAdmin()
--    在首次启动时自动创建，保持与 v1 一致的初始化行为。
--
-- ==================== v3 核心变更说明 ====================
-- 7. 【分片策略下沉到分片维度】
--    kb_document 移除 chunk_strategy / chunk_size / chunk_overlap 三列，
--    文档登记表不再持久化分片策略；改为在 doc_chunk 记录每个分片的：
--      chunk_mode        -- 分片策略名（FIXED_SIZE / TEXT_MODEL / HIERARCHICAL_MODEL 等）
--      params_snapshot   -- 参数快照（JSON），记录该分片实际生效的分块参数
--    同一文档内不同分片可携带各自的分片策略与参数溯源信息，便于检索结果溯源与策略复盘。
-- ============================================================================
