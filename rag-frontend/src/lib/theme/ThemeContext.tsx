import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';

/**
 * 主题管理
 * 提供"浅色苹果极简(默认) / 深色星云(可选)"两套主题的切换，偏好写入 localStorage 刷新保留。
 * 默认浅色苹果极简为主题：不设 data-theme（命中 :root 默认白底蓝强调）。
 * 切到深色星云时在 <html> 挂 data-theme="dark"，命中 index.css 中 [data-theme='dark'] 覆盖。
 */
export type Theme = 'light' | 'dark';

/** localStorage 键名，沿用项目 nebula. 前缀惯例 */
const THEME_KEY = 'nebula.theme';

/** 读取当前保存的主题，非法/缺失时回退默认浅色苹果 */
function readStoredTheme(): Theme {
  try {
    const raw = localStorage.getItem(THEME_KEY);
    return raw === 'dark' ? 'dark' : 'light';
  } catch {
    return 'light';
  }
}

/** 直接把 data-theme 应用到 <html>：浅色(默认)移除属性走 :root，深色挂 'dark' */
function applyTheme(theme: Theme): void {
  const root = document.documentElement;
  if (theme === 'dark') {
    root.setAttribute('data-theme', 'dark');
  } else {
    root.removeAttribute('data-theme');
  }
}

interface ThemeContextValue {
  theme: Theme;
  /** 是否处于深色星云主题 */
  isDark: boolean;
  /** 显式设置主题 */
  setTheme: (theme: Theme) => void;
  /** 在浅色与深色之间切换 */
  toggleTheme: () => void;
}

const ThemeContext = createContext<ThemeContextValue | null>(null);

/**
 * 主题提供者
 * 初始化时同步 localStorage 偏好到 <html> 的 data-theme。
 */
export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<Theme>(() => readStoredTheme());

  // 首次挂载确保 <html> 的 data-theme 与状态一致
  useEffect(() => {
    applyTheme(theme);
  }, [theme]);

  const setTheme = useCallback((next: Theme) => {
    setThemeState(next);
    applyTheme(next);
    try {
      localStorage.setItem(THEME_KEY, next);
    } catch {
      // 隐私模式下写入可能失败，忽略即可
    }
  }, []);

  const toggleTheme = useCallback(() => {
    setThemeState((prev) => {
      const next: Theme = prev === 'dark' ? 'light' : 'dark';
      applyTheme(next);
      try {
        localStorage.setItem(THEME_KEY, next);
      } catch {
        // 忽略写入失败
      }
      return next;
    });
  }, []);

  const value = useMemo<ThemeContextValue>(
    () => ({ theme, isDark: theme === 'dark', setTheme, toggleTheme }),
    [theme, setTheme, toggleTheme],
  );

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

/** 读取主题上下文 */
export function useTheme(): ThemeContextValue {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error('useTheme 必须在 ThemeProvider 内使用');
  return ctx;
}
