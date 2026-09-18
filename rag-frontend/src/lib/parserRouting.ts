/**
 * 文档解析器（解析模型）路由规则。
 *
 * 与后端 `DocumentParseFactory` 的两级路由保持一致：
 *   优先级 1：显式指定 modelName（本模块由用户在下拉框中手动选择）
 *   优先级 2：按扩展名自动匹配（本模块 `resolveParserByFileName`）
 *
 * 后端扩展名 → 解析器现状（见 RagCoreConfig.documentParseFactory）：
 *   - png/jpg/jpeg/bmp  → DeepSeekOcrParser（纯图片多模态 OCR）
 *   - pdf               → MinerUParser（受 rag.parser.pdf-provider 控制，默认 mineru）
 *   - xls/xlsx          → ExcelParser（智能表格）
 *   - txt/md/html 等文本 → TikaDocumentReader（纯文本抽取）
 *   - doc/docx/ppt/pptx → TikaOcrMixedParser（图文混排）
 */

/** 前端可选的解析模型（对应后端 modelName 传参） */
export type ParserModel =
  /** 不传 modelName：由后端按扩展名 + 配置自动路由 */
  | 'auto'
  /** MinerU 官方 API：版面/表格/公式还原，产出 Markdown */
  | 'minerU'
  /** 图文混排：Tika 文本抽取 + 内嵌图片多模态 OCR */
  | 'tika-mixed'
  /** 纯图片多模态 OCR */
  | 'deepseekocr'
  /** Excel 智能表格解析 */
  | 'excelparser';

/** 解析模型展示元信息 */
export const PARSER_MODEL_META: Record<
  ParserModel,
  { label: string; desc: string; /** 需真正下发给后端的 modelName；auto 为 null 表示不下发 */ wireValue: string | null }
> = {
  auto: {
    label: '自动识别',
    desc: '由后端按文件扩展名自动选择解析器（PNG→DeepSeekOCR，PDF→MinerU，表格→ExcelParser）',
    wireValue: null,
  },
  minerU: {
    label: 'MinerU 版面解析',
    desc: '调用远程 MinerU 官方 API，还原版面/表格/公式并产出 Markdown，覆盖任意文件类型',
    wireValue: 'minerU',
  },
  'tika-mixed': {
    label: '图文混排',
    desc: 'Tika 抽取正文文本 + 内嵌图片逐张多模态 OCR，适合 Word/PPT 等图文混排文档',
    wireValue: 'tikamixed',
  },
  deepseekocr: {
    label: 'DeepSeekOCR',
    desc: '纯图片多模态识别，适合 PNG/JPG/BMP 截图与扫描图',
    wireValue: 'deepseekocr',
  },
  excelparser: {
    label: 'ExcelParser',
    desc: '智能表格解析，≤20 行输出 Markdown 表格，超过则输出键值对',
    wireValue: 'excelparser',
  },
};

/**
 * 下拉框选项顺序（auto 置顶，其余与后端路由表的出现顺序一致）。
 */
export const PARSER_MODEL_ORDER: ParserModel[] = [
  'auto',
  'minerU',
  'tika-mixed',
  'deepseekocr',
  'excelparser',
];

/** 按扩展名分组的路由表：与后端 RagCoreConfig.documentParseFactory 的 suppliers 映射一一对应 */
const EXT_PARSER_MAP: Record<string, ParserModel> = {
  // 纯图片 → DeepSeekOCR
  png: 'deepseekocr',
  jpg: 'deepseekocr',
  jpeg: 'deepseekocr',
  bmp: 'deepseekocr',
  // PDF → MinerU（后端 rag.parser.pdf-provider=mineru 为默认值）
  pdf: 'minerU',
  // 表格 → ExcelParser
  xls: 'excelparser',
  xlsx: 'excelparser',
  csv: 'excelparser',
  // 图文混排 → TikaOcrMixedParser
  doc: 'tika-mixed',
  docx: 'tika-mixed',
  ppt: 'tika-mixed',
  pptx: 'tika-mixed',
  // 纯文本 → TikaDocumentReader（后端现状：不加 modelName，即 auto）
  txt: 'auto',
  md: 'auto',
  markdown: 'auto',
  html: 'auto',
  htm: 'auto',
};

/**
 * 从文件名解析扩展名（小写，不含点）。
 *
 * @param fileName 文件名（可含路径）
 * @returns 扩展名；无扩展名时返回空串
 */
export function extractExtension(fileName: string): string {
  const idx = fileName.lastIndexOf('.');
  if (idx < 0 || idx === fileName.length - 1) return '';
  return fileName.slice(idx + 1).toLowerCase();
}

/**
 * 按文件名（扩展名）解析默认解析模型。
 *
 * 与后端路由表保持一致；未知扩展名返回 `auto`，交由后端兜底（后端会回退 TXT 解析器）。
 *
 * @param fileName 文件名
 * @returns 自动识别出的解析模型
 */
export function resolveParserByFileName(fileName: string): ParserModel {
  return EXT_PARSER_MAP[extractExtension(fileName)] ?? 'auto';
}

/**
 * 将前端选择的解析模型转换为下发给后端的 `modelName` 表单值。
 *
 * `auto` 返回 `null`，表示不携带该表单字段，由后端按扩展名自动路由。
 *
 * @param parser 前端选择的解析模型
 * @returns 后端 modelName；无需下发时为 null
 */
export function toWireModelName(parser: ParserModel): string | null {
  return PARSER_MODEL_META[parser].wireValue;
}

/**
 * 判断是否为「自动识别」。
 *
 * @param parser 解析模型
 * @returns true 表示交由后端自动路由
 */
export function isAutoParser(parser: ParserModel): boolean {
  return parser === 'auto';
}
