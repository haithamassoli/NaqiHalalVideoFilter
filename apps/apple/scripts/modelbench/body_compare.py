"""Full-video experiments, separate from the app. Run with the vision-modelbench venv.

python scripts/modelbench/body_compare.py --self-check
python scripts/modelbench/body_compare.py prepare|models|native|infer|gender|render|report
Timing is Mac-host screening, never a physical-phone claim.
"""
import argparse
import bisect
import hashlib
import importlib.metadata
import json
import platform
import resource
import subprocess
import sys
import time
from pathlib import Path
from types import SimpleNamespace
import urllib.request

import cv2
import numpy as np
from vision_yolo import iou, associate_face
from vision_gender import insight_crop, crop

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / "qa-assets/modelbench/body-comparison-2026-10-05"
SOURCE = ROOT / "qa-assets/modelbench/rX6wXhLqOIQ.ios.mp4"
MODELS = WORK / "models"
EXPOSED = {"FEMALE_BREAST_EXPOSED", "MALE_BREAST_EXPOSED", "BELLY_EXPOSED",
           "FEMALE_GENITALIA_EXPOSED", "MALE_GENITALIA_EXPOSED", "BUTTOCKS_EXPOSED", "ANUS_EXPOSED"}


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2) + "\n")
    temporary.replace(path)


def digest(path):
    with path.open("rb") as file:
        return hashlib.file_digest(file, "sha256").hexdigest()


def load(name):
    return json.loads((WORK / (name + ".json")).read_text())


def read_rows(name):
    return load(name)["rows"]


def image_at(sample):
    image = cv2.imread(str(WORK / "frames/source" / sample["file"]))
    if image is None:
        raise ValueError("unreadable sampled frame")
    return image


def clip_box(box):
    a = np.clip(np.asarray(box, dtype=float), 0, 1)
    if a.shape != (4,) or not np.isfinite(a).all():
        raise ValueError("invalid box")
    return a.tolist() if a[2] > a[0] and a[3] > a[1] else None


def padded(box, ratio=.12):
    x1, y1, x2, y2 = box
    dx, dy = (x2-x1)*ratio, (y2-y1)*ratio
    return clip_box([x1-dx, y1-dy, x2+dx, y2+dy])


def cut_score(previous, current):
    return float(np.abs(previous.astype(float)-current.astype(float)).mean()/255)


def prepare():
    info = json.loads((ROOT / "qa-assets/modelbench/rX6wXhLqOIQ.info.json").read_text())
    assert info["id"] == "rX6wXhLqOIQ"
    out = WORK / "frames/source"
    out.mkdir(parents=True, exist_ok=True)
    capture = cv2.VideoCapture(str(SOURCE))
    if not capture.isOpened():
        raise ValueError("video did not open")
    fps = capture.get(cv2.CAP_PROP_FPS)
    assert abs(fps-30) < .001, "this runner requires the verified 30 fps CFR input"
    previous, samples, cuts, index = None, [], [], 0
    started = time.perf_counter()
    while True:
        ok, image = capture.read()
        if not ok:
            break
        small = cv2.resize(cv2.cvtColor(image, cv2.COLOR_BGR2GRAY), (16, 16))
        # ponytail: luma cut heuristic; use annotated shot boundaries if subtle cuts matter.
        is_cut = previous is not None and cut_score(previous, small) > .16
        if is_cut:
            cuts.append(index)
        if index % 6 == 0 or is_cut:
            h, w = image.shape[:2]
            resized = cv2.resize(image, (round(w*640/h), 640), interpolation=cv2.INTER_AREA)
            filename = f"{len(samples)+1:06d}.png"
            if not cv2.imwrite(str(out / filename), resized):
                raise OSError("sample PNG write failed")
            samples.append(dict(file=filename, index=index, time_s=index/fps, cut=is_cut))
        previous, index = small, index+1
    capture.release()
    assert index == 1598, "incomplete or changed source video"
    save(WORK / "manifest.json", dict(source=str(SOURCE), source_url=info["webpage_url"],
         source_sha256=digest(SOURCE), frames=index, width=608, height=1080, fps=fps,
         video_duration_s=index/fps, samples=samples, cuts=cuts, sampling_fps=5,
         extraction_s=time.perf_counter()-started, host="Apple M3, 24GB", os=platform.mac_ver()[0],
         physical_phone=False, cut_threshold=.16))
    print("prepared", index, "frames", len(samples), "samples", len(cuts), "cuts", flush=True)


def download(url, path):
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(path.suffix + ".download")
        request = urllib.request.Request(url)
        if "/NudeNet/releases/download/" in url:
            release_url = "https://api.github.com/repos/notAI-tech/NudeNet/releases/tags/v3.4-weights"
            with urllib.request.urlopen(release_url, timeout=30) as response:
                release = json.load(response)
            asset = next(a for a in release["assets"] if a["name"]==url.rsplit("/",1)[1])
            request = urllib.request.Request(asset["url"]+"?download=1",headers={"Accept":"application/octet-stream"})
        with urllib.request.urlopen(request, timeout=60) as response, temporary.open("wb") as file:
            if any(t in response.headers.get("Content-Type", "") for t in ["html", "json"]):
                raise ValueError(f"model download returned a webpage: {url}")
            import shutil
            shutil.copyfileobj(response, file)
        temporary.replace(path)
    return dict(url=url, file=str(path), bytes=path.stat().st_size, sha256=digest(path))


