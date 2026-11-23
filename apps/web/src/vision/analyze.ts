// Pass 1, the analysis: decoded frames -> EDL. After Windows' Pipeline.analyze (Android's FilterWorker
// pass 1): frames sampled at 10 fps go to the face detector (and gender votes), every second one (5 fps) to
// the NSFW gate; then the gate's hysteresis over the whole timeline, and under wholeFrameBlur every censored
// face span promoted to the whole frame.
//
// Body mode (Android's FilterWorker with bodyBlur): the faces still feed the votes, but the censored tracks
// are people from YOLO11n-seg run through core's BodyTracker (boxes, or silhouettes in precise mode), and a
// face found with no body around it covers the whole frame for that moment. The body model is fetched only
// in this mode.
//
// EDL times are the video track's presentation timestamps in ms, on mediabunny's timeline (the render pass
// reads the same samples), and every rect is upright (display space).
//
// Anything that cannot be analyzed stops the export with an error: a model that will not load, a frame the
// browser cannot decode, a model output that cannot be read, a video with no decodable frame. An empty EDL
// only ever means "analyzed, nothing to censor".

import { VideoSampleSink, type InputVideoTrack, type VideoSample } from 'mediabunny';
import { BodyTracker } from '../core/bodyTracker';
import type { NRect } from '../core/contracts';
import { mergeRanges, promoteFacesToFullFrame, type Edl } from '../core/edl';
import { FaceTracks, SceneCuts } from '../core/faceTracks';
import { MEN, WOMEN, censorFaces, type FilterOps } from '../core/filterOps';
import { fires, intervals } from '../core/nsfwGate';
import { GenderAge } from './genderage';
import { NsfwClassifier, SIDE as NSFW_SIDE } from './nsfw';
import { FramePainter, Slots, analysisSize, type Rgba } from './preprocess';
import { YoloSeg } from './yoloseg';
import { YuNet } from './yunet';

/** Android's pass-1 rate; the NSFW gate reads every second sampled frame (5 fps). */
export const ANALYSIS_FPS = 10;
const NSFW_EVERY = 2;

/**
 * Android's rule: body mode covers the people whose faces are censored, so it needs a face choice, and
 * whole-frame mode already covers more. Bodies asked for with no face choice is refused by `analyze`
 * rather than quietly dropped.
 */
const bodyMode = (ops: Pick<FilterOps, 'censorWho' | 'bodyBlur' | 'wholeFrameBlur'>): boolean =>
  ops.bodyBlur && censorFaces(ops) && !ops.wholeFrameBlur;

/** Runs the analysis over `track`. `onProgress` gets 0..1; `checkCanceled` throws to stop. */
export async function analyze(
  track: InputVideoTrack,
  ops: FilterOps,
  onProgress: (pct: number) => void,
  checkCanceled: () => void,
): Promise<Edl> {
  if (ops.bodyBlur && !censorFaces(ops)) throw new Error('Body censoring needs Faces set to Everyone, Women or Men.');
  if (!(await track.canDecode())) {
    throw new Error(`This browser cannot decode the video (${track.codec ?? 'unknown codec'}), so it cannot be analyzed.`);
  }
  const faces = censorFaces(ops);
  const body = bodyMode(ops);
  const durationMs = Math.ceil((await track.computeDuration()) * 1000); // whole ms, rounded toward covering

  const released: (() => Promise<void>)[] = [];
  const open = async <T extends { release(): Promise<void> }>(load: () => Promise<T>): Promise<T> => {
    const model = await load();
    released.push(() => model.release());
    return model;
  };
  try {
    // Android's rule: only Women/Men pay for the vote.
    const detector = faces ? await open(() => YuNet.load()) : null;
    const voter = faces && (ops.censorWho === WOMEN || ops.censorWho === MEN) ? await open(() => GenderAge.load()) : null;
    const nsfw = ops.censorNsfw ? await open(() => NsfwClassifier.load()) : null;
    const people = body ? await open(() => YoloSeg.load(ops.bodyPrecise)) : null;
    checkCanceled();

    const tracker = new FaceTracks(ops.censorWho);
    const bodies = new BodyTracker(ops.censorWho);
    const cuts = new SceneCuts();
    const painter = new FramePainter();
    const slots = new Slots(ANALYSIS_FPS);
    const firings: number[] = [];
    let taken = 0;

    // Each yielded sample is closed here, taken or not; leaving the loop early (throw) closes the iterator.
    for await (const sample of new VideoSampleSink(track).samples()) {
      try {
        checkCanceled();
        if (!slots.take(Math.round(sample.timestamp * 1e6))) continue;
        const ptsMs = Math.round(sample.timestamp * 1000);
        if (detector) {
          const frame = analysisFrame(painter, sample);
          const sceneCut = cuts.isCut(frame.data, frame.width, frame.height, 4);
          const boxes = await detector.detect(frame);
          const vote = voter ? (rect: NRect) => voter.vote(frame, rect) : null;
          if (people) {
            const found = await people.detect(frame);
            await bodies.onFrame(
              found.map((p) => p.box),
              boxes,
              frame.width,
              frame.height,
              ptsMs,
              { sceneCut, vote, masks: found.map((p) => p.mask) },
            );
          } else {
            if (sceneCut) tracker.cut(ptsMs);
            await tracker.onFrame(boxes, frame.width, frame.height, ptsMs, vote);
          }
        }
        if (nsfw && taken % NSFW_EVERY === 0) {
          const probs = await nsfw.classify(painter.draw(sample, NSFW_SIDE, NSFW_SIDE, false));
          if (fires(probs, ops.strictness)) firings.push(ptsMs);
        }
        taken++;
        if (durationMs > 0) onProgress(Math.min(1, Math.max(0, ptsMs / durationMs)));
      } finally {
        sample.close();
      }
    }
    checkCanceled();
    if (taken === 0) throw new Error('No frame of the video could be decoded, so it cannot be analyzed.');

    const faceTracks = people ? bodies.finish() : tracker.finish();
    // Android's correctness item 7.2: an unknown duration must not clamp every interval to [0, 0].
    const gate = intervals(firings, durationMs > 0 ? durationMs : Number.MAX_SAFE_INTEGER);
    const censorIntervalsMs = people
      ? mergeRanges([...gate, ...bodies.fallbackIntervals], 0)
      : ops.wholeFrameBlur
        ? promoteFacesToFullFrame(gate, faceTracks)
        : gate;
    onProgress(1);
    return { censorIntervalsMs, faceTracks };
  } finally {
    await Promise.allSettled(released.map((release) => release()));
  }
}

/** The upright face-analysis frame: longer side down to 640, area-averaged. */
function analysisFrame(painter: FramePainter, sample: VideoSample): Rgba {
  const [w, h] = analysisSize(sample.displayWidth, sample.displayHeight);
  return painter.draw(sample, w, h, true);
}
