package com.rag.parser.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.rag.core.exception.RagException;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * DeepSeek-OCR 远程客户端（OpenAI 兼容协议）。
 * <p>
 * 通过部署在 vLLM / one-api 等 OpenAI 兼容服务的多模态端点做 OCR：
 * POST {@code {baseUrl}/chat/completions} + Bearer 鉴权，图片/文档以 base64 data URL 传入。
 * 不在本地安装任何渲染/解析库：文档直接以字节流交由远程多模态模型渲染并识别。
 */
public class DeepSeekOcrClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekOcrClient.class);

    private final String apiKey;
    /** OpenAI 兼容端点 Base URL，例如 http://host:8000/v1 */
    private final String baseUrl;
    /** 远程部署的模型名，例如 deepseek-ocr */
    private final String model;
    private final long timeoutMs;

    private final OkHttpClient httpClient;

    public DeepSeekOcrClient(String apiKey, String baseUrl, String model, long timeoutMs) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.timeoutMs = timeoutMs;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * 对图片或文档做 OCR。
     *
     * @param content  文件/图片二进制
     * @param mimeType 媒体类型，例如 image/png、application/pdf
     * @return 识别出的完整文本
     */
    public String ocr(byte[] content, String mimeType) {
        String dataUrl = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(content);
        String prompt = "You are a precise OCR engine. Extract ALL text from the provided image or document, "
                + "preserving the reading order and layout as much as possible. "
                + "Output only the extracted text, without any commentary.";

        // user 消息：文本提示 + 图片/文档数据
        JSONObject imagePart = new JSONObject();
        imagePart.set("type", "image_url");
        JSONObject imageUrl = new JSONObject();
        imageUrl.set("url", dataUrl);
        imagePart.set("image_url", imageUrl);

        JSONObject textPart = new JSONObject();
        textPart.set("type", "text");
        textPart.set("text", prompt);

        JSONObject userMsg = new JSONObject();
        userMsg.set("role", "user");
        JSONArray userContent = new JSONArray();
        userContent.add(textPart);
        userContent.add(imagePart);
        userMsg.set("content", userContent);

        JSONObject systemMsg = new JSONObject();
        systemMsg.set("role", "system");
        systemMsg.set("content", "You are a professional OCR assistant.");

        JSONObject reqBody = new JSONObject();
        reqBody.set("model", model);
        JSONArray messages = new JSONArray();
        messages.add(systemMsg);
        messages.add(userMsg);
        reqBody.set("messages", messages);
        reqBody.set("temperature", 0);
        reqBody.set("max_tokens", 4096);

        String url = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(reqBody.toString(), MediaType.parse("application/json")))
                .build();

        try (Response resp = httpClient.newCall(request).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new RagException("RAG_DS_OCR", "DeepSeek-OCR 调用失败，HTTP 状态码: " + resp.code());
            }
            JSONObject json = JSONUtil.parseObj(resp.body().string());
            if (json.containsKey("error")) {
                JSONObject err = json.getJSONObject("error");
                throw new RagException("RAG_DS_OCR", "DeepSeek-OCR 错误: " + err.getStr("message", err.toString()));
            }
            JSONArray choices = json.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new RagException("RAG_DS_OCR", "DeepSeek-OCR 返回空结果");
            }
            JSONObject choice = choices.getJSONObject(0);
            JSONObject msg = choice.getJSONObject("message");
            return msg.getStr("content", "").trim();
        } catch (IOException e) {
            throw new RagException("RAG_DS_OCR", "DeepSeek-OCR 网络异常: " + e.getMessage(), e);
        }
    }
}
