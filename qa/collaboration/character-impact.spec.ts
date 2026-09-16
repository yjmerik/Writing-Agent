import { test, expect } from '@playwright/test';

const rawBase = process.env.QA_BASE_URL;
if (!rawBase?.trim()) throw new Error('QA_BASE_URL required');
const BASE = rawBase.replace(/\/+$/, '');

/** p39-06: 人物修改预览与安全确认——confirm=false 不落库。 */
test('p39-06: character impact preview does not persist', async () => {
  const pr = await fetch(`${BASE}/api/projects`, { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({title: 'char-impact', concept: 'ci', tags: '', mode: 'SOLO'}) });
  const pid = ((await pr.json()) as {id:string}).id;

  // Create character
  const chRes = await fetch(`${BASE}/api/projects/${pid}/characters`, { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({name: 'Hero', role: '主角', motivation: 'Revenge for family'}) });
  const character = await chRes.json();
  const charId = character.id;

  // PUT with confirm=false → must NOT persist
  const previewRes = await fetch(`${BASE}/api/projects/${pid}/characters/${charId}`, {
    method: 'PUT', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({motivation: 'Preview only', confirm: false}) });
  expect(previewRes.status).toBe(200);
  const preview = await previewRes.json();
  expect(preview.persisted).toBe(false);
  expect(preview.previewToken).toBeTruthy();
  expect(preview.impact).toBeTruthy();
  expect(preview.impact.certainReferences).toBeDefined();
  expect(preview.impact.possibleReferences).toBeDefined();

  // Verify not persisted
  const afterPreview = await (await fetch(`${BASE}/api/projects/${pid}/characters`)).json();
  expect(afterPreview[0].motivation).toBe('Revenge for family');

  // Confirm with valid token
  const confirmRes = await fetch(`${BASE}/api/projects/${pid}/characters/${charId}`, {
    method: 'PUT', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({motivation: 'New confirmed motivation', confirm: true,
      previewToken: preview.previewToken}) });
  expect(confirmRes.status).toBe(200);
  const confirmed = await confirmRes.json();
  expect(confirmed.persisted).toBe(true);

  // Verify persisted
  const afterConfirm = await (await fetch(`${BASE}/api/projects/${pid}/characters`)).json();
  expect(afterConfirm[0].motivation).toBe('New confirmed motivation');

  // Stale token after source changed → 409
  const staleRes = await fetch(`${BASE}/api/projects/${pid}/characters/${charId}`, {
    method: 'PUT', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({motivation: 'Another change', confirm: true,
      previewToken: preview.previewToken}) });
  expect(staleRes.status).toBe(409);

  // Impact preview endpoint (read-only)
  const impactRes = await fetch(`${BASE}/api/projects/${pid}/characters/${charId}/impact-preview`, {
    method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({motivation: 'Another preview'}) });
  expect(impactRes.status).toBe(200);
  const impact = await impactRes.json();
  expect(impact.persisted).toBe(false);
  expect(impact.previewToken).toBeTruthy();
});
