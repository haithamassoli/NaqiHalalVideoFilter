// The output container: MP4, which holds every codec this app writes (Opus included), except one case
// where WebM is the friendlier file: Opus vocals (no AAC encoder) in an in-memory download, which is
// named after the container. A file picked in the save dialog is already named *.mp4, and copied video
// with a rotation or flip keeps it only in MP4 (many players ignore WebM's Projection), so both stay MP4.
import { Mp4OutputFormat, WebMOutputFormat, type OutputFormat, type TransformationMatrix, type VideoCodec } from 'mediabunny';

const IDENTITY: TransformationMatrix = [1, 0, 0, 0, 1, 0, 0, 0, 1];

export interface FormatChoice {
  /** The output video codec (re-encoded or copied), or null without video. */
  videoCodec: VideoCodec | null;
  /** The matrix of copied video; null when there is none or the frames are re-encoded upright. */
  copiedMatrix: TransformationMatrix | null;
  /** The audio is Spleeter's vocals encoded as Opus. */
  opusVocals: boolean;
  /** The output is built in memory and downloaded, not streamed into a file the user named. */
  inMemory: boolean;
}

export function pickFormat({ videoCodec, copiedMatrix, opusVocals, inMemory }: FormatChoice): OutputFormat {
  const webm = new WebMOutputFormat();
  const upright = !copiedMatrix || copiedMatrix.every((v, i) => v === IDENTITY[i]);
  if (inMemory && opusVocals && upright && (!videoCodec || webm.getSupportedVideoCodecs().includes(videoCodec))) {
    return webm;
  }
  return new Mp4OutputFormat({ fastStart: inMemory ? 'in-memory' : false });
}
