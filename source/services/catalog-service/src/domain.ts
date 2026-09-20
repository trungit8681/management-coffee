export type Channel = 'POS' | 'PICKUP' | 'DELIVERY';
export function validChannel(value: unknown): value is Channel {
  return value === 'POS' || value === 'PICKUP' || value === 'DELIVERY';
}
export function priceVnd(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) throw new Error('INVALID_PRICE');
  return value;
}
export function requiredText(value: unknown, field: string): string {
  if (typeof value !== 'string' || !value.trim() || value.length > 200) throw new Error(`INVALID_${field}`);
  return value.trim();
}