def models():
    from ultralytics import YOLO
    artifacts = []
    for name, side in [("yolov8n", 320), ("yolo11n", 320), ("yolov8n-seg", 640)]:
        path = MODELS / (name + ".pt")
        artifacts.append(download(f"https://github.com/ultralytics/assets/releases/download/v8.4.0/{name}.pt", path))
        if not path.with_suffix(".mlpackage").exists():
            YOLO(path).export(format="coreml", imgsz=side, half=True, nms=False, dynamic=False, device="cpu")
    artifacts.append(download("https://github.com/notAI-tech/NudeNet/releases/download/v3.4-weights/320n.pt", MODELS / "nude320.pt"))
    artifacts.append(download("https://github.com/notAI-tech/NudeNet/releases/download/v3.4-weights/640m.pt", MODELS / "nude640.pt"))
    artifacts.append(download("https://github.com/notAI-tech/NudeNet/releases/download/v3.4-weights/640m.onnx", MODELS / "nude640.onnx"))
    for size in ["lite", "full"]:
        artifacts.append(download(f"https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_{size}/float16/latest/pose_landmarker_{size}.task", MODELS / f"pose-{size}.task"))
    # A tensor-input export preserves the official NudeNet package's preprocessing exactly.
    import torch
    import coremltools as ct
    torch.set_num_threads(1)
    for side in [320,640]:
        path = MODELS / f"nude{side}.mlpackage"
        if path.exists():
            continue
        model = YOLO(MODELS / f"nude{side}.pt").model.eval()
        model.model[-1].export = True
        model.model[-1].format = "coreml"
        example = torch.zeros(1, 3, side, side)
        with torch.inference_mode():
            model(example)
            model(example)
        traced = torch.jit.trace(model, example, check_trace=False)
        converted = ct.convert(traced, inputs=[ct.TensorType(name="images", shape=(1,3,side,side))],
            convert_to="mlprogram", minimum_deployment_target=ct.target.iOS18,
            compute_precision=ct.precision.FLOAT16)
        converted.save(str(path))
    import nudenet.nudenet as nude
    artifacts.append(dict(file=str(Path(nude.__file__).parent / "320n.onnx"),
        sha256=digest(Path(nude.__file__).parent / "320n.onnx"), source="nudenet==3.4.2 wheel"))
    save(WORK / "artifacts.json", dict(artifacts=artifacts,
        packages={n: importlib.metadata.version(n) for n in ["ultralytics", "coremltools", "torch", "nudenet", "mediapipe", "onnxruntime"]}))


def native():
    started = time.perf_counter()
    subprocess.run([str(ROOT / "build.noindex/vision-modelbench/vision_native"),
        str(WORK / "frames"), str(WORK / "native"), "default"], check=True)
    manifest = load("manifest")
    samples = {s["file"]: s for s in manifest["samples"]}
    groups = {}
    for line in (WORK / "native/native-default.jsonl").read_text().splitlines():
        row = json.loads(line)
        if "error" in row:
            raise RuntimeError(row)
        row.update(samples[row["frame"]])
        row["timestamp_s"] = row["time_s"]
        row.setdefault("confidence", [])
        row["masks"] = [str(WORK / "native" / p) for p in row["masks"]]
        row["classes"] = ["face" if row["candidate"].startswith("face") else "person"] * len(row["boxes"])
        groups.setdefault(row["candidate"], []).append(row)
    for name, rows in groups.items():
        save(WORK / (name + ".json"), dict(rows=rows, runtime="Apple Vision (Swift)",
            analysis_s=sum(r["processing_ms"] for r in rows)/1000,
            debug_png_export_s=sum(r["png_export_ms"] for r in rows)/1000,
            inference_s=sum(r["inference_ms"] for r in rows)/1000,
            timing_scope="Vision request and mask generation; debug PNG encoding excluded; image path loading included",
            frame_count=len(rows), native_run_total_s=time.perf_counter()-started))


def litert_models():
    import os
    os.chdir(MODELS)  # onnx2tf downloads its sample data into the working directory.
    import torch
    torch.set_num_threads(1)
    from ultralytics import YOLO
    records = []
    for name in ["yolov8n","yolo11n"]:
        package = MODELS / (name+"_saved_model")
        if not (package / (name+"_float32.tflite")).exists():
            YOLO(MODELS / (name+".pt")).export(format="tflite",imgsz=320,nms=False,dynamic=False,device="cpu")
        for path in package.glob("*.tflite"):
            records.append(dict(file=str(path),sha256=digest(path),bytes=path.stat().st_size))
    save(WORK / "litert-artifacts.json",dict(artifacts=records,
        packages={n:importlib.metadata.version(n) for n in ["tensorflow","ai-edge-litert","onnx2tf","numpy"]}))


