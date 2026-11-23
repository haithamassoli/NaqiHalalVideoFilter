#!/usr/bin/env python3
"""One stock Core ML export attempt; keep the tested 20 s SCNet configuration."""
import argparse
import hashlib
import json
import resource
import sys
import time
from pathlib import Path

import numpy as np
import torch
import yaml
import coremltools as ct

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--sources", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--results", type=Path, required=True)
    args = parser.parse_args()
    torch.set_num_threads(4)
    sys.path.insert(0, str(args.sources / "SCNet"))
    from scnet.SCNet import SCNet
    config_path = args.sources / "SCNet/conf/config.yaml"
    model_path = args.assets / "scnet-small.th"
    if any(p.resolve() in {model_path.resolve(), config_path.resolve()} for p in (args.output, args.results)):
        parser.error("Export/results must not overwrite the checkpoint/configuration")
    if args.output.resolve() == args.results.resolve():
        parser.error("Model output and results must use different files")
    record = {"candidate": "scnet-small", "stage": "stock_coreml_export", "segment_s": 20, "input_shape": [1, 2, 882000], "target": "iOS18", "coremltools": ct.__version__, "torch": torch.__version__, "numpy": np.__version__, "model_sha256": hashlib.sha256(model_path.read_bytes()).hexdigest(), "config_sha256": hashlib.sha256(config_path.read_bytes()).hexdigest(), "status": "failed", "attempts": 1, "scope": "Stock converter only; no custom FFT/architecture edits"}
    before = time.perf_counter()
    try:
        config = yaml.safe_load(config_path.read_text())["model"]
        model = SCNet(**config)
        checkpoint = torch.load(model_path, map_location="cpu", weights_only=False)
        model.load_state_dict({k.removeprefix("module."): v for k, v in checkpoint["best_state"].items()})
        model.eval()
        with torch.inference_mode():
            traced = torch.jit.trace(model, torch.zeros(1, 2, 882000), check_trace=False)
        record["load_and_trace_s"] = time.perf_counter() - before
        converted = ct.convert(traced, source="pytorch", convert_to="mlprogram", inputs=[ct.TensorType(name="audio", shape=(1, 2, 882000), dtype=np.float32)], compute_precision=ct.precision.FLOAT16, minimum_deployment_target=ct.target.iOS18, skip_model_load=True)
        converted.save(str(args.output))
        record.update(status="exported_unvalidated", output=str(args.output), note="Conversion alone does not establish native parity or iPhone placement/memory")
    except Exception as error:
        record["error"] = f"{type(error).__name__}: {error}"
        import traceback
        traceback.print_exc()
    record["attempt_s"] = time.perf_counter() - before
    record["peak_rss_bytes"] = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    args.results.parent.mkdir(parents=True, exist_ok=True)
    with args.results.open("a") as stream:
        stream.write(json.dumps(record) + "\n")
    print(json.dumps(record), flush=True)


if __name__ == "__main__":
    main()
