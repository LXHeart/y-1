package com.grassland.identity.security;

import java.util.Random;
import org.springframework.stereotype.Component;

@Component
public class CaptchaGenerator {
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final int LENGTH = 4;
    private final Random random = new Random();

    public String generateText() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    public String generateSvg(String text) {
        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"160\" height=\"60\">");
        svg.append("<rect width=\"160\" height=\"60\" fill=\"#1a1a2e\"/>");
        for (int i = 0; i < 4; i++) {
            svg.append("<line x1=\"").append(random.nextInt(160))
               .append("\" y1=\"").append(random.nextInt(60))
               .append("\" x2=\"").append(random.nextInt(160))
               .append("\" y2=\"").append(random.nextInt(60))
               .append("\" stroke=\"#").append(Integer.toHexString(random.nextInt(0x888888) + 0x444444))
               .append("\" stroke-width=\"1\"/>");
        }
        for (int i = 0; i < 30; i++) {
            svg.append("<circle cx=\"").append(random.nextInt(160))
               .append("\" cy=\"").append(random.nextInt(60))
               .append("\" r=\"1\" fill=\"#").append(Integer.toHexString(random.nextInt(0x888888) + 0x444444))
               .append("\"/>");
        }
        for (int i = 0; i < text.length(); i++) {
            int x = 20 + i * 35;
            int y = 35 + random.nextInt(10);
            int rot = random.nextInt(60) - 30;
            int size = 26 + random.nextInt(8);
            String color = String.format("#%06x", random.nextInt(0xAAAAAA) + 0x555555);
            svg.append("<text x=\"").append(x).append("\" y=\"").append(y)
               .append("\" font-size=\"").append(size)
               .append("\" fill=\"").append(color)
               .append("\" transform=\"rotate(").append(rot).append(" ").append(x).append(",").append(y)
               .append(")\" font-family=\"Arial\" font-weight=\"bold\">")
               .append(text.charAt(i)).append("</text>");
        }
        svg.append("</svg>");
        return svg.toString();
    }
}
