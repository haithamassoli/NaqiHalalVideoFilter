"""Naqi's optional cloud for long videos, on Modal's free monthly credit.

Two jobs, both mirroring what the phone would otherwise do itself:
  music    the source's audio track in -> the same htdemucs on a GPU -> kept stems as AAC .m4a
  analyze  the source video in -> body-mode pass 1 (naqi_analyze.py) -> the phone's checkpoint JSON;
           the phone builds the EDL and renders the blur itself
Any non-200 answer means "do it on the phone": the apps never depend on this service.

Deploy (one Modal workspace per store app, each with its own $30 credit):
    modal secret create naqi NAQI_KEY=<random>          # once per workspace
    modal deploy server/naqi_modal.py                    # MODAL_PROFILE picks the workspace
A redeploy can leave the previous web container serving for a while; `modal container stop` it.

Env knobs (in the `naqi` secret): NAQI_BUDGET_USD (default 25 of the $30 credit),
NAQI_DEVICE_DAILY_SEC (source seconds per device per day, default 4 h).
"""
import asyncio
import os
import time
import uuid
from pathlib import Path

import modal

app = modal.App("naqi")

# modal.com/pricing, $/s. ponytail: hardcoded rates — re-read the page if Modal reprices.
T4, CORE, GIB = 0.000164, 0.0000131, 0.00000222
# Billed but not inside a job: cold start + scaledown idle. Errs high.
OVERHEAD_S = 60

usage = modal.Dict.from_name("naqi-usage", create_if_missing=True)
uploads = modal.Volume.from_name("naqi-uploads-v2", create_if_missing=True, version=2)
MODELS = Path(__file__).parent.parent / "app/src/main/assets/models"  # gitignored; scripts/fetch-models.sh


def _bake_demucs():
    from demucs.pretrained import get_model

    get_model("htdemucs")


audio_image = (
    modal.Image.debian_slim(python_version="3.11")
    .apt_install("ffmpeg")
    .pip_install("torch==2.4.1", "torchaudio==2.4.1", "demucs==4.0.1", "numpy<2")
    .run_function(_bake_demucs)
)
vision_image = (
    modal.Image.from_registry("nvidia/cuda:12.4.1-cudnn-runtime-ubuntu22.04", add_python="3.11")
    .apt_install("ffmpeg", "wget", "unzip")
    .pip_install("onnxruntime-gpu==1.20.2", "opencv-python-headless==4.10.0.84", "numpy<2")
    # det_10g (faces) and genderage — the phone ships this same genderage.onnx, verbatim.
    .run_commands(
        "wget -q https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_l.zip -O /tmp/b.zip"
        " && unzip -j /tmp/b.zip det_10g.onnx genderage.onnx -d /models && rm /tmp/b.zip"
    )
    .add_local_file(MODELS / "yolo11n-seg.onnx", "/models/yolo11n-seg.onnx")
    .add_local_file(MODELS / "nsfw_mnv2_140_int8.onnx", "/models/nsfw_mnv2_140_int8.onnx")
    .add_local_python_source("naqi_analyze")
)
web_image = modal.Image.debian_slim(python_version="3.11").pip_install("fastapi[standard]")


def _month() -> str:
    # ponytail: calendar month, not Modal's billing cycle — the $5 margin under $30 absorbs the skew.
    return time.strftime("m:%Y-%m", time.gmtime())


def _charge(started: float, per_s: float):
    usage[_month()] = usage.get(_month(), 0.0) + (time.time() - started + OVERHEAD_S) * per_s


def _assemble(job: str) -> str:
    """Join the uploaded parts onto this container's disk (the volume is only the hand-off)."""
    import shutil

    uploads.reload()
    parts = sorted(Path(f"/up/{job}").iterdir())
    if not parts:
        raise ValueError("nothing uploaded")
    with open(f"/tmp/{job}.src", "wb") as out:
        for part in parts:
            with open(part, "rb") as f:
                shutil.copyfileobj(f, out, 1 << 22)
    return f"/tmp/{job}.src"


def _done(job: str):
    import shutil

    usage.pop(f"p:{job}", None)
    Path(f"/tmp/{job}.src").unlink(missing_ok=True)
    shutil.rmtree(f"/up/{job}", ignore_errors=True)
    uploads.commit()


