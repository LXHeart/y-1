// transfer.ts — C107F2-30 (W186 NEW): browser-safe project package transfers.
//
// The browser needs a real ZIP (download) and uploads one (import). The broker
// has no zip dependency, so this module carries a minimal STREAMING codec:
// store-only entries for writes (every unzip tool and browser reads them; the
// CRC is computed in a first pass because the local header carries it), and a
// central-directory reader that inflates method-8 entries entry-by-entry
// through zlib.createInflateRaw. Peak memory is bounded by
// STREAM_CHUNK_BYTES regardless of bundle size (§6.13: 不随包总大小全量读内存).
// Transfers live under a bounded staging root keyed by Java-command-derived
// transfer ids; owner binding is enforced by Java BEFORE these internal
// endpoints are reached; staged content expires in 24h (§7.4).
import { createHash } from "node:crypto";
import { createReadStream, createWriteStream, existsSync, openSync, readSync, closeSync, statSync } from "node:fs";
import { mkdir, readFile, rename, rm, stat, writeFile } from "node:fs/promises";
import type { IncomingMessage, ServerResponse } from "node:http";
import { basename, dirname, join, resolve } from "node:path";
import { pipeline } from "node:stream/promises";
import type { Readable, Writable } from "node:stream";
import zlib from "node:zlib";

import { readdir } from "node:fs/promises";

import { STREAM_CHUNK_BYTES } from "./binary-staging.ts";
import { isForbiddenPath, MAX_BUNDLE_FILES, MAX_BUNDLE_TOTAL_BYTES } from "./manifest.ts";

const TRANSFER_TTL_MS = 24 * 60 * 60 * 1000;
const MAX_TRANSFER_BYTES = 4 * 1024 * 1024 * 1024;
const EOCD_SCAN_BYTES = 66000;
/** Fixed DOS timestamp: deterministic bytes for a given bundle, no clock leak. */
const DOS_TIME = 0;
const DOS_DATE = (44 << 9) | (1 << 5) | 1;

export class TransferError extends Error {
  constructor(readonly code: string, message: string) {
    super(message);
    this.name = "TransferError";
  }
}

export function isTransferId(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/iu.test(value);
}

/** Contained transfer dir (never outside the staging root, never a traversal). */
export function transferDirFor(stagingRoot: string, transferId: string): string {
  if (!isTransferId(transferId)) {
    throw new TransferError("invalid_input", "transferId must be a uuid");
  }
  const root = resolve(stagingRoot);
  const dir = resolve(root, "package-transfers", transferId);
  if (!dir.startsWith(root + "/")) {
    throw new TransferError("invalid_input", "transfer escapes the staging root");
  }
  return dir;
}

export type StoredTransfer = {
  readonly transferId: string;
  readonly sha256: string;
  readonly sizeBytes: number;
};

// ---------------------------------------------------------------------------
// Upload: stream the request body into staging, hashing as bytes arrive.
// ---------------------------------------------------------------------------

export async function storeTransferContent(
  stagingRoot: string,
  transferId: string,
  request: IncomingMessage,
): Promise<StoredTransfer> {
  const dir = transferDirFor(stagingRoot, transferId);
  await mkdir(dir, { recursive: true });
  const target = join(dir, "package.zip");
  const hash = createHash("sha256");
  let size = 0;
  const scratch = `${target}.uploading-${process.pid}`;
  try {
    await pipeline(
      request,
      async function* (source: AsyncIterable<Buffer>): AsyncGenerator<Buffer> {
        for await (const chunk of source) {
          const bytes = chunk as Buffer;
          size += bytes.byteLength;
          if (size > MAX_TRANSFER_BYTES) {
            throw new TransferError("too_large", "transfer exceeds the 4 GiB upload cap");
          }
          hash.update(bytes);
          yield bytes;
        }
      },
      createWriteStream(scratch, { highWaterMark: STREAM_CHUNK_BYTES }),
    );
    await rename(scratch, target);
    const stored: StoredTransfer = { transferId, sha256: hash.digest("hex"), sizeBytes: size };
    await recordTransferMeta(stagingRoot, stored);
    return stored;
  } catch (error) {
    // 上传中断不留半包（staging 清理）；同 transferId 重传即重开。
    await rm(scratch, { force: true }).catch(() => {});
    await rm(target, { force: true }).catch(() => {});
    throw error;
  }
}

