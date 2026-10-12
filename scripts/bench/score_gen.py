#!/usr/bin/env python3
"""GEN-0 vs GEN-1 host T0 (plan §7.1; crop/vote from plan-censor-who §3.3/§4 and FaceTracker/FilterWorker).

Stand-in detector is OpenCV YuNet, not ML Kit. Cadence is 5 fps (app faces are 10 fps) on
maxDim=640 frames. Host ORT has no XNNPACK — CPU EP, intra-op 1, spinning off (app: XNNPACK ×4).

  .venv-bench/bin/python scripts/bench/score_gen.py extract
  .venv-bench/bin/python scripts/bench/score_gen.py score qa-assets/bench-out/gen/labels.csv --gen1-scale 1.0
  .venv-bench/bin/python scripts/bench/score_gen.py score qa-assets/bench-out/gen/labels.csv --gen1-scale 1.2
  .venv-bench/bin/python scripts/bench/score_gen.py cost
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

import cv2
import numpy as np
import onnx
import onnxruntime as ort
from onnx import TensorProto, helper, numpy_helper
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
VIDEO = ROOT / "qa-assets/vlog/vlog.webm"
MODELS = ROOT / "qa-assets/models"
OUT = ROOT / "qa-assets/bench-out/gen"
GEN0 = ROOT / "app/src/main/assets/models/genderage.onnx"
GEN1_ONNX = MODELS / "gen1_agegender_0013.onnx"
GEN1_INT8 = MODELS / "gen1_agegender_0013_int8dyn.onnx"
GEN1_XML = MODELS / "age-gender-recognition-retail-0013.xml"
GEN1_BIN = MODELS / "age-gender-recognition-retail-0013.bin"
YUNET = MODELS / "face_detection_yunet_2023mar.onnx"

# App constants (FaceTracker.kt / FilterWorker.kt / FrameSampler.kt).
VOTE_CAP = 5
MIN_FACE_PX = 80  # applied at score, not extract (so size bands exist)
CONF_FLOOR_APP = 0.60
EVICT_MS = 2000
KEYFRAME_PAD = 0.25  # 1.5× square is max(w,h)*1.5 — InsightFace crop, not padRect
SAVED_CROP_SCALE = 1.5  # extract stores this square; GEN-1 recrops at --gen1-scale
CROP_SIDE_GEN0 = 96
CROP_SIDE_GEN1 = 62
MAX_DIM = 640
FACE_FPS = 5.0  # subsample of the app's 10 fps
MIN_FACE_FRAC = 0.1  # ML Kit minFaceSize stand-in
YUNET_SCORE = 0.5
YUNET_NMS = 0.3
IOU_MATCH = 0.3

YUNET_URL = (
    "https://huggingface.co/opencv/face_detection_yunet/resolve/main/"
    "face_detection_yunet_2023mar.onnx?download=true"
)
OMZ_BASE = (
    "https://storage.openvinotoolkit.org/repositories/open_model_zoo/2023.0/"
    "models_bin/1/age-gender-recognition-retail-0013/FP32/"
)

FONT = "/System/Library/Fonts/Supplemental/Arial.ttf"


def _toi(x: float) -> int:
    """Kotlin Float.toInt() — truncate toward zero."""
    return int(x)


def image_session(path: Path) -> ort.InferenceSession:
    """App imageSessionOptions, minus XNNPACK (not in host ORT on mac)."""
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    so.inter_op_num_threads = 1
    so.add_session_config_entry("session.intra_op.allow_spinning", "0")
    return ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])


def download(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists() and dest.stat().st_size > 1000:
        return
    print(f"download {dest.name}", flush=True)
    import urllib.request

    urllib.request.urlretrieve(url, dest)


def ensure_models() -> None:
    download(YUNET_URL, YUNET)
    download(OMZ_BASE + "age-gender-recognition-retail-0013.xml", GEN1_XML)
    download(OMZ_BASE + "age-gender-recognition-retail-0013.bin", GEN1_BIN)


# --- IR → ONNX (this graph: Parameter/Const/Conv/Add/MaxPool/ReLU/SoftMax/Result) ---------------

def ir_to_onnx(xml_path: Path, bin_path: Path) -> onnx.ModelProto:
    tree = ET.parse(xml_path)
    net = tree.getroot()
    blob = bin_path.read_bytes()
    layers = {int(el.attrib["id"]): el for el in net.find("layers")}
    incoming: dict[int, dict[int, tuple[int, int]]] = {}
    for e in net.find("edges"):
        incoming.setdefault(int(e.attrib["to-layer"]), {})[int(e.attrib["to-port"])] = (
            int(e.attrib["from-layer"]),
            int(e.attrib["from-port"]),
        )

    def load_const(layer):
        d = layer.find("data")
        shape = tuple(int(x.strip()) for x in d.attrib["shape"].split(",")) if d.attrib["shape"].strip() else ()
        off, size = int(d.attrib["offset"]), int(d.attrib["size"])
        arr = np.frombuffer(blob[off : off + size], dtype="<f4")
        return arr.reshape(shape) if shape else arr.reshape(())

    def port_name(lid, pid):
        return f"t{lid}_{pid}"

    initializers, nodes, inputs, outputs = [], [], [], []
    produced: dict[tuple[int, int], str] = {}

    for lid in sorted(layers):
        layer = layers[lid]
        typ, name = layer.attrib["type"], layer.attrib["name"]
        if typ == "Parameter":
            out = layer.find("output").find("port")
            dims = [int(d.text) for d in out.findall("dim")]
            tname = out.attrib.get("names") or "data"
            inputs.append(helper.make_tensor_value_info(tname, TensorProto.FLOAT, dims))
            produced[(lid, int(out.attrib["id"]))] = tname
        elif typ == "Const":
            tname = f"c{lid}"
            initializers.append(numpy_helper.from_array(load_const(layer), tname))
            produced[(lid, int(layer.find("output").find("port").attrib["id"]))] = tname
        elif typ == "Result":
            src = incoming[lid][0]
            tname = produced[src]
            oname = name.replace("/sink_port_0", "")
            src_layer = layers[src[0]]
            dims = None
            for p in src_layer.find("output").findall("port"):
                if int(p.attrib["id"]) == src[1]:
                    dims = [int(d.text) for d in p.findall("dim")]
                    break
            if tname != oname:
                nodes.append(helper.make_node("Identity", [tname], [oname], name=f"id_{lid}"))
                tname = oname
            outputs.append(helper.make_tensor_value_info(tname, TensorProto.FLOAT, dims))
        elif typ == "Convolution":
            d = layer.find("data")
            strides = [int(x) for x in d.attrib["strides"].split(",")]
            dilations = [int(x) for x in d.attrib["dilations"].split(",")]
            pads = [int(x) for x in d.attrib["pads_begin"].split(",")] + [
                int(x) for x in d.attrib["pads_end"].split(",")
            ]
            x, w = produced[incoming[lid][0]], produced[incoming[lid][1]]
            outp = layer.find("output").find("port")
            y = port_name(lid, int(outp.attrib["id"]))
            nodes.append(
                helper.make_node(
                    "Conv", [x, w], [y], name=name, strides=strides, dilations=dilations, pads=pads
                )
            )
            produced[(lid, int(outp.attrib["id"]))] = y
        elif typ == "Add":
            a, b = produced[incoming[lid][0]], produced[incoming[lid][1]]
            outp = layer.find("output").find("port")
            y = port_name(lid, int(outp.attrib["id"]))
            nodes.append(helper.make_node("Add", [a, b], [y], name=name))
            produced[(lid, int(outp.attrib["id"]))] = y
        elif typ == "ReLU":
            x = produced[incoming[lid][0]]
            outp = layer.find("output").find("port")
            y = port_name(lid, int(outp.attrib["id"]))
            nodes.append(helper.make_node("Relu", [x], [y], name=name))
            produced[(lid, int(outp.attrib["id"]))] = y
        elif typ == "MaxPool":
            d = layer.find("data")
            strides = [int(x) for x in d.attrib["strides"].split(",")]
            kernel = [int(x) for x in d.attrib["kernel"].split(",")]
            pads = [int(x) for x in d.attrib["pads_begin"].split(",")] + [
                int(x) for x in d.attrib["pads_end"].split(",")
            ]
            ceil_mode = 1 if d.attrib.get("rounding_type") == "ceil" else 0
            x = produced[incoming[lid][0]]
            outp = layer.find("output").findall("port")[0]  # ignore index output
            y = port_name(lid, int(outp.attrib["id"]))
            nodes.append(
                helper.make_node(
                    "MaxPool",
                    [x],
                    [y],
                    name=name,
                    kernel_shape=kernel,
                    strides=strides,
                    pads=pads,
                    ceil_mode=ceil_mode,
                )
            )
            produced[(lid, int(outp.attrib["id"]))] = y
        elif typ == "SoftMax":
            axis = int(layer.find("data").attrib.get("axis", "1"))
            x = produced[incoming[lid][0]]
            outp = layer.find("output").find("port")
            y = port_name(lid, int(outp.attrib["id"]))
            nodes.append(helper.make_node("Softmax", [x], [y], name=name, axis=axis))
            produced[(lid, int(outp.attrib["id"]))] = y
        else:
            raise ValueError(f"unsupported IR op {typ}")

    graph = helper.make_graph(nodes, "age_gender_0013", inputs, outputs, initializers)
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 12)], ir_version=7)
    onnx.checker.check_model(model)
    return model


def convert_gen1() -> Path:
    ensure_models()
    model = ir_to_onnx(GEN1_XML, GEN1_BIN)
    GEN1_ONNX.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, GEN1_ONNX)
    print(f"wrote {GEN1_ONNX} ({GEN1_ONNX.stat().st_size} bytes)", flush=True)
    try:
        from onnxruntime.quantization import QuantType, quantize_dynamic

        quantize_dynamic(str(GEN1_ONNX), str(GEN1_INT8), weight_type=QuantType.QInt8)
        print(f"wrote {GEN1_INT8} ({GEN1_INT8.stat().st_size} bytes) dynamic QInt8", flush=True)
    except Exception as e:
        print(f"INT8 dynamic skipped: {e}", flush=True)
    return GEN1_ONNX


def parity_gen1(crop_paths: list[Path], n: int = 200) -> dict:
    import openvino as ov

    if not GEN1_ONNX.exists():
        convert_gen1()
    rng = np.random.default_rng(0)
    tensors = []
    for p in crop_paths[:n]:
        img = cv2.imread(str(p))
        if img is None:
            continue
        tensors.append(prep_gen1(img))
    while len(tensors) < min(n, max(1, len(tensors))) and len(tensors) < n:
        break
    if len(tensors) < n:
        for _ in range(n - len(tensors)):
            tensors.append(rng.random((1, 3, 62, 62), dtype=np.float32) * 255)
    tensors = tensors[:n]

    core = ov.Core()
    compiled = core.compile_model(core.read_model(str(GEN1_XML), str(GEN1_BIN)), "CPU")
    sess = image_session(GEN1_ONNX)
    onames = [o.name for o in sess.get_outputs()]

    max_prob = max_age = 0.0
    agree = 0
    for x in tensors:
        ov_res = compiled(x)
        ov_prob = ov_age = None
        for k, v in ov_res.items():
            nm = k.get_any_name() if hasattr(k, "get_any_name") else str(k)
            if "prob" in nm:
                ov_prob = np.array(v).reshape(-1)
            else:
                ov_age = np.array(v).reshape(-1)
        on = sess.run(None, {"data": x})
        om = {onames[i]: np.array(on[i]).reshape(-1) for i in range(len(on))}
        op = om["prob"]
        oa = om.get("fc3_a", om.get("age_conv3"))
        max_prob = max(max_prob, float(np.max(np.abs(op - ov_prob))))
        max_age = max(max_age, float(np.max(np.abs(oa - ov_age))))
        agree += int(np.argmax(op[:2]) == np.argmax(ov_prob[:2]))
    out = {
        "n": len(tensors),
        "n_real_crops": min(len(crop_paths), n),
        "max_abs_prob": max_prob,
        "max_abs_age": max_age,
        "argmax_agree": agree,
        "argmax_agree_frac": agree / len(tensors),
        "onnx_bytes": GEN1_ONNX.stat().st_size,
        "xml": str(GEN1_XML),
        "onnx": str(GEN1_ONNX),
    }
    (OUT / "parity.json").parent.mkdir(parents=True, exist_ok=True)
    (OUT / "parity.json").write_text(json.dumps(out, indent=2) + "\n")
    print(
        f"parity n={out['n']} real={out['n_real_crops']} max|Δ|prob={max_prob:.6g} "
        f"max|Δ|age={max_age:.6g} argmax={agree}/{out['n']}",
        flush=True,
    )
    return out


# --- crop geometry (FrameSampler.cropToTensor) --------------------------------------------------

def square_crop_rgb(frame_bgr: np.ndarray, box: tuple[float, float, float, float]) -> np.ndarray:
    """InsightFace square: side = max(w,h)*1.5, centre of box, edge-clamp, RGB uint8 at native px."""
    h, w = frame_bgr.shape[:2]
    x1, y1, x2, y2 = box
    bw, bh = x2 - x1, y2 - y1
    cx, cy = (x1 + x2) / 2.0, (y1 + y2) / 2.0
    half = max(bw, bh) * 1.5 / 2.0
    side_f = half * 2.0
    S = max(_toi(side_f), 1)
    xs = np.clip(np.array([_toi(cx - half + i * (side_f / S)) for i in range(S)], np.int32), 0, w - 1)
    ys = np.clip(np.array([_toi(cy - half + i * (side_f / S)) for i in range(S)], np.int32), 0, h - 1)
    rgb = frame_bgr[:, :, ::-1]
    return rgb[ys[:, None], xs[None, :]]


def centre_crop_box_scale(
    img: np.ndarray, scale: float, saved_scale: float = SAVED_CROP_SCALE
) -> np.ndarray:
    """Centre-crop a saved `saved_scale`× square to `scale`× the detector box.

    Extract stores the InsightFace 1.5× square centred on the YuNet box, then
    edge-clamps sample coords. A centre crop is exact when that window sat
    inside the frame; when it clamped, edge pixels are repeated and the face
    can sit off-centre, so this is an approximation.
    """
    if scale <= 0:
        raise ValueError(f"gen1-scale must be > 0, got {scale}")
    frac = min(scale / saved_scale, 1.0)
    if frac >= 1.0:
        return img
    h, w = img.shape[:2]
    nh = max(1, min(h, int(h * frac + 0.5)))
    nw = max(1, min(w, int(w * frac + 0.5)))
    y0 = (h - nh) // 2
    x0 = (w - nw) // 2
    return img[y0 : y0 + nh, x0 : x0 + nw]


def resize_nn(img: np.ndarray, side: int) -> np.ndarray:
    """Nearest, toward-zero index — same as Kotlin (x0 + i*step).toInt()."""
    h, w = img.shape[:2]
    ys = np.clip((np.arange(side) * h / side).astype(np.int32), 0, h - 1)
    xs = np.clip((np.arange(side) * w / side).astype(np.int32), 0, w - 1)
    return img[ys[:, None], xs[None, :]]


def prep_gen0(rgb: np.ndarray) -> np.ndarray:
    x = resize_nn(rgb, CROP_SIDE_GEN0).astype(np.float32)  # RGB 0..255
    return np.transpose(x, (2, 0, 1))[None]


def prep_gen1(bgr_or_rgb_crop: np.ndarray) -> np.ndarray:
    """62² NCHW BGR 0..255. Accepts BGR (cv2.imread) or RGB native crop (3-channel)."""
    img = bgr_or_rgb_crop
    if img.shape[0] != CROP_SIDE_GEN1 or img.shape[1] != CROP_SIDE_GEN1:
        img = cv2.resize(img, (CROP_SIDE_GEN1, CROP_SIDE_GEN1), interpolation=cv2.INTER_LINEAR)
    # cv2.imread is BGR; our saved PNGs via cv2.imwrite of RGB-swapped are BGR on read.
    x = img.astype(np.float32)
    return np.transpose(x, (2, 0, 1))[None]


def sigmoid_male(female_logit: float, male_logit: float) -> float:
    """FilterWorker: p = 1/(1+exp(-(out[1]-out[0])))."""
    d = male_logit - female_logit
    return float(1.0 / (1.0 + np.exp(-d)))


def crop_vote(p_male: float, floor: float) -> int:
    """+1 male, -1 female, 0 abstain. CONF_FLOOR on max(p, 1-p)."""
    if max(p_male, 1.0 - p_male) < floor:
        return 0
    return 1 if p_male >= 0.5 else -1


def should_censor_women(female_votes: int, male_votes: int) -> bool:
    """FaceTracker.shouldCensor(..., WOMEN): censor unless male strictly won."""
    return not (male_votes > female_votes)


def iou(a, b) -> float:
    ax1, ay1, ax2, ay2 = a
    bx1, by1, bx2, by2 = b
    ix1, iy1 = max(ax1, bx1), max(ay1, by1)
    ix2, iy2 = min(ax2, bx2), min(ay2, by2)
    iw, ih = max(0.0, ix2 - ix1), max(0.0, iy2 - iy1)
    inter = iw * ih
    if inter <= 0:
        return 0.0
    area = (ax2 - ax1) * (ay2 - ay1) + (bx2 - bx1) * (by2 - by1) - inter
    return inter / area if area > 0 else 0.0


# --- decode --------------------------------------------------------------------------------------

def scaled_size(w: int, h: int, max_dim: int) -> tuple[int, int]:
    if max(w, h) <= max_dim:
        return w, h
    if w >= h:
        ow = max_dim
        oh = max(1, int(round(h * max_dim / w)))
    else:
        oh = max_dim
        ow = max(1, int(round(w * max_dim / h)))
    return ow - ow % 2, oh - oh % 2


def probe_video(path: Path) -> tuple[int, int, float]:
    r = subprocess.run(
        [
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_entries",
            "stream=width,height,duration",
            "-of",
            "json",
            str(path),
        ],
        capture_output=True,
        text=True,
        check=True,
    )
    st = json.loads(r.stdout)["streams"][0]
    return int(st["width"]), int(st["height"]), float(st.get("duration") or 0)


def iter_frames(video: Path, fps: float, max_dim: int):
    w, h, _ = probe_video(video)
    ow, oh = scaled_size(w, h, max_dim)
    vf = f"fps={fps:g},scale={ow}:{oh}"
    cmd = [
        "ffmpeg",
        "-hide_banner",
        "-loglevel",
        "error",
        "-i",
        str(video),
        "-vf",
        vf,
        "-pix_fmt",
        "bgr24",
        "-f",
        "rawvideo",
        "pipe:1",
    ]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, bufsize=ow * oh * 3 * 4)
    nbytes = ow * oh * 3
    i = 0
    try:
        while True:
            raw = proc.stdout.read(nbytes)
            if len(raw) < nbytes:
                break
            frame = np.frombuffer(raw, np.uint8).reshape(oh, ow, 3).copy()
            pts_ms = int(round(i * 1000.0 / fps))
            yield pts_ms, frame
            i += 1
    finally:
        proc.kill()
        proc.wait()


# --- tracking ------------------------------------------------------------------------------------

class Track:
    __slots__ = ("id", "last_ms", "box", "samples", "crops", "classified_px", "context")

    def __init__(self, tid: int, box, pts_ms: int):
        self.id = tid
        self.last_ms = pts_ms
        self.box = box
        self.samples = 0
        self.crops: list[dict] = []
        self.classified_px = 0
        self.context = None


def associate(
    live: dict[int, Track], dets: list[dict], pts_ms: int, next_id: list[int]
) -> list[tuple[Track, dict]]:
    """Greedy IoU match; evict unseen ≥2 s. next_id is a 1-element monotonic counter."""
    for tid in [tid for tid, t in live.items() if pts_ms - t.last_ms >= EVICT_MS]:
        live.pop(tid)
    pairs = []
    for ti, t in live.items():
        for di, d in enumerate(dets):
            pairs.append((iou(t.box, d["box"]), ti, di))
    pairs.sort(reverse=True)
    used_t, used_d = set(), set()
    assigned: list[tuple[Track, dict]] = []
    for score, ti, di in pairs:
        if score < IOU_MATCH or ti in used_t or di in used_d:
            continue
        used_t.add(ti)
        used_d.add(di)
        t = live[ti]
        d = dets[di]
        t.box, t.last_ms = d["box"], pts_ms
        assigned.append((t, d))
    for di, d in enumerate(dets):
        if di in used_d:
            continue
        tid = next_id[0]
        next_id[0] += 1
        t = Track(tid, d["box"], pts_ms)
        live[tid] = t
        assigned.append((t, d))
    return assigned


def maybe_keep_crop(track: Track, det: dict, frame: np.ndarray, crop_dir: Path) -> None:
    """App rule: classify if bigger than any already classified, up to VOTE_CAP.

    MIN_FACE_PX is NOT applied here so <40 and 40–80 bands exist; score applies the floor.
    """
    px = det["px"]
    if len(track.crops) >= VOTE_CAP:
        return
    if px <= track.classified_px:
        return
    rgb = square_crop_rgb(frame, det["box"])
    track.samples += 1
    idx = len(track.crops)
    tdir = crop_dir / str(track.id)
    tdir.mkdir(parents=True, exist_ok=True)
    fname = tdir / f"c{idx:02d}_{int(px)}px.png"
    # save RGB via cv2 (expects BGR)
    cv2.imwrite(str(fname), rgb[:, :, ::-1])
    track.crops.append(
        {
            "file": str(fname.relative_to(OUT)),
            "px": int(px),
            "pts_ms": det["pts_ms"],
            "box": [float(x) for x in det["box"]],
            "score": float(det["score"]),
            "crop_side": int(rgb.shape[0]),
        }
    )
    track.classified_px = px


# --- sheets / context ---------------------------------------------------------------------------

def _font(size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(FONT, size=size)


def write_context(track: Track, frame: np.ndarray, det: dict, path: Path) -> None:
    vis = frame.copy()
    x1, y1, x2, y2 = [int(v) for v in det["box"]]
    cv2.rectangle(vis, (x1, y1), (x2, y2), (0, 255, 0), 2)
    bw, bh = x2 - x1, y2 - y1
    half = max(bw, bh) * 1.5 / 2.0
    cx, cy = (x1 + x2) / 2.0, (y1 + y2) / 2.0
    sx1, sy1 = int(cx - half), int(cy - half)
    sx2, sy2 = int(cx + half), int(cy + half)
    cv2.rectangle(vis, (sx1, sy1), (sx2, sy2), (0, 255, 255), 1)
    cv2.putText(vis, f"#{track.id} {det['px']}px", (max(x1, 0), max(y1 - 4, 12)),
                cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 0), 1, cv2.LINE_AA)
    path.parent.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(path), vis, [int(cv2.IMWRITE_JPEG_QUALITY), 85])


def write_sheets(tracks: list[Track], sheet_dir: Path) -> int:
    sheet_dir.mkdir(parents=True, exist_ok=True)
    disp, label_w, pad, rows_n = 128, 140, 10, 12
    cell = disp + pad
    sw = label_w + VOTE_CAP * cell + pad
    sh = rows_n * cell + pad
    font = _font(36)
    small = _font(14)
    n_sheets = 0
    for s0 in range(0, len(tracks), rows_n):
        chunk = tracks[s0 : s0 + rows_n]
        canvas = Image.new("RGB", (sw, sh), (20, 20, 24))
        draw = ImageDraw.Draw(canvas)
        for r, t in enumerate(chunk):
            y = pad + r * cell
            draw.text((8, y + disp // 2 - 18), f"#{t.id}", font=font, fill=(255, 220, 80))
            for c, crop in enumerate(t.crops[:VOTE_CAP]):
                im = Image.open(OUT / crop["file"]).convert("RGB")
                im = im.resize((disp, disp), Image.Resampling.NEAREST)
                x = label_w + c * cell
                canvas.paste(im, (x, y))
                draw.rectangle((x, y, x + disp - 1, y + disp - 1), outline=(80, 80, 80))
                draw.text((x + 4, y + disp - 18), f"{crop['px']}px", font=small, fill=(255, 255, 255))
        name = sheet_dir / f"sheet_{n_sheets:02d}.png"
        canvas.save(name)
        n_sheets += 1
    return n_sheets


# --- extract -------------------------------------------------------------------------------------

def cmd_extract(_args) -> None:
    os.environ.setdefault("OPENCV_LOG_LEVEL", "ERROR")
    ensure_models()
    if not VIDEO.exists():
        sys.exit(f"missing {VIDEO}")
    if not GEN0.exists():
        sys.exit(f"missing {GEN0}")
    for sub in ("crops", "sheets", "context"):
        (OUT / sub).mkdir(parents=True, exist_ok=True)

    w0, h0, dur = probe_video(VIDEO)
    ow, oh = scaled_size(w0, h0, MAX_DIM)
    min_px = MIN_FACE_FRAC * min(ow, oh)
    print(
        f"extract {VIDEO.name} {w0}x{h0} → {ow}x{oh} @ {FACE_FPS:g} fps "
        f"minFace={min_px:.1f}px (0.1*shorter) yunet_score={YUNET_SCORE}",
        flush=True,
    )

    det = cv2.FaceDetectorYN.create(str(YUNET), "", (ow, oh), YUNET_SCORE, YUNET_NMS, 5000)
    det.setInputSize((ow, oh))
    live: dict[int, Track] = {}
    finished: dict[int, Track] = {}
    next_id = [1]
    n_frames = n_dets = 0
    t0 = time.perf_counter()
    for pts_ms, frame in iter_frames(VIDEO, FACE_FPS, MAX_DIM):
        n_frames += 1
        _rv, faces = det.detect(frame)
        dets = []
        if faces is not None:
            for f in faces:
                x, y, bw, bh = float(f[0]), float(f[1]), float(f[2]), float(f[3])
                if bw <= 1 or bh <= 1:
                    continue
                if min(bw, bh) < min_px:
                    continue
                box = (x, y, x + bw, y + bh)
                px = max(bw, bh)
                dets.append({"box": box, "px": px, "score": float(f[-1]), "pts_ms": pts_ms})
        n_dets += len(dets)
        assigned = associate(live, dets, pts_ms, next_id)
        for t, d in assigned:
            maybe_keep_crop(t, d, frame, OUT / "crops")
            if t.context is None and t.crops:
                ctx = OUT / "context" / f"{t.id}.jpg"
                write_context(t, frame, d, ctx)
                t.context = str(ctx.relative_to(OUT))
        if n_frames % 50 == 0:
            print(
                f"  frame {n_frames} t={pts_ms/1000:.1f}s live={len(live)} dets_so_far={n_dets}",
                flush=True,
            )
        # keep a side map of every track we've seen
        for t in live.values():
            finished[t.id] = t

    elapsed = time.perf_counter() - t0
    tracks = [finished[k] for k in sorted(finished) if finished[k].crops]

    n_sheets = write_sheets(tracks, OUT / "sheets")
    n_crops = sum(len(t.crops) for t in tracks)

    index = {
        "video": str(VIDEO.relative_to(ROOT)),
        "src_wh": [w0, h0],
        "analyze_wh": [ow, oh],
        "fps": FACE_FPS,
        "app_face_fps": 10,
        "max_dim": MAX_DIM,
        "min_face_frac": MIN_FACE_FRAC,
        "min_face_px_detector": min_px,
        "min_face_px_vote": MIN_FACE_PX,
        "yunet": str(YUNET.relative_to(ROOT)),
        "yunet_score": YUNET_SCORE,
        "n_frames": n_frames,
        "n_detections": n_dets,
        "n_tracks_with_crops": len(tracks),
        "n_crops": n_crops,
        "n_sheets": n_sheets,
        "extract_s": elapsed,
        "vote_cap": VOTE_CAP,
        "evict_ms": EVICT_MS,
        "note": (
            "5 fps subsample of the app's 10 fps face cadence. YuNet stand-in for ML Kit. "
            "Crop is InsightFace 1.5× square (FrameSampler.cropToTensor), edge-clamped. "
            "VOTE_CAP=5 strictly-larger streaming rule; MIN_FACE_PX=80 applied at score, "
            "not extract, so <40 and 40–80 bands can be labelled."
        ),
        "tracks": [
            {
                "id": t.id,
                "n_crops": len(t.crops),
                "min_px": min(c["px"] for c in t.crops),
                "max_px": max(c["px"] for c in t.crops),
                "context": t.context,
                "crops": t.crops,
            }
            for t in tracks
        ],
    }
    (OUT / "index.json").write_text(json.dumps(index, indent=2) + "\n")
    with (OUT / "labels.csv").open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["track", "label", "n_crops", "min_px", "max_px"])
        w.writeheader()
        for t in tracks:
            w.writerow(
                {
                    "track": t.id,
                    "label": "",
                    "n_crops": len(t.crops),
                    "min_px": min(c["px"] for c in t.crops),
                    "max_px": max(c["px"] for c in t.crops),
                }
            )

    print(
        f"extract done: frames={n_frames} detections={n_dets} tracks={len(tracks)} "
        f"crops={n_crops} sheets={n_sheets} in {elapsed:.1f}s",
        flush=True,
    )
    print(f"  crops → {OUT/'crops'}", flush=True)
    print(f"  sheets → {OUT/'sheets'} ({n_sheets} files)", flush=True)
    print(f"  context → {OUT/'context'}", flush=True)
    print(f"  labels (EMPTY) → {OUT/'labels.csv'}  fill m / f / junk / skip", flush=True)

    convert_gen1()
    crop_paths = [OUT / c["file"] for t in tracks for c in t.crops]
    parity_gen1(crop_paths, n=200)


# --- infer / score -------------------------------------------------------------------------------

def load_index() -> dict:
    p = OUT / "index.json"
    if not p.exists():
        sys.exit(f"missing {p} — run extract first")
    return json.loads(p.read_text())


def infer_all(index: dict, gen1_scale: float = 1.0) -> dict:
    """Run both models on every crop. Returns track_id -> per-crop outputs.

    GEN-0 always sees the saved 1.5× InsightFace square (app crop).
    GEN-1 sees a centre crop at `gen1_scale` × the detector box (OMZ default 1.0).
    """
    sess0 = image_session(GEN0)
    if not GEN1_ONNX.exists():
        convert_gen1()
    sess1 = image_session(GEN1_ONNX)
    in0 = sess0.get_inputs()[0].name
    in1 = sess1.get_inputs()[0].name
    out1 = [o.name for o in sess1.get_outputs()]
    per_track = {}
    for t in index["tracks"]:
        rows = []
        for c in t["crops"]:
            bgr = cv2.imread(str(OUT / c["file"]))
            if bgr is None:
                continue
            rgb = bgr[:, :, ::-1]
            y0 = sess0.run(None, {in0: prep_gen0(rgb)})[0].reshape(-1)
            bgr1 = centre_crop_box_scale(bgr, gen1_scale)
            y1 = sess1.run(None, {in1: prep_gen1(bgr1)})
            ym = {out1[i]: np.array(y1[i]).reshape(-1) for i in range(len(y1))}
            prob = ym["prob"]  # [female, male]
            age = float(ym.get("fc3_a", ym.get("age_conv3"))[0])
            rows.append(
                {
                    "file": c["file"],
                    "px": c["px"],
                    "gen0": {
                        "female": float(y0[0]),
                        "male": float(y0[1]),
                        "age": float(y0[2]),
                        "p_male": sigmoid_male(float(y0[0]), float(y0[1])),
                    },
                    "gen1": {
                        "female": float(prob[0]),
                        "male": float(prob[1]),
                        "age": age * 100.0,
                        "p_male": float(prob[1]),  # already a probability
                        "scale": float(gen1_scale),
                        "feed_hw": [int(bgr1.shape[0]), int(bgr1.shape[1])],
                    },
                }
            )
        per_track[int(t["id"])] = rows
    return per_track


def track_vote(crops: list[dict], model: str, floor: float, min_px: int) -> dict:
    f = m = 0
    n_tried = 0
    for c in crops:
        if c["px"] < min_px:
            continue
        n_tried += 1
        p = c[model]["p_male"]
        v = crop_vote(p, floor)
        if v == 1:
            m += 1
        elif v == -1:
            f += 1
    if m > f:
        verdict = "m"
    elif f > m:
        verdict = "f"
    else:
        verdict = "abstain"  # 0/0 or tie → censor
    return {
        "female_votes": f,
        "male_votes": m,
        "n_tried": n_tried,
        "verdict": verdict,
        "censor_women": should_censor_women(f, m),
    }


def size_band(px: int) -> str:
    if px < 40:
        return "<40"
    if px < 80:
        return "40-80"
    return ">=80"


def metrics_for(labelled: list[dict], per_track: dict, model: str, floor: float, min_px: int) -> dict:
    """labelled items: {id, label} with label in m/f/junk/skip."""
    mf = [x for x in labelled if x["label"] in ("m", "f")]
    junk = [x for x in labelled if x["label"] == "junk"]
    rows = []
    for x in mf:
        v = track_vote(per_track.get(x["id"], []), model, floor, min_px)
        rows.append({**x, **v})
    voting = [r for r in rows if r["verdict"] != "abstain"]
    n_f = sum(1 for r in rows if r["label"] == "f")
    n_m = sum(1 for r in rows if r["label"] == "m")
    n_f_vote = sum(1 for r in voting if r["label"] == "f")
    n_m_vote = sum(1 for r in voting if r["label"] == "m")
    f_ok = sum(1 for r in voting if r["label"] == "f" and r["verdict"] == "f")
    m_ok = sum(1 for r in voting if r["label"] == "m" and r["verdict"] == "m")
    f_acc = (f_ok / n_f_vote) if n_f_vote else float("nan")
    m_acc = (m_ok / n_m_vote) if n_m_vote else float("nan")
    if n_f_vote and n_m_vote:
        bal = 0.5 * (f_acc + m_acc)
    elif n_f_vote or n_m_vote:
        bal = f_acc if n_f_vote else m_acc
    else:
        bal = float("nan")
    # Women mode: spared iff male votes strictly win.
    f_exposed = sum(1 for r in rows if r["label"] == "f" and not r["censor_women"])
    m_visible = sum(1 for r in rows if r["label"] == "m" and not r["censor_women"])
    abstain_frac = (sum(1 for r in rows if r["verdict"] == "abstain") / len(rows)) if rows else float("nan")
    women_exposed = (f_exposed / n_f) if n_f else float("nan")
    men_visible = (m_visible / n_m) if n_m else float("nan")

    bands = {}
    for r in rows:
        crops = [c for c in per_track.get(r["id"], []) if c["px"] >= min_px] or per_track.get(r["id"], [])
        px = max((c["px"] for c in crops), default=0)
        b = size_band(px)
        slot = bands.setdefault(b, {"n": 0, "ok": 0, "exposed_f": 0, "n_f": 0, "visible_m": 0, "n_m": 0})
        slot["n"] += 1
        if r["verdict"] == r["label"]:
            slot["ok"] += 1
        if r["label"] == "f":
            slot["n_f"] += 1
            if not r["censor_women"]:
                slot["exposed_f"] += 1
        if r["label"] == "m":
            slot["n_m"] += 1
            if not r["censor_women"]:
                slot["visible_m"] += 1

    junk_rows = []
    junk_male = junk_female = junk_abs = 0
    for x in junk:
        v = track_vote(per_track.get(x["id"], []), model, floor, min_px)
        junk_rows.append(v)
        if v["verdict"] == "m":
            junk_male += 1
        elif v["verdict"] == "f":
            junk_female += 1
        else:
            junk_abs += 1
    # Women mode spares a junk track iff it voted male-majority.
    junk_spared = sum(1 for v in junk_rows if not v["censor_women"])

    crop = crop_metrics(labelled, per_track, model, floor, min_px)

    return {
        "model": model,
        "floor": floor,
        "min_px": min_px,
        "n_labelled_mf": len(rows),
        "n_female": n_f,
        "n_male": n_m,
        "n_voting": len(voting),
        "n_female_voting": n_f_vote,
        "n_male_voting": n_m_vote,
        "female_acc": f_acc,
        "male_acc": m_acc,
        "balanced_acc": bal,
        "abstain_frac": abstain_frac,
        "women_exposed": women_exposed,
        "women_exposed_n": f_exposed,
        "men_visible": men_visible,
        "men_visible_n": m_visible,
        "clears_bal90": (bal >= 0.90) if bal == bal else False,
        "clears_men70": (men_visible >= 0.70) if men_visible == men_visible else False,
        "neither_class_collapsed": (f_acc == f_acc and m_acc == m_acc and f_acc >= 0.5 and m_acc >= 0.5),
        "bands": bands,
        "junk": {
            "n": len(junk),
            "vote_male": junk_male,
            "vote_female": junk_female,
            "abstain": junk_abs,
            "spared_women_mode": junk_spared,
        },
        "crop": crop,
    }


def crop_metrics(labelled: list[dict], per_track: dict, model: str, floor: float, min_px: int) -> dict:
    """Per-crop vote vs the track label. Abstain excluded from accuracy denom."""
    mf = [x for x in labelled if x["label"] in ("m", "f")]
    junk = [x for x in labelled if x["label"] == "junk"]
    n_f = n_m = 0
    n_f_vote = n_m_vote = 0
    f_ok = m_ok = 0
    n_abs = 0
    bands: dict[str, dict] = {}
    for x in mf:
        for c in per_track.get(x["id"], []):
            if c["px"] < min_px:
                continue
            v = crop_vote(c[model]["p_male"], floor)
            b = size_band(c["px"])
            slot = bands.setdefault(
                b, {"n": 0, "n_voting": 0, "ok": 0, "abstain": 0, "n_f": 0, "n_m": 0}
            )
            slot["n"] += 1
            if x["label"] == "f":
                n_f += 1
                slot["n_f"] += 1
            else:
                n_m += 1
                slot["n_m"] += 1
            if v == 0:
                n_abs += 1
                slot["abstain"] += 1
                continue
            pred = "m" if v == 1 else "f"
            slot["n_voting"] += 1
            if x["label"] == "f":
                n_f_vote += 1
                if pred == "f":
                    f_ok += 1
                    slot["ok"] += 1
            else:
                n_m_vote += 1
                if pred == "m":
                    m_ok += 1
                    slot["ok"] += 1
    n_tried = n_f + n_m
    f_acc = (f_ok / n_f_vote) if n_f_vote else float("nan")
    m_acc = (m_ok / n_m_vote) if n_m_vote else float("nan")
    if n_f_vote and n_m_vote:
        bal = 0.5 * (f_acc + m_acc)
    elif n_f_vote or n_m_vote:
        bal = f_acc if n_f_vote else m_acc
    else:
        bal = float("nan")
    junk_m = junk_f = junk_abs = junk_tried = 0
    for x in junk:
        for c in per_track.get(x["id"], []):
            if c["px"] < min_px:
                continue
            junk_tried += 1
            v = crop_vote(c[model]["p_male"], floor)
            if v == 1:
                junk_m += 1
            elif v == -1:
                junk_f += 1
            else:
                junk_abs += 1
    return {
        "n": n_tried,
        "n_female": n_f,
        "n_male": n_m,
        "n_voting": n_f_vote + n_m_vote,
        "n_female_voting": n_f_vote,
        "n_male_voting": n_m_vote,
        "female_acc": f_acc,
        "male_acc": m_acc,
        "balanced_acc": bal,
        "female_ok": f_ok,
        "male_ok": m_ok,
        "abstain_n": n_abs,
        "abstain_frac": (n_abs / n_tried) if n_tried else float("nan"),
        "bands": bands,
        "junk": {
            "n": junk_tried,
            "vote_male": junk_m,
            "vote_female": junk_f,
            "abstain": junk_abs,
        },
    }


def fmt_pct(x) -> str:
    if x != x:
        return "nan"
    return f"{100.0 * x:.1f}%"


def print_metrics(title: str, m: dict) -> None:
    print(f"\n=== {title}  floor={m['floor']} min_px={m['min_px']} ===")
    print(
        f"  n mf={m['n_labelled_mf']} (f={m['n_female']} m={m['n_male']}) voting={m['n_voting']} "
        f"abstain={fmt_pct(m['abstain_frac'])}"
    )
    print(
        f"  female acc={fmt_pct(m['female_acc'])}  male acc={fmt_pct(m['male_acc'])}  "
        f"balanced={fmt_pct(m['balanced_acc'])}"
    )
    print(
        f"  women exposed={fmt_pct(m['women_exposed'])} ({m['women_exposed_n']}/{m['n_female']})  "
        f"men visible={fmt_pct(m['men_visible'])} ({m['men_visible_n']}/{m['n_male']})"
    )
    print(
        f"  bar: bal≥90 {m['clears_bal90']}  men≥70 {m['clears_men70']}  "
        f"neither collapsed {m['neither_class_collapsed']}"
    )
    j = m["junk"]
    print(
        f"  junk n={j['n']} vote m/f/abs={j['vote_male']}/{j['vote_female']}/{j['abstain']} "
        f"spared in Women={j['spared_women_mode']}"
    )
    for b in ("<40", "40-80", ">=80"):
        if b not in m["bands"]:
            continue
        s = m["bands"][b]
        acc = (s["ok"] / s["n"]) if s["n"] else float("nan")
        print(f"  track band {b}: n={s['n']} acc={fmt_pct(acc)} f={s['n_f']} m={s['n_m']}")
    cr = m.get("crop")
    if cr:
        print(
            f"  per-crop: n={cr['n']} (f={cr['n_female']} m={cr['n_male']}) "
            f"voting={cr['n_voting']} abstain={fmt_pct(cr['abstain_frac'])}"
        )
        print(
            f"  per-crop female acc={fmt_pct(cr['female_acc'])} "
            f"({cr['female_ok']}/{cr['n_female_voting']})  "
            f"male acc={fmt_pct(cr['male_acc'])} ({cr['male_ok']}/{cr['n_male_voting']})  "
            f"balanced={fmt_pct(cr['balanced_acc'])}"
        )
        jc = cr["junk"]
        print(
            f"  per-crop junk n={jc['n']} vote m/f/abs={jc['vote_male']}/{jc['vote_female']}/{jc['abstain']}"
        )
        for b in ("<40", "40-80", ">=80"):
            if b not in cr["bands"]:
                continue
            s = cr["bands"][b]
            acc = (s["ok"] / s["n_voting"]) if s["n_voting"] else float("nan")
            print(
                f"  crop band {b}: n={s['n']} vote={s['n_voting']} acc={fmt_pct(acc)} "
                f"f={s['n_f']} m={s['n_m']} abs={s['abstain']}"
            )


def decision_rule(gen0: dict, gen1: dict) -> str:
    """plan §7.1: cheapest that clears the bar at women-exposed ≤ GEN-0's 4.8% (or GEN-0's measured)."""
    gen0_exp = gen0["women_exposed"]
    cap = gen0_exp if gen0_exp == gen0_exp else 0.048

    def clears(m):
        return (
            m["clears_bal90"]
            and m["clears_men70"]
            and m["neither_class_collapsed"]
            and (m["women_exposed"] == m["women_exposed"] and m["women_exposed"] <= cap + 1e-12)
        )

    c0, c1 = clears(gen0), clears(gen1)
    lines = [
        f"GEN-0 women-exposed cap = {fmt_pct(cap)} (measured on this label set; plan cites 4.8% from S23).",
        f"GEN-0 clears bar: {c0}  GEN-1 clears bar: {c1}",
    ]
    if c1:
        lines.append(
            "Decision (provisional on these labels): GEN-1 is licence-clean-ish (amber) and "
            "clears the bar at women-exposed ≤ GEN-0; pick GEN-1 if it is cheaper (see cost)."
        )
    elif c0 and not c1:
        lines.append(
            "Decision (provisional): GEN-1 misses the bar. GEN-0 is 🔴 and must not ship; "
            "ship GEN-T or Everyone-only until a clean model clears it."
        )
    else:
        lines.append(
            "Decision (provisional): neither clears the bar on this set. "
            "Ship GEN-T, or Everyone-only, until one does. A 🔴 model stays out either way."
        )
    return "\n".join(lines)


def read_labels(path: Path) -> list[dict]:
    rows = []
    with path.open() as f:
        for r in csv.DictReader(f):
            lab = (r.get("label") or "").strip().lower()
            if lab in ("", "nan"):
                continue
            if lab not in ("m", "f", "junk", "skip"):
                print(f"warn: track {r.get('track')} label={lab!r} ignored (want m/f/junk/skip)")
                continue
            if lab == "skip":
                continue
            rows.append({"id": int(r["track"]), "label": lab})
    return rows


def cmd_score(args) -> None:
    labels_path = Path(args.labels)
    if not labels_path.is_absolute():
        labels_path = Path.cwd() / labels_path
    if not labels_path.exists():
        sys.exit(f"missing {labels_path}")
    gen1_scale = float(args.gen1_scale)
    index = load_index()
    labelled = read_labels(labels_path)
    print(
        f"score {labels_path}  labelled={len(labelled)} (skip dropped)  "
        f"gen1_scale={gen1_scale:g} (GEN-0 stays {SAVED_CROP_SCALE:g}×)",
        flush=True,
    )
    per_track = infer_all(index, gen1_scale=gen1_scale)
    infer_payload = {"gen1_scale": gen1_scale, "tracks": {str(k): v for k, v in per_track.items()}}
    (OUT / "infer.json").write_text(json.dumps(infer_payload, indent=2) + "\n")
    scale_tag = f"{gen1_scale:.1f}"
    (OUT / f"infer_s{scale_tag}.json").write_text(json.dumps(infer_payload, indent=2) + "\n")

    floors = [round(x, 2) for x in np.arange(0.50, 0.90 + 1e-9, 0.05)]
    report = {
        "labels": str(labels_path),
        "n_labelled": len(labelled),
        "smoke": bool(args.smoke),
        "gen1_scale": gen1_scale,
        "gen0_scale": SAVED_CROP_SCALE,
        "curves": {},
    }
    for model in ("gen0", "gen1"):
        report["curves"][model] = []
        for fl in floors:
            m = metrics_for(labelled, per_track, model, fl, MIN_FACE_PX)
            report["curves"][model].append(m)
            if abs(fl - CONF_FLOOR_APP) < 1e-9:
                print_metrics(model.upper(), m)
        # also no size floor, at app floor, for the small-band table
        m_all = metrics_for(labelled, per_track, model, CONF_FLOOR_APP, min_px=0)
        report[f"{model}_no_minpx"] = m_all
        print_metrics(f"{model.upper()} (min_px=0, floor={CONF_FLOOR_APP})", m_all)

    g0 = next(m for m in report["curves"]["gen0"] if abs(m["floor"] - CONF_FLOOR_APP) < 1e-9)
    g1 = next(m for m in report["curves"]["gen1"] if abs(m["floor"] - CONF_FLOOR_APP) < 1e-9)
    dec = decision_rule(g0, g1)
    report["decision"] = dec
    print("\n" + dec)
    print("\nCONF_FLOOR curve per-track (balanced / women-exposed / men-visible):")
    print(f"{'floor':>6} {'g0 bal':>8} {'g0 exp':>8} {'g0 vis':>8} {'g1 bal':>8} {'g1 exp':>8} {'g1 vis':>8}")
    for a, b in zip(report["curves"]["gen0"], report["curves"]["gen1"]):
        print(
            f"{a['floor']:6.2f} {fmt_pct(a['balanced_acc']):>8} {fmt_pct(a['women_exposed']):>8} "
            f"{fmt_pct(a['men_visible']):>8} {fmt_pct(b['balanced_acc']):>8} "
            f"{fmt_pct(b['women_exposed']):>8} {fmt_pct(b['men_visible']):>8}"
        )
    print("\nCONF_FLOOR curve per-crop (female / male / balanced acc among voting crops):")
    print(
        f"{'floor':>6} {'g0 f':>8} {'g0 m':>8} {'g0 bal':>8} {'g1 f':>8} {'g1 m':>8} {'g1 bal':>8} "
        f"{'g0 n_f':>7} {'g0 n_m':>7} {'g1 n_f':>7} {'g1 n_m':>7}"
    )
    for a, b in zip(report["curves"]["gen0"], report["curves"]["gen1"]):
        ca, cb = a["crop"], b["crop"]
        print(
            f"{a['floor']:6.2f} {fmt_pct(ca['female_acc']):>8} {fmt_pct(ca['male_acc']):>8} "
            f"{fmt_pct(ca['balanced_acc']):>8} {fmt_pct(cb['female_acc']):>8} "
            f"{fmt_pct(cb['male_acc']):>8} {fmt_pct(cb['balanced_acc']):>8} "
            f"{ca['n_female_voting']:7d} {ca['n_male_voting']:7d} "
            f"{cb['n_female_voting']:7d} {cb['n_male_voting']:7d}"
        )
    outp = OUT / ("score_smoke.json" if args.smoke else "score.json")
    # nan → null
    def _san(o):
        if isinstance(o, float) and o != o:
            return None
        if isinstance(o, dict):
            return {k: _san(v) for k, v in o.items()}
        if isinstance(o, list):
            return [_san(v) for v in o]
        return o

    outp.write_text(json.dumps(_san(report), indent=2) + "\n")
    print(f"wrote {outp}")
    if not args.smoke:
        tagged = OUT / f"score_s{scale_tag}.json"
        tagged.write_text(json.dumps(_san(report), indent=2) + "\n")
        print(f"wrote {tagged}")


def fill_labels_with_gen0(src: Path, dest: Path, per_track: dict) -> None:
    rows = list(csv.DictReader(src.open()))
    with dest.open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["track", "label", "n_crops", "min_px", "max_px"])
        w.writeheader()
        for r in rows:
            v = track_vote(per_track.get(int(r["track"]), []), "gen0", CONF_FLOOR_APP, MIN_FACE_PX)
            lab = v["verdict"] if v["verdict"] in ("m", "f") else "skip"
            w.writerow({**r, "label": lab})


# --- cost (subprocess per arm) -------------------------------------------------------------------

def _peak_rss_mb() -> float:
    import psutil

    return psutil.Process().memory_info().rss / (1024 * 1024)


def cmd_cost_arm(args) -> None:
    index = load_index()
    paths = [OUT / c["file"] for t in index["tracks"] for c in t["crops"]]
    if not paths:
        sys.exit("no crops")
    n = args.n
    warmup = args.warmup
    arm = args.arm
    if arm == "gen0":
        sess = image_session(GEN0)
        name = sess.get_inputs()[0].name
        preps = []
        for p in paths[: max(n + warmup, 1)]:
            bgr = cv2.imread(str(p))
            preps.append(prep_gen0(bgr[:, :, ::-1]))
        run = lambda x: sess.run(None, {name: x})
    elif arm in ("gen1", "gen1_int8"):
        path = GEN1_INT8 if arm == "gen1_int8" else GEN1_ONNX
        if not path.exists():
            print(json.dumps({"arm": arm, "error": f"missing {path}"}))
            return
        sess = image_session(path)
        name = sess.get_inputs()[0].name
        preps = []
        for p in paths[: max(n + warmup, 1)]:
            bgr = cv2.imread(str(p))
            preps.append(prep_gen1(bgr))
        run = lambda x: sess.run(None, {name: x})
    else:
        sys.exit(f"unknown arm {arm}")

    # warmup
    for i in range(min(warmup, len(preps))):
        run(preps[i % len(preps)])
    times = []
    rss0 = _peak_rss_mb()
    for i in range(n):
        x = preps[i % len(preps)]
        t0 = time.perf_counter()
        run(x)
        times.append((time.perf_counter() - t0) * 1000.0)
    rss1 = _peak_rss_mb()
    times.sort()
    med = times[len(times) // 2]
    out = {
        "arm": arm,
        "n": n,
        "warmup": warmup,
        "median_ms": med,
        "p90_ms": times[int(0.9 * (len(times) - 1))],
        "mean_ms": float(np.mean(times)),
        "peak_rss_mb": max(rss0, rss1),
        "file_bytes": (GEN0 if arm == "gen0" else (GEN1_INT8 if arm == "gen1_int8" else GEN1_ONNX)).stat().st_size,
        "providers": ["CPUExecutionProvider"],
        "note": "host CPU EP intra-op=1 spinning=0; app uses XNNPACK×4. contended if other jobs run.",
    }
    print(json.dumps(out), flush=True)


def cmd_cost(args) -> None:
    if not (OUT / "index.json").exists():
        sys.exit("run extract first")
    if not GEN1_ONNX.exists():
        convert_gen1()
    arms = ["gen0", "gen1"]
    if GEN1_INT8.exists():
        arms.append("gen1_int8")
    results = []
    for arm in arms:
        cmd = [
            sys.executable,
            str(Path(__file__).resolve()),
            "_cost_arm",
            "--arm",
            arm,
            "--n",
            str(args.n),
            "--warmup",
            str(args.warmup),
        ]
        print(f"cost arm {arm} ...", flush=True)
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode != 0:
            print(r.stderr, file=sys.stderr)
            sys.exit(f"cost arm {arm} failed")
        line = [ln for ln in r.stdout.splitlines() if ln.startswith("{")][-1]
        rec = json.loads(line)
        results.append(rec)
        print(
            f"  {arm}: median {rec['median_ms']:.3f} ms/crop  p90 {rec['p90_ms']:.3f}  "
            f"rss {rec['peak_rss_mb']:.1f} MB  file {rec['file_bytes']} B",
            flush=True,
        )
    payload = {
        "contended_provisional": True,
        "n": args.n,
        "warmup": args.warmup,
        "arms": results,
        "command": " ".join(
            [sys.executable, "scripts/bench/score_gen.py", "cost", "--n", str(args.n), "--warmup", str(args.warmup)]
        ),
    }
    (OUT / "cost.json").write_text(json.dumps(payload, indent=2) + "\n")
    print(f"wrote {OUT/'cost.json'}  (contended, provisional)")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("extract", help="decode vlog, YuNet, tracks, crops, sheets, empty labels.csv")
    p_score = sub.add_parser("score", help="run GEN-0 and GEN-1 on labelled tracks")
    p_score.add_argument("labels")
    p_score.add_argument("--smoke", action="store_true", help="mark output as smoke, not a result")
    p_score.add_argument(
        "--gen1-scale",
        type=float,
        default=1.0,
        help=(
            "GEN-1 crop as this multiple of the detector box (centre-crop of the "
            "saved 1.5× square). Default 1.0 (OMZ tight box). GEN-0 is unchanged."
        ),
    )
    p_cost = sub.add_parser("cost", help="host ms/crop, one arm per subprocess")
    p_cost.add_argument("--n", type=int, default=100)
    p_cost.add_argument("--warmup", type=int, default=20)
    p_arm = sub.add_parser("_cost_arm")
    p_arm.add_argument("--arm", required=True)
    p_arm.add_argument("--n", type=int, default=100)
    p_arm.add_argument("--warmup", type=int, default=20)
    args = ap.parse_args()
    if args.cmd == "extract":
        cmd_extract(args)
    elif args.cmd == "score":
        cmd_score(args)
    elif args.cmd == "cost":
        cmd_cost(args)
    elif args.cmd == "_cost_arm":
        cmd_cost_arm(args)


if __name__ == "__main__":
    main()
