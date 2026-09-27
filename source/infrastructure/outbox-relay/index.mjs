import pg from 'pg';
import amqp from 'amqplib';
import http from 'node:http';

const required = name => { const value=process.env[name]; if(!value) throw new Error(`${name} is required`); return value; };
const db=new pg.Pool({connectionString:required('DATABASE_URL'),max:2});
const rabbit=required('RABBITMQ_URL');
const source=process.env.EVENT_SOURCE || 'unknown-service';
const exchange=process.env.EVENT_EXCHANGE || 'coffee.events';
const delay=Number(process.env.RELAY_INTERVAL_MS || 500);
let connection,channel;
let lastSuccess=0;
http.createServer((request,response)=>{
  const healthy=Date.now()-lastSuccess<30000;
  response.writeHead(healthy?200:503,{'content-type':'application/json'});
  response.end(JSON.stringify({status:healthy?'UP':'DOWN',source}));
}).listen(8080,'0.0.0.0');

async function broker() {
  if(channel) return channel;
  connection=await amqp.connect(rabbit);
  connection.on('close',()=>{connection=undefined;channel=undefined;});
  channel=await connection.createConfirmChannel();
  await channel.assertExchange(exchange,'topic',{durable:true});
  return channel;
}

async function relay() {
  const client=await db.connect();
  try {
    await client.query('BEGIN');
    const rows=await client.query("SELECT id,aggregate_id,event_type,payload,created_at FROM outbox_event WHERE published_at IS NULL ORDER BY created_at LIMIT 50 FOR UPDATE SKIP LOCKED");
    if(!rows.rowCount) { await client.query('COMMIT'); lastSuccess=Date.now(); return; }
    const ch=await broker();
    for(const row of rows.rows) {
      const envelope={eventId:row.id,eventType:row.event_type,source,aggregateId:row.aggregate_id,occurredAt:row.created_at,payload:row.payload};
      ch.publish(exchange,row.event_type,Buffer.from(JSON.stringify(envelope)),{persistent:true,contentType:'application/json',messageId:row.id,type:row.event_type});
    }
    await ch.waitForConfirms();
    await client.query('UPDATE outbox_event SET published_at=now() WHERE id=ANY($1::uuid[])',[rows.rows.map(row=>row.id)]);
    await client.query('COMMIT'); lastSuccess=Date.now();
  } catch(error) { await client.query('ROLLBACK'); throw error; }
  finally { client.release(); }
}

for(;;) {
  try { await relay(); } catch(error) { console.error(JSON.stringify({level:'error',source,message:error.message})); }
  await new Promise(resolve=>setTimeout(resolve,delay));
}
