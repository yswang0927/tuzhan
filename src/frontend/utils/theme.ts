export type ThemeType = 'light' | 'dark' | 'system';

export const THEME_STORAGE_KEY = 'tuzhan_theme';

/**
 * 应用指定主题到 DOM (<html> 标签与 class)
 */
export function applyTheme(theme: ThemeType) {
    let targetTheme = theme;
    if (theme === 'system') {
        targetTheme = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    }

    document.documentElement.setAttribute('data-theme', targetTheme);
    if (targetTheme === 'dark') {
        document.documentElement.classList.add('dark', 'bp6-dark');
    } else {
        document.documentElement.classList.remove('dark', 'bp6-dark');
    }
}

/**
 * 保存并应用主题
 */
export function saveTheme(newTheme: ThemeType) {
    try {
        window.localStorage.setItem(THEME_STORAGE_KEY, newTheme);
    } catch (e) {
        console.error('Failed to save theme to localStorage:', e);
    }
    applyTheme(newTheme);
}

/**
 * 初始化主题设置（启动时调用）
 */
export function initTheme() {
    // 1. 优先从 localStorage 提取并立即应用（防止闪烁）
    let savedTheme: ThemeType = 'light';
    try {
        const stored = window.localStorage.getItem(THEME_STORAGE_KEY);
        if (stored === 'light' || stored === 'dark' || stored === 'system') {
            savedTheme = stored as ThemeType;
        }
    } catch (e) {}

    applyTheme(savedTheme);

    // 2. 监听系统深色/浅色模式切换
    if (window.matchMedia) {
        const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
        const handleSystemChange = () => {
            let current: ThemeType = 'system';
            try {
                current = (window.localStorage.getItem(THEME_STORAGE_KEY) as ThemeType) || 'system';
            } catch (e) {}
            if (current === 'system') {
                applyTheme('system');
            }
        };

        if (mediaQuery.addEventListener) {
            mediaQuery.addEventListener('change', handleSystemChange);
        }
    }
}
