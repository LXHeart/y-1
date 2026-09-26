/**
 * C107-13 studio/theme-tokens.ts — 草场 design token 映射（W13 共享 token 资源）。
 *
 * Values mirror the y-1 DESIGN contract (brand primary #533afd, active
 * #4434d4, dark surfaces, Space Grotesk display + Inter body). The 0003 patch
 * injects exactly these tokens into the Studio shell; this module is the
 * single source both the patch and tests read, so Studio theming can never
 * drift from the app tokens.
 */
export const Y1_THEME_TOKENS = {
  primary: "#533afd",
  primaryActive: "#4434d4",
  bg: "#0b0a1a",
  surface: "#14122b",
  text: "#edeafd",
  radius: "12px",
  fontDisplay: '"Space Grotesk", "Inter", system-ui, sans-serif',
  fontBody: '"Inter", system-ui, sans-serif',
} as const;

export type Y1ThemeTokenName = keyof typeof Y1_THEME_TOKENS;

export const THEME_CSS_VARIABLES: Readonly<Record<Y1ThemeTokenName, string>> = {
  primary: "--y1-primary",
  primaryActive: "--y1-primary-active",
  bg: "--y1-bg",
  surface: "--y1-surface",
  text: "--y1-text",
  radius: "--y1-radius",
  fontDisplay: "--y1-font-display",
  fontBody: "--y1-font-body",
};

/** The exact style block patch 0003 injects (kept in one place). */
export function themeStyleBlock(): string {
  return `
      :root {
        --y1-primary: ${Y1_THEME_TOKENS.primary};
        --y1-primary-active: ${Y1_THEME_TOKENS.primaryActive};
        --y1-bg: ${Y1_THEME_TOKENS.bg};
        --y1-surface: ${Y1_THEME_TOKENS.surface};
        --y1-text: ${Y1_THEME_TOKENS.text};
        --y1-radius: ${Y1_THEME_TOKENS.radius};
        --y1-font-display: ${Y1_THEME_TOKENS.fontDisplay};
        --y1-font-body: ${Y1_THEME_TOKENS.fontBody};
      }
`;
}

/** Fonts stay self-hosted upstream; Studio never loads an external font CDN. */
export function assertNoExternalFonts(css: string): void {
  if (/https?:\/\/[^"'\s]+\.(woff2?|ttf|otf)/u.test(css) || /fonts\.googleapis/u.test(css)) {
    throw new Error("Studio theme must not load external font CDNs");
  }
}
