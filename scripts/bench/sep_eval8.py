#!/usr/bin/env python3
"""Score one separator arm on the same 8 mixes (plan §5.2 follow-up). Usage: .venv-bench/bin/python scripts/bench/sep_eval8.py <sep0|sep1|sep5|sep6|sep9|sepr2|sepr3>"""
# Same 8 mixes for every arm: per SNR bin, first night_owl (instrumental) + first battle_hymn (sung).
import sys, json, time
sys.path.insert(0, 'scripts/bench')
from pathlib import Path
import numpy as np, score_sep as S
arm = sys.argv[1]
mixes = S.load_manifest()['mixes']
pick, seen = [], set()
for m in mixes:
    k = (m['snr_db'], m['music'])
    if m['music'] in ('night_owl', 'battle_hymn') and k not in seen:
        seen.add(k); pick.append(m)
if arm in ('sep0', 'sep1'):
    sep = S.build_arm(arm)
elif arm in ('sep5', 'sep6', 'sep9'):
    import sep_light as L; sep = L.build_arm(arm)
else:
    import sep_roformer as R; sep = R.RoformerOrt(R.arm_from_cfg(R.active_arm(arm)))
rows = []
for m in pick:
    mix = S.load_wav(Path(m['base'] + '_mix.wav')); sp = S.load_wav(Path(m['base'] + '_speech.wav')); mu = S.load_wav(Path(m['base'] + '_music.wav'))
    t0 = time.perf_counter(); est = sep.separate(mix); wall = time.perf_counter() - t0
    r = {'id': m['id'], 'snr': m['snr_db'], 'music': m['music'], 'wall_s': wall, **S.si_metrics(est, sp, mu)}
    if m['sung']: r['sing_proj'] = S.singing_retained(est, mu)['proj_fraction']
    rows.append(r); print(arm, r, flush=True)
Path(f'qa-assets/bench-out/sep/eval8_{arm}.json').write_text(json.dumps(rows, indent=1))
