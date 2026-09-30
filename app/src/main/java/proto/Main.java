package proto;

import java.nio.file.Files;
import java.nio.file.Path;

public final class Main {
    public static void main(String[] args) throws Exception {
        int port = 5246;
        String dbPath = "proto-negotiator.db";
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--port")) port = Integer.parseInt(args[i + 1]);
            if (args[i].equals("--db")) dbPath = args[i + 1];
        }
        boolean fresh = !Files.exists(Path.of(dbPath));
        try (Storage storage = new Storage(dbPath)) {
            if (fresh) Seed.run(storage);
            HttpServer server = new HttpServer(port, storage);
            server.start();
            System.out.println("协议协商演练器 listening on http://localhost:" + server.boundPort());
            Thread.currentThread().join();
        }
    }
}
