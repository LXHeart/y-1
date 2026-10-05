package com.grassland.intelligence.mediaplatform.segments;

/** Versioned silent visual contract, independent of work IDs and audio policy. */
public record SegmentSpec(String kind, int width, int height, int fpsNumerator, int fpsDenominator,
        int frameCount, long start, String trimUnit, String fit, int crf) {
    public SegmentSpec {
        if (!("image".equals(kind) || "video".equals(kind)) || width < 2 || height < 2
                || width > 3840 || height > 3840 || width % 2 != 0 || height % 2 != 0
                || fpsNumerator < 1 || fpsNumerator > 120000 || fpsDenominator < 1 || fpsDenominator > 10000
                || (double) fpsNumerator / fpsDenominator > 60 || (double) fpsNumerator / fpsDenominator < 1
                || frameCount < 1 || frameCount > 36000 || (double) frameCount * fpsDenominator / fpsNumerator > 600
                || start < 0 || start > 86400000000L || !("frames".equals(trimUnit) || "microseconds".equals(trimUnit))
                || !("fill".equals(fit) || "contain".equals(fit)) || crf < 0 || crf > 40
                || ("image".equals(kind) && start != 0)) {
            throw new IllegalArgumentException("Invalid media segment specification");
        }
    }
    public String rate() { return fpsNumerator + "/" + fpsDenominator; }
    public String canonical() {
        return String.join("|", "silent-segment-v2", kind, "" + width, "" + height, rate(), "" + frameCount,
                "" + start, trimUnit, fit, "" + crf);
    }
}