export async function recordTransferMeta(stagingRoot: string, stored: StoredTransfer): Promise<void> {
  const dir = transferDirFor(stagingRoot, stored.transferId);
  await writeFile(join(dir, "meta.json"), `${JSON.stringify({ sha256: stored.sha256, sizeBytes: stored.sizeBytes })}\n`);
}

export async function transferMeta(stagingRoot: string, transferId: string): Promise<StoredTransfer | null> {
  const dir = transferDirFor(stagingRoot, transferId);
  try {
    const meta = JSON.parse(await readFile(join(dir, "meta.json"), "utf8")) as { sha256?: string; sizeBytes?: number };
    if (typeof meta.sha256 !== "string" || typeof meta.sizeBytes !== "number") return null;
    return { transferId, sha256: meta.sha256, sizeBytes: meta.sizeBytes };
  } catch {
    return null;
  }
}

/** Serve a staged zip: 200/206/416 with ETag=sha256, safe UTF-8 filename, 410 after 24h. */
/**
 * C107F2-35 (§7.4): TTL reclaim for unfinished upload staging / failed imports —
 * transfer dirs older than 24h are removed outright; entries that fail to
 * remove stay on disk (retryable, observable via the returned counts) instead
 * of a silent swallow. Shared/billed media never lives here, so a purge can
 * never touch owner content — only orphaned staging.
 */
export async function purgeExpiredTransfers(
  stagingRoot: string,
  now: number = Date.now(),
): Promise<{ removed: number; retained: number }> {
  const dir = resolve(stagingRoot, "package-transfers");
  let removed = 0;
  let retained = 0;
  let entries: string[] = [];
  try {
    entries = await readdir(dir);
  } catch {
    return { removed, retained }; // no staging yet — nothing to reclaim
  }
  for (const entry of entries) {
    if (!isTransferId(entry)) continue;
    const target = join(dir, entry);
    const info = await stat(target).catch(() => null);
    if (info === null) continue;
    if (now - info.mtimeMs <= TRANSFER_TTL_MS) {
      retained += 1;
      continue;
    }
    try {
      await rm(target, { recursive: true, force: true });
      removed += 1;
    } catch {
      retained += 1; // leave in place — next sweep retries
    }
  }
  return { removed, retained };
}

export async function serveTransferContent(
  stagingRoot: string,
  transferId: string,
  request: IncomingMessage,
  response: ServerResponse,
): Promise<void> {
  const dir = transferDirFor(stagingRoot, transferId);
  const target = join(dir, "package.zip");
  if (!existsSync(target)) {
    response.writeHead(404, { "content-type": "application/json" });
    response.end(JSON.stringify({ error: "unknown transfer" }));
    return;
  }
  const info = await stat(target);
  if (Date.now() - info.mtimeMs > TRANSFER_TTL_MS) {
    await rm(dir, { recursive: true, force: true }).catch(() => {});
    response.writeHead(410, { "content-type": "application/json" });
    response.end(JSON.stringify({ error: "transfer expired" }));
    return;
  }
  const meta = await transferMeta(stagingRoot, transferId);
  const etag = meta === null ? undefined : `"${meta.sha256}"`;
  const filename = `hypit-project-${transferId.slice(0, 8)}.zip`;
  const disposition = `attachment; filename="${filename}"; filename*=UTF-8''${filename}`;
  const range = request.headers.range;
  const match = typeof range === "string" ? /^bytes=(\d+)-(\d*)$/u.exec(range.trim()) : null;
  if (match !== null) {
    const start = Number(match[1]);
    const end = match[2] === undefined || match[2] === "" ? info.size - 1 : Math.min(Number(match[2]), info.size - 1);
    if (!Number.isSafeInteger(start) || start > end || start >= info.size) {
      response.writeHead(416, { "content-range": `bytes */${info.size}` });
      response.end();
      return;
    }
    response.writeHead(206, {
      "content-type": "application/zip",
      "content-length": String(end - start + 1),
      "content-range": `bytes ${start}-${end}/${info.size}`,
      "accept-ranges": "bytes",
      "cache-control": "no-store",
      ...(etag === undefined ? {} : { etag }),
      "content-disposition": disposition,
    });
    createReadStream(target, { start, end }).pipe(response);
    return;
  }
  response.writeHead(200, {
    "content-type": "application/zip",
    "content-length": String(info.size),
    "accept-ranges": "bytes",
    "cache-control": "no-store",
    ...(etag === undefined ? {} : { etag }),
    "content-disposition": disposition,
  });
  createReadStream(target).pipe(response);
}

