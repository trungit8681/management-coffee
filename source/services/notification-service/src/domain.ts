export function render(body: string, variables: Record<string, string>): string {
  if (!body || body.length > 4000) throw new Error('INVALID_TEMPLATE');
  return body.replace(/\{([a-zA-Z][a-zA-Z0-9_]*)\}/g, (_match, key: string) => {
    const value = variables[key];
    if (typeof value !== 'string' || value.length > 500) throw new Error('INVALID_VARIABLE');
    return value;
  });
}
