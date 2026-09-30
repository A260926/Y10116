package proto;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Extracts classpath web assets to a temp directory for the HTTP server. */
public final class Assets {
    private Assets() {}

    public static Path extract() throws IOException {
        Path dir = Files.createTempDirectory("proto-web");
        for (String name : new String[]{"index.html", "app.js", "styles.css",
                "draft.html", "draft.js"}) {
            try (InputStream in = Assets.class.getResourceAsStream("/web/" + name)) {
                if (in == null) throw new IOException("missing resource: " + name);
                Files.copy(in, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        dir.toFile().deleteOnExit();
        return dir;
    }
}
