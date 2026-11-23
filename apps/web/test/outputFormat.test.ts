// The output container: MP4 unless WebM is both possible and what the file will be named.
import { Mp4OutputFormat, WebMOutputFormat, type TransformationMatrix } from 'mediabunny';
import { describe, expect, it } from 'vitest';
import { pickFormat, type FormatChoice } from '../src/outputFormat';

const IDENTITY: TransformationMatrix = [1, 0, 0, 0, 1, 0, 0, 0, 1];
const ROTATED_90: TransformationMatrix = [0, 1, 0, -1, 0, 0, 0, 0, 1];
const base: FormatChoice = { videoCodec: 'vp9', copiedMatrix: IDENTITY, opusVocals: true, inMemory: true };

describe('pickFormat', () => {
  it('writes Opus vocals over upright VP9 or AV1 as WebM in an in-memory download', () => {
    expect(pickFormat(base)).toBeInstanceOf(WebMOutputFormat);
    expect(pickFormat({ ...base, videoCodec: 'av1', copiedMatrix: null })).toBeInstanceOf(WebMOutputFormat);
  });
  it('keeps a file picked in the save dialog (named *.mp4) MP4', () => {
    expect(pickFormat({ ...base, inMemory: false })).toBeInstanceOf(Mp4OutputFormat);
  });
  it('keeps copied rotated video in MP4, whose rotation players honour', () => {
    expect(pickFormat({ ...base, copiedMatrix: ROTATED_90 })).toBeInstanceOf(Mp4OutputFormat);
  });
  it('writes MP4 for AAC audio or a codec WebM cannot hold', () => {
    expect(pickFormat({ ...base, opusVocals: false })).toBeInstanceOf(Mp4OutputFormat);
    expect(pickFormat({ ...base, videoCodec: 'avc' })).toBeInstanceOf(Mp4OutputFormat);
  });
});
