import { app, pool, start, authenticate, authorize, transaction, claim, complete, event, fail, uuid, text, positive, key, hash, newId, type Body } from './platform.js';
import { discount } from './domain.js';

app.post('/api/v1/promotions/vouchers', async (request, reply) => {
  const who = await authenticate(request.headers.authorization), body = request.body as Body;
  const branchId = uuid(body.branchId); authorize(who, 'promotion:manage', branchId);
  const code = text(body.code, 'CODE'), amount = positive(body.discountVnd), limit = positive(body.usageLimit);
  if (typeof body.minTotalVnd !== 'number' || !Number.isSafeInteger(body.minTotalVnd) || body.minTotalVnd < 0) throw fail(400, 'INVALID_MIN_TOTAL');
  if (!Number.isSafeInteger(limit) || limit > 1000000) throw fail(400, 'INVALID_USAGE_LIMIT');
  const expires = new Date(text(body.expiresAt, 'EXPIRY'));
  if (!Number.isFinite(expires.getTime()) || expires <= new Date()) throw fail(400, 'INVALID_EXPIRY');
  const idem = key(request.headers['idempotency-key']), fingerprint = hash([who.id, body]);
  const result = await transaction(async db => {
    const prior = await claim(db, idem, fingerprint); if (prior) return { id: prior };
    const id = newId();
    await db.query('INSERT INTO voucher(id,code,branch_id,discount_vnd,min_total_vnd,usage_limit,expires_at) VALUES($1,$2,$3,$4,$5,$6,$7)',
      [id, code, branchId, amount, body.minTotalVnd, limit, expires]);
    await complete(db, idem, id); await event(db, id, 'VoucherCreated.v1', { voucherId: id, branchId });
    return { id };
  });
  return reply.code(201).send(result);
});

app.post('/api/v1/promotions/reservations', async (request, reply) => {
  const who = await authenticate(request.headers.authorization), body = request.body as Body;
  const branchId = uuid(body.branchId), orderId = uuid(body.orderId), code = text(body.code, 'CODE'), total = positive(body.totalVnd);
  authorize(who, 'promotion:reserve', branchId);
  const result = await transaction(async db => {
    const prior = await db.query('SELECT r.id,r.order_id,r.discount_vnd,r.status,r.total_vnd,v.code,v.branch_id FROM voucher_reservation r JOIN voucher v ON v.id=r.voucher_id WHERE r.order_id=$1', [orderId]);
    if (prior.rowCount) {
      const row = prior.rows[0];
      if (row.code !== code || row.branch_id !== branchId || Number(row.total_vnd) !== total) throw fail(409, 'RESERVATION_CONFLICT');
      return { id: row.id, orderId, discountVnd: Number(row.discount_vnd), status: row.status };
    }
    const available = await db.query('SELECT id,discount_vnd,min_total_vnd FROM voucher WHERE code=$1 AND branch_id=$2 AND expires_at>now()', [code, branchId]);
    if (!available.rowCount) throw fail(409, 'VOUCHER_UNAVAILABLE');
    const voucher = available.rows[0];
    let amount: number;
    try { amount = discount(total, Number(voucher.discount_vnd), Number(voucher.min_total_vnd)); }
    catch { throw fail(409, 'VOUCHER_NOT_APPLICABLE'); }
    const changed = await db.query('UPDATE voucher SET reserved_count=reserved_count+1 WHERE id=$1 AND used_count+reserved_count<usage_limit AND expires_at>now()', [voucher.id]);
    if (!changed.rowCount) throw fail(409, 'VOUCHER_LIMIT_REACHED');
    const id = newId();
    await db.query("INSERT INTO voucher_reservation(id,voucher_id,order_id,total_vnd,discount_vnd,status) VALUES($1,$2,$3,$4,$5,'RESERVED')", [id, voucher.id, orderId, total, amount]);
    await event(db, voucher.id, 'VoucherReserved.v1', { voucherId: voucher.id, orderId, discountVnd: amount });
    return { id, orderId, discountVnd: amount, status: 'RESERVED' };
  });
  return reply.code(201).send(result);
});

async function finish(orderId: string, target: 'COMMITTED' | 'RELEASED', who: Awaited<ReturnType<typeof authenticate>>) {
  return transaction(async db => {
    const found = await db.query('SELECT r.id,r.voucher_id,r.discount_vnd,r.status,v.branch_id FROM voucher_reservation r JOIN voucher v ON v.id=r.voucher_id WHERE r.order_id=$1 FOR UPDATE OF r', [orderId]);
    if (!found.rowCount) throw fail(404, 'RESERVATION_NOT_FOUND');
    const r = found.rows[0];
    authorize(who, 'promotion:commit', r.branch_id);
    if (r.status === target) return { id: r.id, orderId, discountVnd: Number(r.discount_vnd), status: target };
    if (r.status !== 'RESERVED') throw fail(409, 'RESERVATION_ALREADY_FINAL');
    const update = target === 'COMMITTED'
      ? 'UPDATE voucher SET reserved_count=reserved_count-1,used_count=used_count+1 WHERE id=$1 AND reserved_count>0'
      : 'UPDATE voucher SET reserved_count=reserved_count-1 WHERE id=$1 AND reserved_count>0';
    const changed = await db.query(update, [r.voucher_id]);
    if (!changed.rowCount) throw fail(409, 'VOUCHER_COUNTER_CONFLICT');
    await db.query('UPDATE voucher_reservation SET status=$1 WHERE id=$2', [target, r.id]);
    await event(db, r.voucher_id, target === 'COMMITTED' ? 'VoucherCommitted.v1' : 'VoucherReleased.v1', { voucherId: r.voucher_id, orderId });
    return { id: r.id, orderId, discountVnd: Number(r.discount_vnd), status: target };
  });
}
app.post('/api/v1/promotions/reservations/:orderId/commit', async request => {
  const who = await authenticate(request.headers.authorization);
  return finish(uuid((request.params as Body).orderId), 'COMMITTED', who);
});
app.post('/api/v1/promotions/reservations/:orderId/release', async request => {
  const who = await authenticate(request.headers.authorization);
  return finish(uuid((request.params as Body).orderId), 'RELEASED', who);
});
app.get('/api/v1/promotions/vouchers/:code', async request => {
  const who = await authenticate(request.headers.authorization), code = text((request.params as Body).code, 'CODE');
  const branchId = uuid((request.query as Body).branchId); authorize(who, 'promotion:view', branchId);
  const found = await pool.query('SELECT id,code,discount_vnd,min_total_vnd,usage_limit,used_count,reserved_count,expires_at FROM voucher WHERE code=$1 AND branch_id=$2', [code, branchId]);
  if (!found.rowCount) throw fail(404, 'VOUCHER_NOT_FOUND');
  return found.rows[0];
});
start().catch(error => { app.log.error(error); process.exit(1); });
