#!/usr/bin/env bash
# p40-05: Full-chain acceptance verification
set -euo pipefail
echo "=== Collaboration Acceptance Verification ==="
python3 - <<'INNER'
import json

with open('feature_list.json') as f:
    data = json.load(f)

all_ids = []
for phase_start, phase_end in [('p36-01','p36-13'), ('p37-01','p37-16'),
                                 ('p38-01','p38-13'), ('p39-01','p39-10'),
                                 ('p40-01','p40-06')]:
    start_num = int(phase_start.split('-')[1])
    end_num = int(phase_end.split('-')[1])
    phase = phase_start.split('-')[0]
    for i in range(start_num, end_num + 1):
        all_ids.append(f'{phase}-{i:02d}')

completed = []
incomplete = []
for fid in all_ids:
    status = 'unknown'
    for feat in data['features']:
        if feat['id'] == fid:
            status = feat.get('status', 'unknown')
            break
    if status == 'completed':
        completed.append(fid)
    else:
        incomplete.append(fid)

print(f'Completed: {len(completed)} / {len(all_ids)}')
print(f'Incomplete: {len(incomplete)}')
if incomplete:
    print('Incomplete features:', ', '.join(incomplete))

p40_06 = 'unknown'
for feat in data['features']:
    if feat['id'] == 'p40-06':
        p40_06 = feat.get('status', 'unknown')
        break
print(f'p40-06 (real experiment): {p40_06}')

if incomplete:
    print('=== Acceptance: INCOMPLETE ===')
    exit(1)

if p40_06 != 'completed':
    print('=== Acceptance: INCOMPLETE (p40-06 real experiment not yet run) ===')
    exit(1)

print('=== Acceptance: COMPLETE ===')
INNER
