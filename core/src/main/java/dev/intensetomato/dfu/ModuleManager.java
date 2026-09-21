package dev.intensetomato.dfu;

import com.google.gson.Gson;
import dev.intensetomato.dfu.api.DfuContext;
import dev.intensetomato.dfu.api.DfuModule;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipFile;

public final class ModuleManager {
    private static final Gson GSON = new Gson();
    private final Map<String, LoadedModule> modules = new LinkedHashMap<>();
    private final List<URLClassLoader> retiredLoaders = new ArrayList<>();
    private final Path modulesDir = FabricLoader.getInstance().getConfigDir().resolve("diamondfire-utilities/modules");
    private final Path unpackDir = FabricLoader.getInstance().getConfigDir().resolve("diamondfire-utilities/runtime");
    private final String sessionId = UUID.randomUUID().toString();
    private final AtomicLong loadSequence = new AtomicLong();
    private boolean runtimePrepared;

    public void loadInstalled() {
        try {
            Files.createDirectories(modulesDir);
            prepareRuntime();
            try (var stream = Files.list(modulesDir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".dfu")).sorted().forEach(this::load);
            }
        } catch (Throwable e) {
            DiamondFireUtilities.local("§cModule scan failed: " + describe(e));
        }
    }

    public LoadResult load(Path dfu) {
        URLClassLoader loader = null;
        Path runtimeDir = null;
        try {
            Files.createDirectories(modulesDir);
            prepareRuntime();
            try (ZipFile zip = new ZipFile(dfu.toFile())) {
                var manifestEntry = zip.getEntry("module.json");
                var jarEntry = zip.getEntry("module.jar");
                if (manifestEntry == null || jarEntry == null) throw new IllegalStateException("Missing module.json or module.jar");
                Manifest manifest;
                try (var reader = new InputStreamReader(zip.getInputStream(manifestEntry), StandardCharsets.UTF_8)) { manifest = GSON.fromJson(reader, Manifest.class); }
                if (manifest == null || manifest.id == null || manifest.entrypoint == null) throw new IllegalStateException("Invalid module manifest");
                unload(manifest.id, false);
                String version = manifest.version == null || manifest.version.isBlank() ? "unknown" : safePart(manifest.version);
                Path sessionDir = unpackDir.resolve(sessionId);
                runtimeDir = sessionDir.resolve(safePart(manifest.id) + "-" + version + "-load-" + loadSequence.incrementAndGet());
                Files.createDirectories(runtimeDir);
                Path jar = runtimeDir.resolve("module.jar");
                try (var in = zip.getInputStream(jarEntry)) { Files.copy(in, jar); }
                loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, DiamondFireUtilities.class.getClassLoader());
                Class<?> type = Class.forName(manifest.entrypoint, true, loader);
                Object value = type.getDeclaredConstructor().newInstance();
                if (!(value instanceof DfuModule module)) throw new IllegalStateException("Entrypoint does not implement DfuModule");
                if (!manifest.id.equals(module.id())) throw new IllegalStateException("Entrypoint ID does not match manifest");
                module.onLoad(new DfuContext(FabricLoader.getInstance().getConfigDir().resolve("diamondfire-utilities/data").resolve(module.id())));
                modules.put(module.id(), new LoadedModule(module, loader, dfu, runtimeDir));
                return new LoadResult(true, module.name() + " loaded.");
            }
        } catch (Throwable e) {
            if (loader != null) try { loader.close(); } catch (Exception ignored) {}
            if (runtimeDir != null) try { deleteTree(runtimeDir); } catch (Exception ignored) {}
            return new LoadResult(false, "Couldn't load " + dfu.getFileName() + ": " + describe(e));
        }
    }

    public DeleteResult delete(String id) {
        LoadedModule loaded = modules.get(id);
        Path file = loaded != null ? loaded.packageFile : modulesDir.resolve(id + ".dfu");
        String name = loaded != null ? loaded.module.name() : id;
        boolean existed = loaded != null || Files.isRegularFile(file);
        if (!existed) return new DeleteResult(false, "No installed module named " + id + ".");
        try {
            unload(id, false);
            Files.deleteIfExists(file);
            return new DeleteResult(true, name + " deleted and unloaded.");
        } catch (Exception e) {
            return new DeleteResult(false, "Couldn't delete " + id + ": " + e.getMessage());
        }
    }

    private void unload(String id, boolean ignored) throws Exception {
        LoadedModule loaded = modules.remove(id);
        if (loaded == null) return;
        loaded.module.onUnload();
        retiredLoaders.add(loaded.loader);
    }

    private synchronized void prepareRuntime() throws Exception {
        if (runtimePrepared) return;
        Files.createDirectories(unpackDir);
        try (var stream = Files.list(unpackDir)) {
            for (Path path : stream.toList()) {
                try { deleteTree(path); } catch (Exception ignored) {}
            }
        }
        Files.createDirectories(unpackDir.resolve(sessionId));
        runtimePrepared = true;
    }

    private static String safePart(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    public Collection<DfuModule> modules() { return modules.values().stream().map(v -> v.module).toList(); }
    public Optional<DfuModule> get(String id) { LoadedModule value = modules.get(id); return value == null ? Optional.empty() : Optional.of(value.module); }
    public List<String> moduleIds() { return new ArrayList<>(modules.keySet()); }
    public Path packageFile(String id) { LoadedModule value = modules.get(id); return value == null ? modulesDir.resolve(id + ".dfu") : value.packageFile; }
    private record LoadedModule(DfuModule module, URLClassLoader loader, Path packageFile, Path runtimeDir) {}
    private static final class Manifest { String id; String version; String entrypoint; }
    public record LoadResult(boolean success, String message) {}
    public record DeleteResult(boolean success, String message) {}
}
