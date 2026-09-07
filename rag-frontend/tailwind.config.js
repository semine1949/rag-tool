/** @type {import('tailwindcss').Config} */
// 设计系统 Design Token 全量映射到 Tailwind theme
// 说明：所有颜色改为引用 CSS 变量，实现运行期通过 <html data-theme="..."> 切换主题（深浅两套）。
// 变量在三处定义：
//   - :root（深色，默认，值与原设计 token 完全一致，保证零回归）
//   - [data-theme='apple']（浅色 Apple 风）
// 见 src/styles/index.css @layer base 顶部变量定义。
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // 背景层级：纯色，支持透明修饰符（rgb(var(--x)/<alpha-value>)）
        bg: {
          DEFAULT: 'rgb(var(--bg) / <alpha-value>)',
          2: 'rgb(var(--bg-2) / <alpha-value>)',
          3: 'rgb(var(--bg-3) / <alpha-value>)',
        },
        // 玻璃拟态表面：自带透明度，用整段 var（使用处不带 alpha 修饰）
        surface: {
          DEFAULT: 'var(--surface)',
          2: 'var(--surface-2)',
        },
        // 边框：自带透明度，用整段 var
        line: {
          DEFAULT: 'var(--line)',
          2: 'var(--line-2)',
        },
        // 文字：纯色
        text: 'rgb(var(--text) / <alpha-value>)',
        muted: {
          DEFAULT: 'rgb(var(--muted) / <alpha-value>)',
          2: 'rgb(var(--muted-2) / <alpha-value>)',
        },
        // 主色（纯色，支持透明修饰）
        accent: {
          DEFAULT: 'rgb(var(--accent) / <alpha-value>)',
          2: 'rgb(var(--accent-2) / <alpha-value>)',
          3: 'rgb(var(--accent-3) / <alpha-value>)',
        },
        // 状态色（纯色）
        ok: 'rgb(var(--ok) / <alpha-value>)',
        warn: 'rgb(var(--warn) / <alpha-value>)',
        danger: 'rgb(var(--danger) / <alpha-value>)',
        pink: 'rgb(var(--pink) / <alpha-value>)',
        // 明暗翻转的叠加层（rgb 三元组 + 组件侧固定 /alpha）
        // wash：hover/高亮叠加（默认白主题为深色，深色主题为白色）
        // well：输入/凹陷叠加
        wash: 'rgb(var(--wash) / <alpha-value>)',
        well: 'rgb(var(--well) / <alpha-value>)',
        // 渐变/强调色上的文字色（默认白主题为白字，深色主题为深字）
        onaccent: 'rgb(var(--on-accent) / <alpha-value>)',
        // 输入/字段底色（整段 var，自带 alpha，随主题）
        field: 'var(--field-bg)',
      },
      borderRadius: {
        card: '18px',
      },
      fontFamily: {
        sans: [
          '-apple-system',
          'BlinkMacSystemFont',
          'SF Pro Display',
          'SF Pro Text',
          'Segoe UI',
          'PingFang SC',
          'Hiragino Sans GB',
          'Microsoft YaHei',
          'Helvetica Neue',
          'Arial',
          'sans-serif',
        ],
      },
      backgroundImage: {
        grad: 'var(--grad)',
        'grad-soft': 'var(--grad-soft)',
        'auth-left': 'var(--auth-left)',
      },
      boxShadow: {
        card: 'var(--shadow-card)',
        glow: 'var(--shadow-glow)',
        'glow-2': 'var(--shadow-glow-2)',
      },
      backdropBlur: {
        glass: '18px',
      },
      keyframes: {
        float: {
          '0%,100%': { transform: 'translate(0,0) scale(1)' },
          '50%': { transform: 'translate(40px,-30px) scale(1.08)' },
        },
        fade: {
          from: { opacity: '0', transform: 'translateY(8px)' },
          to: { opacity: '1', transform: 'none' },
        },
        pop: {
          from: { transform: 'scale(.94)', opacity: '0' },
          to: { transform: 'scale(1)', opacity: '1' },
        },
        typing: {
          '0%,60%,100%': { opacity: '.3', transform: 'translateY(0)' },
          '30%': { opacity: '1', transform: 'translateY(-5px)' },
        },
      },
      animation: {
        float: 'float 18s ease-in-out infinite',
        fade: 'fade .35s ease',
        pop: 'pop .26s cubic-bezier(.2,.9,.3,1.3)',
        typing: 'typing 1.2s infinite',
      },
    },
  },
  plugins: [],
};
