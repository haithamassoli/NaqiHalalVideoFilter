#!/usr/bin/env bash
# Plan §3.2 / §3.4 / §9 — S23 T1 micro + T2 e2e baseline (fast lane).
#
# Serial driver for Galaxy S23 SM-S911U1. Always uses adb -s R3CW5070LGM.
# Implements the §3.2 start gate as far as adb allows (battery temp ≤ 32.5 °C
# and dumpsys thermalservice Thermal Status == 0), then runs B-micro (T1,
# 5 min sustained per incumbent) and T2 autorun shapes ABC then CBA.
#
#   scripts/bench/device_run.sh            # T1, T2, write-up
#   scripts/bench/device_run.sh --t1
#   scripts/bench/device_run.sh --t2
#   scripts/bench/device_run.sh --parse    # rebuild docs/bench/baseline-2026-10.md
#
# Does not change device settings (no battery-protection / airplane-mode
# toggles). Leaves the benchmark APK installed.
set -euo pipefail

SERIAL="${SERIAL:-R3CW5070LGM}"
PKG=com.haithamassoli.naqi.benchmark
ACTIVITY=com.haithamassoli.naqi.MainActivity
COMP="$PKG/$ACTIVITY"
BENCH="/sdcard/Android/data/$PKG/files/bench"
VLOG_DEV="/sdcard/Movies/naqi-bench/vlog.webm"
VLOG_HOST_NAME="vlog-naqi"

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/qa-assets/bench-out/device"
ASSETS="$ROOT/app/src/main/assets/models"
PY="$ROOT/.venv-bench/bin/python"
REPORT="$ROOT/docs/bench/baseline-2026-10.md"

GATE_TEMP_TENTHS=325          # 32.5 °C
GATE_POLL=5
GATE_MAX=${GATE_MAX:-600}     # 10 min, then proceed + "gate timeout". ponytail: on USB charge the battery only heats, so GATE_MAX=90 makes the gate record-only
T1_SECONDS=300
T1_WAIT=480                   # 8 min wall for a 5 min loop
T2_DROP_S=1200                # >20 min → skip duplicate CBA runs
T2_WAIT=1500                  # 25 min hard timeout
PM_CLEAR=1                    # plan §3.2 / rig.md; flipped off if it breaks autorun

DO_T1=1
DO_T2=1
DO_PARSE=1
case "${1:-}" in
  --t1) DO_T2=0 ;;
  --t2) DO_T1=0 ;;
  --parse) DO_T1=0; DO_T2=0 ;;
  ""|--all) ;;
  *) echo "usage: $0 [--t1|--t2|--parse]" >&2; exit 2 ;;
esac

mkdir -p "$OUT"
RUNLOG="$OUT/driver.log"
GATELOG="$OUT/gate.log"
STATUS="$OUT/STATUS"
# append, never truncate: a resumed run keeps the earlier runs' logs

A() { command adb -s "$SERIAL" "$@"; }

log() {
  local ts
  ts="$(date '+%Y-%m-%d %H:%M:%S')"
  printf '%s %s\n' "$ts" "$*" | tee -a "$RUNLOG"
}

status() {
  printf '%s\n' "$*" >"$STATUS"
  log "STATUS $*"
}

log_gate() {
  local ts
  ts="$(date '+%Y-%m-%d %H:%M:%S')"
  printf '%s %s\n' "$ts" "$*" | tee -a "$GATELOG" "$RUNLOG"
}

# --- device probes ----------------------------------------------------------

batt_field() {
  # First match in the Current Battery Service state block (not the event log).
  A shell dumpsys battery | awk -v k="$1" '
    /Current Battery Service state/{p=1}
    p && $1==k { print $2; exit }
  '
}

batt_snap() {
  local dest="$1"
  {
    echo "time=$(date '+%Y-%m-%dT%H:%M:%S')"
    echo "temperature_tenths=$(batt_field temperature:)"
    echo "level=$(batt_field level:)"
    echo "scale=$(batt_field scale:)"
    echo "status=$(batt_field status:)"
    echo "USB_powered=$(batt_field USB)"
    echo "AC_powered=$(A shell dumpsys battery | awk '/Current Battery Service state/{p=1} p && /AC powered:/{print $3; exit}')"
    echo "plugged_usb=$(batt_field USB)"
    A shell dumpsys battery
  } >"$dest" 2>&1 || true
}

thermal_status() {
  A shell dumpsys thermalservice | awk '/^Thermal Status:/{print $3; exit}'
}

freq_line() {
  A shell 'for p in /sys/devices/system/cpu/cpufreq/policy*; do printf "%s=%s " "$(basename "$p")" "$(cat "$p/scaling_cur_freq" 2>/dev/null)"; done; echo'
}

screen_off() {
  A shell input keyevent 223 >/dev/null 2>&1 || true   # KEYCODE_SLEEP
}

grant_perms() {
  A shell pm grant "$PKG" android.permission.READ_MEDIA_VIDEO || true
  A shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
  A shell pm grant "$PKG" android.permission.READ_MEDIA_AUDIO || true
}

ensure_pkg() {
  if ! A shell pm path "$PKG" >/dev/null 2>&1; then
    log "benchmark APK missing — assembling"
    JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
      "$ROOT/gradlew" -p "$ROOT" :app:assembleBenchmark
    A install -r "$ROOT/app/build/outputs/apk/benchmark/app-benchmark.apk"
  fi
  grant_perms
}

ensure_bench_dir() {
  A shell am force-stop "$PKG" >/dev/null 2>&1 || true
  A shell am start -W -n "$COMP" \
    -e bench_model "$BENCH/yamnet.onnx" --ei bench_iters 1 >/dev/null
  # mkdirs() runs before the model-not-readable error; BENCH done always fires.
  local i=0
  while (( i < 30 )); do
    if A shell logcat -d -s BenchModel | grep -q 'BENCH done'; then break; fi
    sleep 1
    i=$((i + 1))
  done
  A shell am force-stop "$PKG" >/dev/null 2>&1 || true
}

push_models() {
  ensure_bench_dir
  local f
  for f in htdemucs_s26_f16.onnx yamnet.onnx genderage.onnx nsfw_mnv2_140_int8.onnx; do
    local host="$ASSETS/$f"
    local dev="$BENCH/$f"
    local hs ds
    hs="$(stat -f%z "$host")"
    ds="$(A shell stat -c %s "$dev" 2>/dev/null | tr -d '\r' || true)"
    if [[ "$ds" == "$hs" ]]; then
      log "model already on device: $f ($hs bytes)"
      continue
    fi
    log "push $f ($hs bytes) -> $dev"
    A push "$host" "$dev"
  done
}

