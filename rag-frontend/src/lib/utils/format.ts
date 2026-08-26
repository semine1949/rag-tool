/**
 * 通用格式化工具
 */

/** 文件大小：KB -> 人类可读 */
export function formatSize(kb: number): string {
  if (kb < 1024) return `${kb.toFixed(0)} KB`;
  if (kb < 1024 * 1024) return `${(kb / 1024).toFixed(1)} MB`;
  return `${(kb / 1024 / 1024).toFixed(2)} GB`;
}

/** 千分位数字 */
export function formatNumber(n: number): string {
  return n.toLocaleString('zh-CN');
}

/** 百分比 */
export function formatPercent(v: number, digits = 1): string {
  return `${(v * 100).toFixed(digits)}%`;
}

/** 时间戳/ISO -> yyyy-MM-dd HH:mm */
export function formatDateTime(input?: string | number | Date): string {
  if (!input) return '-';
  const d = new Date(input);
  if (Number.isNaN(d.getTime())) return String(input);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(
    d.getHours(),
  )}:${pad(d.getMinutes())}`;
}

/** 相对时间：如 “3 分钟前” */
export function formatRelative(input: string | number | Date): string {
  const t = new Date(input).getTime();
  if (Number.isNaN(t)) return String(input);
  const diff = Date.now() - t;
  const min = Math.floor(diff / 60000);
  if (min < 1) return '刚刚';
  if (min < 60) return `${min} 分钟前`;
  const hour = Math.floor(min / 60);
  if (hour < 24) return `${hour} 小时前`;
  const day = Math.floor(hour / 24);
  if (day < 30) return `${day} 天前`;
  return formatDateTime(input);
}

/** 取用户名首字母作为头像文字 */
export function initials(name: string): string {
  if (!name) return '?';
  return name.slice(0, 2).toUpperCase();
}
