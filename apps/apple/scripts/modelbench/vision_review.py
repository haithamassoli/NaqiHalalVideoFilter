"""Render the measured masks and boxes; never treat another model as ground truth."""
import argparse
import json
from collections import defaultdict
from pathlib import Path

import cv2
import numpy as np
from vision_yolo import associate_face, iou


def read_rows(assets):
    rows = defaultdict(dict)
    for log in [assets / "native-default/native-default.jsonl", *sorted(assets.glob("yolo-*/results.jsonl"))]:
        if not log.exists():
            continue
        for line in log.read_text().splitlines():
            row = json.loads(line)
            if row["candidate"].startswith("yolo") and row["compute_requested"] not in ["ALL", "CPU"]:
                continue
            key = row["video"], row["frame"]
            if row["candidate"].startswith("yolo") and row["compute_requested"] == "CPU" and row["candidate"] in rows[key]:
                continue
            rows[key][row["candidate"]] = row, log.parent
    return rows


def panel(source, row, directory):
    out = source.copy()
    height, width = out.shape[:2]
    colors = [(64,220,255), (255,100,64), (80,255,100), (255,80,180), (200,170,50), (160,40,255)]
    for i, path in enumerate(row.get("masks", [])):
        raw = cv2.imread(str(directory / path), cv2.IMREAD_GRAYSCALE)
        if raw is None:
            raise ValueError(f"Missing mask {path}")
        mask = cv2.resize(raw, (width, height), interpolation=cv2.INTER_LINEAR) > 127
        color = np.asarray(colors[i % len(colors)])
        out[mask] = (out[mask] * 0.4 + color * 0.6).astype(np.uint8)
        contours, _ = cv2.findContours(mask.astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        cv2.drawContours(out, contours, -1, colors[i % len(colors)], 1)
    for i, box in enumerate(row.get("boxes", [])):
        p = np.round(np.asarray(box) * [width, height, width, height]).astype(int)
        cv2.rectangle(out, tuple(p[:2]), tuple(p[2:]), colors[i % len(colors)], 2)
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, required=True)
    args = parser.parse_args()
    rows = read_rows(args.assets)
    output = args.assets / "review"
    output.mkdir(exist_ok=True)
    candidates = ["source", "human-r2", "human-r3", "person-instance", "semantic-fast", "semantic-balanced", "yolo11n-seg", "yolo26n-seg"]
    selected = {"-dQJ3djthDc": [1,9,17,33,37,41,49], "rX6wXhLqOIQ": [1,5,9,17,18,21,25,29,33,37,41,45,49,53]}
    for video, indices in selected.items():
        for index in indices:
            frame = f"{index:04d}.png"
            source = cv2.imread(str(args.assets / "frames" / video / frame))
            height, width = source.shape[:2]
            sheet = np.zeros((2 * (height + 32), 4 * width, 3), np.uint8)
            for i, candidate in enumerate(candidates):
                x, y = (i % 4)*width, (i//4)*(height+32)
                row, directory = rows[video, frame].get(candidate, ({}, output))
                sheet[y:y+height,x:x+width] = panel(source, row, directory) if candidate != "source" else source
                label = candidate + (" (pending)" if candidate != "source" and not row else "")
                cv2.putText(sheet, label, (x+4,y+height+23), cv2.FONT_HERSHEY_SIMPLEX,0.48,(255,255,255),1)
            cv2.imwrite(str(output / f"{video}_{index:04d}_compare.jpg"), sheet)
    # Sparse manual interior probes are diagnostic evidence, not mask AP/recall.
    cases_path = Path(__file__).resolve().parents[2] / "docs/benchmarks/vision-review-cases.json"
    measurements = []
    for case in json.loads(cases_path.read_text())["cases"]:
        key = case["video"], case["frame"]
        source = cv2.imread(str(args.assets / "frames" / key[0] / key[1]))
        height, width = source.shape[:2]
        for candidate, (row, directory) in rows[key].items():
            if candidate.startswith("face"):
                continue
            covered = np.zeros((height,width), dtype=bool)
            for path in row.get("masks", []):
                raw = cv2.imread(str(directory / path), cv2.IMREAD_GRAYSCALE)
                covered |= cv2.resize(raw, (width,height), interpolation=cv2.INTER_LINEAR) > 127
            for box in row.get("boxes", []) if not row.get("masks") else []:
                x1,y1,x2,y2 = np.clip(np.round(np.asarray(box)*[width,height,width,height]),0,[width,height,width,height]).astype(int)
                covered[y1:y2,x1:x2] = True
            hits = [bool(covered[min(height-1,int(y*height)),min(width-1,int(x*width))]) for x,y in case["body_probes"]]
            measurements.append(dict(video=key[0],frame=key[1],candidate=candidate,covered_probes=sum(hits),
                                     total_probes=len(hits),missed_coordinates=[p for p,hit in zip(case["body_probes"],hits) if not hit],
                                     scope="pooled mask/box visible-body interior probes; ownership and boundary coverage unmeasured"))
    (output / "probe-results.json").write_text(json.dumps(measurements,indent=2)+"\n")
    presence = []
    for case in json.loads(cases_path.read_text())["presence_cases"]:
        key = case["video"], case["frame"]
        for candidate, (row, directory) in rows[key].items():
            if candidate.startswith("face") or candidate.startswith("semantic"):
                continue
            boxes = list(row.get("boxes", []))
            if not boxes:
                for path in row.get("masks", []):
                    raw = cv2.imread(str(directory / path), cv2.IMREAD_GRAYSCALE)
                    yy,xx = np.nonzero(raw>127)
                    if len(xx):
                        h,w = raw.shape
                        boxes.append([float(xx.min()/w),float(yy.min()/h),float((xx.max()+1)/w),float((yy.max()+1)/h)])
            pairs = sorted([(iou(gt,box),g,b) for g,gt in enumerate(case["boxes"]) for b,box in enumerate(boxes)],reverse=True)
            matched_gt,matched_boxes = set(),set()
            for score,g,b in pairs:
                if score>=.3 and g not in matched_gt and b not in matched_boxes:
                    matched_gt.add(g);matched_boxes.add(b)
            faces = rows[key].get("face-r4", ({},output))[0].get("boxes", [])
            matched_faces = [associate_face(face,boxes) for face in faces]
            presence.append(dict(video=key[0],frame=key[1],candidate=candidate,
                                 expected_principal_appearances=len(case["boxes"]),matched_principal_appearances=len(matched_gt),
                                 detections=len(boxes),unmatched_detections=len(boxes)-len(matched_boxes),
                                 uniquely_associated_faces=sum(b is not None for b in matched_faces),detected_faces=len(faces),
                                 scope="coarse diagnostic matching; positive-scene background detections not fully annotated"))
    (output / "presence-results.json").write_text(json.dumps(presence,indent=2)+"\n")
    print("Review sheets:", output)


if __name__ == "__main__":
    main()
