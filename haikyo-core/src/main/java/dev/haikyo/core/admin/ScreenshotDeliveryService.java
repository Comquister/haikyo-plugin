/*
 * Haikyo Plugin - Server integration for the Haikyo Minecraft Client
 * Copyright (C) 2026 Haikyo Project Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package dev.haikyo.core.admin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.haikyo.core.util.Hex;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Handles temporary screenshot storage & delivery across all three Phase 7 modes:
 * <ol>
 *   <li>{@code builtin}: serves temporary 128-bit random token URLs ({@code /haikyo/s/<token>}) via
 *       an embedded JDK {@link HttpServer}, automatically returning 404 and deleting files after TTL
 *       (default 1 hour). Automatically falls back to {@code file} if no {@code publicUrl} is configured.</li>
 *   <li>{@code upload}: uploads the JPEG via HTTP {@code PUT} to a server-owner-configured endpoint
 *       (such as an S3 pre-signed/bucket URL or custom upload service) and returns the resulting link.</li>
 *   <li>{@code file}: writes the JPEG under {@code plugins/Haikyo/screens/<token>.jpg} and deletes
 *       expired files after the same TTL.</li>
 * </ol>
 */
public final class ScreenshotDeliveryService implements AutoCloseable {

    public enum Mode {
        BUILTIN,
        UPLOAD,
        FILE;

        public static Mode fromConfig(String raw) {
            if (raw == null) {
                return BUILTIN;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "upload" -> UPLOAD;
                case "file" -> FILE;
                default -> BUILTIN;
            };
        }
    }

    public record DeliveryConfig(
            Mode mode,
            int ttlSeconds,
            String builtinBindHost,
            int builtinPort,
            String builtinPublicUrl,
            String uploadUrl,
            String uploadAuthHeader
    ) {
        public static DeliveryConfig defaults() {
            return new DeliveryConfig(Mode.BUILTIN, 3600, "0.0.0.0", 8765, "", "", "");
        }
    }

    public record DeliveryResult(
            boolean success,
            Mode effectiveMode,
            String token,
            String location,
            Instant expiresAt,
            String error
    ) {
    }

    private record StoredScreen(Path file, String mimeType, Instant expiresAt) {
    }

    private final Path screensDir;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, StoredScreen> activeTokens = new ConcurrentHashMap<>();

    private volatile DeliveryConfig config;
    private volatile HttpServer httpServer;
    private volatile int boundPort = -1;

    public ScreenshotDeliveryService(Path screensDir, DeliveryConfig config, Clock clock) {
        this.screensDir = screensDir;
        this.config = config == null ? DeliveryConfig.defaults() : config;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public synchronized void reconfigure(DeliveryConfig newConfig) {
        this.config = newConfig == null ? DeliveryConfig.defaults() : newConfig;
        stopHttpServer();
        if (this.config.mode() == Mode.BUILTIN
                && this.config.builtinPublicUrl() != null
                && !this.config.builtinPublicUrl().isBlank()) {
            try {
                startHttpServer(this.config.builtinBindHost(), this.config.builtinPort());
            } catch (IOException ignored) {
                // If binding fails, delivery cleanly falls back to file mode
                stopHttpServer();
            }
        }
    }

    public int boundPort() {
        return boundPort;
    }

    public boolean isHttpServerRunning() {
        return httpServer != null;
    }

    /**
     * Stores/delivers a screenshot JPEG and returns either a temporary URL or local file path.
     */
    public DeliveryResult deliver(byte[] imageBytes, String mimeType) {
        if (imageBytes == null || imageBytes.length == 0) {
            return new DeliveryResult(false, Mode.FILE, "", "", clock.instant(), "Empty screenshot payload");
        }
        String safeMime = (mimeType == null || mimeType.isBlank()) ? "image/jpeg" : mimeType;
        String token = generate128BitToken();
        DeliveryConfig cfg = this.config;
        Instant expiresAt = clock.instant().plusSeconds(Math.max(10, cfg.ttlSeconds()));

        if (cfg.mode() == Mode.UPLOAD && cfg.uploadUrl() != null && !cfg.uploadUrl().isBlank()) {
            DeliveryResult uploadRes = tryUpload(cfg, token, imageBytes, safeMime, expiresAt);
            if (uploadRes.success()) {
                return uploadRes;
            }
            // Fall back to local file if upload fails so admin never loses the capture silently
        }

        // Write file on disk (used for both 'file' mode and 'builtin' backing store)
        Path targetFile = screensDir.resolve(token + ".jpg");
        try {
            Files.createDirectories(screensDir);
            Files.write(targetFile, imageBytes);
        } catch (IOException e) {
            return new DeliveryResult(false, Mode.FILE, token, "", expiresAt, "Failed to write screenshot: " + e.getMessage());
        }

        activeTokens.put(token, new StoredScreen(targetFile, safeMime, expiresAt));

        // Builtin mode requires both a configured public URL and a running HTTP server; otherwise fall back to file
        if (cfg.mode() == Mode.BUILTIN
                && cfg.builtinPublicUrl() != null
                && !cfg.builtinPublicUrl().isBlank()
                && httpServer != null) {
            String base = cfg.builtinPublicUrl().trim();
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            String url = base + "/haikyo/s/" + token;
            return new DeliveryResult(true, Mode.BUILTIN, token, url, expiresAt, null);
        }

        return new DeliveryResult(true, Mode.FILE, token, targetFile.toAbsolutePath().toString(), expiresAt, null);
    }

    /**
     * Deletes expired screenshot tokens and their files from disk.
     *
     * @return count of expired screenshots purged
     */
    public int purgeExpired() {
        Instant now = clock.instant();
        int purged = 0;
        Iterator<Map.Entry<String, StoredScreen>> it = activeTokens.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, StoredScreen> e = it.next();
            if (!now.isBefore(e.getValue().expiresAt())) {
                it.remove();
                deleteQuietly(e.getValue().file());
                purged++;
            }
        }
        return purged;
    }

