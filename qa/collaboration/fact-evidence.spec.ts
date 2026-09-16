import { test, expect } from '@playwright/test';

const rawBase = process.env.QA_BASE_URL;
if (!rawBase?.trim()) throw new Error('QA_BASE_URL required');
const BASE = rawBase.replace(/\/+$/, '');

/** p39-05: 事实来源锚点查询与时点筛选——GET /facts/evidence + POST。 */
test('p39-05: fact evidence query with temporal filter', async () => {
  const pr = await fetch(`${BASE}/api/projects`, { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({title: 'fact-ev', concept: 'fe', tags: '', mode: 'SOLO'}) });
  const pid = ((await pr.json()) as {id:string}).id;

  // Add a fact
  const factRes = await fetch(`${BASE}/api/projects/${pid}/facts`, { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({factType: 'WORLD_RULE', subject: 'Magic System', assertion: 'Rain amplifies magic'}) });
  const fact = await factRes.json();

  // Add a chapter for identity
  const chRes = await fetch(`${BASE}/api/projects/${pid}/chapters`, { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({title: 'Ch1', content: 'The rain fell on the ancient stones. '.repeat(30)}) });
  const chapter = await chRes.json();
  const hash = 'test-hash-' + Date.now();

  // POST evidence with identity binding
  const evRes = await fetch(`${BASE}/api/projects/${pid}/facts/${fact.id}/evidence`, {
    method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({chapterIndex: 1, chapterVersion: chapter.version, contentHash: hash,
      quote: 'The rain fell', validFromChapter: 1,
      observers: [{characterId: 'hero', learnedAtChapter: 1}]}) });
  expect(evRes.status).toBe(200);
  const evidence = await evRes.json();
  expect(evidence.quoteKnown).toBe(true);

  // POST without identity → 400
  const noIdRes = await fetch(`${BASE}/api/projects/${pid}/facts/${fact.id}/evidence`, {
    method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({quote: 'x'}) });
  expect(noIdRes.status).toBe(400);

  // GET evidence
  const queryRes = await fetch(`${BASE}/api/projects/${pid}/facts/evidence?factId=${fact.id}`);
  expect(queryRes.status).toBe(200);
  const evidenceList = await queryRes.json();
  expect(evidenceList.length).toBe(1);

  // GET with asOfChapter filter
  const asOfRes = await fetch(`${BASE}/api/projects/${pid}/facts/evidence?factId=${fact.id}&asOfChapter=1`);
  const asOfList = await asOfRes.json();
  expect(asOfList.length).toBe(1);

  // GET with unknown character filter
  const unknownRes = await fetch(`${BASE}/api/projects/${pid}/facts/evidence?factId=${fact.id}&characterId=nobody`);
  const unknownList = await unknownRes.json();
  expect(unknownList.length).toBe(0);
});
