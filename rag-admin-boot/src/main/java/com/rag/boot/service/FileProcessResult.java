package com.rag.boot.service;

/**
 * 文件处理结果
 */
public class FileProcessResult {
    private String fileId;
    private String fileName;
    private Integer chunkCount;
    private Integer insertedCount;
    private Boolean success;
    private String error;

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String fileId;
        private String fileName;
        private Integer chunkCount;
        private Integer insertedCount;
        private Boolean success;
        private String error;

        public Builder fileId(String fileId) { this.fileId = fileId; return this; }
        public Builder fileName(String fileName) { this.fileName = fileName; return this; }
        public Builder chunkCount(Integer chunkCount) { this.chunkCount = chunkCount; return this; }
        public Builder insertedCount(Integer insertedCount) { this.insertedCount = insertedCount; return this; }
        public Builder success(Boolean success) { this.success = success; return this; }
        public Builder error(String error) { this.error = error; return this; }

        public FileProcessResult build() {
            FileProcessResult r = new FileProcessResult();
            r.fileId = this.fileId;
            r.fileName = this.fileName;
            r.chunkCount = this.chunkCount;
            r.insertedCount = this.insertedCount;
            r.success = this.success;
            r.error = this.error;
            return r;
        }
    }

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }

    public Integer getInsertedCount() { return insertedCount; }
    public void setInsertedCount(Integer insertedCount) { this.insertedCount = insertedCount; }

    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
