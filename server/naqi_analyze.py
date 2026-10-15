"""Body-mode pass 1 on a server: a port of the Android analysis that produces its `an-NNN.json` shape.

Output is `{"firingsMs": [...], "edl": {"censorIntervalsMs": fallback, "faceTracks": [...]}}` — exactly
what one segment's checkpoint holds (`work/Checkpoint.kt`), so the phone folds it in with the same code
and builds the EDL (NSFW hysteresis, overflow, whole-frame) itself, then renders as always.

Ported from, and to be kept in step with:
  analysis/FrameSampler.kt  10 fps, 640 px analysis frame, gate on every 2nd sample at a 224² stretch
  analysis/PersonDetector.kt YOLO11n-seg decode, NMS, 32-cell masks, coarse scene cut
  analysis/BodyTracker.kt   tracks, votes, lead-in/hold, orphan-face fallback
  analysis/FaceTracker.kt   padRect, shouldCensor, VOTE_CAP, MIN_FACE_PX
  analysis/NsfwGate.kt      strictness table + delta rule
Faces come from SCRFD (InsightFace det_10g) instead of ML Kit; see FACE_THRESHOLD.
"""
import base64
import json
import subprocess

import numpy as np

FPS = 10
SIDE = 640
GATE = 224
HOLD_MS, LEAD_MS, SETTLE_MS = 900, 300, 1000
VOTE_CAP, MIN_FACE_PX, CONF_FLOOR = 5, 80, 0.60
# ML Kit (FAST, tracking) reports small faces on screens and in crowds that BodyTracker turns into
# whole-frame fallback; SCRFD at 0.5 with a size floor missed those on the body regression clip.
# 0.35 and no floor matched the S23's fallback spans closest. Errs toward covering.
FACE_THRESHOLD = 0.35

NSFW_CLASSES = ["drawings", "hentai", "neutral", "porn", "sexy"]
_TABLE = {"porn": (0.75, 0.10), "sexy": (0.90, 0.10), "hentai": (1.00, 0.50), "neutral": (0.30, 1.00), "drawings": (0.50, 0.50)}


def fires(probs, strictness: int) -> bool:
    s = min(max(strictness, 0), 100)
    d = {c: probs[i] - (_TABLE[c][0] + (_TABLE[c][1] - _TABLE[c][0]) * s / 100) for i, c in enumerate(NSFW_CLASSES)}
    nsfw = max(d["porn"], d["sexy"], d["hentai"])
    return nsfw >= 0 and nsfw > max(d["neutral"], d["drawings"])


def should_censor(female: int, male: int, who: str) -> bool:
    return {"none": False, "women": not male > female, "men": not female > male}.get(who, True)


# ---- geometry (NRect = (l, t, r, b), normalized upright) ----

def area(r):
    return max(0.0, r[2] - r[0]) * max(0.0, r[3] - r[1])


def inter(a, b):
    return max(0.0, min(a[2], b[2]) - max(a[0], b[0])) * max(0.0, min(a[3], b[3]) - max(a[1], b[1]))


def overlap(a, b):
    c = inter(a, b)
    return c / max(area(a) + area(b) - c, 1e-6)


def face_inside(face, body):
    return area(face) > 0 and inter(face, body) / area(face) >= 0.8 and (face[1] + face[3]) / 2 < body[1] + (body[3] - body[1]) * 0.65


def pad_rect(r):
    dx, dy = (r[2] - r[0]) * 0.25, (r[3] - r[1]) * 0.25
    c = lambda v: min(max(v, 0.0), 1.0)
    return (c(r[0] - dx), c(r[1] - dy), c(r[2] + dx), c(r[3] + dy))


# ---- models ----

