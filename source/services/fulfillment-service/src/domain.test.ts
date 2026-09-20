import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { nextStatus } from './domain.js';
test('delivery follows assignment then handover', () => {
  assert.equal(nextStatus('NEW','ASSIGN'),'ASSIGNED'); assert.equal(nextStatus('ASSIGNED','DELIVER'),'DELIVERED');
});
test('unassigned delivery cannot be completed', () => assert.throws(() => nextStatus('NEW','DELIVER')));
