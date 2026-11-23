#!/usr/bin/env bash
# Builds the end-to-end test clip: fixtures/fixture.mp4 (gitignored), 15 s, 1280x720, H.264 + AAC 44.1 kHz.
#
#   0–15 s   music: two-chord pad (C major / A minor) with kick and hi-hat
#   0–5 s    music only (the stretch to measure music removal)
#   5–14 s   speech over the music (macOS `say` when present, else a synthetic vowel-like buzz)
#   3–10 s   a face: Sally Ride's 1984 NASA portrait (public domain, NASA photo S84-37256), the same
#            image apps/windows' vision tests use; no face before 3 s or after 10 s
#
# 15 s spans two Spleeter windows (11.89 s each). Usage: ./scripts/make-fixture.sh [out.mp4]
set -euo pipefail
cd "$(dirname "$0")/.."

PORTRAIT=../windows/app/src/test/resources/vision/portrait.jpg
OUT=${1:-fixtures/fixture.mp4}
DUR=15
mkdir -p "$(dirname "$OUT")"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# Speech, 44.1 kHz mono.
if command -v say >/dev/null; then
  say -o "$tmp/speech.aiff" "This is a test of the Naqi web filter. The music should be removed and this voice should stay. \
One, two, three, four, five. The quick brown fox jumps over the lazy dog."
  ffmpeg -v error -y -i "$tmp/speech.aiff" -ar 44100 -ac 1 -t 9 "$tmp/speech.wav"
else
  # 130 Hz buzz with a few harmonics, syllables at 4 Hz: crude, but voice-like for a separator.
  ffmpeg -v error -y -f lavfi -i "aevalsrc='0.3*(sin(2*PI*130*t)+0.6*sin(2*PI*260*t)+0.5*sin(2*PI*700*t)+0.3*sin(2*PI*1200*t))*pow(sin(PI*4*t),2)':s=44100:d=9" \
    "$tmp/speech.wav"
fi

# Music: chords switch every 2 s, kick every 0.5 s, hi-hat every 0.25 s.
CHORD='if(lt(mod(t,4),2), sin(2*PI*261.63*t)+sin(2*PI*329.63*t)+sin(2*PI*392*t)+0.5*sin(2*PI*523.25*t), sin(2*PI*220*t)+sin(2*PI*261.63*t)+sin(2*PI*329.63*t)+0.5*sin(2*PI*440*t))'
KICK='sin(2*PI*(50+80*exp(-30*mod(t,0.5)))*mod(t,0.5))*exp(-12*mod(t,0.5))'
HAT='(random(0)*2-1)*exp(-80*mod(t,0.25))'
ffmpeg -v error -y -f lavfi -i "aevalsrc='0.08*($CHORD)+0.5*$KICK+0.15*$HAT':s=44100:c=stereo:d=$DUR" "$tmp/music.wav"

ffmpeg -v error -y \
  -f lavfi -i "gradients=s=1280x720:d=$DUR:r=30:speed=0.02:c0=0x203040:c1=0x405060" \
  -loop 1 -framerate 30 -i "$PORTRAIT" \
  -i "$tmp/music.wav" -i "$tmp/speech.wav" \
  -filter_complex "\
[1:v]scale=-2:640,format=yuv420p[face];\
[0:v][face]overlay=x=(W-w)/2:y=(H-h)/2:enable='between(t,3,10)':shortest=1,format=yuv420p[v];\
[3:a]adelay=5000:all=1,apad[sp];\
[2:a][sp]amix=inputs=2:duration=first:normalize=0[a]" \
  -map "[v]" -map "[a]" -t $DUR \
  -c:v libx264 -preset veryfast -crf 20 -pix_fmt yuv420p -g 60 \
  -c:a aac -b:a 160k -ar 44100 -ac 2 -movflags +faststart "$OUT"

ffprobe -v error -show_entries stream=codec_name,width,height,sample_rate,channels -show_entries format=duration -of compact "$OUT"
