import Fastify from 'fastify';
import { createRemoteJWKSet, jwtVerify } from 'jose';
import { createHash, randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { Pool, type PoolClient } from 'pg';

const name = process.env.SERVICE_NAME || 'promotion';
const prefix = name.toUpperCase();
export const pool = new Pool({ connectionString: process.env[`${prefix}_DB_URL`], max: 10 });
export const app = Fastify({ logger: true });
const issuer = process.env[`${prefix}_JWT_ISSUER`] || 'coffee-identity';
const audience = process.env[`${prefix}_JWT_AUDIENCE`] || 'coffee-platform';
const jwks = createRemoteJWKSet(new URL(process.env[`${prefix}_JWKS_URI`] || 'http://localhost:8080/.well-known/jwks.json'));
export type Actor = { id: string; permissions: string[]; branches: string[]; global: boolean };
export type Body = Record<string, unknown>;

export function fail(statusCode: number, code: string): Error & { statusCode: number; code: string } {
  return Object.assign(new Error(code), { statusCode, code });
}
export function uuid(value: unknown): string {
  if (typeof value !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)) throw fail(400, 'INVALID_UUID');
  return value;
}
export function text(value: unknown, field: string): string {
  if (typeof value !== 'string' || !value.trim() || value.length > 500) throw fail(400, `INVALID_${field}`);
  return value.trim();
}
export function positive(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value <= 0) throw fail(400, 'INVALID_AMOUNT');
  return value;
}
export function key(value: unknown): string {
  if (typeof value !== 'string' || !value.trim() || value.length > 128) throw fail(400, 'INVALID_IDEMPOTENCY_KEY');
  return value;
}
export function hash(value: unknown): string { return createHash('sha256').update(JSON.stringify(value)).digest('hex'); }
export function newId(): string { return randomUUID(); }
export async function authenticate(header: unknown): Promise<Actor> {
  if (typeof header !== 'string' || !header.startsWith('Bearer ')) throw fail(401, 'UNAUTHENTICATED');
  try {
    const { payload } = await jwtVerify(header.slice(7), jwks, { issuer, audience, algorithms: ['RS256'] });
    if (!payload.sub || !Array.isArray(payload.permissions) || !Array.isArray(payload.branch_scopes)) throw Error('claims');
    return { id: payload.sub, permissions: payload.permissions as string[], branches: payload.branch_scopes as string[], global: payload.global_scope === true };
  } catch { throw fail(401, 'UNAUTHENTICATED'); }
}
export function authorize(actor: Actor, action: string, branch?: string) {
  if (!actor.permissions.includes(action) || (branch && !actor.global && !actor.branches.includes(branch))) throw fail(403, 'FORBIDDEN');
}
export async function transaction<T>(fn: (db: PoolClient) => Promise<T>): Promise<T> {
  const db = await pool.connect();
  try { await db.query('BEGIN'); const result = await fn(db); await db.query('COMMIT'); return result; }
  catch (error) { await db.query('ROLLBACK'); throw error; }
  finally { db.release(); }
}
export async function claim(db: PoolClient, idem: string, fingerprint: string): Promise<string | null> {
  const added = await db.query('INSERT INTO command_result(idempotency_key,request_hash,result_id) VALUES($1,$2,$3) ON CONFLICT DO NOTHING', [idem, fingerprint, '00000000-0000-0000-0000-000000000000']);
  if (added.rowCount) return null;
  const prior = await db.query('SELECT request_hash,result_id FROM command_result WHERE idempotency_key=$1', [idem]);
  if (prior.rows[0]?.request_hash !== fingerprint) throw fail(409, 'IDEMPOTENCY_CONFLICT');
  return prior.rows[0].result_id as string;
}
export async function complete(db: PoolClient, idem: string, id: string) {
  await db.query('UPDATE command_result SET result_id=$1 WHERE idempotency_key=$2', [id, idem]);
}
export async function event(db: PoolClient, aggregate: string, kind: string, payload: unknown) {
  await db.query('INSERT INTO outbox_event(id,aggregate_id,event_type,payload) VALUES($1,$2,$3,$4)', [newId(), aggregate, kind, payload]);
}
async function migrate() {
  const sql = await readFile(fileURLToPath(new URL(`../sql/V1__${name}.sql`, import.meta.url)), 'utf8');
  await transaction(async db => {
    await db.query('SELECT pg_advisory_xact_lock(hashtext($1))', [`migration-${name}`]);
    await db.query('CREATE TABLE IF NOT EXISTS schema_migration(version integer PRIMARY KEY)');
    const prior = await db.query('SELECT version FROM schema_migration WHERE version=1');
    if (!prior.rowCount) { await db.query(sql); await db.query('INSERT INTO schema_migration(version) VALUES(1)'); }
  });
}
app.setErrorHandler((error, _request, reply) => {
  const status = typeof error.statusCode === 'number' ? error.statusCode : (error as {code?: string}).code === '23505' ? 409
    : error.message.startsWith('INVALID_') ? 400 : 500;
  if (status >= 500) app.log.error(error);
  reply.status(status).send({ code: status >= 500 ? 'INTERNAL_ERROR' : status === 409 ? 'CONFLICT' : (error as {code?: string}).code || 'INVALID_REQUEST' });
});
app.get('/health/live', async () => ({ status: 'UP' }));
app.get('/health/ready', async () => { await pool.query('SELECT 1'); return { status: 'UP' }; });
export async function start() {
  await migrate(); await app.listen({ host: '0.0.0.0', port: Number(process.env.PORT || process.env[`${prefix}_SERVICE_PORT`] || 8080) });
}
