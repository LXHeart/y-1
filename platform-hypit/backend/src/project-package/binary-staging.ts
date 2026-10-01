// binary-staging.ts — C107F2-28 (W179 NEW): controlled binary staging.
//
// Project packages carry PNG/MP4/font bytes that must survive export →
// import bit-for-bit. Every transfer here is a STREAM with a bounded
// highWaterMark (≤1MiB per §6.13): peak memory does not grow with bundle
// size, and hashes are computed while bytes move so the journal/workspace
// manifest records the digest of the bytes that actually landed.
import { createHash } from "node:crypto";
import { createReadStream, createWriteStream } from "node:fs";
import { rename, unlink } from "node:fs/promises";
import { pipeline } from "node:stream/promises";
import type { Readable } from "node:stream";

/** 单流缓冲上限（§6.13：≤1MiB；内存缓冲不随包大小增长）。 */
export const STREAM_CHUNK_BYTES = 512 * 1024;

export type StreamDigest = {
  readonly sha256: string;
  readonly sizeBytes: number;
};

/** Stream a file once, hashing as bytes pass through (never a full readIntoMemory). */
export function streamHash(sourcePath: string): Promise<StreamDigest> {
  return new Promise((resolveStream, rejectStream) => {
    const hash = createHash("sha256");
    let size = 0;
    const stream = createReadStream(sourcePath, { highWaterMark: STREAM_CHUNK_BYTES });
    stream.on("data", (chunk: string | Buffer) => {
      hash.update(chunk);
      size += typeof chunk === "string" ? Buffer.byteLength(chunk) : chunk.byteLength;
    });
    stream.on("error", rejectStream);
    stream.on("end", () => resolveStream({ sha256: hash.digest("hex"), sizeBytes: size }));
  });
}

/**
 * Stream-copy source → target through a scratch inode + atomic rename. The
 * digest of the copied bytes is returned (it is the journal's newHash, so a
 * torn copy can never masquerade as the manifest hash). The scratch file is
 * removed on failure — the target path never holds partial bytes.
 */
export async function streamCopy(sourcePath: string, targetPath: string): Promise<StreamDigest> {
  const hash = createHash("sha256");
  let size = 0;
  const scratch = `${targetPath}.staging-${process.pid}-${Date.now().toString(36)}`;
  try {
    await pipeline(
      createReadStream(sourcePath, { highWaterMark: STREAM_CHUNK_BYTES }),
      async function* (source: Readable) {
        for await (const chunk of source) {
          const bytes = chunk as Buffer;
          hash.update(bytes);
          size += bytes.byteLength;
          yield bytes;
        }
      },
      createWriteStream(scratch, { highWaterMark: STREAM_CHUNK_BYTES }),
    );
    await rename(scratch, targetPath);
    return { sha256: hash.digest("hex"), sizeBytes: size };
  } catch (error) {
    await unlink(scratch).catch(() => {});
    throw error;
  }
}