def _softclip(x):
    import numpy as np

    a = np.abs(x)
    knee = np.sign(x) * (0.95 + np.tanh((a - 0.95) / 0.05) * 0.05)
    return np.where(a <= 0.95, x, knee).astype(np.float32)


@app.cls(gpu="T4", cpu=4, memory=8 * 1024, image=audio_image, volumes={"/up": uploads}, scaledown_window=30, timeout=3 * 3600)
class Separator:
    @modal.enter()
    def load(self):
        from demucs.pretrained import get_model

        self.model = get_model("htdemucs").eval()

    @modal.method()
    def separate(self, job: str, keep: str) -> bytes:
        t0 = time.time()
        try:
            return self._separate(_assemble(job), keep, job)
        finally:
            _charge(t0, T4 + 4 * CORE + 8 * GIB)
            _done(job)

    def _separate(self, src: str, keep: str, job: str) -> bytes:
        import subprocess

        import numpy as np
        import torch
        from demucs.apply import apply_model

        raw, out = f"/tmp/{job}.f32", f"/tmp/{job}.m4a"
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", src, "-vn", "-ac", "2", "-ar", "44100", "-f", "f32le", raw], check=True)
        x = np.memmap(raw, dtype=np.float32, mode="r").reshape(-1, 2)
        n = len(x)
        if n == 0:
            raise ValueError("no audio")
        # Same whole-track mono "ref" normalization as demucs' separate.py and the phones' driver.
        mono = x.mean(axis=1)
        mean, std = float(mono.mean()), float(mono.std()) or 1e-8
        del mono

        names = self.model.sources  # drums, bass, other, vocals
        kept = [names.index("vocals")] + ([names.index("other")] if keep == "vocals_other" else [])
        enc = subprocess.Popen(
            ["ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ar", "44100", "-ac", "2", "-i", "-",
             "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", out],
            stdin=subprocess.PIPE,
        )
        # Blocks bound RAM on a 3 h film; each sees 5 s of its neighbours so the hard cut between
        # blocks lands where both sides had full context — no crossfade needed.
        block, ctx = 600 * 44100, 5 * 44100
        with torch.no_grad():
            for s in range(0, n, block):
                a, b = max(0, s - ctx), min(n, s + block + ctx)
                mix = torch.from_numpy(np.ascontiguousarray((x[a:b].T - mean) / std))[None]
                # shifts=0 and 10 % overlap match the phones' driver (no random shift, STRIDE = 0.9 SEG).
                stems = apply_model(self.model, mix, shifts=0, overlap=0.1, device="cuda")[0]
                y = stems[kept].sum(0).numpy().T * std + mean
                enc.stdin.write(_softclip(y[s - a : s - a + min(block, n - s)]).tobytes())
                usage[f"p:{job}"] = int(100 * min(n, s + block) / n)
        enc.stdin.close()
        if enc.wait() != 0:
            raise RuntimeError("aac encode failed")
        result = Path(out).read_bytes()
        os.remove(raw)
        os.remove(out)
        return result


@app.cls(gpu="T4", cpu=8, memory=8 * 1024, image=vision_image, volumes={"/up": uploads}, scaledown_window=30, timeout=4 * 3600)
class Analyzer:
    @modal.enter()
    def load(self):
        import naqi_analyze

        self.models = naqi_analyze.Models("/models", ["CUDAExecutionProvider", "CPUExecutionProvider"])

    @modal.method()
    def analyze(self, job: str, who: str, precise: bool, nsfw: bool, strictness: int) -> dict:
        import naqi_analyze

        t0 = time.time()
        try:
            return naqi_analyze.analyze(
                _assemble(job), self.models, who, precise, nsfw, strictness,
                progress=lambda p: usage.put(f"p:{job}", p),
            )
        finally:
            _charge(t0, T4 + 8 * CORE + 8 * GIB)
            _done(job)


