#!/usr/bin/env python3
"""Validate persisted harness state; never execute feature verify_cmd strings."""
import argparse
from collections import Counter
from datetime import date
import json
from pathlib import Path
import re
import subprocess


def audit(root, check_git=False):
    root = Path(root).resolve()
    errors, warnings = [], []

    def require(condition, message):
        if not condition:
            errors.append(message)

    def local_file(value):
        if not isinstance(value, str) or not value:
            return False
        path = (root / value).resolve()
        return path.is_relative_to(root) and path.is_file()

    def read_json(name):
        if not local_file(name):
            errors.append(f'missing file: {name}')
            return {}
        try:
            obj = json.loads((root / name).read_text())
            if not isinstance(obj, dict):
                raise ValueError('expected object')
            return obj
        except (ValueError, OSError) as exc:
            errors.append(f'{name}: {exc}')
            return {}

    def ancestor(commit, target):
        if not isinstance(target, str) or not isinstance(commit, str) or not re.fullmatch(r'[0-9a-f]{40}', commit):
            return False
        return subprocess.run(['git', '-C', str(root), 'merge-base', '--is-ancestor', commit, target],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0

    data = read_json('feature_list.json')
    ledger = read_json('docs/harness/completions.json')
    require(data.get('schema_version') == 2, 'feature_list schema_version must be 2')
    require(ledger.get('schema_version') == 1, 'completions schema_version must be 1')
    features = data.get('features', [])
    if not isinstance(features, list) or any(not isinstance(f, dict) for f in features):
        return errors + ['features must be an array of objects'], warnings
    require(bool(features), 'features must not be empty')
    ids = [f.get('id') for f in features]
    if any(not isinstance(i, str) for i in ids):
        return errors + ['feature id must be a string'], warnings
    require(len(ids) == len(set(ids)), 'duplicate feature id')
    by_id = {f['id']: f for f in features}
    records = ledger.get('records', [])
    if not isinstance(records, list) or any(not isinstance(r, dict) for r in records):
        return errors + ['completion records must be an array of objects'], warnings
    record_ids = [r.get('id') for r in records]
    if any(not isinstance(i, str) for i in record_ids):
        return errors + ['completion record id must be a string'], warnings
    require(len(record_ids) == len(set(record_ids)), 'duplicate completion record id')
    record_map = {r['id']: r for r in records}
    active = [f['id'] for f in features if f.get('status') == 'in-progress']
    require(len(active) <= 1, 'at most one active feature')
    require(data.get('active_feature') == (active[0] if len(active) == 1 else None),
            'active_feature must match in-progress feature')
    phases = [f.get('phase') for f in features]
    require(all(isinstance(p, int) and p >= 0 for p in phases), 'phase must be a non-negative integer')
    if phases and all(isinstance(p, int) for p in phases):
        require(data.get('current_phase') == max(phases), 'current_phase must match latest registered phase')
    try:
        date.fromisoformat(data.get('updated_at', ''))
    except (ValueError, TypeError):
        errors.append('updated_at must be an ISO date')

    for f in features:
        fid = f['id']
        for field in ['name', 'description', 'acceptance', 'verify_cmd']:
            require(isinstance(f.get(field), str) and bool(f[field].strip()), f'{fid}: missing {field}')
        require(f.get('status') in ['not-started', 'in-progress', 'deferred', 'completed'], f'{fid}: invalid status')
        deps = f.get('dependencies')
        require(isinstance(deps, list) and all(isinstance(d, str) for d in deps), f'{fid}: invalid dependencies')
        for dep in deps if isinstance(deps, list) else []:
            if not isinstance(dep, str):
                continue
            require(dep in by_id, f'{fid}: unknown dependency {dep}')
            if f.get('status') in ['in-progress', 'completed']:
                require(dep in by_id and by_id[dep].get('status') == 'completed', f'{fid}: incomplete dependency {dep}')
        paths = f.get('files')
        require(isinstance(paths, list) and all(isinstance(p, str) for p in paths), f'{fid}: invalid files')
        if f.get('status') in ['in-progress', 'completed'] and isinstance(paths, list):
            for path in paths:
                require(local_file(path), f'{fid}: missing implementation file {path}')
        if f.get('spec') or (isinstance(f.get('phase'), int) and f['phase'] >= 31):
            require(local_file(f.get('spec')), f'{fid}: missing spec {f.get("spec")}')
        if f.get('status') == 'deferred':
            require(bool(f.get('deferred_reason')), f'{fid}: missing deferred_reason')
        if f.get('status') == 'completed':
            require(f.get('delivery_state') != 'local-unmerged', f'{fid}: local-unmerged cannot be completed')
            require(bool(f.get('evidence')), f'{fid}: missing evidence')
            rec = record_map.get(f.get('completion_record'))
            require(rec is not None, f'{fid}: missing completion_record')
            if rec:
                require(fid in rec.get('feature_ids', []), f'{fid}: absent from completion_record')
                if isinstance(f.get('phase'), int) and f['phase'] >= 31:
                    require(rec.get('kind') == 'verified', f'{fid}: new feature cannot use historical completion')

    visiting, visited = set(), set()
    def visit(fid):
        if fid in visiting:
            errors.append(f'dependency cycle at {fid}')
            return
        if fid in visited:
            return
        visiting.add(fid)
        deps = by_id[fid].get('dependencies', [])
        for dep in deps if isinstance(deps, list) else []:
            if isinstance(dep, str) and dep in by_id:
                visit(dep)
        visiting.remove(fid)
        visited.add(fid)
    for fid in ids:
        visit(fid)

    for rec in records:
        rid = rec['id']
        members = rec.get('feature_ids')
        require(isinstance(members, list) and all(isinstance(i, str) for i in members), f'{rid}: invalid feature_ids')
        for fid in members if isinstance(members, list) else []:
            if not isinstance(fid, str):
                continue
            require(fid in by_id and by_id[fid].get('status') == 'completed'
                    and by_id[fid].get('completion_record') == rid, f'{rid}: inconsistent feature {fid}')
        require(rec.get('kind') in ['verified', 'historical'], f'{rid}: invalid kind')
        if rec.get('kind') == 'historical':
            require(bool(rec.get('notes')), f'{rid}: historical limitations must be documented')
            warnings.append(f'{rid}: historical evidence retained; per-feature merge/run proof unavailable')
            if check_git:
                require(ancestor(rec.get('baseline_commit'), 'main'), f'{rid}: baseline not ancestor of main')
            continue
        require(isinstance(rec.get('branch'), str) and rec['branch'].startswith('codex/'), f'{rid}: missing branch')
        ev_path = rec.get('evidence')
        require(local_file(ev_path), f'{rid}: missing evidence file {ev_path}')
        if local_file(ev_path):
            ev = read_json(ev_path)
            checks = ev.get('checks', [])
            require(isinstance(checks, list) and bool(checks), f'{rid}: missing checks')
            for check in checks if isinstance(checks, list) else []:
                require(isinstance(check, dict) and bool(check.get('command')) and bool(check.get('result'))
                        and check.get('exitCode') == 0, f'{rid}: evidence requires command/result/exitCode=0')
            require(ev.get('commit') == rec.get('main_commit'), f'{rid}: evidence commit does not match main_commit')
            require(ev.get('cleanCheckout') is True, f'{rid}: evidence must describe clean main checkout')
        for key in ['implementation_commit', 'main_commit']:
            require(isinstance(rec.get(key), str) and bool(re.fullmatch(r'[0-9a-f]{40}', rec[key])), f'{rid}: invalid {key}')
        if check_git:
            require(ancestor(rec.get('implementation_commit'), rec.get('main_commit', 'main')), f'{rid}: implementation not ancestor of main_commit')
            require(ancestor(rec.get('main_commit'), 'main'), f'{rid}: main_commit not ancestor of main')

    for path in ['AGENTS.md', 'init.sh', 'docs/specs/TEMPLATE.md', 'progress.md', 'session-handoff.md']:
        require(local_file(path), f'missing artifact {path}')
    for path in ['progress.md', 'session-handoff.md']:
        if not local_file(path):
            continue
        content = (root / path).read_text()
        for heading in ['Current', 'Files', 'Verification', 'Blockers', 'Next Steps']:
            require(f'## {heading}' in content, f'{path}: missing {heading}')
        require(isinstance(data.get('updated_at'), str) and data['updated_at'] in content, f'{path}: date differs from feature_list')
        require(len(content.splitlines()) <= 150, f'{path}: archive history; current entry exceeds 150 lines')
        for link in re.findall(r'\]\(([^)]+)\)', content):
            if '://' not in link and not link.startswith('#'):
                require(local_file(link.split('#')[0]), f'{path}: broken link {link}')
    return errors, warnings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--check-git', action='store_true', help='also verify completion commit ancestry in local main')
    args = parser.parse_args()
    errors, warnings = audit(args.root, args.check_git)
    for item in warnings:
        print('WARN:', item)
    for item in errors:
        print('FAIL:', item)
    if not errors:
        data = json.loads((args.root / 'feature_list.json').read_text())
        print('PASS: harness consistent;', len(data['features']), 'features;', dict(Counter(f['status'] for f in data['features'])))
    return 1 if errors else 0


if __name__ == '__main__':
    raise SystemExit(main())
