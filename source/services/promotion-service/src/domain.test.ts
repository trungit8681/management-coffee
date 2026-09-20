import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { discount } from './domain.js';
test('discount cannot exceed order total', () => assert.equal(discount(10000, 15000, 0), 10000));
test('minimum order total is enforced', () => assert.throws(() => discount(9000, 1000, 10000)));
