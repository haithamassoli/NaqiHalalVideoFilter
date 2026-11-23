"""Summarize measurements and reference/export parity; no teacher labels."""
import argparse
import hashlib
import json
import statistics
from pathlib import Path

import cv2
import numpy as np
from vision_yolo import iou


def rows(path):
    return [json.loads(line) for line in path.read_text().splitlines()]


def stats(values):
    values=sorted(values)
    return dict(count=len(values),median=statistics.median(values),p95=values[int(.95*(len(values)-1))]) if values else None


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assets',required=True,type=Path)
    parser.add_argument('--output',required=True,type=Path)
    args=parser.parse_args()
    all_rows=[]
    for path in sorted(args.assets.glob('native-*/*.jsonl'))+sorted(args.assets.glob('yolo-*/results.jsonl')):
        all_rows.extend(rows(path))
    summary=[]
    for candidate,compute in sorted(set((r['candidate'],r['compute_requested']) for r in all_rows)):
        group=[r for r in all_rows if r['candidate']==candidate and r['compute_requested']==compute]
        timing=[r for r in group if not r.get('warmup',False)]
        summary.append(dict(candidate=candidate,compute_requested=compute,rows=len(group),errors=sum('error' in r for r in group),
                            inference_ms=stats([r['inference_ms'] if 'inference_ms' in r else r['stage_ms']['inference'] for r in timing if 'error' not in r]),
                            prediction_wall_ms=stats([r['prediction_wall_ms'] for r in timing if 'prediction_wall_ms' in r]),
                            infer_mask_export_ms=stats([r['infer_mask_export_ms'] for r in timing]),
                            detections=sum(len(r['boxes']) for r in group),masks=sum(len(r['masks']) for r in group)))
    parity=[]
    reference={(r['candidate'],r['video'],r['frame']):r for r in rows(args.assets/'yolo-reference/results.jsonl')}
    for compute in ['CPU_ONLY','CPU_AND_GPU','ALL']:
        for candidate in ['yolo11n-seg','yolo26n-seg']:
            mask_ious,box_ious,unmatched_ref,unmatched_export,changed_frames=[],[],0,0,0
            for result in rows(args.assets/f'yolo-{compute}/results.jsonl'):
                if result['candidate']!=candidate:continue
                ref=reference[candidate,result['video'],result['frame']]
                pairs=sorted([(iou(a,b),i,j) for i,a in enumerate(ref['boxes']) for j,b in enumerate(result['boxes'])],reverse=True)
                used_ref,used_result=set(),set()
                for overlap,i,j in pairs:
                    if overlap<.5 or i in used_ref or j in used_result:continue
                    used_ref.add(i);used_result.add(j);box_ious.append(overlap)
                    a=cv2.imread(str(args.assets/'yolo-reference'/ref['masks'][i]),0)>127
                    b=cv2.imread(str(args.assets/f'yolo-{compute}'/result['masks'][j]),0)>127
                    union=np.count_nonzero(a|b)
                    mask_ious.append(float(np.count_nonzero(a&b)/union) if union else 1.0)
                unmatched_ref+=len(ref['boxes'])-len(used_ref)
                unmatched_export+=len(result['boxes'])-len(used_result)
                changed_frames+=int(len(ref['boxes'])!=len(result['boxes']))
            parity.append(dict(candidate=candidate,compute_requested=compute,matched_box_iou=stats(box_ious),matched_mask_iou=stats(mask_ious),
                               unmatched_reference_detections=unmatched_ref,unmatched_export_detections=unmatched_export,
                               frames_with_changed_count=changed_frames,threshold=.25,scope='reference/export agreement, not recall or annotation quality'))
    artifacts=[]
    for path in sorted((args.assets/'models').glob('*.pt')):
        package=path.with_suffix('.mlpackage')
        files=[dict(path=p.relative_to(package).as_posix(),sha256=hashlib.sha256(p.read_bytes()).hexdigest(),bytes=p.stat().st_size) for p in sorted(package.rglob('*')) if p.is_file()]
        artifacts.append(dict(candidate=path.stem,weights_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),weights_bytes=path.stat().st_size,
                              checkpoint_source=f'https://github.com/ultralytics/assets/releases/download/v8.4.0/{path.name}',
                              coreml_files=files,export=dict(format='mlprogram',imgsz=640,half=True,nms=False,dynamic=False,
                                                           ultralytics='8.4.29',coremltools='9.0',torch='2.7.0',numpy='2.2.6'),license='AGPL-3.0 (recorded; not screened out)'))
    report=dict(host='Apple M3 24GB',os='macOS 27.0.1 (26A434)',physical_iphone=False,frame_sampling_fps=1,input_long_side=640,
                limitations=['No physical-phone speed, memory or thermal claim','No tracking/identity-switch evaluation','No dense mask ground truth or every-frame coverage guarantee','Compute configuration is requested; individual operation placement not traced'],
                summary=summary,parity=parity,artifacts=artifacts,
                presence_diagnostics=json.loads((args.assets/'review/presence-results.json').read_text()),
                sparse_pixel_probes=json.loads((args.assets/'review/probe-results.json').read_text()))
    args.output.write_text(json.dumps(report,indent=2)+'\n')
    print('Vision summary:',args.output)


if __name__=='__main__':main()