// ---------------------------------------------------------------------------
// Export side: pack a bundle directory into a real ZIP (store-only entries).
// ---------------------------------------------------------------------------

/** Pack the given relative files (already hash-verified by export.ts) into outPath. */
export async function packBundleZip(
  bundleRoot: string,
  files: readonly { readonly path: string; readonly sizeBytes: number }[],
  outPath: string,
): Promise<StoredTransfer> {
  if (files.length > MAX_BUNDLE_FILES) {
    throw new TransferError("too_large", `bundle exceeds ${MAX_BUNDLE_FILES} files`);
  }
  let declaredTotal = 0;
  for (const file of files) {
    if (file.sizeBytes > MAX_BUNDLE_TOTAL_BYTES) {
      throw new TransferError("too_large", `bundle entry exceeds the zip cap: ${file.path}`);
    }
    declaredTotal += file.sizeBytes;
  }
  if (declaredTotal > MAX_BUNDLE_TOTAL_BYTES) {
    throw new TransferError("too_large", "bundle exceeds the 4 GiB zip cap");
  }
  const hash = createHash("sha256");
  let zipBytes = 0;
  const scratch = `${outPath}.packing-${process.pid}`;
  // C107F2-37：导出路径的父目录（package-transfers/<exportId>/）此前从未创建——
  // WriteStream ENOENT 曾以 unhandled 'error' 事件把整个 broker 进程崩掉（export
  // 卡 dispatching、后续命令全部无人处理）。写前建目录；流错误记下并在 write()
  // 里抛出（DispatchError 走常规失败收敛），绝不逸出成进程级事件。
  await mkdir(resolve(scratch, ".."), { recursive: true });
  let streamError: Error | null = null;
  const out = createWriteStream(scratch, { highWaterMark: STREAM_CHUNK_BYTES });
  out.on("error", (error) => {
    streamError = error;
  });
  const central: Buffer[] = [];
  let offset = 0;
  const write = async (buffer: Buffer): Promise<void> => {
    if (streamError !== null) throw streamError;
    hash.update(buffer);
    zipBytes += buffer.byteLength;
    if (!out.write(buffer)) {
      await new Promise<void>((resolveWait) => out.once("drain", resolveWait));
    }
    if (streamError !== null) throw streamError;
  };
  const onData = (chunk: Buffer): void => {
    hash.update(chunk);
    zipBytes += chunk.byteLength;
  };
  try {
    for (const file of files) {
      const name = Buffer.from(file.path, "utf8");
      const source = join(bundleRoot, file.path);
      // CRC 先行（store 条目的 local header 携带 CRC）——第一遍只算哈希。
      const crc = await computeCrc(source);
      const local = Buffer.alloc(30 + name.byteLength);
      local.writeUInt32LE(0x04034b50, 0);
      local.writeUInt16LE(20, 4);
      local.writeUInt16LE(0x0800, 6); // UTF-8 names
      local.writeUInt16LE(0, 8); // stored
      local.writeUInt16LE(DOS_TIME, 10);
      local.writeUInt16LE(DOS_DATE, 12);
      local.writeUInt32LE(crc, 14);
      local.writeUInt32LE(file.sizeBytes, 18);
      local.writeUInt32LE(file.sizeBytes, 22);
      local.writeUInt16LE(name.byteLength, 26);
      local.writeUInt16LE(0, 28);
      name.copy(local, 30);
      await write(local);
      await streamFileInto(out, source, onData);
      const entry = Buffer.alloc(46 + name.byteLength);
      entry.writeUInt32LE(0x02014b50, 0);
      entry.writeUInt16LE(20, 4);
      entry.writeUInt16LE(20, 6);
      entry.writeUInt16LE(0x0800, 8);
      entry.writeUInt16LE(0, 10);
      entry.writeUInt16LE(DOS_TIME, 12);
      entry.writeUInt16LE(DOS_DATE, 14);
      entry.writeUInt32LE(crc, 16);
      entry.writeUInt32LE(file.sizeBytes, 20);
      entry.writeUInt32LE(file.sizeBytes, 24);
      entry.writeUInt16LE(name.byteLength, 28);
      entry.writeUInt16LE(0, 30);
      entry.writeUInt16LE(0, 32);
      entry.writeUInt16LE(0, 34);
      entry.writeUInt16LE(0, 36);
      entry.writeUInt32LE(0, 38);
      entry.writeUInt32LE(offset, 42);
      name.copy(entry, 46);
      central.push(entry);
      offset += local.byteLength + file.sizeBytes;
    }
    const centralStart = offset;
    let centralSize = 0;
    for (const entry of central) {
      await write(entry);
      centralSize += entry.byteLength;
    }
    const eocd = Buffer.alloc(22);
    eocd.writeUInt32LE(0x06054b50, 0);
    eocd.writeUInt16LE(0, 4);
    eocd.writeUInt16LE(0, 6);
    eocd.writeUInt16LE(files.length, 8);
    eocd.writeUInt16LE(files.length, 10);
    eocd.writeUInt32LE(centralSize, 12);
    eocd.writeUInt32LE(centralStart, 16);
    eocd.writeUInt16LE(0, 20);
    await write(eocd);
    out.end();
    await new Promise<void>((resolveEnd, rejectEnd) => {
      out.on("error", rejectEnd);
      out.on("finish", () => resolveEnd());
    });
    await rename(scratch, outPath);
    // C107F2-37：transferId 是目录键（<stagingRoot>/package-transfers/<id>/package.zip），
    // 回导文件名会把 "package.zip" 当 id 喂给 recordTransferMeta → invalid_input。
    return { transferId: basename(dirname(outPath)), sha256: hash.digest("hex"), sizeBytes: zipBytes };
  } catch (error) {
    out.destroy();
    await rm(scratch, { force: true }).catch(() => {});
    throw error;
  }
}

