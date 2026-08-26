/** @type {import('tailwindcss').Config} */
// 设计系统 Design Token 全量映射到 Tailwind theme
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // 背景层级
        bg: {
          DEFAULT: '#070b15',
          2: '#0b1120',
          3: '#0e1626',
        },
        // 玻璃拟态表面
        surface: {
          DEFAULT: 'rgba(18,26,46,0.62)',
          2: 'rgba(24,34,58,0.55)',
        },
        // 边框
        line: {
          DEFAULT: 'rgba(255,255,255,0.08)',
          2: 'rgba(255,255,255,0.14)',
        },
        // 文字
        text: '#e8eefb',
        muted: {
          DEFAULT: '#8a98b5',
          2: '#5f6e8c',
        },
        // 主色
        accent: {
          DEFAULT: '#22d3ee',
          2: '#a855f7',
          3: '#3b82f6',
        },
        // 状态色
        ok: '#34d399',
        warn: '#fbbf24',
        danger: '#f87171',
        pink: '#f472b6',
      },
      borderRadius: {
        card: '16px',
      },
      fontFamily: {
        sans: [
          'Inter',
          'PingFang SC',
          'Microsoft YaHei',
          '-apple-system',
          'BlinkMacSystemFont',
          'Segoe UI',
          'sans-serif',
        ],
      },
      backgroundImage: {
        grad: 'linear-gradient(135deg,#22d3ee 0%,#a855f7 100%)',
        'grad-soft':
          'linear-gradient(135deg,rgba(34,211,238,.16),rgba(168,85,247,.16))',
        'auth-left': 'linear-gradient(160deg,#0a1428,#0c0f1f 60%,#0a0716)',
      },
      boxShadow: {
        card: '0 18px 50px -20px rgba(0,0,0,.7)',
        glow: '0 10px 26px -10px rgba(34,211,238,.6)',
        'glow-2': '0 14px 32px -10px rgba(168,85,247,.7)',
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
