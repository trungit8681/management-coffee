import { app, pool, start, authenticate, authorize, transaction, claim, complete, event, fail, uuid, text, key, hash, newId, type Body } from './platform.js';
import { render } from './domain.js';

app.post('/api/v1/notifications/templates', async (request, reply) => {
  const who = await authenticate(request.headers.authorization); authorize(who, 'notification:manage_template');
  const body = request.body as Body, code = text(body.code, 'CODE'), templateBody = text(body.body, 'BODY');
  const idem = key(request.headers['idempotency-key']), fingerprint = hash([who.id, code, templateBody]);
  const result = await transaction(async db => {
    const prior = await claim(db, idem, fingerprint); if (prior) return { id: prior };
    const id = newId();
    await db.query("INSERT INTO template(id,code,body,status) VALUES($1,$2,$3,'ACTIVE')", [id, code, templateBody]);
    await complete(db, idem, id); await event(db, id, 'TemplateCreated.v1', { templateId: id });
    return { id };
  });
  return reply.code(201).send(result);
});
app.post('/api/v1/notifications', async (request, reply) => {
  const who = await authenticate(request.headers.authorization), body = request.body as Body;
  const branchId = uuid(body.branchId), recipientId = uuid(body.recipientId), referenceId = uuid(body.referenceId);
  const templateCode = text(body.templateCode, 'TEMPLATE_CODE'); authorize(who, 'notification:enqueue', branchId);
  const vars = body.variables;
  if (!vars || typeof vars !== 'object' || Array.isArray(vars)) throw fail(400, 'INVALID_VARIABLES');
  const values = vars as Record<string, string>;
  const idem = key(request.headers['idempotency-key']), fingerprint = hash([who.id, body]);
  const result = await transaction(async db => {
    const prior = await claim(db, idem, fingerprint); if (prior) return { id: prior };
    const template = await db.query("SELECT id,body FROM template WHERE code=$1 AND status='ACTIVE'", [templateCode]);
    if (!template.rowCount) throw fail(404, 'TEMPLATE_NOT_FOUND');
    let content: string;
    try { content = render(template.rows[0].body as string, values); }
    catch { throw fail(400, 'INVALID_VARIABLES'); }
    const id = newId();
    await db.query("INSERT INTO notification(id,recipient_id,branch_id,template_id,reference_id,rendered_body,status) VALUES($1,$2,$3,$4,$5,$6,'QUEUED')",
      [id, recipientId, branchId, template.rows[0].id, referenceId, content]);
    await complete(db, idem, id); await event(db, id, 'NotificationQueued.v1', { notificationId: id, recipientId, branchId });
    return { id };
  });
  return reply.code(202).send(result);
});
app.get('/api/v1/notifications/inbox/:recipientId', async request => {
  const who = await authenticate(request.headers.authorization), recipientId = uuid((request.params as Body).recipientId);
  if (who.id !== recipientId) authorize(who, 'notification:view_inbox');
  const found = await pool.query("SELECT id,branch_id,rendered_body,delivered_at FROM notification WHERE recipient_id=$1 AND status='DELIVERED' ORDER BY delivered_at DESC LIMIT 100", [recipientId]);
  return found.rows.filter(row => who.id === recipientId || who.global || who.branches.includes(row.branch_id));
});
async function deliverBatch() {
  await transaction(async db => {
    const queued = await db.query("SELECT id FROM notification WHERE status='QUEUED' ORDER BY created_at LIMIT 20 FOR UPDATE SKIP LOCKED");
    for (const row of queued.rows) {
      await db.query("UPDATE notification SET status='DELIVERED',delivered_at=now() WHERE id=$1", [row.id]);
      await db.query("INSERT INTO delivery_log(id,notification_id,status) VALUES($1,$2,'DELIVERED')", [newId(), row.id]);
    }
  });
}
start().then(() => {
  const timer = setInterval(() => deliverBatch().catch(error => app.log.error(error)), 2000);
  timer.unref();
}).catch(error => { app.log.error(error); process.exit(1); });
