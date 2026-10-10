package cn.yl.infrastructure.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yl.common.sms.RenderedCaptcha;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** {@link AwtCaptchaRenderer} 单测：产出非空 PNG data URI，且解码含 PNG 魔数。 */
class AwtCaptchaRendererTest {

    private static final String DATA_URI_PREFIX = "data:image/png;base64,";

    private final AwtCaptchaRenderer renderer = new AwtCaptchaRenderer();

    @Test
    void renderProducesPngWithMagicBytes() {
        RenderedCaptcha rendered = renderer.render();

        assertNotNull(rendered.code());
        assertEquals(4, rendered.code().length());
        assertTrue(rendered.code().chars().allMatch(Character::isDigit), "验证码应为数字");

        assertTrue(rendered.dataUri().startsWith(DATA_URI_PREFIX));
        byte[] png =
                Base64.getDecoder().decode(rendered.dataUri().substring(DATA_URI_PREFIX.length()));
        assertTrue(png.length > 0, "PNG 不应为空");
        // PNG 魔数：89 50 4E 47
        assertEquals((byte) 0x89, png[0]);
        assertEquals((byte) 'P', png[1]);
        assertEquals((byte) 'N', png[2]);
        assertEquals((byte) 'G', png[3]);
    }
}
