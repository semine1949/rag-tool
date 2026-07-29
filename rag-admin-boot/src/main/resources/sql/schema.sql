-- ============================================================================
-- RAG Tool - MySQL 建表与种子数据脚本 (新数据库设计)
-- 使用方式：mysql -u<user> -p < schema.sql
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
-- 表示用户在某一租户内的单一身份（角色）
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
DROP TABLE IF EXISTS kb_knowledge_base;
CREATE TABLE kb_knowledge_base (
    kb_id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '知识库ID',
    tenant_id             BIGINT       NOT NULL COMMENT '所属租户ID',
    kb_name               VARCHAR(128) NOT NULL COMMENT '知识库名称',
    description           VARCHAR(512) DEFAULT NULL COMMENT '知识库描述',
    chunk_strategy        VARCHAR(64)  DEFAULT 'FIXED_SIZE' COMMENT '分片策略：FIXED_SIZE/SEMANTIC/TABLE/CODE_FUNCTION/TITLE_HIERARCHY/PARENT_CHILD',
    chunk_size            INT          DEFAULT 500 COMMENT '分片大小（字符数）',
    chunk_overlap         INT          DEFAULT 50 COMMENT '分片重叠窗口（字符数）',
    embedding_model       VARCHAR(128) DEFAULT NULL COMMENT 'Embedding模型名（如 text-embedding-3-small）',
    status                TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1=正常, 0=禁用',
    create_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (kb_id),
    KEY idx_tenant_id (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库表';

-- ==================== 知识库角色权限表 ====================
-- 表示哪些角色（role_code）可访问该知识库
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

-- ==================== 知识库文档表 ====================
DROP TABLE IF EXISTS kb_document;
CREATE TABLE kb_document (
    doc_id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '文档ID',
    kb_id                 BIGINT       NOT NULL COMMENT '所属知识库ID',
    tenant_id             BIGINT       NOT NULL COMMENT '所属租户ID',
    file_name             VARCHAR(512) NOT NULL COMMENT '文件名',
    file_type             VARCHAR(32)  DEFAULT NULL COMMENT '文件类型',
    chunk_count           INT          NOT NULL DEFAULT 0 COMMENT '分片总数',
    version               VARCHAR(32)  DEFAULT NULL COMMENT '当前生效版本号',
    owner_id              BIGINT       NOT NULL COMMENT '上传者/所有者ID',
    collection_name       VARCHAR(128) DEFAULT NULL COMMENT 'Weaviate集合名 T{tenantId}_Kb{kbId}',
    upload_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    PRIMARY KEY (doc_id),
    KEY idx_kb_id (kb_id),
    KEY idx_tenant_id (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库文档登记表';

-- ==================== 文档版本表 ====================
DROP TABLE IF EXISTS doc_version;
CREATE TABLE doc_version (
    id                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    document_id           VARCHAR(128) NOT NULL COMMENT '文档唯一标识',
    tenant_id             BIGINT       NOT NULL DEFAULT 1 COMMENT '所属租户ID',
    kb_id                 BIGINT       NOT NULL DEFAULT 0 COMMENT '所属知识库ID',
    version               VARCHAR(32)  NOT NULL COMMENT '语义化版本号（v1.0.0）',
    chunk_count           INT          NOT NULL DEFAULT 0 COMMENT '分片总数',
    operator_id           BIGINT       DEFAULT NULL COMMENT '操作人ID',
    change_type           VARCHAR(32)  NOT NULL COMMENT '变更类型：INITIAL/MAJOR/MINOR/PATCH',
    change_remark         VARCHAR(512) DEFAULT NULL COMMENT '变更说明',
    collection_name       VARCHAR(128) DEFAULT NULL COMMENT '关联集合名称',
    current_active        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否当前生效版本',
    create_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_document_id (document_id),
    KEY idx_document_version (document_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档版本表';

-- ==================== 角色字典种子数据 ====================
INSERT INTO sys_role (role_id, role_code, role_name, description, status)
VALUES (1, 'TENANT_ADMIN', '租户管理员', '管理本租户知识库、任命知识库管理员、查看全部资源', 1),
       (2, 'KB_ADMIN', '知识库管理员', '管理指定知识库配置与成员', 1),
       (3, 'CONTRIBUTOR', '知识库编辑者', '可上传文档，不可修改配置', 1),
       (4, 'VIEWER', '知识库查看者', '仅查看文档与检索', 1)
ON DUPLICATE KEY UPDATE role_name = VALUES(role_name), description = VALUES(description);

-- ==================== 说明 ====================
-- 默认租户(tenant_id=1) + 管理员admin(通过 user_tenant_role 赋予 TENANT_ADMIN)
-- 由 AuthServiceImpl.initDefaultAdmin() 在首次启动时自动创建。
-- 权限模型完全基于 user_tenant_role（用户在租户的角色）
-- + kb_role_permission（知识库允许的角色），不再依赖 sys_user 上的 tenant 字段。