def infer(name):
    samples = load("manifest")["samples"]
    first = image_at(samples[0])
    options, kind, load_started = {}, None, time.perf_counter()
    if name.startswith("yolo"):
        import torch
        torch.set_num_threads(1)
        from ultralytics import YOLO
        segment = "seg" in name
        lite = "-litert" in name
        stem = name.split("-litert" if lite else "-coreml")[0]
        if lite:
            precision = "float16" if name.endswith("fp16") else "float32"
            package = MODELS / (stem+"_saved_model") / (stem+"_"+precision+".tflite")
        else:
            package = (ROOT / "qa-assets/modelbench/vision/models/yolo11n-seg.mlpackage") if stem == "yolo11n-seg" else MODELS / (stem + ".mlpackage")
        model = YOLO(package, task="segment" if segment else "detect")
        options = dict(imgsz=640 if segment else 320, conf=.1, iou=.7, classes=[0],
            rect=False, device="cpu", verbose=False, retina_masks=segment)
        model.predict(first, **options)
        if lite:
            from ai_edge_litert.interpreter import Interpreter
            backend = model.predictor.model.backend
            backend.interpreter = Interpreter(model_path=str(package),num_threads=1)
            backend.interpreter.allocate_tensors()
            backend.input_details = backend.interpreter.get_input_details()
            backend.output_details = backend.interpreter.get_output_details()
            runtime = "LiteRT CPU XNNPACK, 1 thread (Mac host), "+precision
        else:
            import coremltools as ct
            units = ct.ComputeUnit.CPU_AND_GPU if name.endswith("gpu") else ct.ComputeUnit.ALL
            model.predictor.model.backend.model = ct.models.MLModel(str(package), compute_units=units)
            runtime = "Native Core ML (Python host), " + units.name
        kind = "yolo"
        for _ in range(3):
            model.predict(first, **options)
    elif name.startswith("nude"):
        import nudenet.nudenet as nude
        import onnxruntime as ort
        side = 640 if "640" in name else 320
        if name.endswith("coreml"):
            import coremltools as ct
            model = ct.models.MLModel(str(MODELS / f"nude{side}.mlpackage"), compute_units=ct.ComputeUnit.ALL)
            output = next(iter(model.get_spec().description.output)).name
            predict = lambda blob: model.predict({"images": blob})[output]
            runtime = "Native Core ML tensor FP16, ALL (Python host)"
        else:
            session_options = ort.SessionOptions()
            session_options.intra_op_num_threads = 1
            model_path = MODELS / "nude640.onnx" if side == 640 else Path(nude.__file__).parent / "320n.onnx"
            session = ort.InferenceSession(str(model_path), sess_options=session_options, providers=["CPUExecutionProvider"])
            input_name = session.get_inputs()[0].name
            predict = lambda blob: session.run(None, {input_name: blob})[0]
            runtime = "ONNX Runtime CPU, 1 thread"
        for _ in range(3):
            predict(nude._read_image(first, side)[0])
        kind = "nude"
    elif name.startswith("pose"):
        import mediapipe as mp
        from mediapipe.tasks.python import BaseOptions, vision
        size = name.split("-")[1]
        model = vision.PoseLandmarker.create_from_options(vision.PoseLandmarkerOptions(
            base_options=BaseOptions(model_asset_path=str(MODELS / f"pose-{size}.task")),
            running_mode=vision.RunningMode.VIDEO, num_poses=4,
            min_pose_detection_confidence=.5, min_pose_presence_confidence=.5, min_tracking_confidence=.5))
        kind, runtime = "pose", "MediaPipe CPU video mode (built-in tracking)"
    else:
        raise ValueError(name)
    setup_s, rows = time.perf_counter()-load_started, []
    started = time.perf_counter()
    for sample in samples:
        image = image_at(sample)
        height, width = image.shape[:2]
        row = dict(sample, boxes=[], confidence=[], classes=[], masks=[])
        begin = time.perf_counter()
        if kind == "yolo":
            result = model.predict(image, **options)[0]
            row["inference_ms"] = result.speed["inference"]
            row["prediction_ms"] = (time.perf_counter()-begin)*1000
            row["boxes"] = (result.boxes.xyxy.cpu().numpy()/[width,height,width,height]).tolist()
            row["confidence"] = result.boxes.conf.cpu().tolist()
            row["classes"] = ["person"] * len(row["boxes"])
            if result.masks is not None:
                directory = WORK / "masks" / name
                directory.mkdir(parents=True, exist_ok=True)
                for i, mask in enumerate(result.masks.data.cpu().numpy()):
                    path = directory / f"{sample['file'][:-4]}-{i}.png"
                    mask = cv2.resize(mask, (width,height), interpolation=cv2.INTER_NEAREST)
                    if not cv2.imwrite(str(path), (mask>.5).astype(np.uint8)*255):
                        raise OSError("mask PNG write failed")
                    row["masks"].append(str(path))
        elif kind == "nude":
            regions = [(0,0,width,height)]
            if "tiled" in name:
                s = min(width,height)
                regions += [(0,y,s,s) for y in sorted(set([0,(height-s)//2,height-s]))]
            predictions, row["inference_ms"] = [], 0.
            for ox,oy,rw,rh in regions:
                region = image[oy:oy+rh,ox:ox+rw]
                blob, xr, yr, xp, yp, w, h = nude._read_image(region, side)
                if "rgb" in name:
                    blob = blob[:,::-1].copy()
                infer_started = time.perf_counter()
                output_array = predict(blob)
                row["inference_ms"] += (time.perf_counter()-infer_started)*1000
                if not np.isfinite(output_array).all():
                    raise ValueError("nonfinite model output")
                detected = nude._postprocess([output_array], xp,yp,xr,yr,w,h,side,side)
                for detection in detected:
                    x,y,bw,bh = detection["box"]
                    box = clip_box([(ox+x)/width,(oy+y)/height,(ox+x+bw)/width,(oy+y+bh)/height])
                    if box:
                        predictions.append((box,float(detection["score"]),detection["class"]))
            for box,score,cls in sorted(predictions,key=lambda p:p[1],reverse=True):
                if any(c==cls and iou(box,b)>.5 for b,c in zip(row["boxes"],row["classes"],strict=True)):
                    continue
                row["boxes"].append(box)
                row["confidence"].append(score)
                row["classes"].append(cls)
            row["prediction_ms"] = (time.perf_counter()-begin)*1000
        else:
            rgb = np.ascontiguousarray(image[:,:,::-1])
            result = model.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb), round(sample["time_s"]*1000))
            row["inference_ms"] = (time.perf_counter()-begin)*1000
            row["landmarks"] = [[[p.x,p.y,p.visibility] for p in pose] for pose in result.pose_landmarks]
            for pose in result.pose_landmarks:
                visible = [p for p in pose if p.visibility >= .5]
                if len(visible) < 4:
                    continue
                box = clip_box([min(p.x for p in visible),min(p.y for p in visible),max(p.x for p in visible),max(p.y for p in visible)])
                if box:
                    row["boxes"].append(box)
                    row["confidence"].append(1.)
                    row["classes"].append("person")
        row["infer_mask_export_ms"] = (time.perf_counter()-begin)*1000
        if any(clip_box(b) is None for b in row["boxes"]):
            raise ValueError("empty detector box")
        rows.append(row)
        if len(rows)%75==0:
            print(name,len(rows),"/",len(samples),flush=True)
    if kind == "pose":
        model.close()
    save(WORK / (name + ".json"), dict(rows=rows, runtime=runtime, setup_warmup_s=setup_s,
        analysis_s=sum(r.get("prediction_ms",r["inference_ms"]) for r in rows)/1000,
        sample_decode_and_debug_png_run_s=time.perf_counter()-started, frame_count=len(rows), options=options,
        inference_s=sum(r["inference_ms"] for r in rows)/1000,
        peak_rss_mib=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss/1024**2,
        timing_scope="preprocessing, prediction, postprocessing; debug PNG decode/encode and setup excluded"))
    print(name, "done", len(rows), "samples", flush=True)


def gender():
    import torch
    import onnxruntime as ort
    from safetensors.torch import load_file
    sys.path.insert(0,str(ROOT / "build.noindex/vision-modelbench/MiVOLO"))
    from mivolo.model.create_timm_model import create_model
    from mivolo.data.misc import prepare_classification_images
    torch.set_num_threads(1)
    model = create_model("mivolo_d1_384",num_classes=3,in_chans=6,pretrained=False).eval()
    weights = ROOT / "qa-assets/modelbench/vision/models/mivolo_v2/model.safetensors"
    model.load_state_dict({k.removeprefix("mivolo.model."):v for k,v in load_file(weights).items()},strict=True)
    model = model.to("mps")
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    session = ort.InferenceSession(str(ROOT / "naqi/Resources/Models/genderage_static.onnx"),sess_options=options,providers=["CPUExecutionProvider"])
    bodies, faces = read_rows("yolo11n-coreml"), read_rows("face-r3")
    records, warm = [], torch.zeros(1,6,384,384,device="mps")
    with torch.inference_mode():
        for _ in range(3):
            model(warm)
    torch.mps.synchronize()
    for body_row, face_row in zip(bodies, faces, strict=True):
        assert body_row["index"] == face_row["index"]
        image = image_at(body_row)
        per_body, face_votes = [], []
        for face in face_row["boxes"]:
            started = time.perf_counter()
            logits = session.run(None,{"data":insight_crop(image,face)})[0][0]
            male = float(1/(1+np.exp(-float(logits[1]-logits[0]))))
            face_votes.append(dict(box=face, vote="unknown" if max(male,1-male)<.6 else "male" if male>=.5 else "female",
                                   male_probability=male, ms=(time.perf_counter()-started)*1000))
        for index, (body, confidence) in enumerate(zip(body_row["boxes"],body_row["confidence"],strict=True)):
            if confidence < .25:
                per_body.append(dict(insight="unknown",combined="unknown",bodyonly="unknown",ms=0.))
                continue
            associated = [i for i,f in enumerate(face_row["boxes"]) if associate_face(f,body_row["boxes"]) == index]
            face = face_row["boxes"][associated[0]] if len(associated)==1 else None
            pixels = crop(image,body)
            if min(pixels.shape[:2]) < 8 or max(pixels.shape[:2]) < 48:
                per_body.append(dict(insight="unknown",combined="unknown",bodyonly="unknown",ms=0.))
                continue
            row = dict(insight=face_votes[associated[0]]["vote"] if len(associated)==1 else "unknown",ms=0.)
            for key, input_face in [("combined",crop(image,face) if face else None),("bodyonly",None)]:
                started = time.perf_counter()
                a = prepare_classification_images([input_face],384)
                b = prepare_classification_images([pixels],384)
                tensor = torch.cat([a,b],dim=1).to("mps")
                with torch.inference_mode():
                    probabilities = model(tensor)[0,:2].softmax(0)
                torch.mps.synchronize()
                probabilities = probabilities.cpu().numpy()
                elapsed = (time.perf_counter()-started)*1000
                row[key] = "unknown" if float(probabilities.max())<.6 else ["male","female"][int(probabilities.argmax())]
                row[key+"_ms"] = elapsed
                row[key+"_probabilities"] = probabilities.tolist()
            row["ms"] = row["combined_ms"]+row["bodyonly_ms"]
            per_body.append(row)
        records.append(dict(index=body_row["index"],bodies=per_body,faces=face_votes))
        if len(records)%50==0:
            print("gender",len(records),"/",len(bodies),flush=True)
    save(WORK / "gender.json",dict(rows=records, runtime="InsightFace ONNX CPU; MiVOLO v2 PyTorch MPS",
        weights_sha256=digest(weights), limitation="appearance estimates, not identity; no body-only MobileNet checkpoint or training set",
        mivolo_coreml="previous pinned export failed on col2im; this is a Mac quality/cost experiment"))
    print("gender complete",flush=True)


def tracked_rows(rows, byte=False):
    # Geometry-only experiment; no appearance ReID or cross-shot identities.
    active, next_id, shot, result, cost = {}, 0, 0, [], 0.
    if byte:
        from ultralytics.trackers.byte_tracker import BYTETracker
        from ultralytics.engine.results import Boxes
        tracker = BYTETracker(SimpleNamespace(track_high_thresh=.25,track_low_thresh=.1,
            new_track_thresh=.25,track_buffer=9,match_thresh=.8,fuse_score=True),frame_rate=5)
    for row in rows:
        started = time.perf_counter()
        t = row["time_s"]
        if row["cut"]:
            active.clear()
            shot += 1
            if byte:
                tracker.reset()
        current = []
        if byte:
            values = np.asarray([[*b,c,0] for b,c in zip(row["boxes"],row["confidence"],strict=True)],dtype=np.float32).reshape(-1,6)
            for item in tracker.update(Boxes(values,(640,360))):
                box = clip_box(item[:4])
                if box:
                    current.append(dict(box=box,id=shot*100000+int(item[4]),source=int(item[7]),class_name="person"))
        else:
            used = set()
            for source, (box, confidence, cls) in enumerate(zip(row["boxes"],row["confidence"],row["classes"],strict=True)):
                if confidence < (.2 if cls in EXPOSED else .25):
                    continue
                choices = sorted([(iou(box,v["box"]),key) for key,v in active.items()
                    if key not in used and t-v["time_s"]<=.3 and v["class_name"]==cls], reverse=True)
                if choices and choices[0][0]>=.25 and (len(choices)==1 or choices[0][0]-choices[1][0]>=.15):
                    key = choices[0][1]
                else:
                    next_id += 1
                    key = next_id
                used.add(key)
                current.append(dict(box=box,id=key,source=source,class_name=cls))
            for item in current:
                previous = active.get(item["id"])
                item["velocity"] = ([0]*4 if not previous else
                    ((np.asarray(item["box"])-previous["box"])/max(t-previous["time_s"],.001)).tolist())
                active[item["id"]] = dict(item,time_s=t)
            for key, old in list(active.items()):
                if t-old["time_s"] > .3:
                    del active[key]
                elif key not in used:
                    current.append(dict(old,source=-1))
        cost += time.perf_counter()-started
        result.append(current)
    return result, cost


def union_mask(paths, size):
    mask = np.zeros((size[1],size[0]),np.uint8)
    for path in paths:
        raw = cv2.imread(path,0)
        if raw is None:
            raise ValueError("missing mask")
        mask = np.maximum(mask,cv2.resize(raw,size,interpolation=cv2.INTER_NEAREST))
    return (mask>127).astype(np.uint8)*255


def render(name, candidate, mode="boxes", byte=False, policy=None):
    manifest, rows = load("manifest"), read_rows(candidate)
    face_rows = read_rows("face-r3")
    genders = load("gender")["rows"] if policy else None
    tracks, tracking_s = ([[] for _ in rows],0.) if candidate.startswith("face") else tracked_rows(rows,byte=byte)
    indices = [r["index"] for r in rows]
    out = WORK / "videos" / (name+".mp4")
    out.parent.mkdir(parents=True,exist_ok=True)
    capture = cv2.VideoCapture(str(SOURCE))
    process = subprocess.Popen(["ffmpeg","-v","error","-y","-f","rawvideo","-pix_fmt","bgr24",
        "-s","608x1080","-r","30","-i","pipe:0","-i",str(SOURCE),"-map","0:v:0","-map","1:a:0",
        "-c:v","libx264","-preset","veryfast","-crf","20","-threads","2","-pix_fmt","yuv420p",
        "-c:a","copy","-movflags","+faststart",str(out)],stdin=subprocess.PIPE)
    votes, previous_gray, sampled_mask, previous_sample, frames, changed = {}, None, None, -1, 0, 0
    started = time.perf_counter()
    for index in range(manifest["frames"]):
        ok, image = capture.read()
        if not ok:
            raise ValueError("early render EOF")
        j = bisect.bisect_right(indices,index)-1
        row = rows[j]
        h,w = image.shape[:2]
        mask = np.zeros((h,w),np.uint8)
        if index in manifest["cuts"]:
            votes.clear()
            sampled_mask = None
        if mode == "mask":
            gray = cv2.resize(cv2.cvtColor(image,cv2.COLOR_BGR2GRAY),(90,160))
            if j != previous_sample:
                sampled_mask = union_mask(row["masks"],(90,160))
            elif sampled_mask is not None and previous_gray is not None:
                flow = cv2.calcOpticalFlowFarneback(gray,previous_gray,None,.5,2,13,2,5,1.1,0)
                yy,xx = np.mgrid[:160,:90].astype(np.float32)
                sampled_mask = cv2.remap(sampled_mask,xx+flow[:,:,0],yy+flow[:,:,1],cv2.INTER_NEAREST,borderMode=cv2.BORDER_CONSTANT)
            if sampled_mask is not None:
                mask = cv2.resize(cv2.dilate(sampled_mask,np.ones((3,3),np.uint8)),(w,h),interpolation=cv2.INTER_NEAREST)
            previous_gray = gray
        else:
            for item in tracks[j]:
                if mode == "parts" and item["class_name"] not in EXPOSED:
                    continue
                if policy:
                    key = item["id"]
                    state = votes.setdefault(key,[0,0])
                    if j != previous_sample and item["source"]>=0:
                        vote = genders[j]["bodies"][item["source"]][policy]
                        if vote == "female": state[0]+=1
                        if vote == "male": state[1]+=1
                    # Same conservative unknown rule as the app: two votes before sparing.
                    if state[1]>=2 and state[1]>state[0]:
                        continue
                box = np.asarray(item["box"])
                if not byte:
                    dt = index/30-row["time_s"]
                    prediction = box+np.asarray(item.get("velocity",[0]*4))*min(dt,.2)
                    # Cover the observed and predicted edges, including a shrinking/invalid prediction.
                    box = np.r_[np.minimum(box[:2],prediction[:2]),np.maximum(box[2:],prediction[2:])]
                box = clip_box(box)
                if box:
                    box = padded(box,.25 if candidate.startswith("face") else .12)
                    x1,y1,x2,y2 = np.rint(np.asarray(box)*[w,h,w,h]).astype(int)
                    mask[y1:y2,x1:x2] = 255
        # Identical face geometry for every variant, including the face-only reference.
        for face in face_rows[j]["boxes"]:
            x1,y1,x2,y2 = np.rint(np.asarray(padded(face,.25))*[w,h,w,h]).astype(int)
            mask[y1:y2,x1:x2] = 255
        if np.any(mask):
            changed += 1
            small = cv2.resize(image,(w//4,h//4),interpolation=cv2.INTER_AREA)
            blurred = cv2.resize(cv2.GaussianBlur(small,(0,0),6),(w,h),interpolation=cv2.INTER_LINEAR)
            image = np.where(mask[:,:,None]>0,blurred,image)
        cv2.rectangle(image,(0,0),(w,44),(18,18,18),-1)
        cv2.putText(image,name,(12,29),cv2.FONT_HERSHEY_SIMPLEX,.62,(255,255,255),1,cv2.LINE_AA)
        process.stdin.write(image.tobytes())
        previous_sample, frames = j, frames+1
    capture.release()
    process.stdin.close()
    if process.wait()!=0:
        raise RuntimeError("ffmpeg export failed")
    assert frames==manifest["frames"]
    elapsed = time.perf_counter()-started
    prediction = load(candidate)
    analysis = prediction["analysis_s"]
    gender_s = 0.
    if policy:
        for row in genders:
            gender_s += (sum(f["ms"] for f in row["faces"]) if policy=="insight" else
                         sum(b.get(policy+"_ms",0) for b in row["bodies"]))/1000
    face_s = 0 if candidate.startswith("face") else load("face-r3")["analysis_s"]
    save(WORK / "exports" / (name+".json"),dict(name=name,file=str(out),candidate=candidate,mode=mode,
        tracker="sample-and-hold" if candidate.startswith("face") else "ByteTrack" if byte else "IoU + velocity; hold <=300ms",policy=policy or "everyone",
        frames=frames,modified_frames=changed,tracking_s=tracking_s,render_encode_s=elapsed,
        analysis_s=analysis,face_analysis_s=face_s,gender_s=gender_s,
        measured_stage_sum_s=analysis+face_s+gender_s+tracking_s+elapsed,
        timing_note="sum of separately measured stages, not a single uninterrupted app job; excludes setup/download/frame extraction",
        output_sha256=digest(out),mask_propagation="160px backward optical flow between samples" if mode=="mask" else None))
    print(name,"exported",round(elapsed,3),"seconds",flush=True)


EXPORTS = [
    ("01-face-reference","face-r3","boxes",False,None),
    ("02-vision-body-boxes","human-r2","boxes",False,None),
    ("03-vision-body-r3","human-r3","boxes",False,None),
    ("04-yolov8n-body","yolov8n-coreml","boxes",False,None),
    ("05-yolo11n-body-iou","yolo11n-coreml","boxes",False,None),
    ("06-yolo11n-body-bytetrack","yolo11n-coreml","boxes",True,None),
    ("07-yolov8n-body-mask","yolov8n-seg-coreml","mask",False,None),
    ("08-yolo11n-body-mask","yolo11n-seg-coreml","mask",False,None),
    ("09-vision-mask-fast","semantic-fast","mask",False,None),
    ("10-vision-mask-balanced","semantic-balanced","mask",False,None),
    ("11-vision-mask-accurate","semantic-accurate","mask",False,None),
    ("12-vision-instance-masks","person-instance","mask",False,None),
    ("13-nudenet320-cpu-parts","nude320-cpu","parts",False,None),
    ("14-nudenet320-coreml-parts","nude320-coreml","parts",False,None),
    ("15-nudenet640-cpu-parts","nude640-cpu","parts",False,None),
    ("16-pose-lite-body","pose-lite","boxes",False,None),
    ("17-pose-full-body","pose-full","boxes",False,None),
    ("18-women-face-classifier","yolo11n-coreml","boxes",False,"insight"),
    ("19-women-face-body-mivolo","yolo11n-coreml","boxes",False,"combined"),
    ("20-women-body-only-mivolo","yolo11n-coreml","boxes",False,"bodyonly"),
    ("21-yolov8n-litert-fp32","yolov8n-litert-fp32","boxes",False,None),
    ("22-yolov8n-litert-fp16","yolov8n-litert-fp16","boxes",False,None),
    ("23-yolo11n-litert-fp32","yolo11n-litert-fp32","boxes",False,None),
    ("24-yolo11n-litert-fp16","yolo11n-litert-fp16","boxes",False,None),
    ("25-nude320-tiles-coreml","nude320-tiled-coreml","parts",False,None),
    ("26-nude320-rgb-coreml","nude320-rgb-coreml","parts",False,None),
    ("27-nude320-tiles-rgb","nude320-tiled-rgb-coreml","parts",False,None),
    ("28-nudenet640-coreml-parts","nude640-coreml","parts",False,None),
    ("29-nudenet640-rgb-coreml","nude640-rgb-coreml","parts",False,None),
]


def grid():
    names = ["00-original","01-face-reference","05-yolo11n-body-iou","08-yolo11n-body-mask","27-nude320-tiles-rgb","16-pose-lite-body"]
    labels = ["ORIGINAL","FACE ONLY","YOLO11 BODY BOXES","YOLO11 BODY MASK","NUDE320 TILES + RGB","POSE LITE BOXES"]
    captures = [cv2.VideoCapture(str(WORK / "videos" / (name+".mp4"))) for name in names]
    process = subprocess.Popen(["ffmpeg","-v","error","-y","-f","rawvideo","-pix_fmt","bgr24",
        "-s","1080x1280","-r","30","-i","pipe:0","-i",str(SOURCE),"-map","0:v:0","-map","1:a:0",
        "-c:v","libx264","-preset","veryfast","-crf","21","-threads","2","-pix_fmt","yuv420p",
        "-c:a","copy","-movflags","+faststart",str(WORK / "comparison-grid.mp4")],stdin=subprocess.PIPE)
    for _ in range(load("manifest")["frames"]):
        tiles = []
        for capture,label in zip(captures,labels,strict=True):
            ok,image = capture.read()
            if not ok:
                raise ValueError("comparison grid input ended early")
            image = cv2.resize(image,(360,640),interpolation=cv2.INTER_AREA)
            cv2.rectangle(image,(0,0),(360,32),(18,18,18),-1)
            cv2.putText(image,label,(10,23),cv2.FONT_HERSHEY_SIMPLEX,.52,(255,255,255),1,cv2.LINE_AA)
            tiles.append(image)
        process.stdin.write(np.vstack([np.hstack(tiles[:3]),np.hstack(tiles[3:])]).tobytes())
    for capture in captures:
        capture.release()
    process.stdin.close()
    if process.wait()!=0:
        raise RuntimeError("comparison grid encoding failed")
    print("comparison grid exported",flush=True)


def report():
    manifest = load("manifest")
    exports = [json.loads(p.read_text()) for p in sorted((WORK / "exports").glob("*.json"))]
    analyses = []
    for path in sorted(WORK.glob("*.json")):
        data = json.loads(path.read_text())
        if "analysis_s" in data:
            times = [r["inference_ms"] for r in data["rows"]][3:]
            analyses.append(dict(candidate=path.stem,runtime=data["runtime"],analysis_s=data["analysis_s"],
                setup_warmup_s=data.get("setup_warmup_s"),median_inference_ms=float(np.median(times)),
                p95_inference_ms=float(np.percentile(times,95)),samples=len(data["rows"]),
                detections=sum(len(r["boxes"]) for r in data["rows"]),peak_rss_mib=data.get("peak_rss_mib")))
    summary = dict(manifest=manifest,analyses=analyses,exports=exports,
        limits=["Mac M3 screening, no connected phone or Android measurement", "5fps plus heuristic cut samples; no guaranteed brief-event coverage",
        "OpenCV/FFmpeg common renderer, not app Core Image/AVFoundation export timing", "all-people body comparisons; women policies are separate",
        "MiVOLO runs on MPS and is not a validated iOS artifact", "single clip includes exposed abdomen, but cannot establish nude-chest recall or gender accuracy",
        "model setup, filesystem frame extraction and downloads excluded from stage sums"])
    save(WORK / "summary.json",summary)
    lines = ["# مقارنة تغبيش الجسم — 2026-10-05", "", "الفيديو: https://www.youtube.com/shorts/rX6wXhLqOIQ", "",
        f"MacBook Air M3، ذاكرة 24GB، macOS {manifest['os']}. هذه ليست قياسات هاتف.", "",
        f"الفيديو كامل: {manifest['frames']} إطارًا، {manifest['video_duration_s']:.3f} ثانية، 608×1080 و30fps. "
        f"الكشف 5fps مع عينات إضافية عند القطع: {len(manifest['samples'])} عينة.", "",
        "مصنف المشهد NSFW معطّل كي لا يخفي فشل الكاشف بتغبيش الصورة كاملة. الصوت الأصلي محفوظ.", "",
        "| الفيديو | تحليل الكاشف ث | الوجه ث | تصنيف إضافي ث | تتبّع ث | تصدير ث | مجموع المراحل ث |", "|---|---:|---:|---:|---:|---:|---:|"]
    for row in exports:
        lines.append(f"| [{row['name']}]({row['file']}) | {row['analysis_s']:.2f} | {row['face_analysis_s']:.2f} | {row['gender_s']:.2f} | {row['tracking_s']:.3f} | {row['render_encode_s']:.2f} | {row['measured_stage_sum_s']:.2f} |")
    lines += ["", "مجموع المراحل جمع قياسات مستقلة، ولا يمثل تشغيل مهمة التطبيق من البداية للنهاية. تحميل/تهيئة النموذج خارج هذا المجموع.", "",
        "| الكاشف | وسيط الاستدلال ms | p95 ms | زمن تجهيز/تسخين ث | المسار |", "|---|---:|---:|---:|---|"]
    for row in analyses:
        setup = "—" if row["setup_warmup_s"] is None else f"{row['setup_warmup_s']:.2f}"
        lines.append(f"| {row['candidate']} | {row['median_inference_ms']:.2f} | {row['p95_inference_ms']:.2f} | {setup} | {row['runtime']} |")
    lines += ["", "## حدود المقارنة", ""] + ["- "+p for p in summary["limits"]]
    lines += ["", "اختبار MobileNet/EfficientNet للجنس من الجسم يحتاج أوزانًا مدربة وبيانات؛ لم ندرب مصنفًا من هذا الفيديو الواحد. اختبرنا InsightFace الحالي وMiVOLO v2 للوجه والجسم، والجسم وحده.",
        "", "الكشف والتجزئة والوضعية مهام مختلفة. فيديو الأجزاء المكشوفة قد يشبه الأصل في مشهد بملابس كاملة؛ هذا متوقع ولا يثبت فشلًا أو نجاحًا على العري.",
        "", "مصادر النماذج وSHA256 في artifacts.json. المخرجات الخام في ملفات JSON، والتصديرات وقياساتها في exports/."]
    (WORK / "report.ar.md").write_text("\n".join(lines)+"\n")
    print("report written",WORK / "report.ar.md",flush=True)


def self_check():
    assert clip_box([-1,-2,2,3])==[0.,0.,1.,1.]
    assert clip_box([1,1,0,0]) is None
    assert np.allclose(padded([.2,.3,.4,.7],.1),[.18,.26,.42,.74])
    assert cut_score(np.zeros((16,16),np.uint8),np.full((16,16),255,np.uint8))==1
    rows = [dict(index=i*6,time_s=i*.2,cut=False,boxes=[[.1+i*.01,.1,.5+i*.01,.9]],confidence=[.9],classes=["person"]) for i in range(3)]
    tracks,_ = tracked_rows(rows)
    assert tracks[0][0]["id"]==tracks[2][0]["id"]
    rows[2]["cut"] = True
    tracks,_ = tracked_rows(rows)
    assert tracks[0][0]["id"]!=tracks[2][0]["id"]
    tracks,_ = tracked_rows(rows,byte=True)
    assert len(tracks)==3 and len(tracks[0])==1
    print("clipping, padding, cuts, IoU and ByteTrack checks passed")


if __name__ == "__main__":
    cv2.setNumThreads(1)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action",nargs="?",choices=["prepare","models","native","litert_models","infer","gender","render","grid","report"])
    parser.add_argument("--candidate")
    parser.add_argument("--self-check",action="store_true")
    args = parser.parse_args()
    if args.self_check:
        self_check()
    elif args.action=="infer":
        infer(args.candidate)
    elif args.action=="render":
        for item in EXPORTS:
            if args.candidate is None or args.candidate==item[0]:
                render(*item)
    elif args.action:
        globals()[args.action]()
    else:
        parser.error("action required")
