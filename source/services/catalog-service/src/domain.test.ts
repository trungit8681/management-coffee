import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { priceVnd, validChannel } from './domain.js';
test('accepts integer VND and supported channels', () => {
  assert.equal(priceVnd(25000), 25000); assert.equal(validChannel('POS'), true);
});
test('rejects fractional, negative and unsafe prices', () => {
  for (const value of [-1, 1.5, Number.MAX_SAFE_INTEGER + 1]) assert.throws(() => priceVnd(value));
});