class Models:
    def __init__(self, root: str, providers):
        import onnxruntime as ort

        so = ort.SessionOptions()
        self.yolo = ort.InferenceSession(f"{root}/yolo11n-seg.onnx", so, providers=providers)
        self.face = ort.InferenceSession(f"{root}/det_10g.onnx", so, providers=providers)
        self.nsfw = ort.InferenceSession(f"{root}/nsfw_mnv2_140_int8.onnx", so, providers=["CPUExecutionProvider"])
        self.gender = ort.InferenceSession(f"{root}/genderage.onnx", so, providers=["CPUExecutionProvider"])

    def faces(self, img):
        """SCRFD det_10g on an RGB frame -> upright-normalized boxes, NMS 0.4."""
        import cv2

        h, w = img.shape[:2]
        s = SIDE / max(h, w)
        nh, nw = int(round(h * s)), int(round(w * s))
        canvas = np.zeros((SIDE, SIDE, 3), np.float32)
        canvas[:nh, :nw] = cv2.resize(img, (nw, nh))
        blob = ((canvas - 127.5) / 128.0).transpose(2, 0, 1)[None]
        outs = self.face.run(None, {self.face.get_inputs()[0].name: blob})
        boxes, scores = [], []
        for i, st in enumerate((8, 16, 32)):
            sc = outs[i].reshape(-1)
            bb = outs[i + 3].reshape(-1, 4) * st
            n = SIDE // st
            ys, xs = np.mgrid[:n, :n]
            centers = np.repeat(np.stack([xs, ys], -1).reshape(-1, 2) * st, 2, axis=0).astype(np.float32)
            k = sc >= FACE_THRESHOLD
            boxes.append(np.hstack([centers[k] - bb[k, :2], centers[k] + bb[k, 2:]]) / s)
            scores.append(sc[k])
        boxes, scores = np.concatenate(boxes), np.concatenate(scores)
        out = []
        for i in np.argsort(-scores):
            b = boxes[i]
            r = (max(b[0], 0) / w, max(b[1], 0) / h, min(b[2], w) / w, min(b[3], h) / h)
            if r[2] <= r[0] or r[3] <= r[1] or any(overlap(r, o) > 0.4 for o in out):
                continue
            out.append(r)
        return out

    def people(self, img, masks: bool):
        """YOLO11n-seg persons (PersonDetector.detect) -> ([(box, mask|None)], 16x16 signature)."""
        import cv2

        h, w = img.shape[:2]
        scale = SIDE / max(w, h)
        nw, nh = round(w * scale), round(h * scale)
        left, top = (SIDE - nw) // 2, (SIDE - nh) // 2
        x = np.full((SIDE, SIDE, 3), 114, np.uint8)
        x[top : top + nh, left : left + nw] = cv2.resize(img, (nw, nh)) if (nw, nh) != (w, h) else img
        x = x.astype(np.float32).transpose(2, 0, 1) / 255.0
        gy = top + np.arange(16) * nh // 16
        gx = left + np.arange(16) * nw // 16
        signature = x[:, gy][:, :, gx].mean(axis=0).reshape(-1)
        outs = self.yolo.run(None if masks else ["output0"], {self.yolo.get_inputs()[0].name: x[None]})
        out = outs[0][0]  # [116, 8400]
        proto = outs[1][0] if masks else None  # [32, 160, 160]
        person = out[4]
        cand = np.where((person > 0.25) & ~(out[5:84] > person).any(axis=0))[0]
        cand = cand[np.lexsort((cand, -person[cand]))]
        kept = []
        for i in cand:
            cx, cy, bw, bh = out[:4, i]
            if bw <= 0 or bh <= 0:
                continue
            box = (cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
            if any(overlap(k[1], box) > 0.7 for k in kept):
                continue
            kept.append((i, box))
            if len(kept) == 300:
                break
        res = []
        for i, lb in kept:
            c = lambda v: float(min(max(v, 0.0), 1.0))
            box = (c((lb[0] - left) / (scale * w)), c((lb[1] - top) / (scale * h)), c((lb[2] - left) / (scale * w)), c((lb[3] - top) / (scale * h)))
            if box[2] - box[0] <= 0 or box[3] - box[1] <= 0:
                continue
            res.append((box, _mask(out[84:, i], proto, box, w, h, scale, left, top) if masks else None))
        return res, signature

    def vote(self, img, face) -> int:
        """genderage on InsightFace's square crop (FrameSampler.cropToTensor): +1 male, -1 female, 0 abstain."""
        h, w = img.shape[:2]
        half = max((face[2] - face[0]) * w, (face[3] - face[1]) * h) * 1.5 / 2
        x0 = (face[0] + face[2]) / 2 * w - half
        y0 = (face[1] + face[3]) / 2 * h - half
        step = half * 2 / 96
        xs = np.clip((x0 + np.arange(96) * step).astype(int), 0, w - 1)
        ys = np.clip((y0 + np.arange(96) * step).astype(int), 0, h - 1)
        crop = img[ys][:, xs].astype(np.float32).transpose(2, 0, 1)[None]
        out = self.gender.run(None, {self.gender.get_inputs()[0].name: crop})[0][0]
        p = 1 / (1 + np.exp(-(out[1] - out[0])))
        return 0 if max(p, 1 - p) < CONF_FLOOR else (1 if p >= 0.5 else -1)

    def nsfw_probs(self, img):
        import cv2

        x = cv2.resize(img, (GATE, GATE), interpolation=cv2.INTER_LINEAR).astype(np.float32).transpose(2, 0, 1)[None] / 255.0
        return self.nsfw.run(None, {self.nsfw.get_inputs()[0].name: x})[0][0]


def _mask(coef, proto, box, w, h, scale, left, top):
    """PersonDetector.MaskBuilder: logit sign at each cell centre, 32-cell long side, LSB-first bits."""
    bw, bh = (box[2] - box[0]) * w, (box[3] - box[1]) * h
    long = max(bw, bh)
    cols, rows = max(1, round(32 * bw / long)), max(1, round(32 * bh / long))
    ly = (box[1] * h + (np.arange(rows) + 0.5) * bh / rows) * scale + top
    lx = (box[0] * w + (np.arange(cols) + 0.5) * bw / cols) * scale + left
    py = np.clip((ly / 4).astype(int), 0, 159)
    px = np.clip((lx / 4).astype(int), 0, 159)
    logits = np.tensordot(coef, proto[:, py][:, :, px], axes=1)  # [rows, cols]
    bits = np.packbits((logits > 0).reshape(-1), bitorder="little")
    return [box[0], box[1], box[2], box[3], cols, rows, base64.b64encode(bits.tobytes()).decode()]


# ---- BodyTracker ----

class Track:
    def __init__(self, tid):
        self.id, self.samples, self.start = tid, [], 0
        self.female = self.male = self.tried = 0
        self.last_vote = -(1 << 62)


class BodyTracker:
    def __init__(self, who: str):
        self.who, self.live, self.emitted, self.fallback = who, [], [], []
        self.next_id, self.last_pts, self.shot_start = 0, None, 0

    def lead_in(self, pts, otherwise):
        return min(self.shot_start, otherwise) if pts - self.shot_start <= SETTLE_MS else otherwise

    def on_frame(self, people, faces, width, height, pts, cut, vote):
        since = self.last_pts + 1 if self.last_pts is not None else pts
        if self.last_pts is None:
            self.shot_start = pts
        self.last_pts = pts
        if cut:
            for tr in self.live:
                self.emit(tr, pts - 1)
            self.live, self.shot_start = [], since
        for tr in [t for t in self.live if pts - t.samples[-1][0] > HOLD_MS]:
            self.emit(tr)
            self.live.remove(tr)
        old = list(self.live)
        used = set()
        boxes = [p[0] for p in people]
        scores = [[overlap(b, t.samples[-1][1]) for t in old] for b in boxes]
        for idx, (box, mask) in enumerate(people):
            ranked = sorted(range(len(old)), key=lambda j: -scores[idx][j])
            best = ranked[0] if ranked else None
            score = scores[idx][best] if best is not None else 0.0
            runner = scores[idx][ranked[1]] if len(ranked) > 1 else 0.0
            competing = max((scores[k][best] for k in range(len(boxes)) if k != idx), default=0.0) if best is not None else 0.0
            matched = old[best] if best is not None and score >= 0.25 and score - runner >= 0.10 and score - competing >= 0.10 else None
            if matched is not None and matched.id in used:
                matched = None
            fresh = not any(overlap(box, t.samples[-1][1]) > 0.1 for t in old)
            track = matched
            if track is None:
                track = Track(self.next_id)
                self.next_id += 1
                track.start = self.lead_in(pts, max(self.shot_start, pts - LEAD_MS)) if fresh else since
                self.live.append(track)
            used.add(track.id)
            track.samples.append((pts, box, mask))
            if vote is None or track.tried >= VOTE_CAP:
                continue
            assoc = [f for f in faces if face_inside(f, box) and sum(face_inside(f, b) for b in boxes) == 1]
            if len(assoc) != 1:
                continue
            f = assoc[0]
            px = int(max((f[2] - f[0]) * width, (f[3] - f[1]) * height))
            if px < MIN_FACE_PX or (track.tried > 0 and pts - track.last_vote < 100):
                continue
            track.last_vote = pts
            track.tried += 1
            v = vote(f)
            if v == 1:
                track.male += 1
            elif v == -1:
                track.female += 1
        for tr in old:
            if tr.id not in used and any(overlap(b, tr.samples[-1][1]) > 0.1 for b in boxes):
                self.emit(tr, pts - 1)
                self.live.remove(tr)
        if any(not any(face_inside(f, b) for b in boxes) for f in faces):
            self.fallback.append([max(self.lead_in(pts, since), 0), pts + HOLD_MS])

    def emit(self, tr, end=None):
        end = tr.samples[-1][0] + HOLD_MS if end is None else end
        spare = {"women": tr.male, "men": tr.female}.get(self.who, 2)
        if not should_censor(tr.female, tr.male, self.who) and spare >= 2:
            return
        out = {
            "startMs": max(tr.start, 0),
            "endMs": max(end, tr.samples[-1][0]),
            "keyframes": [[t, *pad_rect(b)] for t, b, _ in tr.samples],
            "union": True,
        }
        if any(m is not None for _, _, m in tr.samples):
            out["masks"] = [m for _, _, m in tr.samples]
        self.emitted.append(out)

    def finish(self):
        for tr in self.live:
            self.emit(tr)
        self.live = []
        return sorted(self.emitted, key=lambda e: e["startMs"])


def merge(ranges):
    out = []
    for a, b in sorted(ranges):
        if out and a <= out[-1][1]:
            out[-1][1] = max(out[-1][1], b)
        else:
            out.append([a, b])
    return out


def analyze(path: str, models: Models, who: str, precise: bool, nsfw: bool, strictness: int, progress=None) -> dict:
    """Decode at 10 fps (ffmpeg autorotates, so every frame is upright) and run the body-mode pass."""
    probe = json.loads(subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_streams", "-show_format", "-of", "json", path],
        check=True, capture_output=True).stdout)
    st = probe["streams"][0]
    # Display matrix on ffmpeg >= 5, the legacy `rotate` tag before that; either way only 90/270 swap axes.
    rot = int(next((d["rotation"] for d in st.get("side_data_list", []) if "rotation" in d), st.get("tags", {}).get("rotate", 0))) % 180
    sw, sh = (st["height"], st["width"]) if rot == 90 else (st["width"], st["height"])
    start_ms = round(float(st.get("start_time") or 0) * 1000)
    duration_ms = float(probe["format"].get("duration") or 0) * 1000
    # The gate wants source pixels (FrameSampler.gatherGate), so frames come out at up to 1280 px and
    # the 640 analysis frame is cut from them; 4K would only cost pipe bandwidth for no gate gain.
    s = min(1.0, 1280 / max(sw, sh))
    fw, fh = int(sw * s) // 2 * 2, int(sh * s) // 2 * 2
    a = min(1.0, SIDE / max(fw, fh))
    aw, ah = max(2, round(fw * a)) // 2 * 2, max(2, round(fh * a)) // 2 * 2
    dec = subprocess.Popen(
        ["ffmpeg", "-v", "error", "-threads", "0", "-i", path, "-an", "-vf", f"fps={FPS},scale={fw}:{fh}", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
        stdout=subprocess.PIPE, bufsize=fw * fh * 3 * 4)
    import cv2

    tracker = BodyTracker(who)
    voter = (lambda img: (lambda f: models.vote(img, f))) if who in ("women", "men") else None
    firings, prev, n = [], None, 0
    frame_bytes = fw * fh * 3
    while True:
        buf = dec.stdout.read(frame_bytes)
        if len(buf) < frame_bytes:
            break
        big = np.frombuffer(buf, np.uint8).reshape(fh, fw, 3)
        pts = start_ms + n * 1000 // FPS
        img = cv2.resize(big, (aw, ah), interpolation=cv2.INTER_AREA) if (aw, ah) != (fw, fh) else big
        if nsfw and n % 2 == 0 and fires(models.nsfw_probs(big), strictness):
            firings.append(pts)
        people, sig = models.people(img, precise)
        cut = prev is not None and float(np.abs(sig - prev).mean()) > 0.20
        prev = sig
        tracker.on_frame(people, models.faces(img), aw, ah, pts, cut, voter(img) if voter else None)
        n += 1
        if progress and n % 50 == 0 and duration_ms > 0:
            progress(min(99, int(100 * (pts - start_ms) / duration_ms)))
    if dec.wait() != 0:
        raise RuntimeError("video decode failed")
    if n == 0:
        raise ValueError("no video frames")
    return {"firingsMs": firings, "edl": {"censorIntervalsMs": merge(tracker.fallback), "faceTracks": tracker.finish()}}


if __name__ == "__main__":  # local check: python naqi_analyze.py <video> <models dir> [who] [precise]
    import sys

    m = Models(sys.argv[2], ["CPUExecutionProvider"])
    r = analyze(sys.argv[1], m, sys.argv[3] if len(sys.argv) > 3 else "everyone", len(sys.argv) > 4 and sys.argv[4] == "precise", False, 40)
    print(json.dumps(r))
