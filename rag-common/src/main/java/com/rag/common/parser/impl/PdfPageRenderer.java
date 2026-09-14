package com.rag.common.parser.impl;

import com.rag.common.exception.RagException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 逐页渲染器（基于 Apache PDFBox 3.0.4）。
 * <p>
 * 适用场景：<b>文本层为空的扫描件 PDF</b>。此类文档整页即一张扫描图，Tika 抽不到任何文本层，
 * 若把整个 PDF 交给多模态模型一次性识别，会因单次请求过大导致识别质量下降甚至失败。
 * 本类负责把 PDF 逐页渲染为 PNG 字节，交由上层逐页调用 OCR。
 * </p>
 * <p>
 * 已知边界：本类不做「页级文本层判定」。若 PDF 为「部分页有文本层、部分页为扫描图」的混合体，
 * 上层按「整份文本层是否为空」做二分分流时会走场景 1，扫描页不会被本类处理
 * （属文档性质二分法的固有边界，见 work-log）。
 * </p>
 * <p>
 * 资源释放：PDFBox 渲染出的 {@link BufferedImage} 占用较多堆外/堆内存，
 * 每页写出 PNG 后立即 {@link BufferedImage#flush()} 释放，避免超长 PDF 造成 OOM。
 * </p>
 */
public class PdfPageRenderer {

    private static final Logger log = LoggerFactory.getLogger(PdfPageRenderer.class);

    /** 单份 PDF 渲染的最大页数（默认值，硬编码；超出部分跳过并告警，不中断整份解析） */
    public static final int DEFAULT_MAX_PAGES = 100;

    /**
     * 逐页渲染 DPI（默认值，硬编码）。
     * <p>
     * 取 120 的原因：A4@150dpi ≈ 1240×1754 px，PNG 约 1~3 MB，base64 后膨胀 33%，
     * 既占用大量 vision token 又易触发请求体大小限制；降至 120dpi 后 ≈ 992×1403 px，
     * PNG 约 0.7~1.5 MB，vision token 同步下降约 35%，而扫描件多为规则印刷体，120dpi 足够识别。
     * </p>
     */
    public static final float DEFAULT_RENDER_DPI = 120f;

    /** 渲染 DPI（120 可满足扫描件 OCR 清晰度，同时显著压缩单页图片体积与 vision token） */
    private final float renderDpi;

    /** 最大渲染页数，超出部分跳过 */
    private final int maxPages;

    /**
     * 使用默认参数构造（DPI=120，最多 100 页）。
     */
    public PdfPageRenderer() {
        this(DEFAULT_RENDER_DPI, DEFAULT_MAX_PAGES);
    }

    /**
     * @param renderDpi 渲染 DPI（&lt;=0 时回落 120）
     * @param maxPages  最大渲染页数（&lt;=0 时回落 100）
     */
    public PdfPageRenderer(float renderDpi, int maxPages) {
        this.renderDpi = renderDpi > 0 ? renderDpi : DEFAULT_RENDER_DPI;
        this.maxPages = maxPages > 0 ? maxPages : DEFAULT_MAX_PAGES;
    }

    /**
     * 将 PDF 逐页渲染为 PNG 字节。
     * <p>
     * 容错策略：
     * <ul>
     *     <li>打开失败 → 抛 {@link RagException}（属于文件级错误，无法继续）</li>
     *     <li>单页渲染失败 → WARN 跳过，不中断整份</li>
     *     <li>页数超过 {@link #maxPages} → 跳过剩余页并 WARN</li>
     * </ul>
     *
     * @param file PDF 文件
     * @return 每页一条记录（页序从 0 开始），按页序升序；可能为空列表
     */
    public List<PageImage> render(File file) {
        List<PageImage> pages = new ArrayList<>();

        // PDFBox 3.x：PDDocument 实现 Closeable，用 try-with-resources 确保句柄释放
        try (PDDocument document = Loader.loadPDF(file)) {
            int totalPages = document.getNumberOfPages();
            PDFRenderer renderer = new PDFRenderer(document);

            int renderCount = Math.min(totalPages, maxPages);
            if (totalPages > maxPages) {
                log.warn("PDF 页数 {} 超过上限 {}，仅渲染前 {} 页: {}",
                        totalPages, maxPages, maxPages, file.getName());
            }

            for (int pageIndex = 0; pageIndex < renderCount; pageIndex++) {
                BufferedImage image = null;
                try {
                    // 逐页渲染为 RGB 位图（扫描件无透明通道，RGB 体积更小）
                    image = renderer.renderImageWithDPI(pageIndex, renderDpi, ImageType.RGB);
                    byte[] pngBytes = toPng(image);
                    pages.add(new PageImage(pageIndex, pngBytes));
                    log.debug("PDF 页面渲染成功: page={}/{}, dpi={}, size={}B, file={}",
                            pageIndex + 1, totalPages, renderDpi, pngBytes.length, file.getName());
                } catch (Exception e) {
                    // 单页失败不影响其余页
                    log.warn("PDF 页面渲染失败并跳过 (page={}, file={}): {}",
                            pageIndex, file.getName(), e.getMessage());
                } finally {
                    if (image != null) {
                        // 释放位图占用的内存（PDFBox 渲染结果可能占用大量堆内存）
                        image.flush();
                    }
                }
            }

            log.info("PDF 逐页渲染完成: {}, 总页数={}, 实际渲染={} 页, dpi={}",
                    file.getName(), totalPages, pages.size(), renderDpi);
        } catch (IOException e) {
            throw new RagException("RAG_PARSE_PDF_RENDER", "PDF 渲染失败: " + e.getMessage(), e);
        }

        return pages;
    }

    /**
     * BufferedImage → PNG 字节。
     */
    private byte[] toPng(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        }
    }

    /**
     * 单页渲染结果。
     */
    public static class PageImage {
        /** 页序（0-based） */
        private final int pageIndex;
        /** 该页 PNG 字节 */
        private final byte[] pngBytes;

        public PageImage(int pageIndex, byte[] pngBytes) {
            this.pageIndex = pageIndex;
            this.pngBytes = pngBytes;
        }

        public int getPageIndex() {
            return pageIndex;
        }

        public byte[] getPngBytes() {
            return pngBytes;
        }
    }
}
