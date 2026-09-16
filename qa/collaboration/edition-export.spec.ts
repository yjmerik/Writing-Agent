import { test, expect } from '@playwright/test';

const rawBase = process.env.QA_BASE_URL;
if (!rawBase?.trim()) throw new Error('QA_BASE_URL required');
const BASE = rawBase.replace(/\/+$/, '');

/** p38-08: 固定成书版本——创建快照、批准（manifestHash+资格）、导出固定字节。 */
test('p38-08: book edition lifecycle', async () => {
  const pr = await fetch(`${BASE}/api/projects`, { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({title: 'edition-test', concept: 'et', tags: '', mode: 'SOLO'}) });
  const pid = ((await pr.json()) as {id:string}).id;

  // Add chapters
  for (let i = 1; i <= 2; i++) {
    await fetch(`${BASE}/api/projects/${pid}/chapters`, { method: 'POST', headers: {'Content-Type':'application/json'},
      body: JSON.stringify({title: `Ch${i}`, content: `Chapter ${i} content for edition test. `.repeat(30)}) });
  }

  // Create edition snapshot
  const createRes = await fetch(`${BASE}/api/projects/${pid}/book-editions`, { method: 'POST' });
  expect(createRes.status).toBe(200);
  const edition = await createRes.json();
  expect(edition.editionId).toBeTruthy();
  expect(edition.manifestHash).toBeTruthy();
  expect(edition.state).toBe('DRAFT');
  expect(edition.chapterCount).toBe(2);

  // List
  const listRes = await fetch(`${BASE}/api/projects/${pid}/book-editions`);
  expect(listRes.status).toBe(200);
  const list = await listRes.json();
  expect(list.length).toBe(1);

  // Detail
  const detailRes = await fetch(`${BASE}/api/projects/${pid}/book-editions/${edition.editionId}`);
  expect(detailRes.status).toBe(200);
  const detail = await detailRes.json();
  expect(detail.chapters.length).toBe(2);
  expect(detail.chapters[0].chapterIndex).toBe(1);

  // Approve without hash → 400
  const noHashRes = await fetch(`${BASE}/api/projects/${pid}/book-editions/${edition.editionId}/approve`, {
    method: 'POST', headers: {'Content-Type':'application/json'}, body: '{}' });
  expect(noHashRes.status).toBe(400);

  // Approve with wrong hash → 409
  const wrongHashRes = await fetch(`${BASE}/api/projects/${pid}/book-editions/${edition.editionId}/approve`, {
    method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({manifestHash: 'wrong'}) });
  expect(wrongHashRes.status).toBe(409);

  // Approve before chapters are author-approved → 409 (eligibility)
  const notApprovedRes = await fetch(`${BASE}/api/projects/${pid}/book-editions/${edition.editionId}/approve`, {
    method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({manifestHash: edition.manifestHash}) });
  expect(notApprovedRes.status).toBe(409);

  // Export (DRAFT mode works, shows DRAFT label)
  const exportRes1 = await fetch(`${BASE}/api/projects/${pid}/book-editions/${edition.editionId}/export`);
  expect(exportRes1.status).toBe(200);
  const md1 = await exportRes1.text();
  expect(md1).toContain('DRAFT');
  expect(md1).toContain('Ch1');

  // Approve both chapters via versioned API
  for (let i = 1; i <= 2; i++) {
    const ctx = await (await fetch(`${BASE}/api/projects/${pid}/chapters/${i}/review-context`)).json();
    const approveRes = await fetch(`${BASE}/api/projects/${pid}/chapters/${i}/review-decisions`, {
      method: 'POST', headers: {'Content-Type':'application/json'},
      body: JSON.stringify({identity: ctx.identity, reviewSetHash: ctx.reviewSetHash,
        idempotencyKey: `ed-ap-${i}`}) });
    expect(approveRes.status).toBe(200);
  }

  // Now approve edition
  const approveRes = await fetch(`${BASE}/api/projects/${pid}/book-editions/${edition.editionId}/approve`, {
    method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({manifestHash: edition.manifestHash}) });
  expect(approveRes.status).toBe(200);

  // Export after approval → same content (fixed bytes)
  const exportRes2 = await fetch(`${BASE}/api/projects/${pid}/book-editions/${edition.editionId}/export`);
  const md2 = await exportRes2.text();
  expect(md2).toContain('APPROVED');
  expect(md2).toContain('Ch1'); expect(md2).toContain('Ch2'); // same chapter content
});
