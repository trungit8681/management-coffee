import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { render } from './domain.js';
test('renders declared variables', () => assert.equal(render('Order {number} ready', {number:'42'}), 'Order 42 ready'));
test('rejects missing variables', () => assert.throws(() => render('Order {number} ready', {})));
