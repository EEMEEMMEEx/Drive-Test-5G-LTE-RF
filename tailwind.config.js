/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        bg: 'var(--color-bg)',
        surface: 'var(--color-surface)',
        'surface-elevated': 'var(--color-surface-elevated)',
        border: 'var(--color-border)',
        'border-input': 'var(--color-border-input)',
        text: 'var(--color-text)',
        'text-muted': 'var(--color-text-muted)',
        accent: 'var(--color-accent)',
        'on-accent': 'var(--color-on-accent)',
        danger: 'var(--color-danger)',
        success: 'var(--color-success)',
        warning: 'var(--color-warning)',
        tier1: 'var(--color-tier1)',
        tier2: 'var(--color-tier2)',
        tier3: 'var(--color-tier3)',
        tier4: 'var(--color-tier4)',
        tier5: 'var(--color-tier5)',
        sim21: 'var(--color-sim2-1)',
        sim22: 'var(--color-sim2-2)',
        sim23: 'var(--color-sim2-3)',
        sim24: 'var(--color-sim2-4)',
        sim25: 'var(--color-sim2-5)',
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', '-apple-system', 'Segoe UI', 'Roboto', 'sans-serif'],
        mono: ['JetBrains Mono', 'ui-monospace', 'SFMono-Regular', 'Menlo', 'monospace'],
      },
    },
  },
  plugins: [],
};

