import { app, pool, start, authenticate, authorize, transaction, claim, complete, event, fail, uuid, text, key, hash, newId, type Body } from './platform.js';
import { nextStatus, type DeliveryStatus } from './domain.js';

const orderBase = process.env.FULFILLMENT_ORDER_BASE_URL || 'http://order-service:8080/';
type Order = { id: string; branchId: string; channel: string; status: string; paymentStatus: string };
async function order(id: string, bearer: string): Promise<Order> {
  try {
    const response = await fetch(new URL(`api/v1/orders/${id}`, orderBase), { headers: { Authorization: bearer }, signal: AbortSignal.timeout(4000) });
    if (!response.ok) throw Error('order unavailable');
    return await response.json() as Order;
  } catch { throw fail(503, 'ORDER_UNAVAILABLE'); }
}
async function get(id: string) {
  const found = await pool.query('SELECT id,order_id,branch_id,address,status,driver_id,version FROM delivery WHERE id=$1', [id]);
  if (!found.rowCount) throw fail(404, 'DELIVERY_NOT_FOUND');
  return found.rows[0];
}
app.post('/api/v1/fulfillment/deliveries', async (request, reply) => {
  const who = await authenticate(request.headers.authorization), body = request.body as Body;
  const orderId = uuid(body.orderId), branchId = uuid(body.branchId), address = text(body.address, 'ADDRESS');
  authorize(who, 'fulfillment:create', branchId);
  const source = await order(orderId, request.headers.authorization!);
  if (source.id !== orderId || source.branchId !== branchId || source.channel !== 'DELIVERY' || source.status !== 'CONFIRMED')
    throw fail(409, 'ORDER_NOT_DELIVERABLE');
  const idem = key(request.headers['idempotency-key']), fingerprint = hash([who.id, orderId, branchId, address]);
  const result = await transaction(async db => {
    const prior = await claim(db, idem, fingerprint);
    if (prior) return { id: prior };
    const id = newId();
    await db.query("INSERT INTO delivery(id,order_id,branch_id,address,status) VALUES($1,$2,$3,$4,'NEW')", [id, orderId, branchId, address]);
    await complete(db, idem, id); await event(db, id, 'DeliveryCreated.v1', { deliveryId: id, orderId, branchId });
    return { id };
  });
  return reply.code(201).send(result);
});
app.get('/api/v1/fulfillment/deliveries/:id', async request => {
  const who = await authenticate(request.headers.authorization), delivery = await get(uuid((request.params as Body).id));
  authorize(who, 'fulfillment:view', delivery.branch_id); return delivery;
});
app.post('/api/v1/fulfillment/deliveries/:id/assign', async request => {
  const who = await authenticate(request.headers.authorization), id = uuid((request.params as Body).id);
  const driverId = uuid((request.body as Body).driverId), version = Number(request.headers['if-match']);
  if (!Number.isSafeInteger(version) || version < 0) throw fail(400, 'INVALID_VERSION');
  return transaction(async db => {
    const found = await db.query('SELECT id,branch_id,status,version FROM delivery WHERE id=$1 FOR UPDATE', [id]);
    if (!found.rowCount) throw fail(404, 'DELIVERY_NOT_FOUND');
    const row = found.rows[0]; authorize(who, 'fulfillment:assign', row.branch_id);
    if (Number(row.version) !== version) throw fail(409, 'VERSION_CONFLICT');
    nextStatus(row.status as DeliveryStatus, 'ASSIGN');
    await db.query("UPDATE delivery SET status='ASSIGNED',driver_id=$1,version=version+1,updated_at=now() WHERE id=$2", [driverId, id]);
    await event(db, id, 'DeliveryAssigned.v1', { deliveryId: id, driverId });
    return { id, status: 'ASSIGNED', version: version + 1 };
  });
});
app.post('/api/v1/fulfillment/deliveries/:id/complete', async request => {
  const who = await authenticate(request.headers.authorization), id = uuid((request.params as Body).id);
  const current = await get(id); authorize(who, 'fulfillment:complete', current.branch_id);
  const source = await order(current.order_id, request.headers.authorization!);
  if (source.id !== current.order_id || source.branchId !== current.branch_id || source.paymentStatus !== 'PAID')
    throw fail(409, 'CASH_NOT_COLLECTED');
  return transaction(async db => {
    const found = await db.query('SELECT id,status,branch_id FROM delivery WHERE id=$1 FOR UPDATE', [id]);
    if (!found.rowCount) throw fail(404, 'DELIVERY_NOT_FOUND');
    const row = found.rows[0]; authorize(who, 'fulfillment:complete', row.branch_id);
    if (row.status === 'DELIVERED') return { id, status: 'DELIVERED' };
    nextStatus(row.status as DeliveryStatus, 'DELIVER');
    await db.query("UPDATE delivery SET status='DELIVERED',version=version+1,updated_at=now() WHERE id=$1", [id]);
    await db.query("INSERT INTO delivery_attempt(id,delivery_id,result,actor_id) VALUES($1,$2,'DELIVERED',$3)", [newId(), id, who.id]);
    await event(db, id, 'DeliveryCompleted.v1', { deliveryId: id, orderId: current.order_id });
    return { id, status: 'DELIVERED' };
  });
});
app.post('/api/v1/fulfillment/deliveries/:id/fail', async request => {
  const who = await authenticate(request.headers.authorization), id = uuid((request.params as Body).id);
  return transaction(async db => {
    const found = await db.query('SELECT id,status,branch_id FROM delivery WHERE id=$1 FOR UPDATE', [id]);
    if (!found.rowCount) throw fail(404, 'DELIVERY_NOT_FOUND');
    const row = found.rows[0]; authorize(who, 'fulfillment:complete', row.branch_id);
    if (row.status === 'FAILED') return { id, status: 'FAILED' };
    nextStatus(row.status as DeliveryStatus, 'FAIL');
    await db.query("UPDATE delivery SET status='FAILED',version=version+1,updated_at=now() WHERE id=$1", [id]);
    await db.query("INSERT INTO delivery_attempt(id,delivery_id,result,actor_id) VALUES($1,$2,'FAILED',$3)", [newId(), id, who.id]);
    await event(db, id, 'DeliveryFailed.v1', { deliveryId: id });
    return { id, status: 'FAILED' };
  });
});
start().catch(error => { app.log.error(error); process.exit(1); });
