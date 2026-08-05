package com.grassland.intelligence.media;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KybMediaPolicyTest {

    @Test
    void allowsOnlyJpegPngAndPdf() {
        assertThat(KybMediaPolicy.isAllowedMime("image/jpeg")).isTrue();
        assertThat(KybMediaPolicy.isAllowedMime("image/png")).isTrue();
        assertThat(KybMediaPolicy.isAllowedMime("application/pdf")).isTrue();
        assertThat(KybMediaPolicy.isAllowedMime("image/gif")).isFalse();
        assertThat(KybMediaPolicy.isAllowedMime("video/mp4")).isFalse();
    }

    @Test
    void validatesCompleteSignaturesForEachAllowedType() {
        assertThat(KybMediaPolicy.hasExpectedSignature(
                "image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff})).isTrue();
        assertThat(KybMediaPolicy.hasExpectedSignature(
                "image/png", new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})).isTrue();
        assertThat(KybMediaPolicy.hasExpectedSignature(
                "application/pdf", new byte[] {0x25, 0x50, 0x44, 0x46, 0x2d})).isTrue();
    }

    @Test
    void rejectsTruncatedOrSpoofedSignatures() {
        assertThat(KybMediaPolicy.hasExpectedSignature(
                "image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8})).isFalse();
        assertThat(KybMediaPolicy.hasExpectedSignature(
                "image/png", new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47})).isFalse();
        assertThat(KybMediaPolicy.hasExpectedSignature(
                "application/pdf", new byte[] {0x25, 0x50, 0x44, 0x46})).isFalse();
        assertThat(KybMediaPolicy.hasExpectedSignature("image/png", new byte[8])).isFalse();
        assertThat(KybMediaPolicy.hasExpectedSignature("text/csv", new byte[] {1, 2, 3})).isFalse();
        assertThat(KybMediaPolicy.hasExpectedSignature("image/png", null)).isFalse();
    }
}
