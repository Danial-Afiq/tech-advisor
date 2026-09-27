import type { CSSProperties } from "react";

/**
 * The prototype palette mapped onto daisyUI v5 theme variables. Applied inline
 * on top of `data-theme="dark"` by `AppShell`, so daisyUI components pick it
 * up without any change to global CSS.
 */
export const advisorTheme = {
  "--color-base-100": "#0d1727",
  "--color-base-200": "#0a1423",
  "--color-base-300": "#16243b",
  "--color-base-content": "#eef5ff",
  "--color-primary": "#7c5cff",
  "--color-primary-content": "#ffffff",
  "--color-secondary": "#111d31",
  "--color-secondary-content": "#eef5ff",
  "--color-accent": "#28c0ff",
  "--color-accent-content": "#08101f",
  "--color-neutral": "#111d31",
  "--color-neutral-content": "#eef5ff",
  "--color-info": "#28c0ff",
  "--color-success": "#2ed39a",
  "--color-warning": "#f6b84b",
  "--color-error": "#ff6d7a",
  "--radius-selector": "999px",
  "--radius-field": "12px",
  "--radius-box": "22px",
  "--depth": "0",
  "--noise": "0",
} as CSSProperties;