    public int activeCount() {
        return activeTokens.size();
    }

    @Override
    public synchronized void close() {
        stopHttpServer();
    }

    private void startHttpServer(String bindHost, int port) throws IOException {
        String host = (bindHost == null || bindHost.isBlank()) ? "0.0.0.0" : bindHost.trim();
        HttpServer srv = HttpServer.create(new InetSocketAddress(host, Math.max(0, port)), 0);
        srv.createContext("/haikyo/s/", this::handleHttpRequest);
        srv.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        srv.start();
        this.httpServer = srv;
        this.boundPort = srv.getAddress().getPort();
    }

    private synchronized void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
            boundPort = -1;
        }
    }

    private void handleHttpRequest(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String prefix = "/haikyo/s/";
            if (path == null || !path.startsWith(prefix)) {
                sendText(exchange, 404, "Not Found");
                return;
            }
            String token = path.substring(prefix.length());
            if (!token.matches("[0-9a-f]{32}")) {
                sendText(exchange, 404, "Not Found");
                return;
            }
            StoredScreen screen = activeTokens.get(token);
            if (screen == null) {
                sendText(exchange, 404, "Not Found");
                return;
            }
            if (!clock.instant().isBefore(screen.expiresAt())) {
                activeTokens.remove(token);
                deleteQuietly(screen.file());
                sendText(exchange, 404, "Expired");
                return;
            }
            if (!Files.isRegularFile(screen.file())) {
                activeTokens.remove(token);
                sendText(exchange, 404, "Not Found");
                return;
            }
            byte[] bytes = Files.readAllBytes(screen.file());
            exchange.getResponseHeaders().set("Content-Type", screen.mimeType());
            exchange.getResponseHeaders().set("Cache-Control", "no-store, max-age=0");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } finally {
            exchange.close();
        }
    }

    private DeliveryResult tryUpload(DeliveryConfig cfg, String token, byte[] bytes, String mime, Instant expiresAt) {
        try {
            String targetUrl = cfg.uploadUrl().trim().replace("{token}", token);
            if (!cfg.uploadUrl().contains("{token}")) {
                targetUrl = targetUrl.endsWith("/") ? targetUrl + token + ".jpg" : targetUrl + "/" + token + ".jpg";
            }
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", mime)
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
            if (cfg.uploadAuthHeader() != null && !cfg.uploadAuthHeader().isBlank()) {
                req.header("Authorization", cfg.uploadAuthHeader().trim());
            }
            HttpResponse<String> resp = client.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                String body = resp.body() == null ? "" : resp.body().trim();
                String link = (body.startsWith("http://") || body.startsWith("https://")) ? body : targetUrl;
                return new DeliveryResult(true, Mode.UPLOAD, token, link, expiresAt, null);
            }
            return new DeliveryResult(false, Mode.UPLOAD, token, "", expiresAt, "Upload HTTP " + resp.statusCode());
        } catch (Exception e) {
            return new DeliveryResult(false, Mode.UPLOAD, token, "", expiresAt, e.getMessage());
        }
    }

    private String generate128BitToken() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        return Hex.encode(bytes);
    }

    private static void sendText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
        }
    }
}