async function computeCrc(sourcePath: string): Promise<number> {
  let crc = 0;
  let first = true;
  await pipeline(
    createReadStream(sourcePath, { highWaterMark: STREAM_CHUNK_BYTES }),
    async function* (source: AsyncIterable<Buffer>): AsyncGenerator<Buffer> {
      for await (const chunk of source) {
        const bytes = chunk as Buffer;
        crc = first ? zlib.crc32(bytes) >>> 0 : zlib.crc32(bytes, crc) >>> 0;
        first = false;
        yield bytes;
      }
    },
    // CRC pass reads only; discard bytes into a sink.
    async function* (source: AsyncIterable<Buffer>): AsyncGenerator<never> {
      for await (const _ of source) {
        void _;
      }
    },
  );
  return crc;
}

async function streamFileInto(out: Writable, sourcePath: string, onData: (chunk: Buffer) => void): Promise<void> {
  await pipeline(
    createReadStream(sourcePath, { highWaterMark: STREAM_CHUNK_BYTES }),
    async function* (source: AsyncIterable<Buffer>): AsyncGenerator<Buffer> {
      for await (const chunk of source) {
        const bytes = chunk as Buffer;
        onData(bytes);
        yield bytes;
      }
    },
    out,
    { end: false },
  );
}

// ---------------------------------------------------------------------------
// Import side: extract a staged zip into a bundle directory (all gates inline).
// ---------------------------------------------------------------------------