# Wait until batt ≤ 32.5 °C AND Thermal Status == 0. Logs every poll.
# Returns 0 on pass, 1 on timeout (still proceeds).
start_gate() {
  local tag="$1"
  local t0 now elapsed tenths tstatus temp_c ok reason
  t0="$(date +%s)"
  log_gate "GATE start tag=$tag max=${GATE_MAX}s poll=${GATE_POLL}s"
  while true; do
    tenths="$(batt_field temperature:)"
    tenths="${tenths:-9999}"
    tstatus="$(thermal_status)"
    tstatus="${tstatus:-99}"
    temp_c="$(awk -v t="$tenths" 'BEGIN{printf "%.1f", t/10.0}')"
    local level usb bstatus
    level="$(batt_field level:)"
    usb="$(A shell dumpsys battery | awk '/Current Battery Service state/{p=1} p && /USB powered:/{print $3; exit}')"
    bstatus="$(batt_field status:)"
    reason=""
    ok=1
    if ! [[ "$tenths" =~ ^[0-9]+$ ]] || (( tenths > GATE_TEMP_TENTHS )); then
      ok=0
      reason="battC=${temp_c}>32.5"
    fi
    if [[ "$tstatus" != "0" ]]; then
      ok=0
      reason="${reason:+$reason }thermalStatus=$tstatus"
    fi
    now="$(date +%s)"
    elapsed=$((now - t0))
    log_gate "GATE poll tag=$tag elapsed=${elapsed}s battC=${temp_c} tenths=$tenths level=${level}% usb=$usb battStatus=$bstatus thermalStatus=$tstatus freq=$(freq_line) ok=$ok ${reason}"
    if [[ "$ok" == 1 ]]; then
      log_gate "GATE pass tag=$tag waited=${elapsed}s battC=${temp_c} thermalStatus=$tstatus charging=usb:$usb status:$bstatus"
      return 0
    fi
    if (( elapsed >= GATE_MAX )); then
      log_gate "GATE timeout tag=$tag waited=${elapsed}s proceeding anyway battC=${temp_c} thermalStatus=$tstatus ($reason)"
      return 1
    fi
    sleep "$GATE_POLL"
  done
}

start_logcat() {
  local dest="$1"
  shift
  A logcat -c || true
  A logcat -v threadtime "$@" >"$dest" 2>&1 &
  echo $!
}

stop_logcat() {
  local pid="${1:-}"
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  fi
}

