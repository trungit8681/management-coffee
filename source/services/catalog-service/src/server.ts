import Fastify from 'fastify';
import { createRemoteJWKSet, jwtVerify } from 'jose';
import { createHash, randomUUID } from 'node:crypto';
import { existsSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { Pool, type PoolClient } from 'pg';
import { priceVnd, requiredText, validChannel } from './domain.js';

const localEnv = fileURLToPath(new URL('../.env', import.meta.url));
if (!process.env.CATALOG_DB_URL && existsSync(localEnv)) process.loadEnvFile(localEnv);
if (!process.env.CATALOG_DB_URL) throw new Error('CATALOG_DB_URL is required');
const pool = new Pool({ connectionString: process.env.CATALOG_DB_URL, max: 10 });
const app = Fastify({ logger: true });
const issuer = process.env.CATALOG_JWT_ISSUER || 'coffee-identity';
const audience = process.env.CATALOG_JWT_AUDIENCE || 'coffee-platform';
const jwks = createRemoteJWKSet(new URL(process.env.CATALOG_JWKS_URI || 'http://localhost:8080/.well-known/jwks.json'));
type Actor = { id: string; permissions: string[]; branchScopes: string[]; globalScope: boolean };
type Body = Record<string, unknown>;

async function migrate() {
  const sql = await readFile(fileURLToPath(new URL('../sql/V1__catalog.sql', import.meta.url)), 'utf8');
  const db = await pool.connect();
  try {
    await db.query('BEGIN');
    await db.query('SELECT pg_advisory_xact_lock(18493001)');
    await db.query('CREATE TABLE IF NOT EXISTS schema_migration (version integer PRIMARY KEY)');
    const applied = await db.query('SELECT version FROM schema_migration WHERE version=1');
    if (!applied.rowCount) { await db.query(sql); await db.query('INSERT INTO schema_migration(version) VALUES (1)'); }
    await db.query('COMMIT');
  } catch (error) { await db.query('ROLLBACK'); throw error; }
  finally { db.release(); }
}
async function actor(header: unknown): Promise<Actor> {
  if (typeof header !== 'string' || !header.startsWith('Bearer ')) throw http(401, 'UNAUTHENTICATED');
  try {
    const { payload } = await jwtVerify(header.slice(7), jwks, { issuer, audience, algorithms: ['RS256'] });
    if (typeof payload.sub !== 'string' || !Array.isArray(payload.permissions) || !Array.isArray(payload.branch_scopes))
      throw Error('claims');
    return { id: payload.sub, permissions: payload.permissions as string[], branchScopes: payload.branch_scopes as string[], globalScope: payload.global_scope === true };
  } catch { throw http(401, 'UNAUTHENTICATED'); }
}
function requireAction(who: Actor, action: string, branchId?: string) {
  if (!who.permissions.includes(action) || (branchId && !who.globalScope && !who.branchScopes.includes(branchId)))
    throw http(403, 'FORBIDDEN');
}
function http(statusCode: number, code: string): Error & { statusCode: number; code: string } {
  return Object.assign(new Error(code), { statusCode, code });
}
function uuid(value: unknown): string {
  if (typeof value !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)) throw http(400, 'INVALID_UUID');
  return value;
}
function key(value: unknown): string {
  if (typeof value !== 'string' || !value.trim() || value.length > 128) throw http(400, 'INVALID_IDEMPOTENCY_KEY');
  return value;
}
function fingerprint(value: unknown, who: Actor): string {
  return createHash('sha256').update(JSON.stringify([value, who.id])).digest('hex');
}
async function transaction<T>(fn: (db: PoolClient) => Promise<T>): Promise<T> {
  const db = await pool.connect();
  try { await db.query('BEGIN'); const result = await fn(db); await db.query('COMMIT'); return result; }
  catch (error) { await db.query('ROLLBACK'); throw error; }
  finally { db.release(); }
}
app.setErrorHandler((error, _request, reply) => {
  const status = 'statusCode' in error && typeof error.statusCode === 'number' ? error.statusCode
    : (error as { code?: string }).code === '23505' ? 409
      : error.message.startsWith('INVALID_') ? 400 : 500;
  if (status >= 500) app.log.error(error);
  reply.status(status).send({ code: status >= 500 ? 'INTERNAL_ERROR' : status === 409 ? 'CONFLICT' : (error as { code?: string }).code || error.message || 'INVALID_REQUEST' });
});
app.get('/health/live', async () => ({ status: 'UP' }));
app.get('/health/ready', async () => { await pool.query('SELECT 1'); return { status: 'UP' }; });

