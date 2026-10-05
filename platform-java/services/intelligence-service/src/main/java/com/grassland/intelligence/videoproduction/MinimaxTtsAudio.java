package com.grassland.intelligence.videoproduction;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * MiniMax async downloads may be POSIX tar bundles, not raw audio. Never
 * extracts to disk.
 */
final class MinimaxTtsAudio {
	private static final int BLOCK = 512;

	private MinimaxTtsAudio() {
	}

	static byte[] unpack(byte[] bytes) {
		if (bytes.length < BLOCK || !text(bytes, 257, 5).equals("ustar")) {
			return bytes;
		}
		byte[] audio = null;
		int offset = 0;
		while (offset + BLOCK <= bytes.length) {
			if (zeroBlock(bytes, offset)) {
				for (int i = offset; i < bytes.length; i++) {
					if (bytes[i] != 0) {
						throw invalid();
					}
				}
				if (audio == null) {
					throw invalid();
				}
				return audio;
			}
			if (!text(bytes, offset + 257, 5).equals("ustar")) {
				throw invalid();
			}
			long checksum = 0;
			for (int i = 0; i < BLOCK; i++) {
				checksum += i >= 148 && i < 156 ? 32 : Byte.toUnsignedInt(bytes[offset + i]);
			}
			if (checksum != octal(bytes, offset + 148, 8)) {
				throw invalid();
			}
			long size = octal(bytes, offset + 124, 12);
			long next = offset + BLOCK + ((size + BLOCK - 1) / BLOCK) * BLOCK;
			if (size > bytes.length || next > bytes.length) {
				throw invalid();
			}
			String name = text(bytes, offset, 100).toLowerCase(Locale.ROOT);
			int type = bytes[offset + 156];
			// Only regular files and directories are part of the supported provider bundle.
			if (type != 0 && type != '0' && type != '5') {
				throw invalid();
			}
			if (type == '5' && size != 0) {
				throw invalid();
			}
			if (type != '5' && (name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".flac"))) {
				if (audio != null || size == 0) {
					throw invalid();
				}
				audio = Arrays.copyOfRange(bytes, offset + BLOCK, offset + BLOCK + (int) size);
			}
			offset = (int) next;
		}
		// A truncated archive (including missing terminator) must not become playable
		// media.
		throw invalid();
	}

	private static boolean zeroBlock(byte[] bytes, int offset) {
		for (int i = offset; i < offset + BLOCK; i++) {
			if (bytes[i] != 0) {
				return false;
			}
		}
		return true;
	}

	private static String text(byte[] bytes, int start, int length) {
		int end = start;
		while (end < start + length && bytes[end] != 0) {
			end++;
		}
		return new String(bytes, start, end - start, StandardCharsets.US_ASCII);
	}

	private static long octal(byte[] bytes, int start, int length) {
		String value = text(bytes, start, length).trim();
		if (!value.matches("[0-7]{1,11}")) {
			throw invalid();
		}
		return Long.parseLong(value, 8);
	}

	private static IllegalStateException invalid() {
		return new IllegalStateException("MiniMax TTS 音频归档无效或不含唯一音频");
	}
}
