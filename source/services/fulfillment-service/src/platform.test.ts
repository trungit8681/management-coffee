import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { uuid } from './platform.js';
test('accepts a complete UUID and rejects a truncated one', () => {
  assert.equal(uuid('110e6b66-6317-4f64-a164-35c652bf8c51'), '110e6b66-6317-4f64-a164-35c652bf8c51');
  assert.throws(() => uuid('110e6b66-6317-4f64-35c652bf8c51'));
});
