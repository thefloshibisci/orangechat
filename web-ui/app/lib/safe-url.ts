const SAFE_EXTERNAL_PROTOCOLS = new Set(["http:", "https:", "mailto:"]);

export function safeExternalUrl(rawUrl: string | undefined | null): string | undefined {
  if (!rawUrl) return undefined;
  const trimmed = rawUrl.trim();
  if (!trimmed) return undefined;
  try {
    const parsed = new URL(trimmed);
    return SAFE_EXTERNAL_PROTOCOLS.has(parsed.protocol) ? trimmed : undefined;
  } catch {
    return undefined;
  }
}

export function safeMarkdownHref(rawUrl: string | undefined | null): string | undefined {
  if (!rawUrl) return undefined;
  const trimmed = rawUrl.trim();
  if (!trimmed) return undefined;
  if (trimmed.startsWith("#")) return trimmed;
  return safeExternalUrl(trimmed);
}
