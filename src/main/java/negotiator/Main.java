package negotiator;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * 入口：--port 指定端口；--db 指定 SQLite 文件（默认 ./negotiator.db）。
 * 首次启动时预置“TLS 风格分层协议”场景（v1/v2/v3）与一条 v3 明确降级到 v2 的已完成会话。
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        int port = 5246;
        String dbPath = "negotiator.db";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--db" -> dbPath = args[++i];
                default -> System.err.println("未知参数: " + args[i]);
            }
        }

        Service.CrashPolicy crashPolicy = "false".equalsIgnoreCase(
                System.getProperty("negotiator.exitOnCrash", "true"))
                ? sid -> { throw new Service.CrashSignal(sid); }
                : Service.CrashPolicy.realProcess();

        try (Db db = new Db(dbPath)) {
            Service service = new Service(db, crashPolicy);
            if (db.countScenarios() == 0) {
                Seed.install(service);
            }
            HttpServer http = new HttpServer(port, service);
            http.start();
            System.out.println("协议协商演练器已启动: http://localhost:" + http.port());
            System.out.println("SQLite 数据库: " + dbPath);
            Thread.currentThread().join();
        }
    }
}
