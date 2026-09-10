const bcrypt = require('bcryptjs');
const { Client } = require('pg');

(async () => {
  const hash = await bcrypt.hash('TestPassword123', 12);

  const client = new Client({
    connectionString: 'postgresql://postgres:postgres@localhost:5432/ludomada'
  });

  await client.connect();

  const result = await client.query(
    'UPDATE "User" SET "passwordHash" = $1 WHERE username = $2',
    [hash, 'testplayer1']
  );

  console.log('Updated:', result.rowCount);

  await client.end();
})();
