package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class MinimaxTtsAudioTest {
	@Test
	void extractsAudioAndIgnoresTitlesAndMetadata() {
		byte[] audio = {73, 68, 51, 1, 2, 3};
		assertThat(MinimaxTtsAudio.unpack(tar("nested/content.mp3", audio, "nested/content.titles", new byte[]{9})))
				.isEqualTo(audio);
	}

	@Test
	void rawAudioStillWorks() {
		byte[] audio = {73, 68, 51, 1, 2, 3};
		assertThat(MinimaxTtsAudio.unpack(audio)).isSameAs(audio);
	}

	@Test
	void rejectsAmbiguousOrMissingAudio() {
		assertThatThrownBy(() -> MinimaxTtsAudio.unpack(tar("a.mp3", new byte[]{1}, "b.mp3", new byte[]{2})))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> MinimaxTtsAudio.unpack(tar("a.titles", new byte[]{1})))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> MinimaxTtsAudio.unpack(tar("a.mp3", new byte[0])))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void rejectsTruncationChecksumCorruptionAndOversizedEntry() {
		byte[] valid = tar("a.mp3", new byte[]{1});
		assertThatThrownBy(() -> MinimaxTtsAudio.unpack(Arrays.copyOf(valid, 513)))
				.isInstanceOf(IllegalStateException.class);
		byte[] corrupt = valid.clone();
		corrupt[0]++;
		assertThatThrownBy(() -> MinimaxTtsAudio.unpack(corrupt)).isInstanceOf(IllegalStateException.class);
		byte[] oversized = valid.clone();
		put(oversized, 124, "77777777777");
		checksum(oversized);
		assertThatThrownBy(() -> MinimaxTtsAudio.unpack(oversized)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void rejectsLinksAndTrailingGarbage() {
		byte[] link = tar("a.mp3", new byte[]{1});
		link[156] = '2';
		checksum(link);
		assertThatThrownBy(() -> MinimaxTtsAudio.unpack(link)).isInstanceOf(IllegalStateException.class);
		byte[] garbage = tar("a.mp3", new byte[]{1});
		garbage[garbage.length - 1] = 1;
		assertThatThrownBy(() -> MinimaxTtsAudio.unpack(garbage)).isInstanceOf(IllegalStateException.class);
	}

	static byte[] tar(Object... entries) {
		var out = new ByteArrayOutputStream();
		for (int i = 0; i < entries.length; i += 2) {
			byte[] data = (byte[]) entries[i + 1];
			byte[] header = new byte[512];
			put(header, 0, (String) entries[i]);
			put(header, 124, String.format("%011o", data.length));
			header[156] = '0';
			put(header, 257, "ustar");
			checksum(header);
			out.writeBytes(header);
			out.writeBytes(data);
			out.writeBytes(new byte[(512 - data.length % 512) % 512]);
		}
		out.writeBytes(new byte[1024]);
		return out.toByteArray();
	}

	private static void checksum(byte[] header) {
		Arrays.fill(header, 148, 156, (byte) 32);
		int sum = 0;
		for (int i = 0; i < 512; i++) {
			sum += Byte.toUnsignedInt(header[i]);
		}
		put(header, 148, String.format("%06o\0 ", sum));
	}

	private static void put(byte[] bytes, int offset, String value) {
		byte[] text = value.getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(text, 0, bytes, offset, text.length);
	}
}
