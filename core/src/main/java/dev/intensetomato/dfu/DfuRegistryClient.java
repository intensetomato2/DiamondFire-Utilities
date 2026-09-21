package dev.intensetomato.dfu;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipFile;

public final class DfuRegistryClient {
    private static final String RAW_ROOT = "https://raw.githubusercontent.com/intensetomato2/DFU-Modules/main/";
    private static final String REGISTRY_URL = RAW_ROOT + "registry.json";
    private static final Gson GSON = new Gson();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Path root = FabricLoader.getInstance().getConfigDir().resolve("diamondfire-utilities");
    private volatile Registry registry = new Registry();

    public CompletableFuture<ModuleManifest> manifest(String id) {
        String wanted = id.toLowerCase(Locale.ROOT);
        RegistryEntry entry = registry.modules.stream().filter(m -> wanted.equalsIgnoreCase(m.id)).findFirst().orElse(null);
        if (entry == null || !entry.verified || entry.manifest == null || entry.manifest.isBlank()) return CompletableFuture.completedFuture(null);
        return get(resolve(entry.manifest)).thenApply(body -> {
            ModuleManifest manifest = GSON.fromJson(body, ModuleManifest.class);
            if (manifest == null || manifest.id == null || !manifest.id.equalsIgnoreCase(entry.id)) return null;
            return manifest;
        });
    }

    public boolean verified(String id) {
        return registry.modules.stream().anyMatch(e -> e.verified && id.equalsIgnoreCase(e.id));
    }

    public String packageHash(Path path) {
        try { return sha256(Files.readAllBytes(path)); } catch (IOException e) { return ""; }
    }

    public CompletableFuture<Registry> refresh() {
        return get(REGISTRY_URL).thenApply(body -> {
            Registry next = GSON.fromJson(body, Registry.class);
            if (next == null || next.modules == null) throw new IllegalStateException("Invalid DFU module registry");
            registry = next;
            try {
                Files.createDirectories(root);
                Files.writeString(root.resolve("registry.json"), body);
            } catch (IOException ignored) {}
            return next;
        });
    }

    public void loadCache() {
        Path cache = root.resolve("registry.json");
        if (!Files.isRegularFile(cache)) return;
        try {
            Registry cached = GSON.fromJson(Files.readString(cache), Registry.class);
            if (cached != null && cached.modules != null) registry = cached;
        } catch (Exception ignored) {}
    }

    public List<RegistryEntry> entries() {
        return List.copyOf(registry.modules);
    }

    public List<String> moduleIds() {
        List<String> ids = new ArrayList<>();
        for (RegistryEntry entry : registry.modules) if (entry.id != null && !entry.id.isBlank()) ids.add(entry.id);
        return ids;
    }

    public CompletableFuture<InstallResult> install(String id) {
        String wanted = id.toLowerCase(Locale.ROOT);
        return refresh().thenCompose(r -> {
            RegistryEntry entry = r.modules.stream().filter(m -> wanted.equalsIgnoreCase(m.id)).findFirst().orElse(null);
            if (entry == null) return CompletableFuture.completedFuture(new InstallResult(false, "No verified DFU module named " + id + ".", null));
            if (!entry.verified) return CompletableFuture.completedFuture(new InstallResult(false, entry.name + " is not marked verified in the official registry.", null));
            if (entry.manifest == null || entry.manifest.isBlank()) return CompletableFuture.completedFuture(new InstallResult(false, "The registry entry has no manifest.", null));
            return get(resolve(entry.manifest)).thenCompose(body -> {
                ModuleManifest manifest = GSON.fromJson(body, ModuleManifest.class);
                if (manifest == null || manifest.id == null || !manifest.id.equalsIgnoreCase(entry.id)) return CompletableFuture.completedFuture(new InstallResult(false, "The module manifest does not match the registry entry.", null));
                if (manifest.download == null || manifest.download.isBlank() || manifest.sha256 == null || manifest.sha256.isBlank()) return CompletableFuture.completedFuture(new InstallResult(false, entry.name + " does not have a release package yet.", null));
                return downloadAndVerify(entry, manifest);
            });
        }).exceptionally(error -> new InstallResult(false, cleanError(error), null));
    }

    private CompletableFuture<InstallResult> downloadAndVerify(RegistryEntry entry, ModuleManifest manifest) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(manifest.download)).timeout(Duration.ofSeconds(30)).header("User-Agent", "DiamondFire-Utilities").GET().build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).thenApply(response -> {
            if (response.statusCode() / 100 != 2) return new InstallResult(false, "Download failed with HTTP " + response.statusCode() + ".", null);
            byte[] bytes = response.body();
            String actual = sha256(bytes);
            if (!actual.equalsIgnoreCase(manifest.sha256)) return new InstallResult(false, "SHA-256 verification failed. The module was not installed.", null);
            try {
                Path dir = root.resolve("modules");
                Files.createDirectories(dir);
                Path temp = dir.resolve(entry.id + ".dfu.tmp");
                Path target = dir.resolve(entry.id + ".dfu");
                Files.write(temp, bytes);
                validatePackage(temp, manifest);
                Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                return new InstallResult(true, entry.name + " " + manifest.version + " installed.", target);
            } catch (Exception e) {
                return new InstallResult(false, "The package was rejected: " + e.getMessage(), null);
            }
        });
    }

    private void validatePackage(Path file, ModuleManifest expected) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            var metadata = zip.getEntry("module.json");
            var payload = zip.getEntry("module.jar");
            if (metadata == null || payload == null) throw new IOException("module.json or module.jar is missing");
            try (var in = zip.getInputStream(metadata)) {
                ModuleManifest inside = GSON.fromJson(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), ModuleManifest.class);
                if (inside == null || inside.id == null || !inside.id.equalsIgnoreCase(expected.id)) throw new IOException("package module ID does not match manifest");
                if (inside.version == null || !inside.version.equals(expected.version)) throw new IOException("package version does not match manifest");
            }
        }
    }

    private CompletableFuture<String> get(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("User-Agent", "DiamondFire-Utilities").GET().build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("GitHub returned HTTP " + response.statusCode());
            return response.body();
        });
    }

    private static String resolve(String value) {
        if (value.startsWith("https://") || value.startsWith("http://")) return value;
        return RAW_ROOT + value.replaceFirst("^/+", "");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cleanError(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    public static final class Registry {
        public int format;
        public List<RegistryEntry> modules = new ArrayList<>();
    }

    public static final class RegistryEntry {
        public String id;
        public String name;
        public String game;
        public String author;
        public String description;
        public String version;
        public boolean verified;
        public String manifest;
    }

    public static final class ModuleManifest {
        public String id;
        public String name;
        public String game;
        public String author;
        public String version;
        public String description;
        public String entrypoint;
        public String dfu;
        public String download;
        public String sha256;
    }

    public record InstallResult(boolean success, String message, Path path) {}
}
