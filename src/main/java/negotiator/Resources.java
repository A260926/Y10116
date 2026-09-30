package negotiator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class Resources {
    private Resources() {}

    public static byte[] read(String path) {
        try (InputStream is = Resources.class.getResourceAsStream(path)) {
            if (is == null) throw new IllegalArgumentException("资源不存在: " + path);
            return is.readAllBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("读取资源失败: " + path, e);
        }
    }

    public static String utf8(String path) {
        return new String(read(path), StandardCharsets.UTF_8);
    }
}