app.post('/api/v1/catalog/products', async (request, reply) => {
  const who = await actor(request.headers.authorization); requireAction(who, 'catalog:manage_product');
  const body = request.body as Body;
  const sku = requiredText(body.sku, 'SKU'), name = requiredText(body.name, 'NAME'), category = requiredText(body.category, 'CATEGORY');
  const variantCode = requiredText(body.variantCode, 'VARIANT_CODE');
  const variantName = requiredText(body.variantName, 'VARIANT_NAME');
  const idem = key(request.headers['idempotency-key']), hash = fingerprint(body, who);
  const result = await transaction(async db => {
    await db.query('SELECT pg_advisory_xact_lock(hashtext($1))', ['product:' + idem]);
    const prior = await db.query('SELECT request_hash,result_id FROM command_result WHERE idempotency_key=$1', [idem]);
    if (prior.rowCount) {
      if (prior.rows[0].request_hash !== hash) throw http(409, 'IDEMPOTENCY_CONFLICT');
      return { id: prior.rows[0].result_id as string };
    }
    const id = randomUUID(), variantId = randomUUID();
    await db.query('INSERT INTO product(id,sku,name,category,status,actor_id,idempotency_key,request_hash) VALUES($1,$2,$3,$4,$5,$6,$7,$8)',
      [id, sku, name, category, 'ACTIVE', who.id, idem, hash]);
    await db.query('INSERT INTO variant(id,product_id,code,name,status) VALUES($1,$2,$3,$4,$5)',
      [variantId, id, variantCode, variantName, 'ACTIVE']);
    await db.query('INSERT INTO command_result(idempotency_key,request_hash,result_id) VALUES($1,$2,$3)', [idem, hash, id]);
    await db.query("INSERT INTO outbox_event(id,aggregate_id,event_type,payload) VALUES($1,$2,'ProductCreated.v1',$3)",
      [randomUUID(), id, { productId: id, variantId }]);
    return { id, variantId };
  });
  return reply.code(201).send(result);
});

app.put('/api/v1/catalog/variants/:variantId/prices', async (request) => {
  const who = await actor(request.headers.authorization);
  const variantId = uuid((request.params as Body).variantId), body = request.body as Body;
  const branchId = uuid(body.branchId); requireAction(who, 'catalog:manage_price', branchId);
  if (!validChannel(body.channel)) throw http(400, 'INVALID_CHANNEL');
  const channel = body.channel, amount = priceVnd(body.unitPriceVnd);
  const idem = key(request.headers['idempotency-key']), hash = fingerprint({ variantId, branchId, channel, amount }, who);
  return transaction(async db => {
    await db.query('SELECT pg_advisory_xact_lock(hashtext($1))', [variantId + branchId + channel]);
    const prior = await db.query('SELECT request_hash,result_id FROM command_result WHERE idempotency_key=$1', [idem]);
    if (prior.rowCount) {
      if (prior.rows[0].request_hash !== hash) throw http(409, 'IDEMPOTENCY_CONFLICT');
      const old = await db.query('SELECT version FROM price WHERE id=$1', [prior.rows[0].result_id]);
      return { id: prior.rows[0].result_id, version: Number(old.rows[0].version) };
    }
    const variant = await db.query("SELECT v.id FROM variant v JOIN product p ON p.id=v.product_id WHERE v.id=$1 AND v.status='ACTIVE' AND p.status='ACTIVE'", [variantId]);
    if (!variant.rowCount) throw http(404, 'VARIANT_NOT_FOUND');
    const latest = await db.query('SELECT COALESCE(MAX(version),0)+1 AS next FROM price WHERE variant_id=$1 AND branch_id=$2 AND channel=$3', [variantId, branchId, channel]);
    const version = Number(latest.rows[0].next), id = randomUUID();
    await db.query('UPDATE price SET active=false WHERE variant_id=$1 AND branch_id=$2 AND channel=$3 AND active', [variantId, branchId, channel]);
    await db.query('INSERT INTO price(id,variant_id,branch_id,channel,unit_price_vnd,version,actor_id) VALUES($1,$2,$3,$4,$5,$6,$7)',
      [id, variantId, branchId, channel, amount, version, who.id]);
    await db.query('INSERT INTO command_result(idempotency_key,request_hash,result_id) VALUES($1,$2,$3)', [idem, hash, id]);
    await db.query("INSERT INTO outbox_event(id,aggregate_id,event_type,payload) VALUES($1,$2,'PricePublished.v1',$3)",
      [randomUUID(), variantId, { variantId, branchId, channel, unitPriceVnd: amount, version }]);
    return { id, version };
  });
});

app.get('/api/v1/catalog/variants/:variantId/sellable', async request => {
  const who = await actor(request.headers.authorization);
  const variantId = uuid((request.params as Body).variantId), query = request.query as Body;
  const branchId = uuid(query.branchId); requireAction(who, 'catalog:view_menu', branchId);
  if (!validChannel(query.channel)) throw http(400, 'INVALID_CHANNEL');
  const result = await pool.query("SELECT p.unit_price_vnd,p.version FROM price p JOIN variant v ON v.id=p.variant_id JOIN product pr ON pr.id=v.product_id WHERE p.variant_id=$1 AND p.branch_id=$2 AND p.channel=$3 AND p.active AND v.status='ACTIVE' AND pr.status='ACTIVE'",
    [variantId, branchId, query.channel]);
  if (!result.rowCount) throw http(404, 'ITEM_NOT_SELLABLE');
  return { variantId, branchId, channel: query.channel, unitPriceVnd: Number(result.rows[0].unit_price_vnd), priceVersion: Number(result.rows[0].version), sellable: true };
});

async function main() {
  await migrate();
  await app.listen({ host: '0.0.0.0', port: Number(process.env.PORT || process.env.CATALOG_SERVICE_PORT || 8080) });
}
main().catch(error => { app.log.error(error); process.exit(1); });