# One container takes every request, so a job's parts all land on one disk and are committed once, at
# submit; committing per part measured 3-30 s each. A single request ingests at ~3 MB/s, so the phone
# sends parts in parallel (~11 MB/s at 8). ponytail: one container is plenty for a free-tier app.
@app.function(image=web_image, secrets=[modal.Secret.from_name("naqi")], volumes={"/up": uploads}, timeout=900, max_containers=1)
@modal.concurrent(max_inputs=100)
@modal.asgi_app()
def api():
    from fastapi import FastAPI, Header, HTTPException, Request, Response
    from fastapi.responses import JSONResponse

    web = FastAPI()
    budget = float(os.environ.get("NAQI_BUDGET_USD", "25"))
    daily = float(os.environ.get("NAQI_DEVICE_DAILY_SEC", str(4 * 3600)))

    def check_key(key: str):
        # ponytail: one shared key baked into the apps, so it only stops drive-by traffic. The budget
        # cap is what actually bounds the damage; App Attest / Play Integrity if abuse shows up.
        if key != os.environ["NAQI_KEY"]:
            raise HTTPException(401)

    def day(device: str) -> str:
        return f"d:{time.strftime('%Y-%m-%d', time.gmtime())}:{device}"

    def refusal(device: str, seconds: float):
        """Why this job may not run here, or None — asked before a single byte is uploaded."""
        if not device or seconds <= 0:
            raise HTTPException(400)
        if usage.get(_month(), 0.0) >= budget:
            return JSONResponse({"reason": "quota"}, status_code=429)
        if usage.get(day(device), 0.0) + seconds > daily:
            return JSONResponse({"reason": "device"}, status_code=429)
        return None

    def job_dir(job: str) -> Path:
        if len(job) != 32 or not job.isalnum():
            raise HTTPException(400)
        return Path(f"/up/{job}")

    # Uploads come in parts of a few MB: a Modal web request may not run past 150 s, and a whole film
    # over a phone's uplink would. Each part is its own short request, and the phone retries a part.
    @web.post("/v1/upload")
    def start(seconds: float = 0, x_naqi_key: str = Header(""), x_naqi_device: str = Header("")):
        check_key(x_naqi_key)
        if (no := refusal(x_naqi_device, seconds)) is not None:
            return no
        usage[day(x_naqi_device)] = usage.get(day(x_naqi_device), 0.0) + seconds
        job = uuid.uuid4().hex
        job_dir(job).mkdir()
        return {"job": job}

    @web.put("/v1/upload/{job}/{part}")
    async def part(job: str, part: int, request: Request, x_naqi_key: str = Header("")):
        check_key(x_naqi_key)
        data = await request.body()
        if not data or not 0 <= part < 100_000 or len(data) > 64 << 20:
            raise HTTPException(400)
        d = job_dir(job)
        d.mkdir(exist_ok=True)  # a restarted container lost the start's directory, not the job
        # Off the event loop: a blocking volume write would stall every other part in flight.
        await asyncio.to_thread((d / f"{part:05d}").write_bytes, data)
        return Response(status_code=204)

    @web.post("/v1/music")
    def music(job: str, keep: str = "vocals", x_naqi_key: str = Header("")):
        check_key(x_naqi_key)
        if keep not in ("vocals", "vocals_other"):
            raise HTTPException(400)
        job_dir(job)
        uploads.commit()
        return {"id": f"{Separator().separate.spawn(job, keep).object_id}.{job}"}

    @web.post("/v1/analyze")
    def analyze(job: str, who: str = "everyone", precise: bool = False, nsfw: bool = True, strictness: int = 40,
                x_naqi_key: str = Header("")):
        check_key(x_naqi_key)
        if who not in ("everyone", "women", "men"):
            raise HTTPException(400)
        job_dir(job)
        uploads.commit()
        return {"id": f"{Analyzer().analyze.spawn(job, who, precise, nsfw, strictness).object_id}.{job}"}

    @web.get("/v1/jobs/{id}")
    def result(id: str, x_naqi_key: str = Header("")):
        check_key(x_naqi_key)
        call_id, _, job = id.partition(".")
        try:
            data = modal.FunctionCall.from_id(call_id).get(timeout=0)
        except TimeoutError:
            return JSONResponse({"progress": usage.get(f"p:{job}", 0)}, status_code=202)
        return Response(data, media_type="audio/mp4") if isinstance(data, bytes) else JSONResponse(data)

    @web.get("/v1/status")
    def status(x_naqi_key: str = Header("")):
        check_key(x_naqi_key)
        return {"spentUsd": round(usage.get(_month(), 0.0), 3), "budgetUsd": budget}

    return web