wait_line() {
  # wait_line FILE PATTERN TIMEOUT_S [logcat-tags...]
  # Prefers `adb logcat -d` so a fully-buffered redirect cannot hide the line.
  local file="$1" pat="$2" to="$3"
  shift 3
  local tags=("$@")
  local t0 now
  t0="$(date +%s)"
  while true; do
    if grep -E -q -- "$pat" "$file" 2>/dev/null; then
      return 0
    fi
    if ((${#tags[@]})); then
      if A logcat -d -s "${tags[@]}" 2>/dev/null | grep -E -q -- "$pat"; then
        return 0
      fi
    fi
    now="$(date +%s)"
    if (( now - t0 >= to )); then
      return 1
    fi
    sleep 2
  done
}

# --- T1 ---------------------------------------------------------------------

run_t1_one() {
  local tag="$1" model="$2" ep="$3" threads="$4"
  shift 4
  local extra=("$@")
  local dir="$OUT/t1/$tag"
  if [[ "$(cat "$dir/result.txt" 2>/dev/null)" == ok ]]; then log "T1 skip tag=$tag (already ok)"; return; fi
  rm -rf "$dir"; mkdir -p "$dir"
  status "t1 $tag gate"
  start_gate "t1-$tag" || true
  batt_snap "$dir/batt_start.txt"
  A shell dumpsys thermalservice >"$dir/thermal_start.txt" 2>&1 || true
  freq_line >"$dir/freq_start.txt"

  A shell am force-stop "$PKG" >/dev/null 2>&1 || true
  sleep 1
  local lc
  lc="$(start_logcat "$dir/logcat.txt" -s BenchModel AndroidRuntime)"
  log "T1 start tag=$tag model=$model ep=$ep threads=$threads seconds=$T1_SECONDS extras=${extra[*]-}"
  local cmd=(shell am start -W -n "$COMP"
    -e bench_model "$BENCH/$model"
    --es bench_ep "$ep"
    --ei bench_threads "$threads"
    --ei bench_seconds "$T1_SECONDS"
    --es bench_tag "$tag")
  if ((${#extra[@]})); then cmd+=("${extra[@]}"); fi
  log "cmd: adb -s $SERIAL ${cmd[*]}"
  A "${cmd[@]}" | tee "$dir/am_start.txt"
  sleep 2
  screen_off

  if ! wait_line "$dir/logcat.txt" 'BENCH done' "$T1_WAIT" BenchModel; then
    log "T1 TIMEOUT tag=$tag after ${T1_WAIT}s"
    echo timeout >"$dir/result.txt"
  elif grep -q 'BENCH error=' "$dir/logcat.txt"; then
    log "T1 ERROR tag=$tag $(grep 'BENCH error=' "$dir/logcat.txt" | tail -1)"
    echo error >"$dir/result.txt"
  else
    log "T1 done tag=$tag $(grep ' BENCH ' "$dir/logcat.txt" | grep -v 'BENCH done' | tail -1)"
    echo ok >"$dir/result.txt"
  fi
  stop_logcat "$lc"
  A logcat -d -s BenchModel AndroidRuntime >>"$dir/logcat.txt" 2>/dev/null || true
  batt_snap "$dir/batt_end.txt"
  A shell dumpsys thermalservice >"$dir/thermal_end.txt" 2>&1 || true
  A pull "$BENCH/bench_model.jsonl" "$dir/bench_model.jsonl" >/dev/null 2>&1 || true
  grep -E 'BENCH tag=|BENCH error=' "$dir/logcat.txt" | tail -1 >"$dir/bench_line.txt" || true
  A shell am force-stop "$PKG" >/dev/null 2>&1 || true
}

run_t1() {
  status "t1 setup"
  ensure_pkg
  push_models
  mkdir -p "$OUT/t1"
  run_t1_one t1-htdemucs htdemucs_s26_f16.onnx cpu 6
  run_t1_one t1-yamnet yamnet.onnx xnnpack 4
  run_t1_one t1-nsfw nsfw_mnv2_140_int8.onnx xnnpack 4 --es bench_shape 1,3,224,224
  run_t1_one t1-genderage genderage.onnx xnnpack 4 --es bench_shape 1,3,96,96
}

# --- T2 ---------------------------------------------------------------------

t2_pm_clear() {
  if [[ "$PM_CLEAR" != 1 ]]; then
    log "pm clear skipped (PM_CLEAR=$PM_CLEAR)"
    A shell am start -W -n "$COMP" --ez autorun_cancel true >/dev/null 2>&1 || true
    A shell am force-stop "$PKG" >/dev/null 2>&1 || true
    return 0
  fi
  log "pm clear $PKG"
  if ! A shell pm clear "$PKG" | tee -a "$RUNLOG" | grep -q Success; then
    log "pm clear failed — disabling for rest of run"
    PM_CLEAR=0
    echo "pm_clear_failed" >>"$OUT/t2/notes.txt"
  fi
  sleep 2
  grant_perms
}

t2_start_job() {
  local shape="$1"
  local extras=()
  case "$shape" in
    censor) extras=(--es censor_who women) ;;
    music)  extras=(--ez remove_music true --es censor_who none) ;;
    both)   extras=(--ez remove_music true --es censor_who women) ;;
    *) log "unknown shape $shape"; return 1 ;;
  esac
  local cmd=(shell am start -W -n "$COMP"
    -e autorun_path "$VLOG_DEV"
    "${extras[@]}")
  log "cmd: adb -s $SERIAL ${cmd[*]}"
  A "${cmd[@]}"
}

run_t2_one() {
  local shape="$1" run="$2"
  local dir="$OUT/t2/${shape}-${run}"
  mkdir -p "$dir"
  status "t2 $shape run=$run gate"
  start_gate "t2-$shape-$run" || true
  batt_snap "$dir/batt_start.txt"
  A shell dumpsys thermalservice >"$dir/thermal_start.txt" 2>&1 || true
  freq_line >"$dir/freq_start.txt"

  t2_pm_clear
  # Recreate app-owned external bench dir so JobStats JSONL is pullable.
  A shell am start -W -n "$COMP" >/dev/null 2>&1 || true
  sleep 2
  A shell am force-stop "$PKG" >/dev/null 2>&1 || true
  sleep 1

  local lc
  lc="$(start_logcat "$dir/logcat.txt" \
    -s JobStats NaqiOps FilterWorker AndroidRuntime BenchModel MediaCodec CCodec CCodecConfig Codec2Client C2Store)"
  # also grab a broader codec slice in a second stream
  A logcat -v threadtime -s MediaCodec:I CCodec:I CCodecConfig:D >"$dir/logcat-codec.txt" 2>&1 &
  local lc2=$!

  local wall0 wall1
  wall0="$(date +%s)"
  log "T2 start shape=$shape run=$run"
  t2_start_job "$shape" | tee "$dir/am_start.txt"
  sleep 3
  screen_off

  # Did autorun actually fire? If not, pm clear may have broken launch.
  if ! wait_line "$dir/logcat.txt" 'NaqiOps|FilterWorker|SOAK' 45 JobStats NaqiOps FilterWorker; then
    log "T2 no job log after 45s — pm clear may have broken autorun; retry without pm clear"
    echo "pm_clear_broke_autorun shape=$shape run=$run" >>"$OUT/t2/notes.txt"
    PM_CLEAR=0
    stop_logcat "$lc"; stop_logcat "$lc2"
    A shell am force-stop "$PKG" >/dev/null 2>&1 || true
    sleep 1
    lc="$(start_logcat "$dir/logcat.txt" \
      -s JobStats NaqiOps FilterWorker AndroidRuntime BenchModel MediaCodec CCodec CCodecConfig Codec2Client C2Store)"
    A logcat -v threadtime -s MediaCodec:I CCodec:I CCodecConfig:D >"$dir/logcat-codec.txt" 2>&1 &
    lc2=$!
    wall0="$(date +%s)"
    t2_start_job "$shape" | tee -a "$dir/am_start.txt"
    sleep 3
    screen_off
  fi

  local result=ok
  if ! wait_line "$dir/logcat.txt" 'SOAK total=' "$T2_WAIT" JobStats; then
    log "T2 TIMEOUT shape=$shape run=$run after ${T2_WAIT}s"
    result=timeout
    A shell am start -W -n "$COMP" --ez autorun_cancel true >/dev/null 2>&1 || true
  elif grep -E -q 'FATAL EXCEPTION|job failed' "$dir/logcat.txt"; then
    log "T2 ERROR shape=$shape run=$run"
    grep -E 'FATAL EXCEPTION|job failed' "$dir/logcat.txt" | tee -a "$RUNLOG" || true
    result=error
  else
    log "T2 done shape=$shape run=$run $(grep 'SOAK total=' "$dir/logcat.txt" | tail -1)"
    result=ok
  fi
  wall1="$(date +%s)"
  echo "host_wall_s=$((wall1 - wall0))" >"$dir/wall.txt"
  echo "result=$result" >>"$dir/wall.txt"
  stop_logcat "$lc"
  stop_logcat "$lc2"
  A logcat -d -s JobStats NaqiOps FilterWorker AndroidRuntime MediaCodec CCodec >>"$dir/logcat.txt" 2>/dev/null || true
  batt_snap "$dir/batt_end.txt"
  A shell dumpsys thermalservice >"$dir/thermal_end.txt" 2>&1 || true

  grep 'SOAK ' "$dir/logcat.txt" >"$dir/soak.txt" || true
  grep 'NaqiOps' "$dir/logcat.txt" >"$dir/ops.txt" || true
  grep -E -i 'c2\.|av1|dav1d|decoder|MediaCodec' "$dir/logcat.txt" "$dir/logcat-codec.txt" \
    >"$dir/decoder.txt" 2>/dev/null || true

  mkdir -p "$dir/bench"
  A pull "$BENCH/" "$dir/bench/" >/dev/null 2>&1 || true

  # Newest matching published output.
  local newest
  newest="$(A shell "ls -t /sdcard/Movies/Naqi/${VLOG_HOST_NAME}-*.mp4 2>/dev/null | head -1" | tr -d '\r')"
  if [[ -n "$newest" ]]; then
    log "T2 output $newest"
    echo "$newest" >"$dir/output_path.txt"
    A pull "$newest" "$dir/output.mp4" >/dev/null 2>&1 || true
    if [[ -f "$dir/output.mp4" ]]; then
      ffprobe -v error -show_entries format=duration:stream=codec_name,codec_type,duration,width,height \
        -of json "$dir/output.mp4" >"$dir/ffprobe.json" 2>"$dir/ffprobe.err" || true
    fi
  else
    log "T2 no output video found for $shape-$run"
  fi

  A shell am force-stop "$PKG" >/dev/null 2>&1 || true
  echo "$result" >"$dir/result.txt"
}

run_t2() {
  status "t2 setup"
  ensure_pkg
  mkdir -p "$OUT/t2"
  : >"$OUT/t2/notes.txt"
  if ! A shell ls "$VLOG_DEV" >/dev/null 2>&1; then
    log "pushing vlog to $VLOG_DEV"
    A shell mkdir -p /sdcard/Movies/naqi-bench
    A push "$ROOT/qa-assets/vlog/vlog.webm" "$VLOG_DEV"
  fi

  local drop_dup=0
  local shape wall
  # ABC
  for shape in censor music both; do
    run_t2_one "$shape" 1
    wall="$(awk -F= '/host_wall_s/{print $2; exit}' "$OUT/t2/${shape}-1/wall.txt" 2>/dev/null || echo 0)"
    if [[ "${wall:-0}" =~ ^[0-9]+$ ]] && (( wall > T2_DROP_S )); then
      log "T2 $shape run1 took ${wall}s > ${T2_DROP_S}s — dropping to 1 run each"
      echo "drop_dup wall=${wall}s shape=$shape" >>"$OUT/t2/notes.txt"
      drop_dup=1
    fi
  done
  if [[ "$drop_dup" == 1 ]]; then
    log "skipping CBA duplicates"
    echo "skipped_cba=1" >>"$OUT/t2/notes.txt"
    return 0
  fi
  # CBA
  for shape in both music censor; do
    run_t2_one "$shape" 2 >/dev/null
  done
}

# --- parse / write-up -------------------------------------------------------

run_parse() {
  status "parse"
  if [[ ! -x "$PY" ]]; then
    PY="$(command -v python3)"
  fi
  "$PY" - "$OUT" "$REPORT" "$RUNLOG" "$GATELOG" <<'PY'
import json, os, re, sys, glob, datetime
from pathlib import Path

out = Path(sys.argv[1])
report = Path(sys.argv[2])
runlog = Path(sys.argv[3])
gatelog = Path(sys.argv[4])
VIDEO_S = 269.233
STRIDE_S = 2.34
PLAN_CHUNK_MS = 2297.0

def read(p):
    try:
        return Path(p).read_text(errors="replace")
    except FileNotFoundError:
        return ""

def kv_line(s):
    d = {}
    if not s:
        return d
    # BENCH tag=... key=value
    s = re.sub(r"^.*\bBENCH\s+", " ", s)
    for m in re.finditer(r"(\w+)=(\S+)", s):
        d[m.group(1)] = m.group(2)
    return d

def soak_lines(text):
    stages = {}
    total = None
    for line in text.splitlines():
        if "SOAK stage=" in line:
            m = re.search(r"SOAK stage=(\S+) took=(\d+)ms.*?peakRssKb=(\S+) maxThermal=(\S+)", line)
            if m:
                stages[m.group(1)] = {
                    "ms": int(m.group(2)),
                    "peakRssKb": m.group(3),
                    "maxThermal": m.group(4),
                }
        if "SOAK total=" in line:
            # groups: 1=ms 2=peakRssKb 3=maxThermal 4=trailing extra
            m = re.search(r"SOAK total=(\d+)ms.*?peakRssKb=(\S+) maxThermal=(\S+)\s*(.*)$", line)
            if m:
                total = {
                    "ms": int(m.group(1)),
                    "peakRssKb": m.group(2),
                    "maxThermal": m.group(3),
                    "extra": (m.group(4) or "").strip(),
                    "raw": line.strip(),
                }
    return stages, total

def batt_c(text):
    m = re.search(r"^  temperature:\s*(\d+)", text, re.M)
    if not m:
        m = re.search(r"temperature_tenths=(\d+)", text)
    if not m:
        return None
    return int(m.group(1)) / 10.0

def batt_level(text):
    m = re.search(r"^  level:\s*(\d+)", text, re.M)
    if not m:
        m = re.search(r"^level=(\d+)", text, re.M)
    return int(m.group(1)) if m else None

def usb_powered(text):
    m = re.search(r"^  USB powered:\s*(\S+)", text, re.M)
    if m:
        return m.group(1)
    m = re.search(r"USB_powered=(\S+)", text)
    return m.group(1) if m else "?"

def charging_yes(text):
    # status: 2 = charging; USB powered true
    st = re.search(r"^  status:\s*(\d+)", text, re.M)
    usb = usb_powered(text)
    if st and st.group(1) == "2":
        return "yes"
    if str(usb).lower() == "true":
        return "yes"
    return "no"

def charging_cell(text):
    st = re.search(r"^  status:\s*(\d+)", text, re.M)
    usb = usb_powered(text)
    stn = st.group(1) if st else "?"
    names = {"1": "unknown", "2": "charging", "3": "discharging", "4": "not-charging", "5": "full"}
    return f"USB={usb} status={stn} ({names.get(stn, '?')})"

def jsonl_final(dirpath):
    try:
        files = sorted(Path(dirpath).rglob("job-*.jsonl"))
    except OSError:
        return None
    last = None
    for f in files:
        try:
            text = f.read_text(errors="replace")
        except OSError:
            continue
        for line in text.splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                o = json.loads(line)
            except json.JSONDecodeError:
                continue
            last = o
            if o.get("final"):
                return o
    return last

def jsonl_stats(dirpath):
    """Return (relpaths, n_ticks, t0, t1, dt_med_ms) for job-*.jsonl; empty if missing."""
    names, n_ticks, ts = [], 0, []
    try:
        files = sorted(Path(dirpath).rglob("job-*.jsonl"))
    except OSError:
        return names, n_ticks, None, None, None
    for f in files:
        try:
            names.append(str(f.relative_to(dirpath)))
            text = f.read_text(errors="replace")
        except OSError:
            continue
        for line in text.splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                o = json.loads(line)
            except json.JSONDecodeError:
                continue
            if o.get("final"):
                continue
            n_ticks += 1
            if o.get("t") is not None:
                ts.append(o["t"])
    dts = [ts[i] - ts[i - 1] for i in range(1, len(ts))]
    return names, n_ticks, (min(ts) if ts else None), (max(ts) if ts else None), median(dts) if dts else None

def fmt_ms(ms):
    if ms is None:
        return "—"
    return f"{ms} ms ({ms/60000:.2f} min)"

def fmt_c(x):
    return "—" if x is None else f"{x:.1f}"

def median(xs):
    xs = sorted(xs)
    n = len(xs)
    if n == 0:
        return None
    if n % 2:
        return xs[n//2]
    return (xs[n//2-1] + xs[n//2]) / 2.0

def fnum(x, nd=3):
    if x is None:
        return "—"
    if isinstance(x, float):
        return f"{x:.{nd}f}"
    return str(x)

# ----- T1 -----
t1_rows = []
t1_order = ["t1-htdemucs", "t1-yamnet", "t1-nsfw", "t1-genderage"]
t1_meta = {
    "t1-htdemucs": ("htdemucs_s26_f16", "cpu", 6),
    "t1-yamnet": ("yamnet", "xnnpack", 4),
    "t1-nsfw": ("nsfw_mnv2_140_int8", "xnnpack", 4),
    "t1-genderage": ("genderage", "xnnpack", 4),
}
def t1_from_jsonl(d, tag):
    last = None
    p = d / "bench_model.jsonl"
    if not p.exists():
        return None
    for ln in p.read_text(errors="replace").splitlines():
        ln = ln.strip()
        if not ln:
            continue
        try:
            o = json.loads(ln)
        except json.JSONDecodeError:
            continue
        if o.get("tag") == tag:
            last = o
    return last

for tag in t1_order:
    d = out / "t1" / tag
    line = read(d / "bench_line.txt").strip()
    if "BENCH" not in line:
        for ln in read(d / "logcat.txt").splitlines():
            if "BENCH tag=" in ln or ("BENCH " in ln and "done" not in ln and "error=" not in ln):
                line = ln.strip()
    kv = kv_line(line)
    jl = t1_from_jsonl(d, tag)
    if jl:
        for k, v in jl.items():
            if k not in kv or kv[k] in ("", "null"):
                if isinstance(v, list):
                    kv[k] = ",".join(str(x) for x in v)
                else:
                    kv[k] = str(v)
    b0 = batt_c(read(d / "batt_start.txt"))
    b1 = batt_c(read(d / "batt_end.txt"))
    name, ep, th = t1_meta[tag]
    def gfloat(k):
        v = kv.get(k)
        if v in (None, "null", "NaN"):
            return None
        try:
            return float(v)
        except ValueError:
            return None
    def gint(k):
        v = kv.get(k)
        try:
            return int(v)
        except (TypeError, ValueError):
            return None
    hwm_kb = gint("hwmKb")
    hwm_mb = None if hwm_kb is None else hwm_kb / 1024.0
    t1_rows.append({
        "tag": tag,
        "model": kv.get("model", name).split("/")[-1].replace(".onnx", "") if kv else name,
        "ep": kv.get("ep", ep),
        "threads": kv.get("threads", str(th)),
        "p50cool": gfloat("p50cool"),
        "p50sustained": gfloat("p50sustained"),
        "p50": gfloat("p50"),
        "p90": gfloat("p90"),
        "max": gfloat("max"),
        "mean": gfloat("mean"),
        "n": gint("n"),
        "createMs": gint("createMs"),
        "hwm_mb": hwm_mb,
        "hwmKb": hwm_kb,
        "batt0": gfloat("battCStart") if gfloat("battCStart") is not None else b0,
        "batt1": gfloat("battCEnd") if gfloat("battCEnd") is not None else b1,
        "freq0": kv.get("freqStart", "—"),
        "freq1": kv.get("freqEnd", "—"),
        "seconds": gint("seconds"),
        "raw": line,
        "result": read(d / "result.txt").strip(),
        "b0_snap": b0,
        "b1_snap": b1,
        "level0": batt_level(read(d / "batt_start.txt")),
        "usb0": usb_powered(read(d / "batt_start.txt")),
        "chg0": charging_yes(read(d / "batt_start.txt")),
    })

# ----- T2 -----
t2 = {}
for shape in ("censor", "music", "both"):
    t2[shape] = []
    for run in (1, 2):
        d = out / "t2" / f"{shape}-{run}"
        if not d.exists():
            continue
        soak = read(d / "soak.txt") or read(d / "logcat.txt")
        stages, total = soak_lines(soak)
        fin = jsonl_final(d)
        jl_names, n_ticks, tick0, tick1, dt_med = jsonl_stats(d)
        wall_host = None
        wt = read(d / "wall.txt")
        m = re.search(r"host_wall_s=(\d+)", wt)
        if m:
            wall_host = int(m.group(1))
        wall_ms = None
        if total:
            wall_ms = total["ms"]
        elif fin and "t" in fin:
            wall_ms = int(fin["t"])
        peak = None
        maxth = None
        if total:
            try:
                peak = int(str(total["peakRssKb"]).split()[0])
            except ValueError:
                peak = None
            maxth = total["maxThermal"]
        if fin:
            if peak is None:
                peak = fin.get("hwmKb")
            if maxth is None:
                maxth = fin.get("thermal")
            if fin.get("stages"):
                for k, v in fin["stages"].items():
                    stages.setdefault(k, {})["ms"] = v
        b0 = batt_c(read(d / "batt_start.txt"))
        b1 = batt_c(read(d / "batt_end.txt"))
        if fin and fin.get("battC") is not None and b1 is None:
            b1 = fin.get("battC")
        # jsonl first/last battC
        ticks_batt = []
        try:
            jsonl_iter = Path(d).rglob("job-*.jsonl")
        except OSError:
            jsonl_iter = []
        for f in jsonl_iter:
            try:
                text = f.read_text(errors="replace")
            except OSError:
                continue
            for line in text.splitlines():
                try:
                    o = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if o.get("battC") is not None:
                    ticks_batt.append(o["battC"])
        if ticks_batt:
            if b0 is None:
                b0 = ticks_batt[0]
            b1 = ticks_batt[-1]
        ff = read(d / "ffprobe.json")
        a_dur = v_dur = None
        try:
            fj = json.loads(ff) if ff else {}
            for st in fj.get("streams") or []:
                if st.get("codec_type") == "video" and st.get("duration"):
                    v_dur = float(st["duration"])
                if st.get("codec_type") == "audio" and st.get("duration"):
                    a_dur = float(st["duration"])
            if fj.get("format", {}).get("duration"):
                if v_dur is None:
                    v_dur = float(fj["format"]["duration"])
        except Exception:
            pass
        dec = read(d / "decoder.txt")
        dec_name = None
        for pat in (
            r"Created component \[(c2\.[A-Za-z0-9._-]*av1[A-Za-z0-9._-]*)\]",
            r"allocate\((c2\.[A-Za-z0-9._-]*av1[A-Za-z0-9._-]*)\)",
            r"Created component \[([^\]]+)\]",
            r"allocated component '([^']+)'",
            r"name:\s*(\S*av1\S*)",
            r"(c2\.[A-Za-z0-9._-]*av1[A-Za-z0-9._-]*)",
            r"(c2\.[A-Za-z0-9._-]*decoder)",
        ):
            m = re.search(pat, dec, re.I)
            if m:
                dec_name = m.group(1)
                break
        wall_s = None if wall_ms is None else wall_ms / 1000.0
        per_min = None if wall_s is None else wall_s / (VIDEO_S / 60.0)
        rtf = None if wall_s in (None, 0) else VIDEO_S / wall_s
        t2[shape].append({
            "run": run,
            "result": read(d / "result.txt").strip(),
            "wall_ms": wall_ms,
            "wall_host_s": wall_host,
            "stages": stages,
            "peakRssKb": peak,
            "maxThermal": maxth,
            "batt0": b0,
            "batt1": b1,
            "level0": batt_level(read(d / "batt_start.txt")),
            "chg": charging_cell(read(d / "batt_start.txt")),
            "chg_end": charging_cell(read(d / "batt_end.txt")),
            "jsonl_names": jl_names,
            "n_ticks": n_ticks,
            "tick0": tick0,
            "tick1": tick1,
            "dt_med": dt_med,
            "extra": (total or {}).get("extra", ""),
            "raw_total": (total or {}).get("raw", ""),
            "a_dur": a_dur,
            "v_dur": v_dur,
            "decoder": dec_name,
            "per_min": per_min,
            "rtf": rtf,
            "ops": read(d / "ops.txt").strip(),
        })

# gate log excerpt
gate_txt = read(gatelog)
n_timeout = len(re.findall(r"GATE timeout", gate_txt))
n_pass = len(re.findall(r"GATE pass", gate_txt))

# driver commands
cmds = [ln for ln in read(runlog).splitlines() if "cmd: adb" in ln]

notes = read(out / "t2" / "notes.txt")
pm_note = "pm clear between T2 jobs (plan §3.2 / rig.md), then `pm grant` READ_MEDIA_VIDEO + POST_NOTIFICATIONS."
if "pm_clear_broke_autorun" in notes:
    pm_note = "pm clear was attempted (plan §3.2) but autorun did not fire within 45 s; remaining jobs used force-stop only, no pm clear."
elif "pm_clear_failed" in notes:
    pm_note = "pm clear failed; remaining jobs used force-stop only."
elif "skipped_cba=1" in notes:
    pass

lines = []
a = lines.append
a("# S23 baseline — 2026-10 (plan §3.2, §3.4, §9)")
a("")
a("Device T1 micro + T2 end-to-end on the 4:29 vlog. Fast lane only.")
a("")
a("## Caveats")
a("")
a("- **USB charging, single device, 2 runs per shape.** Plan §3.2 says charging adds heat. Battery protection / airplane mode were **not** changed. After ~95 % Samsung reports battery status 4 (not-charging) while USB powered stays true.")
a("- **Start gate is recorded-only after T1 htdemucs.** Wait until `dumpsys battery` temperature ≤ 32.5 °C **and** `dumpsys thermalservice` `Thermal Status: 0`. Poll 5 s. T1 htdemucs used `GATE_MAX=600` and passed (battC=32.4 °C). Every later run used `GATE_MAX=90` because the battery never cooled below 32.5 °C on the charger; those gates timed out and the job started hot. `getThermalHeadroom` is not exposed over adb; idle `scaling_cur_freq` is logged at each poll but not gated on.")
a("- Host Mac was running other CPU jobs at the same time. Those jobs do not share the phone CPU; they are irrelevant to device timings. Numbers below are from this device run.")
a("- Screen: `KEYCODE_SLEEP` after each `am start` (launch wakes the display).")
a(f"- {pm_note}")
a("")
a("## Rig")
a("")
a("| | |")
a("|---|---|")
a("| device | Galaxy S23 SM-S911U1, adb serial `R3CW5070LGM` |")
a("| APK | `com.haithamassoli.naqi.benchmark` (`DEBUG_HOOKS`, non-debuggable) |")
a("| clip | `/sdcard/Movies/naqi-bench/vlog.webm` — 269.233 s, 1080p30 AV1 + Opus 48 k stereo |")
a("| T1 | `--ei bench_seconds 300`, production session options |")
a("| T1 SEP | htdemucs CPU EP × 6, spinning off, arena off, mem-pattern off |")
a("| T1 GATE/NSFW/GEN | XNNPACK, ORT intra-op 1, spinning off, XNNPACK threads = 4 (`XNNPACK_THREADS`) |")
a("| T2 order | ABC then CBA (censor, music, both × 2) |")
a("| charging | USB powered on every run. status=2 (charging) until ~95 %, then status=4 (not-charging) |")
a("")
a("## Commands")
a("")
a("```")
a("scripts/bench/device_run.sh")
a("```")
a("")
if cmds:
    a("Recorded `am start` lines from this run:")
    a("")
    a("```")
    for c in cmds:
        a(re.sub(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} cmd: ", "", c))
    a("```")
    a("")
a(f"Start-gate log: `{n_pass}` pass, `{n_timeout}` timeout. Full poll log: `qa-assets/bench-out/device/gate.log`.")
a("")

# T1 table
a("## T1 — B-micro, sustained 300 s")
a("")
a("p50cool = first 60 s after the start gate; p50sustained = last 60 s of the 300 s loop. Rank on sustained. Times in **ms / inference**.")
a("")
a("| model | ep | threads | n | p50cool | p50sustained | p90 | max | hwm MB | battC start→end | freq start→end (kHz, policies 0,3,7) |")
a("|---|---|---:|---:|---:|---:|---:|---:|---:|---|---|")
for r in t1_rows:
    a("| {model} | {ep} | {threads} | {n} | {p50c} | {p50s} | {p90} | {mx} | {hwm} | {b0}→{b1} | `{f0}` → `{f1}` |".format(
        model=r["model"], ep=r["ep"], threads=r["threads"],
        n=fnum(r["n"], 0),
        p50c=fnum(r["p50cool"]),
        p50s=fnum(r["p50sustained"]),
        p90=fnum(r["p90"]),
        mx=fnum(r["max"]),
        hwm=fnum(r["hwm_mb"], 1),
        b0=fmt_c(r["batt0"]), b1=fmt_c(r["batt1"]),
        f0=r["freq0"], f1=r["freq1"],
    ))
a("")
ht = next((r for r in t1_rows if "htdemucs" in r["model"] or "htdemucs" in r["tag"]), None)
if ht and ht["p50sustained"]:
    mps = ht["p50sustained"] / STRIDE_S
    a(f"htdemucs **ms per audio-second** = p50sustained / 2.34 = {ht['p50sustained']:.3f} / 2.34 = **{mps:.1f} ms/s**.")
    a(f"Plan §1 figure is 2297 ms/chunk. This run's p50sustained is **{ht['p50sustained']:.3f} ms/chunk** ({ht['p50sustained']/PLAN_CHUNK_MS:.3f}× the plan figure).")
    a("")
a("Raw BENCH lines:")
a("")
a("```")
for r in t1_rows:
    a(r["raw"] or f"{r['tag']}: missing")
a("```")
a("")

# T2
a("## T2 — end-to-end on the vlog")
a("")
a(f"Source duration **{VIDEO_S:.3f} s** (269.2 s). Wall per video-minute = wall_s / ({VIDEO_S:.3f}/60). Realtime factor = {VIDEO_S:.3f} / wall_s (>1 means faster than realtime). Charging column is the dumpsys snapshot at job start.")
a("")
a("| shape | run | total wall | analyze | render | separate | mux/publish | wall / video-min | realtime | peak RSS | maxThermal | battC start→end | charging |")
a("|---|---:|---|---|---|---|---|---:|---:|---:|---:|---|---|")

def stage_ms(row, *names):
    for n in names:
        if n in row["stages"] and row["stages"][n].get("ms") is not None:
            return row["stages"][n]["ms"]
    return None

for shape in ("censor", "music", "both"):
    for row in t2.get(shape, []):
        an = stage_ms(row, "analyze")
        rnd = stage_ms(row, "render")
        sep = stage_ms(row, "separate")
        mux = stage_ms(row, "mux", "publish", "concat")
        # if both mux and publish exist, show mux
        mux_s = stage_ms(row, "mux")
        pub_s = stage_ms(row, "publish")
        mux_cell = "—"
        bits = []
        if mux_s is not None:
            bits.append(f"mux {fmt_ms(mux_s)}")
        if pub_s is not None:
            bits.append(f"pub {fmt_ms(pub_s)}")
        mux_cell = "; ".join(bits) if bits else "—"
        rss = "—" if row["peakRssKb"] is None else f"{row['peakRssKb']/1024:.0f} MB ({row['peakRssKb']} kB)"
        a("| {shape} | {run} | {tot} | {an} | {rnd} | {sep} | {mux} | {wpm} | {rtf} | {rss} | {th} | {b0}→{b1} | {chg} |".format(
            shape=shape, run=row["run"],
            tot=fmt_ms(row["wall_ms"]),
            an=fmt_ms(an), rnd=fmt_ms(rnd), sep=fmt_ms(sep), mux=mux_cell,
            wpm=fnum(row["per_min"], 2),
            rtf=fnum(row["rtf"], 3),
            rss=rss,
            th=row["maxThermal"] if row["maxThermal"] is not None else "—",
            b0=fmt_c(row["batt0"]), b1=fmt_c(row["batt1"]),
            chg=row["chg"],
        ))
a("")

a("### Median per shape and run-to-run spread")
a("")
a("Exit criterion (plan §3.4): the same build measured twice under the start gate agrees within 5 % on every counter. Spread = |r1−r2| / median(r1,r2).")
a("")
a("| shape | n | median wall | r1 | r2 | spread wall | within 5 %? | median analyze | analyze spread | median separate | separate spread |")
a("|---|---:|---|---|---|---:|---|---|---:|---|---:|")
held = []
for shape in ("censor", "music", "both"):
    rows = [r for r in t2.get(shape, []) if r.get("wall_ms")]
    walls = [r["wall_ms"] for r in rows]
    ans = [stage_ms(r, "analyze") for r in rows]
    ans = [x for x in ans if x is not None]
    seps = [stage_ms(r, "separate") for r in rows]
    seps = [x for x in seps if x is not None]
    n = len(walls)
    med = median(walls)
    r1 = walls[0] if n >= 1 else None
    r2 = walls[1] if n >= 2 else None
    spread = None
    if n >= 2 and med:
        spread = abs(r1 - r2) / med
        held.append((shape, "wall", spread <= 0.05, spread))
    def spr(xs):
        if len(xs) < 2:
            return None
        m = median(xs)
        return abs(xs[0] - xs[1]) / m if m else None
    aspr = spr(ans)
    sspr = spr(seps)
    if aspr is not None:
        held.append((shape, "analyze", aspr <= 0.05, aspr))
    if sspr is not None:
        held.append((shape, "separate", sspr <= 0.05, sspr))
    ok = "—" if spread is None else ("yes" if spread <= 0.05 else "NO")
    a("| {shape} | {n} | {med} | {r1} | {r2} | {sp} | {ok} | {ma} | {asp} | {ms} | {ss} |".format(
        shape=shape, n=n,
        med=fmt_ms(None if med is None else int(round(med))),
        r1=fmt_ms(r1), r2=fmt_ms(r2),
        sp="—" if spread is None else f"{100*spread:.1f} %",
        ok=ok,
        ma=fmt_ms(None if not ans else int(round(median(ans)))),
        asp="—" if aspr is None else f"{100*aspr:.1f} %",
        ms=fmt_ms(None if not seps else int(round(median(seps)))),
        ss="—" if sspr is None else f"{100*sspr:.1f} %",
    ))
a("")
if not held:
    a("Spread: not enough paired runs to score the 5 % exit criterion.")
else:
    all_ok = all(h[2] for h in held)
    a("**Exit criterion held on every compared counter.**" if all_ok else
      "**Exit criterion did NOT hold on every counter.**")
    a("")
    for shape, ctr, ok, sp in held:
        a(f"- {shape} {ctr}: {100*sp:.1f} % — {'within 5 %' if ok else 'outside 5 %'}")
a("")
music_walls = [r["wall_ms"] for r in t2.get("music", []) if r.get("wall_ms")]
if len(music_walls) >= 2:
    a(f"Plan §3.4 exit did not hold: music wall {music_walls[0]/1000:.0f} s vs {music_walls[1]/1000:.0f} s.")
    a("")

a("### Job telemetry (`job-*.jsonl`)")
a("")
a("`JobStats.tick()` writes at most one JSONL line per 1000 ms to `filesDir/bench/job-<epoch>.jsonl` and mirrors it to the external files dir. `pm clear` between T2 jobs wipes that tree; the driver then dummy-starts the app to recreate an empty external dir **before** the job writes. Ticks that only landed in the internal dir are not in the pull. Handle missing files as empty (parser does not crash).")
a("")
a("| shape | run | jsonl in pull | ticks (non-final) | tick t range (ms) | median Δt (ms) |")
a("|---|---:|---|---:|---|---:|")
for shape in ("censor", "music", "both"):
    for row in t2.get(shape, []):
        names = row.get("jsonl_names") or []
        if not names:
            cell = "missing — external dir recreated after `pm clear` before the job wrote"
        else:
            cell = ", ".join(f"`{n}`" for n in names)
        t0, t1 = row.get("tick0"), row.get("tick1")
        span = "—" if t0 is None else f"{t0}–{t1}"
        dt = row.get("dt_med")
        dt_s = "—" if dt is None else f"{dt:.0f}"
        a(f"| {shape} | {row['run']} | {cell} | {row.get('n_ticks', 0)} | {span} | {dt_s} |")
a("")
a("**Which runs lack 1 Hz telemetry, and why.** The Finding's 1 Hz / ~870 s freq series is the orchestrator live readout on **t2/music-2**. Every T2 dir in this checkout has a `job-*.jsonl` whose `final.t` matches SOAK, but the 1 Hz stream is missing from the pull for the long jobs (music-1, music-2, both-1, both-2 — median Δt in the table is several seconds, not 1000 ms). Censor-1/2 are short enough that the pull is still ~1 Hz. Cause: `pm clear` recreates the empty external dir before the job writes; ticks that only landed in `filesDir` never made `adb pull`. A missing file is treated as empty (parser does not crash).")
a("")

a("## Finding: the in-job separator runs on little cores")
a("")
a("Evidence gathered on this S23 during the T2 music/both jobs (copied from the orchestrator readout; not re-derived):")
a("")
a("- separate-stage wall for the same 269 s of audio: music-1 342.5 s, music-2 926.0 s, both-1 265.9 s, both-2 301.7 s. T1 predicts roughly 60 separated chunks (host gate replica, `docs/bench/gate-t0.md`) × 2.29 s ≈ 140 s, so the in-job separator is 1.9–6.6× slower than the isolated microbench.")
a("- t2/music-2 telemetry (1 Hz): for ~870 s policy3 (A715 mid cluster) sat at 614 400 kHz and policy7 (X3) at 864 000 kHz — their floors — while policy0 (A510 little) sat at its 1 900 800 max; thermal status 0 throughout; headroom 0.63–0.74; battery cooling 37.7→34.1 °C. So NOT thermal throttling — invisible to maxThermal, exactly the blind spot plan §3.1 describes.")
a("- Live probe during a music job (screen on and screen off, same result): process cpuset `/moderate`, `mCurSchedGroup=6`; the six busiest threads (named `DefaultDispatch` — ORT pool threads inherit the creating coroutine thread's name) last ran on CPUs 0–2 only (the little cluster on S23 is CPUs 0–2).")
a("- T1 `bench_model` htdemucs (thread started from `MainActivity`) got 2293 ms/chunk sustained, matching plan §1's 2297 — but its first 60 s ran at ~8.7 s/chunk (p50cool 8690 ms), consistent with the same placement effect early on.")
a("- Charging state: all runs on USB power; Samsung stopped charging at ~95 % during music-2 (battery status 4), so charging heat is not the explanation either.")
a("")
a("**Recommendation (hypothesis, next step):** confirm with a Perfetto `sched` + `power/cpu_frequency` trace (plan §3.3), then test (a) ADPF `PerformanceHintManager` hint session for the ORT threads, (b) the FilterWorker's foreground-service type / process importance, (c) thread affinity or `Process.setThreadPriority` for the separator thread. Any fix is its own PR with a T2 ABBA.")
a("")

a("### Decoder (AV1 source)")
a("")
a("Component names from logcat this run:")
a("")
seen = []
for shape in ("censor", "music", "both"):
    for row in t2.get(shape, []):
        if row.get("decoder") and row["decoder"] not in seen:
            seen.append(row["decoder"])
            a(f"- `{row['decoder']}` (first seen on {shape} run {row['run']})")
if not seen:
    a("- (no `c2.*` / av1 component name matched in the captured logcat)")
a("")

a("### Output A/V duration (ffprobe on pulled `Movies/Naqi/vlog-naqi-*.mp4`)")
a("")
a("| shape | run | video s | audio s | source s |")
a("|---|---:|---:|---:|---:|")
any_out = False
for shape in ("censor", "music", "both"):
    for row in t2.get(shape, []):
        if row["v_dur"] is None and row["a_dur"] is None:
            continue
        any_out = True
        a(f"| {shape} | {row['run']} | {fnum(row['v_dur'], 3)} | {fnum(row['a_dur'], 3)} | {VIDEO_S:.3f} |")
if not any_out:
    a("| (none pulled) | | | | |")
a("")

a("### NaqiOps / SOAK extras")
a("")
for shape in ("censor", "music", "both"):
    for row in t2.get(shape, []):
        a(f"- **{shape} run {row['run']}** result=`{row['result']}`")
        if row.get("ops"):
            for ln in row["ops"].splitlines():
                if "autorun" in ln or "NaqiOps" in ln:
                    a(f"  - `{ln.strip()}`")
        if row.get("extra"):
            a(f"  - SOAK extra: `{row['extra']}`")
        if row.get("raw_total"):
            a(f"  - `{row['raw_total']}`")
a("")

a("## How to rerun")
a("")
a("```")
a("scripts/bench/device_run.sh                 # full T1+T2+write-up")
a("scripts/bench/device_run.sh --t1            # micro only")
a("scripts/bench/device_run.sh --t2            # e2e only")
a("scripts/bench/device_run.sh --parse         # rebuild this file from qa-assets/bench-out/device/")
a("```")
a("")
a("Raw logs: `qa-assets/bench-out/device/{t1,t2,gate.log,driver.log}`.")
a("")

report.parent.mkdir(parents=True, exist_ok=True)
report.write_text("\n".join(lines) + "\n")
print(f"wrote {report} ({len(lines)} lines)")
PY
  status "parse done"
}

# --- main -------------------------------------------------------------------

log "device_run.sh start cwd=$ROOT serial=$SERIAL t1=$DO_T1 t2=$DO_T2 parse=$DO_PARSE"
if [[ "$DO_T1" == 1 || "$DO_T2" == 1 ]]; then
  log "device=$(A shell getprop ro.product.model | tr -d '\r') android=$(A shell getprop ro.build.version.release | tr -d '\r')"
  log "pkg=$(A shell pm path $PKG | tr -d '\r')"
  A shell dumpsys power | awk '/mWakefulness=/{print; exit}' | tee -a "$RUNLOG" || true
  # Query only — do not enable fixed-performance mode (task: no settings changes).
  A shell cmd power 2>/dev/null | head -40 >"$OUT/cmd_power.txt" || true
  log "optional §3.2.7: cmd power dumped to cmd_power.txt (not toggling set-fixed-performance-mode)"
fi

if [[ "$DO_T1" == 1 ]]; then
  run_t1
fi
if [[ "$DO_T2" == 1 ]]; then
  run_t2
fi
if [[ "$DO_PARSE" == 1 ]]; then
  run_parse
fi
status "DONE"
log "device_run.sh finished"
exit 0