export type ExtractedBundle = {
  readonly dir: string;
  readonly files: readonly { readonly path: string; readonly sizeBytes: number }[];
};

/**
 * Extract a staged package.zip into <transferDir>/extracted with full
 * hostile-input gates: entry/size caps, forbidden paths, duplicates, symlink
 * entries and zip-slip are all refusals; bytes stream through a bounded
 * buffer. Extraction output feeds the SAME verify-then-land import gates
 * (import.ts), so a hostile zip never reaches a workspace.
 */
export async function extractBundleZip(stagingRoot: string, transferId: string): Promise<ExtractedBundle> {
  const dir = transferDirFor(stagingRoot, transferId);
  const zipPath = join(dir, "package.zip");
  if (!existsSync(zipPath)) {
    throw new TransferError("not_found", "transfer content missing");
  }
  const targetDir = join(dir, "extracted");
  await rm(targetDir, { recursive: true, force: true }).catch(() => {});
  await mkdir(targetDir, { recursive: true });
  const entries = readCentralDirectory(zipPath);
  if (entries.length > MAX_BUNDLE_FILES) {
    throw new TransferError("too_large", `zip exceeds ${MAX_BUNDLE_FILES} entries`);
  }
  const seen = new Set<string>();
  let total = 0;
  for (const entry of entries) {
    if (entry.symlink) {
      throw new TransferError("invalid_input", `zip entry is a symlink: ${entry.path}`);
    }
    if (isForbiddenPath(entry.path)) {
      throw new TransferError("invalid_input", `zip entry carries a forbidden path: ${entry.path}`);
    }
    if (seen.has(entry.path)) {
      throw new TransferError("invalid_input", `duplicate zip entry: ${entry.path}`);
    }
    seen.add(entry.path);
    const destination = resolve(targetDir, entry.path);
    if (!destination.startsWith(targetDir + "/")) {
      throw new TransferError("invalid_input", `zip entry escapes the target root: ${entry.path}`);
    }
    if (entry.directory) {
      await mkdir(destination, { recursive: true });
      continue;
    }
    total += entry.sizeBytes;
    if (total > MAX_BUNDLE_TOTAL_BYTES) {
      throw new TransferError("too_large", "zip exceeds the 4 GiB extract cap");
    }
    await mkdir(resolve(destination, ".."), { recursive: true });
    await extractEntry(zipPath, entry, destination);
  }
  return {
    dir: targetDir,
    files: entries.filter((entry) => !entry.directory).map((entry) => ({ path: entry.path, sizeBytes: entry.sizeBytes })),
  };
}

type CentralEntry = {
  readonly path: string;
  readonly offset: number;
  readonly sizeBytes: number;
  readonly compressedSize: number;
  readonly method: number;
  readonly crc: number;
  readonly directory: boolean;
  readonly symlink: boolean;
};

