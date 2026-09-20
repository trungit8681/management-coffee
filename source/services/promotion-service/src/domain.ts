export function discount(totalVnd: number, discountVnd: number, minTotalVnd: number): number {
  if (![totalVnd, discountVnd, minTotalVnd].every(Number.isSafeInteger) || totalVnd <= 0 || discountVnd <= 0 || minTotalVnd < 0)
    throw new Error('INVALID_DISCOUNT');
  if (totalVnd < minTotalVnd) throw new Error('MIN_TOTAL_NOT_MET');
  return Math.min(totalVnd, discountVnd);
}
