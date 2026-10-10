package cn.yl.infrastructure.sms;

import cn.yl.common.sms.RenderedCaptcha;
import cn.yl.common.sms.SmsCaptchaRenderer;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/**
 * 图形验证码渲染器（P2-8-A，设计 §4.1/§5 SMS-01）。
 *
 * <p><b>零新依赖</b>：仅用 JDK AWT（{@link BufferedImage} + {@link Graphics2D}）与 {@link ImageIO} 生成 4 位数字
 * PNG（含干扰线与噪点），不引入任何图像库。
 *
 * <p>服务端无显示设备：静态块内一次性置 {@code java.awt.headless=true}，避免容器环境初始化 AWT 失败。
 *
 * <p>★ 抗 OCR 强度有限（残留 P2-8-R5）：A 段是「有图形验证码」而非「强图形验证码」，增强属 P2-8-B。
 */
@Component
public class AwtCaptchaRenderer implements SmsCaptchaRenderer {

    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;
    private static final int CODE_LENGTH = 4;
    private static final int NOISE_LINES = 6;
    private static final int NOISE_DOTS = 40;
    private static final int FONT_SIZE = 28;
    private static final int MIN_TONE = 0;
    private static final int MAX_TONE_FG = 110;
    private static final int MIN_TONE_NOISE = 120;
    private static final int MAX_TONE_NOISE = 200;
    private static final String DATA_URI_PREFIX = "data:image/png;base64,";

    private static final SecureRandom RANDOM = new SecureRandom();

    static {
        // headless 仅设一次；类加载即执行，避免依赖运行环境已设置
        System.setProperty("java.awt.headless", "true");
    }

    @Override
    public RenderedCaptcha render() {
        String code = randomDigits();
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            drawScene(graphics, code);
        } finally {
            graphics.dispose();
        }
        return new RenderedCaptcha(code, toDataUri(image));
    }

    private void drawScene(Graphics2D graphics, String code) {
        graphics.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(new Color(0xF2, 0xF2, 0xF2));
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
        drawNoiseLines(graphics);
        drawDigits(graphics, code);
        drawNoiseDots(graphics);
    }

    private void drawNoiseLines(Graphics2D graphics) {
        for (int i = 0; i < NOISE_LINES; i++) {
            graphics.setColor(noiseColor());
            graphics.drawLine(
                    randomInt(WIDTH), randomInt(HEIGHT), randomInt(WIDTH), randomInt(HEIGHT));
        }
    }

    private void drawDigits(Graphics2D graphics, String code) {
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, FONT_SIZE));
        int step = WIDTH / (code.length() + 1);
        for (int i = 0; i < code.length(); i++) {
            graphics.setColor(foregroundColor());
            int x = step * (i + 1) - FONT_SIZE / 3;
            int y = FONT_SIZE + randomInt(7) - 3;
            graphics.drawString(String.valueOf(code.charAt(i)), x, y);
        }
    }

    private void drawNoiseDots(Graphics2D graphics) {
        for (int i = 0; i < NOISE_DOTS; i++) {
            graphics.setColor(noiseColor());
            graphics.fillRect(randomInt(WIDTH), randomInt(HEIGHT), 1, 1);
        }
    }

    private String toDataUri(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return DATA_URI_PREFIX + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("图形验证码渲染失败", e);
        }
    }

    private String randomDigits() {
        StringBuilder builder = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            builder.append((char) ('0' + RANDOM.nextInt(10)));
        }
        return builder.toString();
    }

    private Color noiseColor() {
        return new Color(randomTone(MIN_TONE_NOISE, MAX_TONE_NOISE));
    }

    private Color foregroundColor() {
        return new Color(randomTone(MIN_TONE, MAX_TONE_FG));
    }

    private int randomTone(int min, int max) {
        return min + RANDOM.nextInt(max - min);
    }

    private int randomInt(int bound) {
        return RANDOM.nextInt(bound);
    }
}