function readCentralDirectory(zipPath: string): CentralEntry[] {
  const info = statSync(zipPath);
  const scanSize = Math.min(EOCD_SCAN_BYTES, info.size);
  const fd = openSync(zipPath, "r");
  try {
    const tail = Buffer.alloc(scanSize);
    readSync(fd, tail, 0, scanSize, info.size - scanSize);
    let eocd = -1;
    for (let index = tail.byteLength - 22; index >= 0; index -= 1) {
      if (tail.readUInt32LE(index) === 0x06054b50) {
        eocd = index;
        break;
      }
    }
    if (eocd < 0) {
      throw new TransferError("invalid_input", "not a zip archive (no EOCD)");
    }
    const count = tail.readUInt16LE(eocd + 10);
    const centralSize = tail.readUInt32LE(eocd + 12);
    const centralOffset = tail.readUInt32LE(eocd + 16);
    const central = Buffer.alloc(centralSize);
    readSync(fd, central, 0, centralSize, centralOffset);
    const entries: CentralEntry[] = [];
    let cursor = 0;
    for (let index = 0; index < count; index += 1) {
      if (cursor + 46 > central.byteLength || central.readUInt32LE(cursor) !== 0x02014b50) {
        throw new TransferError("invalid_input", "corrupted zip central directory");
      }
      const method = central.readUInt16LE(cursor + 10);
      const crc = central.readUInt32LE(cursor + 16);
      const compressedSize = central.readUInt32LE(cursor + 20);
      const sizeBytes = central.readUInt32LE(cursor + 24);
      const nameLen = central.readUInt16LE(cursor + 28);
      const extraLen = central.readUInt16LE(cursor + 30);
      const commentLen = central.readUInt16LE(cursor + 32);
      const externalAttrs = central.readUInt32LE(cursor + 38);
      const localOffset = central.readUInt32LE(cursor + 42);
      const path = central.toString("utf8", cursor + 46, cursor + 46 + nameLen);
      entries.push({
        path,
        offset: localOffset,
        sizeBytes,
        compressedSize,
        method,
        crc,
        directory: path.endsWith("/") || (externalAttrs >>> 16 & 0o170000) === 0o040000,
        symlink: (externalAttrs >>> 16 & 0o170000) === 0o120000,
      });
      cursor += 46 + nameLen + extraLen + commentLen;
    }
    return entries;
  } finally {
    closeSync(fd);
  }
}

/** Extract one entry: stored bytes copy raw, deflated entries stream through inflateRaw. */
async function extractEntry(zipPath: string, entry: CentralEntry, destination: string): Promise<void> {
  let dataOffset = 0;
  {
    const fd = openSync(zipPath, "r");
    try {
      const local = Buffer.alloc(30);
      readSync(fd, local, 0, 30, entry.offset);
      if (local.readUInt32LE(0) !== 0x04034b50) {
        throw new TransferError("invalid_input", `corrupted zip local header: ${entry.path}`);
      }
      const nameLen = local.readUInt16LE(26);
      const extraLen = local.readUInt16LE(28);
      dataOffset = entry.offset + 30 + nameLen + extraLen;
    } finally {
      closeSync(fd);
    }
  }
  const ranged = createReadStream(zipPath, {
    start: dataOffset,
    end: dataOffset + entry.compressedSize - 1,
    highWaterMark: STREAM_CHUNK_BYTES,
  });
  const out = createWriteStream(destination, { highWaterMark: STREAM_CHUNK_BYTES });
  const hash = createHash("sha256");
  let size = 0;
  let crc = 0;
  let first = true;
  const verify = async function* (source: AsyncIterable<Buffer>): AsyncGenerator<Buffer> {
    for await (const chunk of source) {
      const bytes = chunk as Buffer;
      hash.update(bytes);
      size += bytes.byteLength;
      crc = first ? zlib.crc32(bytes) >>> 0 : zlib.crc32(bytes, crc) >>> 0;
      first = false;
      yield bytes;
    }
  };
  try {
    if (entry.method === 0) {
      await pipeline(ranged, verify, out);
    } else if (entry.method === 8) {
      await pipeline(ranged, zlib.createInflateRaw(), verify, out);
    } else {
      throw new TransferError("invalid_input", `unsupported zip method ${entry.method}: ${entry.path}`);
    }
  } catch (error) {
    await rm(destination, { force: true }).catch(() => {});
    throw error;
  }
  if (size !== entry.sizeBytes || crc !== entry.crc) {
    await rm(destination, { force: true }).catch(() => {});
    throw new TransferError("invalid_input", `zip entry integrity mismatch: ${entry.path}`);
  }
  void hash;
}
