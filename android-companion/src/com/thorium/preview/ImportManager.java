package com.thorium.preview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;

import com.thorium.lucent.metadata.WallpaperAccent;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZFileOptions;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Conservative, non-blocking Downloads importer for the THOR library.
 *
 * A file is moved only after its payload validates as a supported ROM. Ambiguous
 * disc/archive formats are deliberately left in Downloads. Metadata and the
 * registry are written atomically so a killed service cannot corrupt the library.
 */
final class ImportManager {
    private static final String TAG = "ThorImporter";
    private static final String USER_AGENT = "Lucent-Importer/1.0";
    private static final long MAX_ARCHIVE_ENTRY_BYTES = 128L * 1024L * 1024L * 1024L;
    private static final long ARCHIVE_INSPECTION_BYTES = 8L * 1024L * 1024L;
    private static final SevenZFileOptions SEVEN_Z_OPTIONS = SevenZFileOptions.builder()
            .withMaxMemoryLimitInKb(448 * 1024)
            .build();
    private static final File DOWNLOADS = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS);
    private static final File GAMES = new File(Environment.getExternalStorageDirectory(), "Games");
    private static final File PEGASUS = new File(Environment.getExternalStorageDirectory(),
            "pegasus-frontend");
    private static final File PEGASUS_CONFIG = new File(Environment.getExternalStorageDirectory(),
            "Android/data/org.pegasus_frontend.android/files/pegasus-frontend");
    private static final File LUCENT_CONFIG = new File(Environment.getExternalStorageDirectory(),
            "Android/data/com.thorium.preview/files/pegasus-frontend");
    private static final File REGISTRY = new File(PEGASUS, "thorium-imports.json");
    private static final String BACKGROUND_SOURCE_WALLPAPER = "audited-wallpaper";
    private static final String BACKGROUND_SOURCE_LAUNCHBOX = "launchbox-fanart-background";
    private static final String BACKGROUND_SOURCE_OFFICIAL = "official-gallery-image";
    private static final String BACKGROUND_SOURCE_SCREENSHOT = "exact-gameplay-screenshot";
    private static final String BACKGROUND_TRANSFORM = "center-crop-1920x1080-no-stretch";
    private static final Map<String, Boolean> WALLPAPER_QUALITY_CACHE =
            new ConcurrentHashMap<>();
    private static volatile boolean wallpaperQualityCacheLoaded;
    private static final File AUTO_METADATA = new File(new File(PEGASUS_CONFIG, "metafiles"),
            "99-lucent-auto-import.metadata.pegasus.txt");
    private static final File LUCENT_AUTO_METADATA = new File(new File(LUCENT_CONFIG, "metafiles"),
            "99-lucent-auto-import.metadata.pegasus.txt");
    private static final File LEGACY_AUTO_METADATA = new File(PEGASUS,
            "99-lucent-auto-import.metadata.pegasus.txt");
    private static final File THORIUM_LEGACY_AUTO_METADATA = new File(PEGASUS,
            "99-thorium-auto-import.metadata.pegasus.txt");
    private static final File THORIUM_LEGACY_CONFIG_METADATA = new File(
            new File(PEGASUS_CONFIG, "metafiles"),
            "99-thorium-auto-import.metadata.pegasus.txt");
    private static final long RESCAN_THROTTLE_MS = 45_000L;
    private static final long MISS_RETRY_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]+\\.(?:png|jpg|jpeg))\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NINTENDO_TITLE = Pattern.compile(
            "<title[^>]*>(.*?) for Nintendo Switch(?:[^<]*)</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern NINTENDO_PRODUCT_ASSET = Pattern.compile(
            "store/software/switch/(\\d{14})/([a-f0-9]{40,64})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NINTENDO_SQUARE_COVER = Pattern.compile(
            "productImage\\([^)]*square[^)]*\\).*?\"url\":\"(https://assets\\.nintendo\\.com/image/fetch/[^\"]+)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern NINTENDO_GALLERY_ASSET = Pattern.compile(
            "\\\"publicId\\\":\\\"(/?store/software/switch/[^\\\"]+)\\\",\\\"resourceType\\\":\\\"(video|image)\\\"",
            Pattern.CASE_INSENSITIVE);
    private static final byte[] GB_LOGO = new byte[]{
            (byte)0xCE,(byte)0xED,0x66,0x66,(byte)0xCC,0x0D,0x00,0x0B,0x03,0x73,0x00,(byte)0x83,
            0x00,0x0C,0x00,0x0D,0x00,0x08,0x11,0x1F,(byte)0x88,(byte)0x89,0x00,0x0E,(byte)0xDC,
            (byte)0xCC,0x6E,(byte)0xE6,(byte)0xDD,(byte)0xDD,(byte)0xD9,(byte)0x99,(byte)0xBB,
            (byte)0xBB,0x67,0x63,0x6E,0x0E,(byte)0xEC,(byte)0xCC,(byte)0xDD,(byte)0xDC,(byte)0x99,
            (byte)0x9F,(byte)0xBB,(byte)0xB9,0x33,0x3E
    };

    private final Context context;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object statusLock = new Object();
    private volatile long lastScanStarted;
    private volatile String lastDownloadFingerprint = "";
    private JSONObject status = idleStatus();
    private volatile Map<String, GameRankingsRecord> gamerankingsIndex;
    private volatile Map<String, GameRankingsRecord> gamerankingsAliasIndex;
    private volatile Map<String, MobyGamesRecord> mobygamesIndex;
    private volatile Map<String, MobyGamesRecord> mobygamesAliasIndex;
    private volatile Map<String, BundledGameRecord> bundledGameIndex;

    ImportManager(Context context) {
        this.context = context.getApplicationContext();
    }

    void startScan() {
        startScan(false, false);
    }

    void startInitialScan() {
        if (!ThemeInstaller.hasStorageAccess(context)) {
            setStatus("permission", 0.0,
                    "Grant library access to begin the automatic first scan",
                    Collections.emptyList(), 0, false);
            return;
        }
        // App launch is deliberately lightweight: only Downloads are checked.
        // A full-library artwork/score audit belongs on the desktop maintenance
        // workflow, not in the handheld's startup path.
        startScan(false, false);
    }

    /** User-requested maintenance pass. Unlike the background scan, this is a
     * full storage discovery and is never suppressed by the fingerprint/time
     * throttle. The running guard still prevents two destructive writers from
     * touching the registry at the same time. */
    void startManualScan() {
        startScan(true, true);
    }

    private void startScan(boolean fullDiscovery, boolean force) {
        long now = SystemClock.elapsedRealtime();
        String fingerprint = downloadFingerprint();
        if (running.get() || (!force && now - lastScanStarted < RESCAN_THROTTLE_MS &&
                fingerprint.equals(lastDownloadFingerprint))) return;
        if (!running.compareAndSet(false, true)) return;
        lastScanStarted = now;
        lastDownloadFingerprint = fingerprint;
        setStatus("scanning", 0.01, "Scanning internal and SD Downloads for verified games…",
                Collections.emptyList(), 0, false);
        Thread worker = new Thread(() -> {
            try {
                runScan(fullDiscovery);
            } catch (Throwable error) {
                Log.e(TAG, "Import failed", error);
                setStatus("error", 1.0, "Import stopped safely: " + shortError(error),
                        Collections.emptyList(), 0, false);
            } finally {
                running.set(false);
            }
        }, "thor-library-import");
        worker.setDaemon(true);
        worker.start();
    }

    String statusJson() {
        synchronized (statusLock) {
            return status.toString();
        }
    }

    /** Atomically acknowledge the one frontend rebuild requested by a
     * completed import. Clearing the bit before relaunch prevents the fresh
     * QML instance from entering a restart loop when it reads status. */
    boolean consumeReloadRequest() {
        synchronized (statusLock) {
            if (!status.optBoolean("needsReload", false)) return false;
            try {
                status.put("needsReload", false);
                status.put("message", "New games indexed • refreshing Lucent library…");
                status.put("updatedAt", System.currentTimeMillis());
            } catch (Exception ignored) {}
            return true;
        }
    }

    Set<String> activeSystemFolders() {
        Set<String> systems = new LinkedHashSet<>();
        JSONArray registry = readRegistry();
        for (int index = 0; index < registry.length(); index++) {
            JSONObject row = registry.optJSONObject(index);
            if (row == null || row.optBoolean("archived", false) &&
                    !row.optBoolean("forceInclude", false)) continue;
            String folder = row.optString("system");
            if (GameSystems.byFolder(folder) != null) systems.add(folder);
        }
        return systems;
    }

    String archiveJson() {
        JSONObject response = new JSONObject();
        JSONArray archived = new JSONArray();
        JSONArray registry = readRegistry();
        try {
            for (int i = 0; i < registry.length(); i++) {
                JSONObject row = registry.optJSONObject(i);
                if (row == null || !row.optBoolean("archived", false) ||
                        row.optBoolean("forceInclude", false)) continue;
                JSONObject item = new JSONObject();
                item.put("id", row.optString("sourceIdentity"));
                item.put("title", row.optString("title"));
                GameSystems.SystemDef system = GameSystems.byFolder(row.optString("system"));
                item.put("system", system == null ? row.optString("system") : system.collection);
                item.put("reason", "Missing exact box art");
                archived.put(item);
            }
            response.put("count", archived.length());
            response.put("games", archived);
        } catch (Exception ignored) {}
        return response.toString();
    }

    synchronized boolean includeArchived(String identity) {
        if (identity == null || identity.isEmpty()) return false;
        JSONArray registry = readRegistry();
        boolean changed = false;
        for (int i = 0; i < registry.length(); i++) {
            JSONObject row = registry.optJSONObject(i);
            if (row == null || !identity.equals(row.optString("sourceIdentity"))) continue;
            try {
                row.put("forceInclude", true);
                changed = true;
            } catch (Exception ignored) {}
            break;
        }
        if (!changed) return false;
        try {
            writeJsonAtomic(REGISTRY, registry);
            writeMetadata(registry);
            return true;
        } catch (Exception error) {
            Log.e(TAG, "Unable to include archived game", error);
            return false;
        }
    }

    synchronized boolean renameGame(String identity, String requestedTitle) {
        String title = cleanTitle(requestedTitle == null ? "" : requestedTitle);
        if (identity == null || identity.isEmpty() || title.isEmpty()) return false;
        JSONArray registry = readRegistry();
        boolean changed = false;
        File originalRom = null;
        File renamedRom = null;
        for (int i = 0; i < registry.length(); i++) {
            JSONObject row = registry.optJSONObject(i);
            if (row == null || !identity.equals(row.optString("sourceIdentity"))) continue;
            try {
                originalRom = new File(row.optString("file"));
                String extension = extension(originalRom.getName());
                renamedRom = new File(originalRom.getParentFile(), safeFilename(title) +
                        (extension.isEmpty() ? "" : "." + extension));
                if (!originalRom.equals(renamedRom)) {
                    if (renamedRom.exists() || !originalRom.renameTo(renamedRom)) return false;
                    row.put("file", renamedRom.getAbsolutePath());
                }
                row.put("title", title);
                changed = true;
            } catch (Exception ignored) {}
            break;
        }
        if (!changed) return false;
        try {
            writeJsonAtomic(REGISTRY, registry);
            writeMetadata(registry);
            return true;
        } catch (Exception error) {
            if (originalRom != null && renamedRom != null && renamedRom.isFile() &&
                    !originalRom.isFile()) renamedRom.renameTo(originalRom);
            Log.e(TAG, "Unable to rename game", error);
            return false;
        }
    }

    synchronized boolean deleteGame(String identity) {
        if (identity == null || identity.isEmpty()) return false;
        JSONArray registry = readRegistry();
        int found = -1;
        JSONObject row = null;
        for (int i = 0; i < registry.length(); i++) {
            JSONObject candidate = registry.optJSONObject(i);
            if (candidate != null && identity.equals(candidate.optString("sourceIdentity"))) {
                found = i;
                row = candidate;
                break;
            }
        }
        if (found < 0 || row == null) return false;
        File rom = new File(row.optString("file"));
        if (!rom.isFile()) return false;
        File volume = storageVolumeRoot(rom);
        // Stage the file on the same volume first, so a metadata write failure
        // can still restore it atomically. The staged copy is permanently
        // deleted only after Pegasus's registry and metadata commit succeeds.
        File trash = new File(new File(volume, ".LucentTrash"),
                row.optString("system", "unknown"));
        if (!trash.mkdirs() && !trash.isDirectory()) return false;
        File target = uniqueTrashTarget(trash, rom.getName());
        if (!rom.renameTo(target)) return false;
        try {
            registry.remove(found);
            writeJsonAtomic(REGISTRY, registry);
            writeMetadata(registry);
            if (target.delete()) return true;

            // A filesystem refusal must not silently turn a permanent-delete
            // request into an undeclared recoverable trash operation.
            if (!target.renameTo(rom)) return false;
            registry.put(row);
            writeJsonAtomic(REGISTRY, registry);
            writeMetadata(registry);
            return false;
        } catch (Exception error) {
            // Restore the ROM if metadata could not be committed. A failed UI
            // operation must never leave a game silently detached from Pegasus.
            target.renameTo(rom);
            Log.e(TAG, "Unable to permanently delete game", error);
            return false;
        }
    }

    private static File storageVolumeRoot(File file) {
        String path = file.getAbsolutePath();
        // /storage/emulated is a framework mount, not the writable user
        // volume. Trying to create /storage/emulated/.LucentTrash made every
        // delete of an internally stored game fail. The actual shared-storage
        // root is /storage/emulated/0 (Environment's external directory).
        String internal = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (path.equals(internal) || path.startsWith(internal + File.separator))
            return Environment.getExternalStorageDirectory();
        if (path.startsWith("/storage/")) {
            int slash = path.indexOf('/', "/storage/".length());
            if (slash > 0) return new File(path.substring(0, slash));
        }
        return Environment.getExternalStorageDirectory();
    }

    private static File uniqueTrashTarget(File directory, String name) {
        File target = new File(directory, name);
        if (!target.exists()) return target;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        return new File(directory, base + "-" + System.currentTimeMillis() + extension);
    }

    private void runScan(boolean fullDiscovery) throws Exception {
        PEGASUS.mkdirs();
        // Thorium's retired combined metafile can coexist with Lucent's
        // per-system files after an in-place upgrade. Pegasus then exposes a
        // second, stale copy of every imported game; most of those old rows do
        // not contain video paths. Remove only our generated legacy files.
        THORIUM_LEGACY_AUTO_METADATA.delete();
        THORIUM_LEGACY_CONFIG_METADATA.delete();
        File metadataParent = AUTO_METADATA.getParentFile();
        if (metadataParent != null) metadataParent.mkdirs();
        File lucentMetadataParent = LUCENT_AUTO_METADATA.getParentFile();
        if (lucentMetadataParent != null) lucentMetadataParent.mkdirs();
        GAMES.mkdirs();
        File mediaRoot = mediaRoot();
        File cacheRoot = new File(PEGASUS, ".thorium-import-cache");
        cacheRoot.mkdirs();

        setStatus("scanning", 0.03, "Scanning internal and SD Downloads for verified games…",
                Collections.emptyList(), 0, false);
        List<Candidate> candidates = discoverCandidates(cacheRoot);
        if (fullDiscovery) {
            setStatus("discovering", 0.06,
                    "Finding existing game libraries on internal and removable storage…",
                    Collections.emptyList(), 0, false);
            candidates.addAll(discoverExistingCandidates());
        }
        List<String> titles = new ArrayList<>();
        for (Candidate candidate : candidates) titles.add(candidate.title);

        if (!candidates.isEmpty()) {
            setStatus("identified", 0.12,
                    candidates.size() == 1 ? "1 new game identified" :
                            candidates.size() + " new games identified",
                    titles, 0, false);
        }

        JSONArray registry = readRegistry();
        Set<String> registeredSources = registeredSources(registry);
        List<ImportedGame> imported = new ArrayList<>();
        Map<File, Integer> archiveTotals = new HashMap<>();
        Map<File, Integer> archiveSuccesses = new HashMap<>();
        for (Candidate candidate : candidates) {
            if (candidate.zipEntry != null)
                archiveTotals.put(candidate.source, archiveTotals.getOrDefault(candidate.source, 0) + 1);
        }
        int index = 0;
        for (Candidate candidate : candidates) {
            index++;
            double base = 0.12 + (0.38 * (index - 1) / Math.max(1, candidates.size()));
            setStatus("transferring", base, "Adding " + candidate.title + "…",
                    titles, imported.size(), false);
            if (registeredSources.contains(candidate.identity)) {
                JSONObject saved = registeredGame(registry, candidate.identity);
                // A previous scan may have been interrupted after the durable
                // registry write but before media enrichment and metadata
                // generation. Resume that exact ROM instead of permanently
                // treating the half-finished row as complete.
                if (saved != null && saved.optInt("enrichmentVersion", 0) < 2) {
                    ImportedGame pending = ImportedGame.fromJson(saved);
                    if (pending != null) {
                        enrichBundledMetadata(pending);
                        imported.add(pending);
                    }
                }
                if (candidate.zipEntry != null)
                    archiveSuccesses.put(candidate.source,
                            archiveSuccesses.getOrDefault(candidate.source, 0) + 1);
                continue;
            }
            ImportedGame game = importCandidate(candidate, mediaRoot, cacheRoot);
            if (game != null) {
                // Text metadata is packaged with Lucent and becomes visible in
                // Pegasus before any slower media or network work begins.
                enrichBundledMetadata(game);
                imported.add(game);
                registry.put(game.toJson());
                writeJsonAtomic(REGISTRY, registry);
                // Pegasus must never have an empty library merely because a
                // long artwork/video pass is interrupted. Publish the ROM
                // record immediately, then enrich it in place below.
                writeMetadata(registry);
                // Only now that the registry row and metadata are durable is
                // the plain-file Downloads source safe to remove. In-place
                // games are their own source and must never be deleted.
                if (candidate.zipEntry == null && !candidate.inPlace &&
                        !candidate.source.equals(game.rom))
                    candidate.source.delete();
                if (candidate.zipEntry != null)
                    archiveSuccesses.put(candidate.source,
                            archiveSuccesses.getOrDefault(candidate.source, 0) + 1);
            }
        }
        for (Map.Entry<File, Integer> archive : archiveTotals.entrySet()) {
            if (archive.getValue().equals(archiveSuccesses.get(archive.getKey())))
                archive.getKey().delete();
        }

        // Pending rows must resume even when the one-time full filesystem
        // discovery flag is already set. Otherwise an interrupted first scan
        // has no candidates on later launches and can never finish media.
        Set<String> queuedIdentities = new HashSet<>();
        for (ImportedGame game : imported) queuedIdentities.add(game.sourceIdentity);
        for (int rowIndex = 0; rowIndex < registry.length(); rowIndex++) {
            JSONObject row = registry.optJSONObject(rowIndex);
            if (row == null || queuedIdentities.contains(row.optString("sourceIdentity"))) continue;
            ImportedGame pending = ImportedGame.fromJson(row);
            if (pending != null && row.optInt("enrichmentVersion", 0) < 2) {
                enrichBundledMetadata(pending);
                imported.add(pending);
                queuedIdentities.add(pending.sourceIdentity);
            }
        }

        // Rebuild from the durable registry on every scan. This is the
        // recovery path for versions that could save registry rows without
        // ever regenerating 99-thorium-auto-import.metadata.pegasus.txt.
        writeMetadata(registry);

        if (!imported.isEmpty()) {
            setStatus("artwork", 0.55, "Downloading exact box art…", titles,
                    imported.size(), false);
            for (ImportedGame game : imported) {
                enrichBoxArt(game, cacheRoot, mediaRoot);
                enrichBackground(game, cacheRoot, mediaRoot);
            }

            setStatus("video", 0.68, "Finding video previews…", titles,
                    imported.size(), false);
            for (ImportedGame game : imported) enrichVideo(game, cacheRoot, mediaRoot);

            setStatus("scores", 0.80,
                    "Matching ratings, releases, developers, and publishers…", titles,
                    imported.size(), false);
            for (ImportedGame game : imported) {
                // Packaged metadata is authoritative and instant. Online
                // lookup remains a fallback only for titles not present in the
                // bundled catalog, never a startup-wide scraping pass.
                if (!hasCompleteBundledMetadata(game)) enrichMetacritic(game, cacheRoot);
                enrichGameRankings(game);
                enrichMobyGames(game);
                calculateCriticComposite(game);
                // A verified ROM is always a game. Media can continue filling
                // in later; it must never make a discovered title disappear.
                game.archived = false;
                game.enriched = true;

                // Commit every completed game independently. If Android
                // stops the service, the next run resumes only unfinished
                // entries and Pegasus retains everything completed so far.
                registry = mergeRegistry(readRegistry(), Collections.singletonList(game));
                writeJsonAtomic(REGISTRY, registry);
                writeMetadata(registry);
            }

            // Replace provisional registry rows with enriched records.
            registry = mergeRegistry(readRegistry(), imported);
            writeJsonAtomic(REGISTRY, registry);
            setStatus("writing", 0.90, "Updating Lucent library…", titles,
                    imported.size(), false);
            writeMetadata(registry);
        }

        // Keep provenance current for the games processed in this scan. This
        // is a local manifest write; it does not search or download old media.
        writeBackgroundProvenanceManifest(readRegistry(), mediaRoot);

        String message;
        boolean reload = !imported.isEmpty();
        if (!imported.isEmpty()) {
            message = imported.size() + (imported.size() == 1 ? " game added" : " games added");
            message += " • reload Lucent when convenient";
        } else {
            message = "Library scan complete • no new games";
        }
        setStatus("complete", 1.0, message, titles, imported.size(), reload);
    }

    private List<Candidate> discoverCandidates(File cacheRoot) throws Exception {
        List<Candidate> found = new ArrayList<>();
        List<File> files = new ArrayList<>();
        for (File downloadRoot : downloadRoots()) {
            Log.i(TAG, "Scanning download root " + downloadRoot.getAbsolutePath());
            File[] entries = downloadRoot.listFiles();
            if (entries != null) Collections.addAll(files, entries);
        }
        files.sort(Comparator.comparing(File::getAbsolutePath, String.CASE_INSENSITIVE_ORDER));
        for (File file : files) {
            if (!file.isFile() || file.getName().startsWith(".") || isPartial(file.getName())) continue;
            String extension = extension(file.getName());
            if ("zip".equals(extension) || "7z".equals(extension)) {
                if (isSwitchSupplementalName(file.getName())) continue;
                found.addAll("zip".equals(extension) ? inspectZip(file, cacheRoot) :
                        inspectSevenZip(file, cacheRoot));
                continue;
            }
            if (isSwitchExtension(extension) && isSwitchSupplementalName(file.getAbsolutePath()))
                continue;
            GameSystems.SystemDef system = identify(file, extension);
            if (system == null) continue;
            String title = cleanTitle(stem(file.getName()));
            found.add(new Candidate(file, null, system, title,
                    canonical(file.getAbsolutePath()) + ":" + file.length()));
        }
        return found;
    }

    private List<Candidate> discoverExistingCandidates() {
        List<Candidate> found = new ArrayList<>();
        Set<String> referenced = existingMetadataPaths();
        Set<String> knownGames = existingMetadataGameIdentities();
        Log.i(TAG, "Full discovery baseline: " + metadataFiles().size() +
                " metadata files, " + referenced.size() + " ROM paths, " +
                knownGames.size() + " system/title identities");
        Set<String> visited = new HashSet<>();
        List<File> roots = libraryRoots();
        int[] inspected = new int[]{0};
        for (File root : roots)
            scanLibraryRoot(root, 0, found, referenced, knownGames, visited, inspected);
        return found;
    }

    private void scanLibraryRoot(File directory, int depth, List<Candidate> found,
                                 Set<String> referenced, Set<String> knownGames,
                                 Set<String> visited, int[] inspected) {
        if (directory == null || depth > 12 || inspected[0] > 250000) return;
        String canonical = canonical(directory.getAbsolutePath());
        if (!visited.add(canonical) || shouldSkipLibraryDirectory(directory)) return;
        File[] entries = directory.listFiles();
        if (entries == null) return;
        Arrays.sort(entries, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File entry : entries) {
            if (inspected[0]++ > 250000) return;
            if (entry.isDirectory()) {
                scanLibraryRoot(entry, depth + 1, found, referenced, knownGames, visited, inspected);
                continue;
            }
            if (!entry.isFile() || entry.getName().startsWith(".") || isPartial(entry.getName()))
                continue;
            String path = canonical(entry.getAbsolutePath());
            if (referenced.contains(path)) continue;
            String ext = extension(entry.getName());
            if (isSwitchExtension(ext) && isSwitchSupplementalName(entry.getAbsolutePath()))
                continue;
            GameSystems.SystemDef system = GameSystems.byPath(entry);
            if (system == null) system = identify(entry, ext);
            if (system == null) system = GameSystems.unambiguousByExtension(ext);
            if (system == null) continue;
            String title = cleanTitle(stem(entry.getName()));
            // Storage migrations and backups frequently change an absolute
            // path without changing the actual game. Pegasus already owns the
            // canonical system/title identity, so do not generate a second
            // entry merely because another copy exists somewhere else.
            Set<String> candidateIdentities = new HashSet<>();
            addGameIdentities(candidateIdentities, system.folder, title);
            addGameIdentities(candidateIdentities, system.folder, stem(entry.getName()));
            File parent = entry.getParentFile();
            if (parent != null && !GameSystems.isKnownSystemDirectory(parent))
                addGameIdentities(candidateIdentities, system.folder, parent.getName());
            if (!Collections.disjoint(knownGames, candidateIdentities)) continue;
            knownGames.addAll(candidateIdentities);
            found.add(new Candidate(entry, null, system, title,
                    path + ":" + entry.length(), true));
        }
    }

    private static List<File> libraryRoots() {
        LinkedHashMap<String, File> roots = new LinkedHashMap<>();
        List<File> volumes = new ArrayList<>();
        volumes.add(Environment.getExternalStorageDirectory());
        File[] storage = new File("/storage").listFiles();
        if (storage != null) {
            for (File candidate : storage) {
                String name = candidate.getName();
                if (candidate.isDirectory() && !"emulated".equals(name) && !"self".equals(name))
                    volumes.add(candidate);
            }
        }
        for (File volume : volumes) {
            // First-run discovery must not depend on user folder naming. Scan
            // each readable storage volume itself; the recursive scanner only
            // accepts verified ROM formats and skips Android/app/media trees.
            if (volume.isDirectory() && volume.canRead())
                roots.put(canonical(volume.getAbsolutePath()), volume);
            for (String common : new String[]{"Games", "games", "ROMs", "Roms", "roms",
                    "Emulation", "emulation", "RetroArch", "retropie", "recalbox", "batocera"}) {
                File root = new File(volume, common);
                if (root.isDirectory() && root.canRead()) roots.put(canonical(root.getAbsolutePath()), root);
            }
            File[] children = volume.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child.isDirectory() && child.canRead() && GameSystems.isKnownSystemDirectory(child))
                        roots.put(canonical(child.getAbsolutePath()), child);
                }
            }
        }
        return new ArrayList<>(roots.values());
    }

    private static boolean shouldSkipLibraryDirectory(File directory) {
        String name = directory.getName().toLowerCase(Locale.US);
        return name.startsWith(".") || name.contains("backup") || name.contains("quarantine") ||
                name.contains("trash") ||
                "saves".equals(name) || "save".equals(name) ||
                "update".equals(name) || "updates".equals(name) ||
                "dlc".equals(name) || "add-on".equals(name) || "add-ons".equals(name) ||
                "android".equals(name) || "download".equals(name) ||
                "downloads".equals(name) || "pegasusmedia".equals(name) ||
                "pegasus-frontend".equals(name) || "dcim".equals(name) ||
                "pictures".equals(name) || "movies".equals(name) || "music".equals(name) ||
                "alarms".equals(name) || "audiobooks".equals(name) ||
                "notifications".equals(name) || "podcasts".equals(name) ||
                "recordings".equals(name) || "ringtones".equals(name) ||
                "pegasusbackups".equals(name) || "pegasusquarantine".equals(name) ||
                "thorbackups".equals(name) || "dolphinforhandheld".equals(name) ||
                "retroarch".equals(name) || "winlator".equals(name) ||
                "citra-emu".equals(name) || "azahar".equals(name);
    }

    private static Set<String> existingMetadataPaths() {
        Set<String> paths = new HashSet<>();
        for (File file : metadataFiles()) {
            try {
                for (String stanza : splitStanzas(readText(file))) {
                    String path = field(stanza, "file");
                    if (!path.isEmpty()) paths.add(canonical(path));
                }
            } catch (Exception ignored) {}
        }
        return paths;
    }

    private static Set<String> existingMetadataGameIdentities() {
        Set<String> identities = new HashSet<>();
        for (File file : metadataFiles()) {
            try {
                for (String stanza : splitStanzas(readText(file))) {
                    String title = field(stanza, "game");
                    String path = field(stanza, "file");
                    if (title.isEmpty() || path.isEmpty()) continue;
                    GameSystems.SystemDef system = systemFromRomPath(path);
                    if (system != null) {
                        addGameIdentities(identities, system.folder, title);
                        File rom = new File(path);
                        addGameIdentities(identities, system.folder, stem(rom.getName()));
                        File parent = rom.getParentFile();
                        if (parent != null && !GameSystems.isKnownSystemDirectory(parent))
                            addGameIdentities(identities, system.folder, parent.getName());
                    }
                }
            } catch (Exception ignored) {}
        }
        return identities;
    }

    private static void addGameIdentities(Set<String> identities, String folder, String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) return;
        identities.add(folder + "|" + normalized);
        String alias = scoreAlias(normalized);
        if (!alias.isEmpty()) identities.add(folder + "|" + alias);
    }

    private static List<File> metadataFiles() {
        LinkedHashMap<String, File> files = new LinkedHashMap<>();
        List<File> roots = Arrays.asList(PEGASUS,
                new File(PEGASUS_CONFIG, "metafiles"),
                new File(LUCENT_CONFIG, "metafiles"));
        for (File root : roots) {
            File[] metadata = root.listFiles((dir, name) ->
                    name.endsWith(".metadata.pegasus.txt") ||
                            name.equals("metadata.pegasus.txt"));
            if (metadata == null) continue;
            for (File file : metadata)
                files.put(canonical(file.getAbsolutePath()), file);
        }
        return new ArrayList<>(files.values());
    }

    private List<Candidate> inspectZip(File archive, File cacheRoot) {
        List<Candidate> found = new ArrayList<>();
        File staging = new File(cacheRoot, "zip-inspect");
        staging.mkdirs();
        try (ZipFile zip = new ZipFile(archive)) {
            zip.stream().filter(entry -> !entry.isDirectory()).forEach(entry -> {
                String ext = extension(entry.getName());
                if (isSwitchExtension(ext) && (isSwitchSupplementalName(archive.getName()) ||
                        isSwitchSupplementalName(entry.getName()))) return;
                if (GameSystems.unambiguousByExtension(ext) == null &&
                        !isPotentialAmbiguous(ext)) return;
                File temporary = new File(staging, sha1(archive.getAbsolutePath() + "!" + entry.getName()) +
                        "." + ext);
                try {
                    extract(zip, entry, temporary, ARCHIVE_INSPECTION_BYTES);
                    GameSystems.SystemDef system = identify(temporary, ext);
                    if (system != null) {
                        String title = cleanTitle(stem(new File(entry.getName()).getName()));
                        found.add(new Candidate(archive, entry.getName(), system, title,
                                canonical(archive.getAbsolutePath()) + "!" + entry.getName() +
                                        ":" + entry.getCrc()));
                    }
                } catch (Exception ignored) {
                    // Malformed and encrypted archives are ignored without touching Downloads.
                } finally {
                    temporary.delete();
                }
            });
        } catch (Exception ignored) {
        }
        return found;
    }

    /** Inspect every plausible payload in a 7z archive rather than trusting
     * the archive filename. This mirrors ZIP handling: Lucent only accepts an
     * entry after its extracted bytes validate as a ROM for a known system. */
    private List<Candidate> inspectSevenZip(File archive, File cacheRoot) {
        List<Candidate> found = new ArrayList<>();
        File staging = new File(cacheRoot, "7z-inspect");
        staging.mkdirs();
        try (SevenZFile sevenZ = new SevenZFile(archive, SEVEN_Z_OPTIONS)) {
            SevenZArchiveEntry entry;
            while ((entry = sevenZ.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.hasStream() || entry.getSize() <= 0 ||
                        entry.getSize() > MAX_ARCHIVE_ENTRY_BYTES) continue;
                String ext = extension(entry.getName());
                if (isSwitchExtension(ext) && (isSwitchSupplementalName(archive.getName()) ||
                        isSwitchSupplementalName(entry.getName()))) continue;
                if (GameSystems.unambiguousByExtension(ext) == null &&
                        !isPotentialAmbiguous(ext)) continue;
                File temporary = new File(staging,
                        sha1(archive.getAbsolutePath() + "!" + entry.getName()) + "." + ext);
                try {
                    extract(sevenZ, entry, temporary, ARCHIVE_INSPECTION_BYTES);
                    GameSystems.SystemDef system = identify(temporary, ext);
                    if (system != null) {
                        String title = cleanTitle(stem(new File(entry.getName()).getName()));
                        long signature = entry.getHasCrc() ? entry.getCrcValue() : entry.getSize();
                        found.add(new Candidate(archive, entry.getName(), system, title,
                                canonical(archive.getAbsolutePath()) + "!" + entry.getName() +
                                        ":" + signature));
                    }
                } catch (Exception ignored) {
                    // Malformed, unsupported, and encrypted archives remain untouched.
                } finally {
                    temporary.delete();
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "Unable to inspect 7z archive " + archive.getAbsolutePath(), error);
        }
        return found;
    }

    private ImportedGame importCandidate(Candidate candidate, File mediaRoot, File cacheRoot)
            throws Exception {
        String extension = candidate.zipEntry == null ? extension(candidate.source.getName()) :
                extension(candidate.zipEntry);
        String canonicalTitle = canonicalTitle(candidate.system, candidate.title, cacheRoot);
        if (!canonicalTitle.isEmpty()) candidate.title = canonicalTitle;
        if (candidate.inPlace) return new ImportedGame(candidate, candidate.source);
        File systemFolder = new File(GAMES, candidate.system.folder);
        systemFolder.mkdirs();
        File target = new File(systemFolder, safeFilename(candidate.title) + "." + extension);

        if (target.isFile()) {
            if (candidate.zipEntry == null && sameContent(candidate.source, target)) {
                // The verified payload already lives in the library, but its
                // registry row may be missing (interrupted earlier scan, lost
                // registry). Republish the row; the caller deletes the
                // duplicate Downloads source only after that row and its
                // metadata have committed.
                return new ImportedGame(candidate, target);
            }
            // Crash-safe archive recovery: Android may stop the service in
            // the tiny interval after the verified payload is renamed but
            // before its registry row is committed. On the next scan, accept
            // that target only when its full size and CRC exactly match the
            // same ZIP/7z member, then publish metadata before deleting the
            // source archive. A title match alone is never sufficient.
            if (candidate.zipEntry != null && archiveEntryMatchesTarget(candidate, target))
                return new ImportedGame(candidate, target);
            // Never overwrite an existing game or silently rename a different payload.
            return null;
        }

        File partial = new File(systemFolder, "." + target.getName() + ".importing");
        if (candidate.zipEntry == null) {
            copy(candidate.source, partial);
        } else if ("7z".equals(extension(candidate.source.getName()))) {
            extractSevenZipEntry(candidate.source, candidate.zipEntry, partial);
        } else {
            try (ZipFile zip = new ZipFile(candidate.source)) {
                ZipEntry entry = zip.getEntry(candidate.zipEntry);
                if (entry == null) return null;
                extract(zip, entry, partial);
            }
        }
        GameSystems.SystemDef verified = identify(partial, extension);
        if (verified == null || !verified.folder.equals(candidate.system.folder)) {
            partial.delete();
            return null;
        }
        if (!partial.renameTo(target)) {
            partial.delete();
            return null;
        }
        // The Downloads source is deleted by the caller only after this ROM's
        // registry row and metadata have committed, mirroring the archive
        // sweep. Deleting here would lose the game if the service dies before
        // the commit: the source would be gone and no row would exist.
        return new ImportedGame(candidate, target);
    }

    private static boolean archiveEntryMatchesTarget(Candidate candidate, File target) {
        if (candidate == null || candidate.zipEntry == null || target == null || !target.isFile())
            return false;
        try {
            long expectedSize = -1L;
            long expectedCrc = -1L;
            if ("7z".equals(extension(candidate.source.getName()))) {
                try (SevenZFile sevenZ = new SevenZFile(candidate.source, SEVEN_Z_OPTIONS)) {
                    SevenZArchiveEntry entry;
                    while ((entry = sevenZ.getNextEntry()) != null) {
                        if (candidate.zipEntry.equals(entry.getName())) {
                            expectedSize = entry.getSize();
                            if (entry.getHasCrc()) expectedCrc = entry.getCrcValue();
                            break;
                        }
                    }
                }
            } else {
                try (ZipFile zip = new ZipFile(candidate.source)) {
                    ZipEntry entry = zip.getEntry(candidate.zipEntry);
                    if (entry != null) {
                        expectedSize = entry.getSize();
                        expectedCrc = entry.getCrc();
                    }
                }
            }
            if (expectedSize < 0 || expectedSize != target.length() || expectedCrc < 0)
                return false;
            CRC32 crc = new CRC32();
            try (InputStream input = new BufferedInputStream(new FileInputStream(target))) {
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) crc.update(buffer, 0, count);
            }
            return crc.getValue() == expectedCrc;
        } catch (Exception error) {
            Log.w(TAG, "Unable to verify staged archive recovery for " + target, error);
            return false;
        }
    }

    private void enrichBoxArt(ImportedGame game, File cacheRoot, File mediaRoot) {
        try {
            CatalogMatch match = null;
            NintendoMedia official = nintendoMedia(game.system, game.title, cacheRoot);
            if (official != null && !official.cover.isEmpty())
                match = new CatalogMatch(official.cover, game.title);
            if (match == null) match = boxArtMatch(game.system, game.title, cacheRoot);
            if (match == null) return;
            File folder = new File(mediaRoot, "boxfront/" + game.system.folder);
            folder.mkdirs();
            String ext = extension(match.url);
            File target = new File(folder, sha1(game.rom.getAbsolutePath()) + "." +
                    (ext.isEmpty() ? "png" : ext));
            if (download(match.url, target, 64L * 1024L * 1024L)) game.boxArt = target.getAbsolutePath();
        } catch (Exception error) {
            Log.w(TAG, "Box art unavailable for " + game.title, error);
        }
    }

    private void enrichBackground(ImportedGame game, File cacheRoot, File mediaRoot) {
        resolveBackground(game, cacheRoot, mediaRoot);
        // Derive the accent here, while the importer already owns the file, so
        // the theme can select a game with zero image work. An empty value is
        // durable state too: it records "evaluated, no usable hue" and lets the
        // theme fall back to the system accent without ever retrying.
        game.accentSource = accentSourceKey(game.background);
        game.accent = wallpaperAccent(game.background);
    }

    /**
     * Identity of the wallpaper an accent was derived from. Including size and
     * modified time means a wallpaper replaced at the same path re-derives,
     * while an untouched library never decodes anything twice.
     */
    private static String accentSourceKey(String wallpaperPath) {
        if (wallpaperPath == null || wallpaperPath.isEmpty()) return "";
        File wallpaper = new File(wallpaperPath);
        if (!wallpaper.isFile()) return wallpaperPath;
        return wallpaperPath + ':' + wallpaper.length() + ':' + wallpaper.lastModified();
    }

    /**
     * Deterministic complementary accent for one wallpaper file, or an empty
     * string when the image is missing, unreadable, or carries no usable hue.
     */
    private static String wallpaperAccent(String wallpaperPath) {
        if (wallpaperPath == null || wallpaperPath.isEmpty()) return "";
        File wallpaper = new File(wallpaperPath);
        if (!wallpaper.isFile() || wallpaper.length() <= 512) return "";
        Bitmap sample = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(wallpaper.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return "";
            // Power-of-two subsampling is exact and identical on every decoder
            // version, so a rescan cannot produce a different accent for an
            // unchanged file. WallpaperAccent strides the rest of the way down.
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inSampleSize = 1;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            while (longest / (options.inSampleSize * 2) >= WallpaperAccent.SAMPLE_EDGE)
                options.inSampleSize *= 2;
            sample = BitmapFactory.decodeFile(wallpaper.getAbsolutePath(), options);
            if (sample == null) return "";
            int width = sample.getWidth();
            int height = sample.getHeight();
            if (width <= 0 || height <= 0) return "";
            int[] pixels = new int[width * height];
            sample.getPixels(pixels, 0, width, 0, 0, width, height);
            String accent = WallpaperAccent.fromPixels(pixels, width, height);
            return accent == null ? "" : accent;
        } catch (Throwable error) {
            Log.w(TAG, "Unable to derive a wallpaper accent for " + wallpaperPath, error);
            return "";
        } finally {
            if (sample != null) sample.recycle();
        }
    }

    private void resolveBackground(ImportedGame game, File cacheRoot, File mediaRoot) {
        try {
            File pipelineFolder = new File(mediaRoot, "game-wallpapers/" + game.system.folder);
            String digestPrefix = sha1(game.rom.getAbsolutePath());
            File pipeline = new File(pipelineFolder, digestPrefix + ".jpg");
            if (!pipeline.isFile()) {
                File[] longerDigest = pipelineFolder.listFiles((directory, name) ->
                        name.startsWith(digestPrefix) && name.toLowerCase(Locale.US).endsWith(".jpg"));
                if (longerDigest != null && longerDigest.length == 1) pipeline = longerDigest[0];
            }
            if (pipeline.isFile() && pipeline.length() > 512 && wallpaperCanvasIsValid(pipeline)) {
                game.background = pipeline.getAbsolutePath();
                if (game.backgroundSource.isEmpty())
                    game.backgroundSource = BACKGROUND_SOURCE_WALLPAPER;
                return;
            }

            // LaunchBox exposes exact per-game Fanart - Background assets. The
            // result page is matched by both normalized title and platform;
            // only the dedicated fanart class is accepted (never box fronts,
            // banners, or a fuzzy title result).
            CatalogMatch launchBox = launchBoxFanartMatch(game.system, game.title, cacheRoot);
            if (launchBox != null) {
                File folder = new File(mediaRoot,
                        "game-wallpapers-launchbox-fanart/" + game.system.folder);
                File target = new File(folder, sha1(game.rom.getAbsolutePath()) + ".jpg");
                if (acceptWallpaper(downloadAndCrop16x9(launchBox.url, target, cacheRoot,
                        64L * 1024L * 1024L), target)) {
                    game.background = target.getAbsolutePath();
                    game.backgroundSource = BACKGROUND_SOURCE_LAUNCHBOX;
                    game.backgroundSourceUrl = launchBox.url;
                    game.backgroundTransform = BACKGROUND_TRANSFORM;
                    return;
                }
            }

            // Prefer an exact, first-party gallery image before falling back
            // to gameplay. The Nintendo adapter validates the product title
            // and NSUID, so cross-title artwork can never leak into a game.
            NintendoMedia official = nintendoMedia(game.system, game.title, cacheRoot);
            if (official != null && !official.background.isEmpty()) {
                File folder = new File(mediaRoot,
                        "game-wallpapers-official-gallery/" + game.system.folder);
                File target = new File(folder, sha1(game.rom.getAbsolutePath()) + ".jpg");
                if (acceptWallpaper(downloadAndCrop16x9(official.background, target, cacheRoot,
                        64L * 1024L * 1024L), target)) {
                    game.background = target.getAbsolutePath();
                    game.backgroundSource = BACKGROUND_SOURCE_OFFICIAL;
                    game.backgroundSourceUrl = official.background;
                    game.backgroundTransform = BACKGROUND_TRANSFORM;
                    return;
                }
            }

            // Last resort: an exact-title libretro gameplay snap. It is kept
            // in a dedicated tree and tagged in both registry and Pegasus
            // metadata, so it can always be audited or replaced separately
            // from real wallpaper. catalogMatch rejects fuzzy/cross-platform
            // matches and chooses region variants deterministically.
            CatalogMatch screenshot = catalogMatch(game.system, game.title, cacheRoot,
                    "Named_Snaps");
            if (screenshot != null) {
                File folder = new File(mediaRoot,
                        "game-wallpapers-screenshot-fallback/" + game.system.folder);
                File target = new File(folder, sha1(game.rom.getAbsolutePath()) + ".jpg");
                if (acceptWallpaper(downloadAndCrop16x9(screenshot.url, target, cacheRoot,
                        64L * 1024L * 1024L), target)) {
                    game.background = target.getAbsolutePath();
                    game.backgroundSource = BACKGROUND_SOURCE_SCREENSHOT;
                    game.backgroundSourceUrl = screenshot.url;
                    game.backgroundTransform = BACKGROUND_TRANSFORM;
                    return;
                }
            }

            // An empty field is intentional: the theme shows a neutral system
            // backdrop rather than retaining the previous game's wallpaper.
            game.background = "";
            game.backgroundSource = "";
            game.backgroundSourceUrl = "";
            game.backgroundTransform = "";
            return;
        } catch (Exception error) {
            Log.w(TAG, "Wallpaper unavailable for " + game.title, error);
        }
    }

    private static boolean acceptWallpaper(boolean downloaded, File target) {
        if (!downloaded) return false;
        if (wallpaperCanvasIsValid(target)) return true;
        // Never let a dimensionally correct but visually padded canvas become
        // durable state. Leaving it in place would make every later scan
        // detect and "repair" the same file again.
        if (target.isFile() && !target.delete())
            Log.w(TAG, "Unable to remove rejected wallpaper " + target);
        return false;
    }

    private static boolean downloadAndCrop16x9(String url, File target, File cacheRoot,
                                                long maxBytes) throws Exception {
        if (isExact1080p(target)) return true;
        File source = new File(cacheRoot, "background-sources/" + sha1(url) + ".image");
        if (!download(url, source, maxBytes)) return false;

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1) return false;

        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= 1920 &&
                bounds.outHeight / (sample * 2) >= 1080) sample *= 2;
        decode.inSampleSize = sample;
        Bitmap input = BitmapFactory.decodeFile(source.getAbsolutePath(), decode);
        if (input == null) return false;

        Bitmap output = null;
        File part = new File(target.getAbsolutePath() + ".part");
        try {
            output = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            float scale = Math.max(1920f / input.getWidth(), 1080f / input.getHeight());
            float width = input.getWidth() * scale;
            float height = input.getHeight() * scale;
            RectF destination = new RectF((1920f - width) / 2f, (1080f - height) / 2f,
                    (1920f + width) / 2f, (1080f + height) / 2f);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG |
                    Paint.DITHER_FLAG);
            canvas.drawBitmap(input, null, destination, paint);
            target.getParentFile().mkdirs();
            try (FileOutputStream stream = new FileOutputStream(part)) {
                if (!output.compress(Bitmap.CompressFormat.JPEG, 92, stream)) return false;
                stream.getFD().sync();
            }
            if (target.exists() && !target.delete()) return false;
            return part.renameTo(target) && isExact1080p(target);
        } finally {
            if (output != null) output.recycle();
            input.recycle();
            if (part.exists() && !target.equals(part)) part.delete();
        }
    }

    private static boolean isExact1080p(File file) {
        if (file == null || !file.isFile() || file.length() <= 512) return false;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        return bounds.outWidth == 1920 && bounds.outHeight == 1080;
    }

    /**
     * Reject a common false-positive: a 16:9 file that merely embeds narrower
     * cover/key art between blurred or flat padding bands. Dimensions alone
     * cannot distinguish it from a real wallpaper. This deliberately analyzes
     * a tiny sampled bitmap and memoizes by path/size/mtime so library audits do
     * not turn navigation into image-decoding work.
     */
    private static boolean wallpaperCanvasIsValid(File file) {
        if (!isExact1080p(file)) return false;
        loadWallpaperQualityCache();
        String cacheKey = file.getAbsolutePath() + ':' + file.length() + ':' + file.lastModified();
        Boolean cached = WALLPAPER_QUALITY_CACHE.get(cacheKey);
        if (cached != null) return cached;

        Bitmap sample = null;
        boolean valid = true;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inSampleSize = 16;
            sample = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (sample == null || sample.getWidth() < 80 || sample.getHeight() < 45) {
                valid = false;
            } else {
                int width = sample.getWidth();
                int height = sample.getHeight();
                float[] rowDetail = new float[height];
                float[] rowSeam = new float[Math.max(1, height - 1)];
                int[] row = new int[width];
                int[] previousLuma = new int[width];
                for (int y = 0; y < height; y++) {
                    sample.getPixels(row, 0, width, 0, y, width, 1);
                    long activity = 0L;
                    long verticalActivity = 0L;
                    for (int x = 1; x < width; x++) {
                        int left = row[x - 1];
                        int right = row[x];
                        int leftLuma = (77 * ((left >> 16) & 255) +
                                150 * ((left >> 8) & 255) + 29 * (left & 255)) >> 8;
                        int rightLuma = (77 * ((right >> 16) & 255) +
                                150 * ((right >> 8) & 255) + 29 * (right & 255)) >> 8;
                        activity += Math.abs(rightLuma - leftLuma);
                        if (y > 0) verticalActivity += Math.abs(rightLuma - previousLuma[x]);
                        previousLuma[x] = rightLuma;
                    }
                    if (width > 0) {
                        int first = row[0];
                        int firstLuma = (77 * ((first >> 16) & 255) +
                                150 * ((first >> 8) & 255) + 29 * (first & 255)) >> 8;
                        if (y > 0) verticalActivity += Math.abs(firstLuma - previousLuma[0]);
                        previousLuma[0] = firstLuma;
                    }
                    rowDetail[y] = activity / (float) Math.max(1, width - 1);
                    if (y > 0) rowSeam[y - 1] = verticalActivity / (float) Math.max(1, width);
                }
                float top = mean(rowDetail, 0.04f, 0.22f);
                float center = mean(rowDetail, 0.36f, 0.64f);
                float bottom = mean(rowDetail, 0.78f, 0.96f);
                float paddingRatio = (top + bottom) / (2f * Math.max(0.01f, center));
                int topSeam = maxIndex(rowSeam, 0.12f, 0.42f);
                int bottomSeam = maxIndex(rowSeam, 0.58f, 0.88f);
                float seamMean = mean(rowSeam, 0f, 1f);
                float seamStrength = (rowSeam[topSeam] + rowSeam[bottomSeam]) /
                        (2f * Math.max(0.01f, seamMean));
                int symmetry = Math.abs((topSeam + bottomSeam) - (rowSeam.length - 1));
                boolean extremelyFlatOuterBands = center > 4.5f && paddingRatio < 0.20f;
                boolean pairedPaddingSeams = paddingRatio < 0.55f &&
                        seamStrength > 4.0f && symmetry < Math.max(3, height * 0.08f);
                valid = !(extremelyFlatOuterBands || pairedPaddingSeams);
            }
        } catch (Exception error) {
            Log.w(TAG, "Wallpaper canvas audit failed for " + file, error);
            valid = false;
        } finally {
            if (sample != null) sample.recycle();
        }
        WALLPAPER_QUALITY_CACHE.put(cacheKey, valid);
        return valid;
    }

    private static synchronized void loadWallpaperQualityCache() {
        if (wallpaperQualityCacheLoaded) return;
        wallpaperQualityCacheLoaded = true;
        File cache = new File(new File(PEGASUS, ".thorium-import-cache"),
                "wallpaper-quality.json");
        if (!cache.isFile()) return;
        try {
            JSONObject saved = new JSONObject(readText(cache));
            java.util.Iterator<String> keys = saved.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                WALLPAPER_QUALITY_CACHE.put(key, saved.optBoolean(key, false));
            }
        } catch (Exception error) {
            Log.w(TAG, "Unable to read wallpaper quality cache", error);
        }
    }

    private static void persistWallpaperQualityCache(File cacheRoot) {
        try {
            JSONObject saved = new JSONObject();
            for (Map.Entry<String, Boolean> entry : WALLPAPER_QUALITY_CACHE.entrySet())
                saved.put(entry.getKey(), entry.getValue());
            writeTextAtomic(new File(cacheRoot, "wallpaper-quality.json"),
                    saved.toString(2) + "\n");
        } catch (Exception error) {
            Log.w(TAG, "Unable to persist wallpaper quality cache", error);
        }
    }

    private static float mean(float[] values, float from, float to) {
        int start = Math.max(0, Math.min(values.length - 1,
                Math.round((values.length - 1) * from)));
        int end = Math.max(start + 1, Math.min(values.length,
                Math.round(values.length * to)));
        float sum = 0f;
        for (int i = start; i < end; i++) sum += values[i];
        return sum / Math.max(1, end - start);
    }

    private static int maxIndex(float[] values, float from, float to) {
        int start = Math.max(0, Math.min(values.length - 1,
                Math.round((values.length - 1) * from)));
        int end = Math.max(start + 1, Math.min(values.length,
                Math.round(values.length * to)));
        int result = start;
        for (int i = start + 1; i < end; i++)
            if (values[i] > values[result]) result = i;
        return result;
    }

    private void enrichVideo(ImportedGame game, File cacheRoot, File mediaRoot) {
        try {
            File existing = existingVideo(mediaRoot, game.system, game.title);
            if (existing != null) {
                game.video = existing.getAbsolutePath();
                return;
            }
            VideoMatch match = null;
            NintendoMedia official = nintendoMedia(game.system, game.title, cacheRoot);
            if (official != null && !official.video.isEmpty())
                match = new VideoMatch(official.video);
            if (match == null && !game.system.videoArchive.isEmpty())
                match = videoMatch(game.system, game.title, cacheRoot);
            if (match == null)
                match = archiveSearchVideo(game.system, game.title, cacheRoot);
            if (match == null) return;
            File folder = new File(mediaRoot, "internet-archive/" + game.system.folder + "/videos");
            folder.mkdirs();
            File target = new File(folder, safeFilename(game.title) + ".mp4");
            if (download(match.url, target, 192L * 1024L * 1024L)) game.video = target.getAbsolutePath();
        } catch (Exception error) {
            Log.w(TAG, "Video unavailable for " + game.title, error);
        }
    }

    private static File existingVideo(File mediaRoot, GameSystems.SystemDef system, String title) {
        String key = normalize(title);
        File[] folders = new File[]{
                new File(mediaRoot, "upscaled-1080p/" + system.folder + "/videos"),
                new File(mediaRoot, "screenscraper/" + system.folder + "/videos"),
                new File(mediaRoot, "internet-archive/" + system.folder + "/videos")
        };
        for (File folder : folders) {
            File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.US).endsWith(".mp4"));
            if (files == null) continue;
            for (File file : files)
                if (normalize(stem(file.getName())).equals(key) && file.length() > 512) return file;
        }
        return null;
    }

    private void enrichBundledMetadata(ImportedGame game) {
        BundledGameRecord record = bundledGameRecord(game.system.folder, game.title);
        if (record == null) return;
        if (record.critic > 0) game.critic = (int)Math.round(record.critic * 10.0);
        if (record.user > 0) game.user = record.user;
        if (!record.release.isEmpty()) game.release = record.release;
        addUnique(game.developers, record.developer);
        addUnique(game.publishers, record.publisher);
        if (!record.criticSources.isEmpty()) game.criticSources = record.criticSources;
        if (!record.userSources.isEmpty()) game.userSources = record.userSources;
        if (game.critic > 0 || game.user > 0)
            game.scoreSource = "Lucent bundled catalog";
    }

    private static boolean hasCompleteBundledMetadata(ImportedGame game) {
        return "Lucent bundled catalog".equals(game.scoreSource) && game.critic > 0 &&
                game.user > 0 && !game.release.isEmpty();
    }

    private BundledGameRecord bundledGameRecord(String folder, String title) {
        return bundledGameIndex().get(folder + "\t" + normalize(title));
    }

    private synchronized Map<String, BundledGameRecord> bundledGameIndex() {
        if (bundledGameIndex != null) return bundledGameIndex;
        Map<String, BundledGameRecord> index = new HashMap<>();
        int resourceId = context.getResources().getIdentifier(
                "lucent_game_metadata", "raw", context.getPackageName());
        if (resourceId == 0) {
            bundledGameIndex = index;
            return index;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getResources().openRawResource(resourceId), StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) { header = false; continue; }
                String[] fields = line.split("\t", -1);
                if (fields.length < 3) continue;
                double critic = numeric(fieldAt(fields, 3));
                double user = numeric(fieldAt(fields, 4));
                BundledGameRecord record = new BundledGameRecord(fieldAt(fields, 2),
                        critic, user, fieldAt(fields, 5), fieldAt(fields, 6),
                        fieldAt(fields, 7), fieldAt(fields, 8), fieldAt(fields, 9));
                index.put(fieldAt(fields, 0) + "\t" + fieldAt(fields, 1), record);
            }
        } catch (Exception error) {
            Log.e(TAG, "Unable to load bundled Lucent game metadata", error);
        }
        bundledGameIndex = index;
        return index;
    }

    private static double numeric(String value) {
        try { return Double.parseDouble(value); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static String fieldAt(String[] fields, int index) {
        return index >= 0 && index < fields.length ? fields[index] : "";
    }

    private void enrichMetacritic(ImportedGame game, File cacheRoot) {
        if (game.system.metacriticPlatform.isEmpty()) return;
        try {
            String encoded = URLEncoder.encode(game.title, "UTF-8").replace("+", "%20");
            String query = "?offset=0&limit=30&mcoTypeId=13&componentName=search" +
                    "&componentDisplayName=Search&componentType=SearchResults";
            JSONObject root = new JSONObject(new String(fetchBytes(
                    "https://backend.metacritic.com/finder/metacritic/search/" + encoded +
                            "/web" + query, 12L * 1024L * 1024L), StandardCharsets.UTF_8));
            JSONArray items = root.optJSONObject("data") == null ? null :
                    root.optJSONObject("data").optJSONArray("items");
            if (items == null) return;
            String expected = normalize(game.title);
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null || !normalize(item.optString("title")).equals(expected)) continue;
                if (!hasPlatform(item.optJSONArray("platforms"), game.system.metacriticPlatform)) continue;
                JSONObject critic = item.optJSONObject("criticScoreSummary");
                JSONObject user = item.optJSONObject("userScore");
                if (critic != null && critic.optDouble("score", 0) > 0) {
                    game.metacriticCritic = critic.optInt("score");
                    game.metacriticReviews = critic.optInt("reviewCount", 0);
                }
                if (user != null && user.optDouble("score", 0) > 0)
                    game.user = user.optDouble("score");
                game.release = item.optString("releaseDate", "");
                game.metacriticSlug = item.optString("slug", "");
                enrichMetacriticDetails(game);
                return;
            }
        } catch (Exception error) {
            Log.w(TAG, "Metacritic unavailable for " + game.title, error);
        }
    }

    private void enrichMetacriticDetails(ImportedGame game) {
        if (game.metacriticSlug.isEmpty()) return;
        try {
            String slug = encodePath(game.metacriticSlug);
            String productQuery = "?componentName=product&componentDisplayName=Product" +
                    "&componentType=Product";
            JSONObject productRoot = new JSONObject(new String(fetchBytes(
                    "https://backend.metacritic.com/games/metacritic/" + slug +
                            "/web" + productQuery, 12L * 1024L * 1024L), StandardCharsets.UTF_8));
            JSONObject product = productRoot.optJSONObject("data") == null ? null :
                    productRoot.optJSONObject("data").optJSONObject("item");
            if (product != null) {
                JSONObject production = product.optJSONObject("production");
                JSONArray companies = production == null ? null : production.optJSONArray("companies");
                if (companies != null) {
                    for (int i = 0; i < companies.length(); i++) {
                        JSONObject company = companies.optJSONObject(i);
                        if (company == null || company.optString("name").trim().isEmpty()) continue;
                        if ("Developer".equalsIgnoreCase(company.optString("typeName")))
                            addUnique(game.developers, company.optString("name"));
                        else if ("Publisher".equalsIgnoreCase(company.optString("typeName")))
                            addUnique(game.publishers, company.optString("name"));
                    }
                }
                JSONArray platforms = product.optJSONArray("platforms");
                if (platforms != null) {
                    for (int i = 0; i < platforms.length(); i++) {
                        JSONObject platform = platforms.optJSONObject(i);
                        if (platform == null || !platform.optString("name").equalsIgnoreCase(
                                game.system.metacriticPlatform)) continue;
                        JSONObject critic = platform.optJSONObject("criticScoreSummary");
                        if (critic != null && critic.optDouble("score", 0) > 0) {
                            game.metacriticCritic = critic.optInt("score");
                            game.metacriticReviews = critic.optInt("reviewCount", 0);
                        }
                        String release = platform.optString("releaseDate", "");
                        if (!release.isEmpty()) game.release = release;
                        break;
                    }
                }
            }
            String userQuery = "?platform=" + encodePath(game.system.metacriticSlug) +
                    "&componentName=user-score-summary&componentDisplayName=User%20Score%20Summary" +
                    "&componentType=MetaScoreSummary";
            JSONObject userRoot = new JSONObject(new String(fetchBytes(
                    "https://backend.metacritic.com/reviews/metacritic/user/games/" + slug +
                            "/stats/web" + userQuery, 8L * 1024L * 1024L), StandardCharsets.UTF_8));
            JSONObject user = userRoot.optJSONObject("data") == null ? null :
                    userRoot.optJSONObject("data").optJSONObject("item");
            if (user != null && user.optDouble("score", 0) > 0) {
                game.user = user.optDouble("score");
                game.metacriticUser = game.user;
            }
        } catch (Exception error) {
            // Search-level data is retained if a detail endpoint is unavailable.
            Log.w(TAG, "Metacritic detail unavailable for " + game.title, error);
        }
    }

    private void enrichGameRankings(ImportedGame game) {
        GameRankingsRecord record = gameRankingsRecord(game.system.folder, game.title);
        if (record == null) return;
        game.gamerankingsScore = record.score;
        game.gamerankingsReviews = record.reviews;
        game.gamerankingsUrl = record.url;
        if (game.release.isEmpty() && !record.year.isEmpty())
            game.release = record.year + "-01-01";
    }

    private void enrichMobyGames(ImportedGame game) {
        MobyGamesRecord record = mobygamesRecord(game.system.folder, game.title);
        if (record == null) return;
        game.mobygamesScore = record.score;
        game.mobygamesUrl = record.url;
        if (game.release.isEmpty() && !record.year.isEmpty())
            game.release = record.year + "-01-01";
        addUnique(game.developers, record.developer);
    }

    private static void calculateCriticComposite(ImportedGame game) {
        double weighted = 0;
        int weight = 0;
        if (game.metacriticCritic > 0) {
            int reviews = Math.max(1, game.metacriticReviews);
            weighted += game.metacriticCritic * reviews;
            weight += reviews;
        }
        if (game.gamerankingsScore > 0) {
            int reviews = Math.max(1, game.gamerankingsReviews);
            weighted += game.gamerankingsScore * reviews;
            weight += reviews;
        }
        if (game.mobygamesScore > 0) {
            // The public MobyGames browser guarantees at least five critic
            // ratings for every packaged record. Use that documented lower
            // bound rather than inventing a review count.
            weighted += game.mobygamesScore * 5;
            weight += 5;
        }
        // Preserve Lucent's bundled composite when no external component was
        // available. This keeps offline imports from losing known scores.
        if (weight == 0) return;
        game.critic = (int)Math.round(weighted / weight);
        List<String> sources = new ArrayList<>();
        if (game.metacriticCritic > 0) sources.add("Metacritic");
        if (game.gamerankingsScore > 0) sources.add("GameRankings");
        if (game.mobygamesScore > 0) sources.add("MobyGames");
        game.scoreSource = join(sources, " + ");
    }

    private int repairMissingScores() {
        File[] files = PEGASUS.listFiles((dir, name) -> name.endsWith(".metadata.pegasus.txt") &&
                !name.equals(AUTO_METADATA.getName()));
        if (files == null) return 0;
        int repaired = 0;
        for (File metadata : files) {
            try {
                List<String> stanzas = splitStanzas(readText(metadata));
                boolean changed = false;
                for (int i = 0; i < stanzas.size(); i++) {
                    String stanza = stanzas.get(i);
                    if (!stanza.startsWith("game:") || !field(stanza, "x-critic").isEmpty() ||
                            !field(stanza, "x-metacritic-critic").isEmpty()) continue;
                    String title = field(stanza, "game");
                    GameSystems.SystemDef system = systemFromRomPath(field(stanza, "file"));
                    if (system == null) continue;
                    GameRankingsRecord record = gameRankingsRecord(system.folder, title);
                    if (record == null) continue;
                    StringBuilder scored = new StringBuilder(removeField(stanza, "rating").trim());
                    scored.append("\nrating: ").append(String.format(Locale.US, "%.4f", record.score / 100.0));
                    scored.append("\nx-critic: ").append(format(record.score / 10.0));
                    scored.append("\nx-critic-composite: ").append(format(record.score / 10.0));
                    scored.append("\nx-gamerankings-score: ").append(formatScore(record.score));
                    scored.append("\nx-gamerankings-reviews: ").append(record.reviews);
                    if (!record.url.isEmpty())
                        scored.append("\nx-gamerankings-url: ").append(metadataSafe(record.url));
                    scored.append("\nx-gamerankings-snapshot: 2019-12-08");
                    scored.append("\nx-score-source: GameRankings");
                    scored.append("\nx-critic-sources: GameRankings=")
                            .append(format(record.score / 10.0));
                    stanzas.set(i, scored.toString());
                    changed = true;
                    repaired++;
                }
                if (changed) writeTextAtomic(metadata, joinStanzas(stanzas));
            } catch (Exception error) {
                Log.w(TAG, "Historical score audit skipped " + metadata, error);
            }
        }
        return repaired;
    }

    private GameRankingsRecord gameRankingsRecord(String folder, String title) {
        Map<String, GameRankingsRecord> index = gameRankingsIndex();
        String normalized = normalize(title);
        GameRankingsRecord exact = index.get(folder + "\t" + normalized);
        if (exact != null) return exact;
        return gamerankingsAliasIndex.get(folder + "\t" + scoreAlias(normalized));
    }

    private synchronized Map<String, GameRankingsRecord> gameRankingsIndex() {
        if (gamerankingsIndex != null) return gamerankingsIndex;
        Map<String, GameRankingsRecord> index = new HashMap<>();
        Map<String, GameRankingsRecord> aliases = new HashMap<>();
        Set<String> ambiguousAliases = new HashSet<>();
        int resourceId = context.getResources().getIdentifier(
                "gamerankings_scores", "raw", context.getPackageName());
        if (resourceId == 0) {
            gamerankingsAliasIndex = aliases;
            gamerankingsIndex = index;
            return index;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getResources().openRawResource(resourceId), StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) { header = false; continue; }
                String[] fields = line.split("\t", -1);
                if (fields.length < 8) continue;
                double score;
                int reviews;
                try {
                    score = Double.parseDouble(fields[3]);
                    reviews = Integer.parseInt(fields[4]);
                } catch (NumberFormatException ignored) { continue; }
                GameRankingsRecord record = new GameRankingsRecord(
                        score, reviews, fields[5], fields[6], fields[7]);
                index.put(fields[0] + "\t" + fields[1], record);

                // The archived catalog and ROM sets often differ only in logo
                // spacing (Mega Man/Megaman), Roman numerals, or a filename
                // truncated near 40 characters. Add only aliases that resolve
                // to exactly one game on the same platform; ambiguous aliases
                // are removed rather than guessed.
                putUniqueAlias(aliases, ambiguousAliases,
                        fields[0] + "\t" + scoreAlias(fields[1]), record);
                if (fields[1].length() > 35) {
                    int firstPrefix = Math.max(35, fields[1].length() - 6);
                    for (int length = firstPrefix; length < fields[1].length(); length++)
                        putUniqueAlias(aliases, ambiguousAliases,
                                fields[0] + "\t" + scoreAlias(fields[1].substring(0, length)), record);
                }
            }
        } catch (Exception error) {
            Log.e(TAG, "Unable to load packaged GameRankings index", error);
        }
        for (String key : ambiguousAliases) aliases.remove(key);
        gamerankingsAliasIndex = aliases;
        gamerankingsIndex = index;
        return index;
    }

    private MobyGamesRecord mobygamesRecord(String folder, String title) {
        String normalized = normalize(title);
        MobyGamesRecord exact = mobygamesIndex().get(folder + "\t" + normalized);
        if (exact != null) return exact;
        Set<MobyGamesRecord> candidates = new HashSet<>();
        for (String alias : historicalAliases(normalized)) {
            MobyGamesRecord record = mobygamesAliasIndex.get(folder + "\t" + alias);
            if (record != null) candidates.add(record);
        }
        return candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    private synchronized Map<String, MobyGamesRecord> mobygamesIndex() {
        if (mobygamesIndex != null) return mobygamesIndex;
        Map<String, MobyGamesRecord> index = new HashMap<>();
        Map<String, MobyGamesRecord> aliases = new HashMap<>();
        Set<String> ambiguousAliases = new HashSet<>();
        int resourceId = context.getResources().getIdentifier(
                "mobygames_historical", "raw", context.getPackageName());
        if (resourceId == 0) {
            mobygamesAliasIndex = aliases;
            mobygamesIndex = index;
            return index;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getResources().openRawResource(resourceId), StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) { header = false; continue; }
                String[] fields = line.split("\t", -1);
                if (fields.length < 7) continue;
                double score;
                try { score = Double.parseDouble(fields[3]); }
                catch (NumberFormatException ignored) { continue; }
                MobyGamesRecord record = new MobyGamesRecord(
                        score, fields[4], fields[5], fields[6]);
                index.put(fields[0] + "\t" + fields[1], record);
                for (String alias : historicalAliases(fields[1]))
                    putUniqueMobyAlias(aliases, ambiguousAliases,
                            fields[0] + "\t" + alias, record);
            }
        } catch (Exception error) {
            Log.e(TAG, "Unable to load packaged MobyGames historical index", error);
        }
        for (String key : ambiguousAliases) aliases.remove(key);
        mobygamesAliasIndex = aliases;
        mobygamesIndex = index;
        return index;
    }

    private static void putUniqueMobyAlias(Map<String, MobyGamesRecord> aliases,
            Set<String> ambiguous, String key, MobyGamesRecord record) {
        if (key.endsWith("\t") || ambiguous.contains(key)) return;
        MobyGamesRecord previous = aliases.putIfAbsent(key, record);
        if (previous != null && previous != record) {
            aliases.remove(key);
            ambiguous.add(key);
        }
    }

    private static Set<String> historicalAliases(String normalized) {
        Set<String> expanded = new HashSet<>();
        expanded.add(normalized);
        String[] words = normalized.split(" ");
        if (words.length > 1 && ("the".equals(words[0]) || "a".equals(words[0]) || "an".equals(words[0])))
            expanded.add(joinWords(words, 1, words.length));
        if (words.length > 1 && ("the".equals(words[words.length - 1]) ||
                "a".equals(words[words.length - 1]) || "an".equals(words[words.length - 1]))) {
            expanded.add(joinWords(words, 0, words.length - 1));
            expanded.add(words[words.length - 1] + " " + joinWords(words, 0, words.length - 1));
        }
        for (String value : new ArrayList<>(expanded))
            expanded.add(value.replaceAll("(?:^|\\s)and(?:\\s|$)", " ").trim().replaceAll("\\s+", " "));
        Set<String> result = new HashSet<>();
        for (String value : expanded) {
            result.add(value);
            result.add(scoreAlias(value));
        }
        result.remove("");
        return result;
    }

    private static String joinWords(String[] words, int start, int end) {
        StringBuilder result = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (result.length() > 0) result.append(' ');
            result.append(words[i]);
        }
        return result.toString();
    }

    private static void putUniqueAlias(Map<String, GameRankingsRecord> aliases,
            Set<String> ambiguous, String key, GameRankingsRecord record) {
        if (key.endsWith("\t") || ambiguous.contains(key)) return;
        GameRankingsRecord previous = aliases.putIfAbsent(key, record);
        if (previous != null && previous != record) {
            aliases.remove(key);
            ambiguous.add(key);
        }
    }

    private static String scoreAlias(String normalized) {
        String[] tokens = normalized.split(" ");
        StringBuilder compact = new StringBuilder();
        for (String token : tokens) {
            switch (token) {
                case "ii": compact.append('2'); break;
                case "iii": compact.append('3'); break;
                case "iv": compact.append('4'); break;
                case "v": compact.append('5'); break;
                case "vi": compact.append('6'); break;
                case "vii": compact.append('7'); break;
                case "viii": compact.append('8'); break;
                case "ix": compact.append('9'); break;
                case "x": compact.append("10"); break;
                default: compact.append(token);
            }
        }
        return compact.toString();
    }

    private static Set<String> mediaAliases(String title) {
        Set<String> values = new HashSet<>();
        String key = normalize(title);
        values.add(key);
        values.add(scoreAlias(key));
        String relaxed = key.replaceAll(
                        "\\b(?:fan translation|english translation|translation|prototype|beta|demo)\\b",
                        " ")
                .replaceAll("\\s+", " ").trim()
                .replace(" the the ", " the ");
        values.add(relaxed);
        values.add(scoreAlias(relaxed));
        String articleless = relaxed.replaceAll("\\b(?:the|a|an)\\b", " ")
                .replaceAll("\\s+", " ").trim();
        values.add(articleless);
        values.add(scoreAlias(articleless));
        String sourceTitle = cleanTitle(title);
        int sourceColon = sourceTitle.indexOf(':');
        if (sourceColon > 3) {
            String shortTitle = normalize(sourceTitle.substring(0, sourceColon)
                    .replaceAll(",\\s*(The|A|An)$", ""));
            if (shortTitle.contains(" ")) {
                values.add(shortTitle);
                values.add(scoreAlias(shortTitle));
            }
        }
        if (relaxed.startsWith("castlevania rondo of blood")) {
            values.add("akumajou dracula x chi no rondo");
            values.add(scoreAlias("akumajou dracula x chi no rondo"));
        }
        // The PS2 archive catalogs the North American KOF 2002 disc under
        // the retail 2002/2003 two-disc compilation name.
        if (relaxed.startsWith("king of fighters 2002"))
            values.add("kingoffighters20022003usadisc1");
        if (relaxed.startsWith("the ")) {
            values.add(relaxed.substring(4));
            values.add(scoreAlias(relaxed.substring(4)));
        }
        return values;
    }

    private static String nintendoSlug(String title) {
        switch (normalize(title)) {
            case "blasphemous ii": return "blasphemous-2-switch";
            case "mega man 11": return "mega-man-11-switch";
            case "metroid dread": return "metroid-dread-switch";
            case "new super mario bros u deluxe": return "new-super-mario-bros-u-deluxe-switch";
            case "prince of persia the lost crown":
                return "prince-of-persia-the-lost-crown-switch";
            case "super mario 3d world bowsers fury":
                return "super-mario-3d-world-plus-bowsers-fury-switch";
            case "the legend of zelda links awakening":
                return "the-legend-of-zelda-links-awakening-switch";
            default:
                String slug = normalize(title).replace(' ', '-');
                return slug.isEmpty() ? "" : slug + "-switch";
        }
    }

    private static boolean nintendoTitlesEquivalent(String requested, String official) {
        String left = scoreAlias(normalize(requested));
        String right = scoreAlias(normalize(official.replace("&trade;", "")
                .replace("&reg;", "").replace("&#39;", "'")
                .replace("™", "").replace("®", "").replace("©", "")));
        return left.equals(right);
    }

    private static int archiveCandidateScore(String requested, GameSystems.SystemDef system,
                                             String candidateTitle) {
        String candidate = normalize(candidateTitle);
        String compactCandidate = scoreAlias(candidate);
        String lower = " " + candidateTitle.toLowerCase(Locale.US) + " ";
        String[] rejected = new String[]{"soundtrack", " ost ", "music", "podcast", "reaction",
                "speedrun", "tournament", "walkthrough", "longplay", "let s play", "parody",
                "ending", "mod showcase", "full game"};
        for (String token : rejected) if (lower.contains(token)) return -1000;
        if (!normalize(requested).contains(" vs ") && lower.matches(".*\\bvs\\b.*"))
            return -1000;
        if (lower.contains("disaster")) return -1000;
        if (lower.matches(".*\\bin\\s+\\d{1,3}:\\d{2}.*")) return -1000;
        int score = 0;
        for (String alias : mediaAliases(requested)) {
            if (alias.isEmpty()) continue;
            if (candidate.equals(alias)) score = Math.max(score, 140);
            else if (candidate.contains(alias)) score = Math.max(score, 115);
            String compact = scoreAlias(alias);
            if (!compact.isEmpty() && compactCandidate.contains(compact))
                score = Math.max(score, 110);
        }
        if (score == 0) return -1000;
        String[] preferred = new String[]{"official", "trailer", "gameplay", "intro",
                "commercial", "review", "in brief", "in breif"};
        for (String token : preferred) if (lower.contains(token)) score += 4;
        if (system != null) {
            String platform = normalize(system.collection);
            if (!platform.isEmpty() && candidate.contains(platform)) score += 8;
        }
        return score;
    }

    private static String jsonUnescape(String value) {
        if (value == null) return "";
        return value.replace("\\u0026", "&").replace("\\/", "/");
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(value); }
        catch (Exception ignored) { return 0L; }
    }

    private static boolean missingMediaFile(String path) {
        return path == null || path.isEmpty() || !new File(path).isFile();
    }

    private static boolean needsMediaRepair(ImportedGame game) {
        return missingMediaFile(game.boxArt) || missingMediaFile(game.background) ||
                !wallpaperCanvasIsValid(new File(game.background)) ||
                missingMediaFile(game.video);
    }

    private int repairMissingMedia(File cacheRoot, File mediaRoot, List<String> statusTitles,
                                   int added, boolean needsReload) {
        File[] files = PEGASUS.listFiles((dir, name) -> name.endsWith(".metadata.pegasus.txt") &&
                !name.startsWith("99-lucent-auto-") &&
                !name.startsWith("99-thorium-auto-"));
        if (files == null) return 0;
        int repaired = 0;
        for (int fileIndex = 0; fileIndex < files.length; fileIndex++) {
            File metadata = files[fileIndex];
            try {
                List<String> stanzas = splitStanzas(readText(metadata));
                boolean changed = false;
                for (int i = 0; i < stanzas.size(); i++) {
                    String stanza = stanzas.get(i);
                    if (!stanza.startsWith("game:")) continue;
                    String title = field(stanza, "game");
                    String romPath = field(stanza, "file");
                    if (i <= 1 || i % 20 == 0) {
                        double fileFraction = stanzas.isEmpty() ? 1.0 :
                                Math.min(1.0, (double)(i + 1) / stanzas.size());
                        double overall = (fileIndex + fileFraction) / Math.max(1, files.length);
                        setDetailedStatus("artwork", 0.93 + 0.035 * overall,
                                "Auditing the full library media…",
                                metadata.getName() + "  •  " + cleanTitle(title),
                                fileIndex + 1, files.length, statusTitles, added, needsReload);
                    }
                    String artPath = field(stanza, "assets.boxFront");
                    String backgroundPath = field(stanza, "assets.background");
                    String videoPath = field(stanza, "assets.video");
                    boolean needsArt = missingMediaFile(artPath);
                    boolean needsBackground = missingMediaFile(backgroundPath) ||
                            !wallpaperCanvasIsValid(new File(backgroundPath));
                    boolean needsVideo = missingMediaFile(videoPath);
                    if (!needsArt && !needsBackground && !needsVideo) continue;
                    GameSystems.SystemDef system = systemFromRomPath(romPath);
                    File rom = new File(romPath);
                    if (system == null || !rom.isFile()) continue;
                    ImportedGame game = new ImportedGame(system,
                            "metadata:" + romPath, cleanTitle(title), rom);
                    game.boxArt = artPath;
                    game.background = backgroundPath;
                    game.video = videoPath;
                    game.backgroundSource = field(stanza, "x-background-source");
                    game.backgroundSourceUrl = field(stanza, "x-background-source-url");
                    game.backgroundTransform = field(stanza, "x-background-transform");
                    game.inferBackgroundProvenance();

                    if (needsArt && !recentlyMissed(cacheRoot, system, title)) {
                        enrichBoxArt(game, cacheRoot, mediaRoot);
                        if (missingMediaFile(game.boxArt)) recordMiss(cacheRoot, system, title);
                    }
                    if (needsBackground) enrichBackground(game, cacheRoot, mediaRoot);
                    if (needsVideo) enrichVideo(game, cacheRoot, mediaRoot);

                    String updated = stanza;
                    if (needsArt && !missingMediaFile(game.boxArt)) {
                        updated = removeField(updated, "assets.boxFront").trim() +
                                "\nassets.boxFront: " + game.boxArt;
                        repaired++;
                    }
                    if (needsBackground && !missingMediaFile(game.background)) {
                        updated = removeField(updated, "assets.background");
                        updated = removeField(updated, "x-background-source");
                        updated = removeField(updated, "x-background-source-url");
                        updated = removeField(updated, WallpaperAccent.METADATA_FIELD);
                        updated = removeField(updated, "x-background-transform").trim() +
                                "\nassets.background: " + game.background +
                                "\nx-background-source: " + game.backgroundSource;
                        if (!game.backgroundSourceUrl.isEmpty())
                            updated += "\nx-background-source-url: " + game.backgroundSourceUrl;
                        if (!game.backgroundTransform.isEmpty())
                            updated += "\nx-background-transform: " + game.backgroundTransform;
                        // Hand-authored collections are repaired in place, so
                        // the accent has to travel with the new wallpaper.
                        if (!game.accent.isEmpty())
                            updated += "\n" + WallpaperAccent.METADATA_FIELD + ": " + game.accent;
                        repaired++;
                    }
                    if (needsVideo && !missingMediaFile(game.video)) {
                        updated = removeField(updated, "assets.video").trim() +
                                "\nassets.video: " + game.video;
                        repaired++;
                    }
                    if (!updated.equals(stanza)) {
                        stanzas.set(i, updated);
                        changed = true;
                    }
                }
                if (changed) writeTextAtomic(metadata, joinStanzas(stanzas));
            } catch (Exception error) {
                Log.w(TAG, "Media audit skipped " + metadata, error);
            }
        }
        // Persist the content audit keyed by path, size, and mtime. Subsequent
        // launches reuse it immediately and only decode changed/new artwork.
        persistWallpaperQualityCache(cacheRoot);
        return repaired;
    }

    private CatalogMatch boxArtMatch(GameSystems.SystemDef system, String title, File cacheRoot)
            throws Exception {
        return catalogMatch(system, title, cacheRoot, "Named_Boxarts");
    }

    private CatalogMatch launchBoxFanartMatch(GameSystems.SystemDef system, String title,
                                               File cacheRoot) throws Exception {
        File searchCache = new File(cacheRoot,
                "launchbox-search-" + system.folder + '-' + sha1(normalize(title)) + ".html");
        String results = cachedText(searchCache,
                "https://gamesdb.launchbox-app.com/games/results?id=" +
                        URLEncoder.encode(title, "UTF-8"),
                30L * 24L * 60L * 60L * 1000L, 12L * 1024L * 1024L);
        Pattern card = Pattern.compile(
                "href=\"/games/details/(\\d+)-[^\"]+\".*?" +
                        "<h3[^>]*>(.*?)</h3>\\s*<p[^>]*>(.*?)</p>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher cards = card.matcher(results);
        String gameId = "";
        Set<String> requestedAliases = mediaAliases(title);
        while (cards.find()) {
            String candidateTitle = htmlText(cards.group(2));
            String platform = htmlText(cards.group(3));
            boolean exactTitle = false;
            String normalizedCandidate = normalize(candidateTitle);
            for (String alias : requestedAliases) {
                if (normalizedCandidate.equals(alias)) {
                    exactTitle = true;
                    break;
                }
            }
            if (!exactTitle || !launchBoxPlatformMatches(system, platform)) continue;
            gameId = cards.group(1);
            break;
        }
        if (gameId.isEmpty()) return null;

        File imagesCache = new File(cacheRoot, "launchbox-images-" + gameId + ".html");
        String images = cachedText(imagesCache,
                "https://gamesdb.launchbox-app.com/games/images/" + gameId,
                30L * 24L * 60L * 60L * 1000L, 24L * 1024L * 1024L);
        Pattern fanart = Pattern.compile(
                "<a\\b[^>]*href=\"(https://images\\.launchbox-app\\.com/[^\"]+)\"" +
                        "[^>]*data-title=\"[^\"]*Fanart - Background[^\"]*\"" +
                        "[^>]*data-footer=\"(\\d+)\\s*x\\s*(\\d+)[^\"]*\"",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher candidates = fanart.matcher(images);
        String bestUrl = "";
        long bestPixels = 0L;
        while (candidates.find()) {
            int width = Integer.parseInt(candidates.group(2));
            int height = Integer.parseInt(candidates.group(3));
            if (width < 960 || height < 540) continue;
            float aspect = width / (float) height;
            if (aspect < 1.35f || aspect > 2.35f) continue;
            long pixels = (long) width * height;
            if (pixels > bestPixels) {
                bestPixels = pixels;
                bestUrl = htmlText(candidates.group(1));
            }
        }
        return bestUrl.isEmpty() ? null : new CatalogMatch(bestUrl, cleanTitle(title));
    }

    private static boolean launchBoxPlatformMatches(GameSystems.SystemDef system,
                                                    String platform) {
        String candidate = normalize(platform);
        if (candidate.equals(normalize(system.collection))) return true;
        for (String alias : system.aliases)
            if (candidate.equals(normalize(alias))) return true;
        // LaunchBox uses these shorter official labels for a few collections.
        if ("megadrive".equals(system.folder)) return candidate.equals("sega genesis");
        if ("psx".equals(system.folder)) return candidate.equals("sony playstation");
        if ("gba".equals(system.folder)) return candidate.equals("nintendo game boy advance");
        return false;
    }

    private static String htmlText(String value) {
        if (value == null) return "";
        return value.replaceAll("<[^>]+>", " ")
                .replace("&#x27;", "'").replace("&#39;", "'")
                .replace("&quot;", "\"").replace("&amp;", "&")
                .replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
    }

    private CatalogMatch catalogMatch(GameSystems.SystemDef system, String title, File cacheRoot,
                                      String category) throws Exception {
        if (system.libretro.isEmpty()) return null;
        File cache = new File(cacheRoot, "libretro-" + system.folder + "-" + category + ".html");
        String html = cachedText(cache,
                "https://thumbnails.libretro.com/" + encodePath(system.libretro) + "/" + category + "/",
                7L * 24L * 60L * 60L * 1000L, 32L * 1024L * 1024L);
        Set<String> keys = mediaAliases(title);
        List<String> exact = new ArrayList<>();
        String requestedVariantText = decode(title).toLowerCase(Locale.US);
        Matcher matcher = HREF.matcher(html);
        while (matcher.find()) {
            String href = matcher.group(1);
            String decodedCandidate = decode(href);
            String candidateVariantText = decodedCandidate.toLowerCase(Locale.US);
            // Parenthetical stripping makes retail and demo/kiosk/prototype
            // releases normalize to the same key. Never let a special build
            // rename a normal retail ROM unless the source title explicitly
            // requested that same variant.
            boolean unwantedVariant = false;
            for (String variant : new String[]{"demo", "kiosk", "prototype", "proto", "beta", "sample"}) {
                if (candidateVariantText.matches(".*\\b" + variant + "\\b.*") &&
                        !requestedVariantText.matches(".*\\b" + variant + "\\b.*")) {
                    unwantedVariant = true;
                    break;
                }
            }
            if (unwantedVariant) continue;
            String candidate = normalize(stem(decodedCandidate));
            String candidateArticleless = candidate.replaceAll("\\b(?:the|a|an)\\b", " ")
                    .replaceAll("\\s+", " ").trim();
            if (keys.contains(candidate) || keys.contains(scoreAlias(candidate)) ||
                    keys.contains(candidateArticleless) || keys.contains(scoreAlias(candidateArticleless)))
                exact.add(href);
        }
        if (exact.isEmpty()) return null;
        exact.sort((left, right) -> Integer.compare(regionRank(left), regionRank(right)));
        String href = exact.get(0);
        String base = "https://thumbnails.libretro.com/" + encodePath(system.libretro) +
                "/" + category + "/";
        return new CatalogMatch(base + encodeHref(href), cleanTitle(stem(decode(href))));
    }

    private String canonicalTitle(GameSystems.SystemDef system, String title, File cacheRoot) {
        try {
            CatalogMatch match = boxArtMatch(system, title, cacheRoot);
            if (match == null) return "";
            String canonical = cleanTitle(match.title);
            String source = cleanTitle(title);
            // Catalogs occasionally expose a ROM-dat filename rather than a
            // display title. Keep the source title's punctuation when both
            // identities match exactly and only the catalog added dump tags.
            if (looksLikeDumpTitle(match.title) &&
                    normalize(source).equals(normalize(canonical))) return source;
            return canonical;
        } catch (Exception ignored) {
            return "";
        }
    }

    private VideoMatch videoMatch(GameSystems.SystemDef system, String title, File cacheRoot)
            throws Exception {
        File cache = new File(cacheRoot, "archive-" + system.folder + ".json");
        String json = cachedText(cache, "https://archive.org/metadata/" + system.videoArchive,
                30L * 24L * 60L * 60L * 1000L, 96L * 1024L * 1024L);
        JSONObject root = new JSONObject(json);
        JSONArray files = root.optJSONArray("files");
        if (files == null) return null;
        Set<String> aliases = mediaAliases(title);
        String selected = null;
        for (int i = 0; i < files.length(); i++) {
            JSONObject item = files.optJSONObject(i);
            String name = item == null ? "" : item.optString("name", "");
            if (!name.toLowerCase(Locale.US).endsWith(".mp4") ||
                    name.toLowerCase(Locale.US).endsWith(".ia.mp4")) continue;
            String candidate = normalize(stem(name));
            String compact = scoreAlias(candidate);
            String articleless = candidate.replaceAll("\\b(?:the|a|an)\\b", " ")
                    .replaceAll("\\s+", " ").trim();
            // EmuMovies-style archive filenames commonly append a packed
            // region/disc marker (for example TonyHawksProSkater3usa.mp4).
            String withoutArchiveSuffix = compact.replaceFirst(
                    "(?:usa|us|world|europe|eu|japan)(?:disc[0-9]+)?$", "");
            if (aliases.contains(candidate) || aliases.contains(compact) ||
                    aliases.contains(articleless) || aliases.contains(scoreAlias(articleless)) ||
                    aliases.contains(withoutArchiveSuffix)) {
                if (selected == null || regionRank(name) < regionRank(selected)) selected = name;
            }
        }
        if (selected == null) return null;
        String base;
        if (!root.optString("d1").isEmpty() && !root.optString("dir").isEmpty())
            base = "https://" + root.optString("d1") + root.optString("dir") + "/";
        else base = "https://archive.org/download/" + system.videoArchive + "/";
        return new VideoMatch(base + encodeHref(selected));
    }

    /**
     * Nintendo's own product pages provide an exact cover, full-width gallery
     * images, and MP4 gallery videos. This is both more accurate and more
     * resilient for Switch than treating a single community archive as the
     * entire catalog. A page is accepted only when its title matches the ROM.
     */
    private NintendoMedia nintendoMedia(GameSystems.SystemDef system, String title, File cacheRoot) {
        if (system == null || !"switch".equals(system.folder)) return null;
        String slug = nintendoSlug(title);
        if (slug.isEmpty()) return null;
        try {
            File cache = new File(cacheRoot, "nintendo/" + slug + ".html");
            String html = cachedText(cache,
                    "https://www.nintendo.com/us/store/products/" + slug + "/",
                    30L * 24L * 60L * 60L * 1000L, 4L * 1024L * 1024L);
            Matcher titleMatcher = NINTENDO_TITLE.matcher(html);
            if (!titleMatcher.find() || !nintendoTitlesEquivalent(title, titleMatcher.group(1)))
                return null;

            // The first Nintendo software asset belongs to the primary product
            // (the rest of this large page may contain many cross-sells). Its
            // NSUID lets us reject every unrelated gallery item precisely.
            Matcher productAsset = NINTENDO_PRODUCT_ASSET.matcher(html);
            if (!productAsset.find()) return null;
            String productId = productAsset.group(1);
            String coverHash = productAsset.group(2);
            String cover = "https://assets.nintendo.com/image/upload/q_auto/f_auto/" +
                    "store/software/switch/" + productId + "/" + coverHash;
            String background = "", video = "";

            String decoded = html.replace("\\\"", "\"");
            String sku = "71" + productId.substring(6);
            int productAt = decoded.indexOf("Product:{\"sku\":\"" + sku +
                    "\"}\":{\"__typename\":\"Product\"");
            if (productAt >= 0) {
                int productEnd = Math.min(decoded.length(), productAt + 250_000);
                Matcher square = NINTENDO_SQUARE_COVER.matcher(
                        decoded.substring(productAt, productEnd));
                if (square.find()) cover = jsonUnescape(square.group(1));
            }
            Matcher asset = NINTENDO_GALLERY_ASSET.matcher(decoded);
            while (asset.find()) {
                String publicId = asset.group(1);
                if (publicId.startsWith("/")) publicId = publicId.substring(1);
                if (!publicId.contains("/" + productId + "/")) continue;
                String type = asset.group(2).toLowerCase(Locale.US);
                if ("video".equals(type) && video.isEmpty())
                    video = "https://assets.nintendo.com/video/upload/q_auto/f_auto/" +
                            publicId + ".mp4";
                else if ("image".equals(type) && background.isEmpty() &&
                        !publicId.endsWith("/" + coverHash))
                    background = "https://assets.nintendo.com/image/upload/q_auto:best/f_auto/" +
                            publicId;
            }
            if (cover.isEmpty() && background.isEmpty() && video.isEmpty()) return null;
            return new NintendoMedia(cover, background, video);
        } catch (Exception error) {
            Log.w(TAG, "Official Nintendo media unavailable for " + title, error);
            return null;
        }
    }

    private VideoMatch archiveSearchVideo(GameSystems.SystemDef system, String title, File cacheRoot) {
        try {
            String queryTitle = cleanTitle(title)
                    .replaceAll("\\([^)]*\\)|\\[[^]]*]", " ")
                    .replaceAll("\\s+", " ").trim();
            int queryColon = queryTitle.indexOf(':');
            if (queryColon > 3) {
                String shortQuery = queryTitle.substring(0, queryColon)
                        .replaceAll(",\\s*(The|A|An)$", "");
                if (normalize(shortQuery).contains(" ")) queryTitle = shortQuery;
            }
            String query = "title:(\"" + queryTitle + "\") AND mediatype:(movies)";
            String url = "https://archive.org/advancedsearch.php?q=" +
                    URLEncoder.encode(query, "UTF-8") +
                    "&fl%5B%5D=identifier&fl%5B%5D=title&rows=75&page=1&output=json";
            File cache = new File(cacheRoot, "archive-search-v3/" + system.folder + "/" +
                    sha1(normalize(title)) + ".json");
            JSONObject root = new JSONObject(cachedText(cache, url,
                    14L * 24L * 60L * 60L * 1000L, 4L * 1024L * 1024L));
            JSONObject response = root.optJSONObject("response");
            JSONArray docs = response == null ? null : response.optJSONArray("docs");
            if (docs == null) return null;
            List<JSONObject> ranked = new ArrayList<>();
            for (int i = 0; i < docs.length(); i++) {
                JSONObject doc = docs.optJSONObject(i);
                if (doc != null) ranked.add(doc);
            }
            ranked.sort((left, right) -> Integer.compare(
                    archiveCandidateScore(title, system,
                            right.optString("title") + " " + right.optString("identifier")),
                    archiveCandidateScore(title, system,
                            left.optString("title") + " " + left.optString("identifier"))));
            // A high-scoring Archive item can still contain no usable MP4 (or
            // only a multi-gigabyte longplay). Keep trying the remaining exact
            // candidates instead of treating that first dead end as final.
            for (JSONObject candidate : ranked) {
                int score = archiveCandidateScore(title, system,
                        candidate.optString("title") + " " + candidate.optString("identifier"));
                if (score < 90) break;
                VideoMatch match = archiveItemVideo(candidate.optString("identifier"), cacheRoot);
                if (match != null) return match;
            }
            return null;
        } catch (Exception error) {
            Log.w(TAG, "Archive fallback unavailable for " + title, error);
            return null;
        }
    }

    private VideoMatch archiveItemVideo(String identifier, File cacheRoot) throws Exception {
        if (identifier == null || identifier.isEmpty()) return null;
        File metadataCache = new File(cacheRoot, "archive-items/" + sha1(identifier) + ".json");
        JSONObject metadata = new JSONObject(cachedText(metadataCache,
                "https://archive.org/metadata/" + encodePath(identifier),
                30L * 24L * 60L * 60L * 1000L, 16L * 1024L * 1024L));
        JSONArray files = metadata.optJSONArray("files");
        if (files == null) return null;
        String selected = "";
        int selectedQuality = Integer.MIN_VALUE;
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            String name = file == null ? "" : file.optString("name");
            String lower = name.toLowerCase(Locale.US);
            if (!lower.endsWith(".mp4") || lower.contains("sample") ||
                    lower.contains("thumb") || lower.contains("spectrogram")) continue;
            long size = parseLong(file.optString("size"));
            if (size > 0 && (size < 256L * 1024L || size > 192L * 1024L * 1024L)) continue;
            String format = file.optString("format").toLowerCase(Locale.US);
            int quality = 0;
            if (lower.contains("720") || lower.contains("1080")) quality += 25;
            if (format.contains("h.264") || format.contains("mpeg4")) quality += 20;
            if (lower.contains("512kb")) quality += 8;
            if (lower.endsWith(".ia.mp4")) quality -= 5;
            if (size > 0) quality += Math.min(20, (int)(size / (8L * 1024L * 1024L)));
            if (quality > selectedQuality) { selectedQuality = quality; selected = name; }
        }
        if (selected.isEmpty()) return null;
        return new VideoMatch("https://archive.org/download/" + encodePath(identifier) + "/" +
                encodeHref(selected));
    }

    private void writeMetadata(JSONArray registry) throws Exception {
        // Generated metadata is rebuilt frequently (startup, media repair, and
        // imports). Older builds treated the registry as the only source of
        // truth and silently discarded exact historical enrichment that had
        // subsequently been added to the Pegasus metafiles. Hydrate missing
        // registry fields from the current stanza with the same canonical ROM
        // path before rendering. The ROM path is deliberately the only join
        // key: similarly named regional releases must never borrow metadata.
        boolean registryChanged = hydrateRegistryFromExistingMetadata(registry);
        registryChanged |= refreshWallpaperAccents(registry);
        for (int i = 0; i < registry.length(); i++) {
            JSONObject row = registry.optJSONObject(i);
            if (row == null || row.optLong("addedAt", 0L) > 0L) continue;
            File rom = new File(row.optString("file"));
            long addedAt = rom.isFile() && rom.lastModified() > 0L ?
                    rom.lastModified() : System.currentTimeMillis();
            row.put("addedAt", addedAt);
            registryChanged = true;
        }
        if (registryChanged)
            writeJsonAtomic(REGISTRY, registry);
        Map<String, LinkedHashMap<String, JSONObject>> groups = new LinkedHashMap<>();
        for (int i = 0; i < registry.length(); i++) {
            JSONObject row = registry.optJSONObject(i);
            if (row == null || !new File(row.optString("file")).isFile()) continue;
            if (row.optBoolean("archived", false) &&
                    !row.optBoolean("forceInclude", false)) continue;
            String folder = row.optString("system");
            String identity = dedupeGameIdentity(row.optString("title"));
            LinkedHashMap<String, JSONObject> games = groups.computeIfAbsent(
                    folder, key -> new LinkedHashMap<>());
            JSONObject previous = games.get(identity);
            if (previous == null || metadataQuality(row) > metadataQuality(previous))
                games.put(identity, row);
        }
        Set<String> activeGeneratedFiles = new HashSet<>();
        for (Map.Entry<String, LinkedHashMap<String, JSONObject>> group : groups.entrySet()) {
            GameSystems.SystemDef system = GameSystems.byFolder(group.getKey());
            if (system == null) continue;
            List<JSONObject> games = new ArrayList<>(group.getValue().values());
            games.sort(Comparator.comparing(value -> value.optString("title"),
                    String.CASE_INSENSITIVE_ORDER));
            StringBuilder out = new StringBuilder(
                    "# Generated atomically by Lucent Library Importer.\n");
            // The owner's library intentionally groups the lone PC Engine CD
            // title with Windows instead of exposing a one-game platform card.
            String displayCollection = "pcenginecd".equals(system.folder) ?
                    "Microsoft Windows" : system.collection;
            out.append("\ncollection: ").append(displayCollection).append('\n');
            out.append("shortname: ").append(system.folder).append('\n');
            String launch = launchCommand(system.folder);
            if (!launch.isEmpty()) out.append("launch: ").append(launch).append('\n');
            out.append("files:\n");
            for (JSONObject row : games)
                out.append("  ").append(metadataSafe(row.optString("file"))).append('\n');
            for (JSONObject row : games) {
                String displayTitle = cleanTitle(row.optString("title"));
                out.append("\ngame: ").append(metadataSafe(displayTitle)).append('\n');
                double userScore = row.optDouble("user", 0);
                int userKey = userScore > 0 ? Math.max(0, 10000 - (int)Math.round(userScore * 1000)) : 99999;
                out.append("sort-by: ").append(String.format(Locale.US, "%05d", userKey))
                        .append(' ').append(metadataSafe(displayTitle.toLowerCase(Locale.US)))
                        .append('\n');
                int criticScore = row.optInt("critic", 0);
                if (criticScore > 0)
                    out.append("rating: ").append(String.format(Locale.US, "%.4f", criticScore / 100.0))
                            .append('\n');
                else
                    out.append("rating: 0%\n");
                out.append("file: ").append(metadataSafe(row.optString("file"))).append('\n');
                out.append("x-lucent-id: ")
                        .append(metadataSafe(row.optString("sourceIdentity"))).append('\n');
                out.append("x-added-at: ").append(row.optLong("addedAt", 0L)).append('\n');
                appendMetadataList(out, "developer", row.optJSONArray("developers"));
                appendMetadataList(out, "publisher", row.optJSONArray("publishers"));
                if (userScore > 0) {
                    out.append("x-user-score: ").append(format(userScore)).append('\n');
                    out.append("x-user-composite: ").append(format(userScore)).append('\n');
                    double metacriticUser = row.optDouble("metacriticUser", 0);
                    double gamefaqsUser = row.optDouble("gamefaqsUser", 0);
                    if (metacriticUser > 0)
                        out.append("x-metacritic-user: ").append(format(metacriticUser)).append('\n');
                    if (gamefaqsUser > 0) {
                        out.append("x-gamefaqs-user: ").append(format(gamefaqsUser)).append('\n');
                        if (!row.optString("gamefaqsUrl").isEmpty())
                            out.append("x-gamefaqs-url: ")
                                    .append(metadataSafe(row.optString("gamefaqsUrl"))).append('\n');
                    }
                    String preservedUserSources = row.optString("userSources");
                    if (!preservedUserSources.isEmpty())
                        out.append("x-user-sources: ").append(metadataSafe(preservedUserSources)).append('\n');
                    else if (metacriticUser > 0)
                        out.append("x-user-sources: Metacritic users=")
                                .append(format(metacriticUser)).append('\n');
                }
                if (criticScore > 0) {
                    double normalizedCritic = criticScore / 10.0;
                    out.append("x-critic: ").append(format(normalizedCritic)).append('\n');
                    out.append("x-critic-composite: ").append(format(normalizedCritic)).append('\n');
                    List<String> criticSources = new ArrayList<>();
                    int metacriticCritic = row.optInt("metacriticCritic", 0);
                    if (metacriticCritic > 0) {
                        int reviews = row.optInt("metacriticReviews", 0);
                        out.append("x-metacritic-critic: ").append(metacriticCritic).append('\n');
                        if (reviews > 0)
                            out.append("x-metacritic-reviews: ").append(reviews).append('\n');
                        criticSources.add("Metacritic=" + format(metacriticCritic / 10.0) +
                                (reviews > 0 ? " (n=" + reviews + ")" : ""));
                    }
                    if (row.optDouble("gamerankingsScore", 0) > 0) {
                        out.append("x-gamerankings-score: ")
                                .append(formatScore(row.optDouble("gamerankingsScore"))).append('\n');
                        out.append("x-gamerankings-reviews: ")
                                .append(row.optInt("gamerankingsReviews")).append('\n');
                        if (!row.optString("gamerankingsUrl").isEmpty())
                            out.append("x-gamerankings-url: ")
                                    .append(metadataSafe(row.optString("gamerankingsUrl"))).append('\n');
                        out.append("x-gamerankings-snapshot: 2019-12-08\n");
                        criticSources.add("GameRankings=" +
                                format(row.optDouble("gamerankingsScore") / 10.0) +
                                " (n=" + row.optInt("gamerankingsReviews") + ")");
                    }
                    if (row.optDouble("mobygamesScore", 0) > 0) {
                        out.append("x-mobygames-critic: ")
                                .append(format(row.optDouble("mobygamesScore") / 10.0)).append('\n');
                        out.append("x-mobygames-critic-count-min: 5\n");
                        if (!row.optString("mobygamesUrl").isEmpty())
                            out.append("x-mobygames-url: ")
                                    .append(metadataSafe(row.optString("mobygamesUrl"))).append('\n');
                        criticSources.add("MobyGames=" +
                                format(row.optDouble("mobygamesScore") / 10.0) + " (n=5 min)");
                    }
                    out.append("x-score-source: weighted composite\n");
                    String preservedCriticSources = row.optString("criticSources");
                    out.append("x-critic-sources: ")
                            .append(criticSources.isEmpty() && !preservedCriticSources.isEmpty() ?
                                    metadataSafe(preservedCriticSources) : join(criticSources, " | "))
                            .append('\n');
                }
                if (!row.optString("release").isEmpty())
                    out.append("release: ").append(metadataSafe(row.optString("release"))).append('\n');
                if (!row.optString("metacriticSlug").isEmpty())
                    out.append("x-metacritic-url: https://www.metacritic.com/game/")
                            .append(row.optString("metacriticSlug")).append("/\n");
                if (!row.optString("boxArt").isEmpty())
                    out.append("assets.boxFront: ").append(metadataSafe(row.optString("boxArt"))).append('\n');
                if (!row.optString("background").isEmpty())
                    out.append("assets.background: ").append(metadataSafe(row.optString("background"))).append('\n');
                if (!row.optString("backgroundSource").isEmpty())
                    out.append("x-background-source: ")
                            .append(metadataSafe(row.optString("backgroundSource"))).append('\n');
                if (!row.optString("backgroundSourceUrl").isEmpty())
                    out.append("x-background-source-url: ")
                            .append(metadataSafe(row.optString("backgroundSourceUrl"))).append('\n');
                if (!row.optString("backgroundTransform").isEmpty())
                    out.append("x-background-transform: ")
                            .append(metadataSafe(row.optString("backgroundTransform"))).append('\n');
                // Precomputed "by wallpaper" accent. The theme reads it as
                // game.extra["lucent-accent"] and never derives a color itself.
                String accent = WallpaperAccent.sanitize(
                        row.optString(WallpaperAccent.REGISTRY_FIELD));
                if (!accent.isEmpty())
                    out.append(WallpaperAccent.METADATA_FIELD).append(": ")
                            .append(accent).append('\n');
                if (!row.optString("video").isEmpty())
                    out.append("assets.video: ").append(metadataSafe(row.optString("video"))).append('\n');
            }
            String generatedName = "99-lucent-auto-" + system.folder +
                    ".metadata.pegasus.txt";
            activeGeneratedFiles.add(generatedName);
            writeTextAtomic(new File(LUCENT_AUTO_METADATA.getParentFile(), generatedName),
                    out.toString());
            writeCompatibilityMirror(new File(AUTO_METADATA.getParentFile(), generatedName),
                    out.toString());
        }
        cleanupGeneratedMetadata(AUTO_METADATA.getParentFile(), activeGeneratedFiles);
        cleanupGeneratedMetadata(LUCENT_AUTO_METADATA.getParentFile(), activeGeneratedFiles);
        // Both Android package variants have their own canonical `metafiles`
        // directory. Early builds also mirrored the same generated files into
        // the shared Pegasus root, so each ROM was parsed twice and the second
        // pass emitted ownership conflicts. Remove those redundant mirrors.
        cleanupGeneratedMetadata(PEGASUS, Collections.emptySet());
        // A Pegasus metafile represents one collection. Older Lucent builds
        // placed several collection headers in this combined file, causing
        // every imported game to appear in every system. Leave a harmless
        // marker at the old path while the per-system files above own games.
        String retired = "# Retired combined Lucent metadata; per-system files are authoritative.\n";
        writeTextAtomic(LUCENT_AUTO_METADATA, retired);
        writeCompatibilityMirror(AUTO_METADATA, retired);
        writeTextAtomic(LEGACY_AUTO_METADATA, retired);
    }

    /**
     * Precompute the "by wallpaper" accent for every registry row whose
     * wallpaper has not been evaluated yet.
     *
     * The accent is keyed by the wallpaper path it was derived from, so a
     * library update decodes an image exactly once: later scans, renames, and
     * deletions all fall straight through. Selecting a game in the theme must
     * never cost image work, so nothing here is deferred to browse time.
     */
    private static boolean refreshWallpaperAccents(JSONArray registry) {
        boolean changed = false;
        for (int index = 0; index < registry.length(); index++) {
            JSONObject row = registry.optJSONObject(index);
            if (row == null) continue;
            String background = row.optString("background");
            String key = accentSourceKey(background);
            // The recorded source, not the accent, is the "already evaluated"
            // marker: a greyscale wallpaper legitimately resolves to nothing
            // and must not be decoded again on every later scan.
            if (row.has(WallpaperAccent.REGISTRY_SOURCE_FIELD) &&
                    key.equals(row.optString(WallpaperAccent.REGISTRY_SOURCE_FIELD)))
                continue;
            try {
                row.put(WallpaperAccent.REGISTRY_FIELD, wallpaperAccent(background));
                row.put(WallpaperAccent.REGISTRY_SOURCE_FIELD, key);
                changed = true;
            } catch (Exception ignored) {}
        }
        return changed;
    }

    private static boolean hydrateRegistryFromExistingMetadata(JSONArray registry) {
        Map<String, String> stanzaByPath = new HashMap<>();
        Set<String> registryPaths = new HashSet<>();
        for (int index = 0; index < registry.length(); index++) {
            JSONObject row = registry.optJSONObject(index);
            if (row != null && !row.optString("file").isEmpty())
                registryPaths.add(canonical(row.optString("file")));
        }
        boolean addedRows = false;
        for (File metadata : metadataFiles()) {
            try {
                for (String stanza : splitStanzas(readText(metadata))) {
                    String path = field(stanza, "file");
                    if (path.isEmpty()) continue;
                    String key = canonical(path);
                    String previous = stanzaByPath.get(key);
                    if (previous == null || metadataStanzaRichness(stanza) >
                            metadataStanzaRichness(previous))
                        stanzaByPath.put(key, stanza);
                }
            } catch (Exception ignored) {}
        }
        // A verified ROM may predate the registry while still having a valid
        // per-system Lucent metafile. Never delete that game merely because a
        // newer scan rebuilds generated files from the registry. Reconcile
        // only Lucent-owned auto metafiles; hand-authored/core collections are
        // intentionally not duplicated into the importer registry.
        for (File metadata : metadataFiles()) {
            String name = metadata.getName();
            if (!name.startsWith("99-lucent-auto-") ||
                    name.equals("99-lucent-auto-import.metadata.pegasus.txt")) continue;
            try {
                for (String stanza : splitStanzas(readText(metadata))) {
                    String path = field(stanza, "file");
                    String title = field(stanza, "game");
                    if (path.isEmpty() || title.isEmpty() || !new File(path).isFile()) continue;
                    String canonicalPath = canonical(path);
                    if (registryPaths.contains(canonicalPath)) continue;
                    GameSystems.SystemDef system = systemFromRomPath(path);
                    if (system == null) continue;
                    JSONObject row = new JSONObject();
                    row.put("sourceIdentity", field(stanza, "x-lucent-id").isEmpty() ?
                            canonicalPath + ":" + new File(path).length() :
                            field(stanza, "x-lucent-id"));
                    row.put("system", system.folder);
                    row.put("title", cleanTitle(title));
                    row.put("file", path);
                    row.put("boxArt", field(stanza, "assets.boxFront"));
                    row.put("background", field(stanza, "assets.background"));
                    row.put("backgroundSource", field(stanza, "x-background-source"));
                    row.put("backgroundSourceUrl", field(stanza, "x-background-source-url"));
                    row.put("backgroundTransform", field(stanza, "x-background-transform"));
                    row.put("video", field(stanza, "assets.video"));
                    String recoveredAccent = WallpaperAccent.sanitize(
                            field(stanza, WallpaperAccent.METADATA_FIELD));
                    if (!recoveredAccent.isEmpty()) {
                        row.put(WallpaperAccent.REGISTRY_FIELD, recoveredAccent);
                        row.put(WallpaperAccent.REGISTRY_SOURCE_FIELD,
                                accentSourceKey(field(stanza, "assets.background")));
                    }
                    row.put("enrichmentVersion", 2);
                    row.put("archived", false);
                    row.put("forceInclude", false);
                    ensureBackgroundProvenance(row);
                    registry.put(row);
                    registryPaths.add(canonicalPath);
                    addedRows = true;
                }
            } catch (Exception ignored) {}
        }
        for (int index = 0; index < registry.length(); index++) {
            JSONObject row = registry.optJSONObject(index);
            if (row == null) continue;
            String stanza = stanzaByPath.get(canonical(row.optString("file")));
            if (stanza == null) continue;
            try {
                String exactTitle = field(stanza, "game");
                if (!exactTitle.isEmpty()) row.put("title", cleanTitle(exactTitle));
                putNumericIfMissing(row, "user", field(stanza, "x-user-score"), 1.0);
                putNumericIfMissing(row, "critic", field(stanza, "x-critic"), 10.0);
                putNumericIfMissing(row, "metacriticUser", field(stanza, "x-metacritic-user"), 1.0);
                putNumericIfMissing(row, "gamefaqsUser", field(stanza, "x-gamefaqs-user"), 1.0);
                putNumericIfMissing(row, "metacriticCritic", field(stanza, "x-metacritic-critic"), 1.0);
                putNumericIfMissing(row, "gamerankingsScore", field(stanza, "x-gamerankings-score"), 10.0);
                putNumericIfMissing(row, "gamerankingsReviews", field(stanza, "x-gamerankings-reviews"), 1.0);
                putNumericIfMissing(row, "mobygamesScore", field(stanza, "x-mobygames-critic"), 10.0);
                putStringIfMissing(row, "release", field(stanza, "release"));
                putStringIfMissing(row, "gamerankingsUrl", field(stanza, "x-gamerankings-url"));
                putStringIfMissing(row, "mobygamesUrl", field(stanza, "x-mobygames-url"));
                putStringIfMissing(row, "gamefaqsUrl", field(stanza, "x-gamefaqs-url"));
                putStringIfMissing(row, "userSources", field(stanza, "x-user-sources"));
                putStringIfMissing(row, "criticSources", field(stanza, "x-critic-sources"));
                // Media repairs are applied to the exact ROM stanza after a
                // deeper provider audit. Preserve those paths just like the
                // enriched scores; otherwise a later startup scan can rebuild
                // an auto metafile from a thinner registry and erase them.
                putStringIfMissing(row, "boxArt", field(stanza, "assets.boxFront"));
                putStringIfMissing(row, "background", field(stanza, "assets.background"));
                putStringIfMissing(row, "backgroundSource", field(stanza, "x-background-source"));
                putStringIfMissing(row, "backgroundSourceUrl", field(stanza, "x-background-source-url"));
                putStringIfMissing(row, "backgroundTransform", field(stanza, "x-background-transform"));
                putStringIfMissing(row, "video", field(stanza, "assets.video"));
                // Recover a previously derived accent instead of decoding the
                // wallpaper again after a registry loss. Recording the source
                // alongside it keeps the refresh pass a no-op.
                String storedAccent = WallpaperAccent.sanitize(
                        field(stanza, WallpaperAccent.METADATA_FIELD));
                if (!storedAccent.isEmpty() &&
                        row.optString(WallpaperAccent.REGISTRY_FIELD).isEmpty()) {
                    row.put(WallpaperAccent.REGISTRY_FIELD, storedAccent);
                    row.put(WallpaperAccent.REGISTRY_SOURCE_FIELD,
                            accentSourceKey(row.optString("background")));
                }
                if (ensureBackgroundProvenance(row)) addedRows = true;
            } catch (Exception ignored) {}
        }
        return addedRows;
    }

    private static boolean ensureBackgroundProvenance(JSONObject row) {
        String background = row.optString("background");
        if (background.isEmpty() || !row.optString("backgroundSource").isEmpty()) return false;
        String source = inferBackgroundSource(background);
        try {
            row.put("backgroundSource", source);
            if ((BACKGROUND_SOURCE_SCREENSHOT.equals(source) ||
                    BACKGROUND_SOURCE_OFFICIAL.equals(source)) &&
                    row.optString("backgroundTransform").isEmpty())
                row.put("backgroundTransform", BACKGROUND_TRANSFORM);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String inferBackgroundSource(String background) {
        String normalized = background.replace('\\', '/').toLowerCase(Locale.US);
        if (normalized.contains("/game-wallpapers-screenshot-fallback/"))
            return BACKGROUND_SOURCE_SCREENSHOT;
        if (normalized.contains("/game-wallpapers-official-gallery/"))
            return BACKGROUND_SOURCE_OFFICIAL;
        if (normalized.contains("/game-wallpapers-launchbox-fanart/"))
            return BACKGROUND_SOURCE_LAUNCHBOX;
        if (normalized.contains("/game-wallpapers/"))
            return BACKGROUND_SOURCE_WALLPAPER;
        return "unclassified-existing-background";
    }

    private static void putNumericIfMissing(JSONObject row, String key, String raw, double scale) {
        if (row.optDouble(key, 0) > 0 || raw == null || raw.trim().isEmpty()) return;
        try {
            double value = Double.parseDouble(raw.trim().replace("%", "")) * scale;
            if (value > 0) row.put(key, value);
        } catch (Exception ignored) {}
    }

    private static void putStringIfMissing(JSONObject row, String key, String value) {
        if (!row.optString(key).isEmpty() || value == null || value.trim().isEmpty()) return;
        try { row.put(key, value.trim()); } catch (Exception ignored) {}
    }

    private static int metadataStanzaRichness(String stanza) {
        int score = 0;
        String[] valuable = {"x-critic", "x-user-score", "release", "x-gamefaqs-user",
                "x-gamerankings-score", "x-mobygames-critic", "x-metacritic-critic",
                "assets.boxFront", "assets.background", "x-background-source",
                "x-background-source-url", "assets.video"};
        for (String name : valuable)
            if (!field(stanza, name).isEmpty()) score++;
        return score;
    }

    private static void cleanupGeneratedMetadata(File directory, Set<String> keep) {
        File[] files = directory.listFiles((dir, name) ->
                name.startsWith("99-lucent-auto-") &&
                        name.endsWith(".metadata.pegasus.txt") &&
                        !name.equals(AUTO_METADATA.getName()));
        if (files == null) return;
        for (File file : files)
            if (!keep.contains(file.getName())) file.delete();
    }

    private static int metadataQuality(JSONObject value) {
        int quality = value.optInt("enrichmentVersion", 0) * 100;
        if (!value.optString("boxArt").isEmpty()) quality += 20;
        if (!value.optString("background").isEmpty()) quality += 12;
        if (!value.optString("video").isEmpty()) quality += 10;
        if (value.optInt("critic", 0) > 0) quality += 5;
        if (value.optDouble("user", 0) > 0) quality += 3;
        if (!value.optString("release").isEmpty()) quality += 2;
        return quality;
    }

    private static String dedupeGameIdentity(String title) {
        // Catalogs disagree about leading articles, ampersands, and whether a
        // subtitle separator is rendered as ':' or '&'. Those presentation
        // differences must not produce two cards for the same game. This key
        // is used only inside one platform; launch paths remain untouched.
        String relaxed = normalize(title)
                .replaceAll("\\b(?:the|a|an|and)\\b", " ")
                .replaceAll("\\s+", " ").trim();
        return scoreAlias(relaxed);
    }

    private String launchCommand(String system) {
        // EngineRouteStore is the single source of truth: internal by default
        // where a bundled engine exists, external as a per-system user choice or
        // automatically for a system with no internal engine. It fails closed
        // (returns "") only when neither an internal engine nor any supported
        // external emulator exists for the system.
        return EngineRouteStore.launchCommand(context, system);
    }

    /**
     * Re-emits every system's collection launch command after a route change.
     * Fresh metadata is regenerated from the registry (picking up the new route
     * via {@link #launchCommand}) and any on-disk metadata is normalized, then a
     * library reload is requested so Pegasus reads the new {@code launch:} lines.
     */
    synchronized boolean rewriteLaunchRoutes() {
        try {
            JSONArray registry = readRegistry();
            writeMetadata(registry);
            LaunchMetadataRouter.normalize(context);
            requestLibraryReload();
            return true;
        } catch (Exception error) {
            Log.e(TAG, "Unable to rewrite launch routes after a route change", error);
            return false;
        }
    }

    /** Flags the current status so the next /import/reload refreshes Lucent. */
    private void requestLibraryReload() {
        synchronized (statusLock) {
            try {
                status.put("needsReload", true);
                status.put("message", "Launch route updated • refreshing Lucent library…");
                status.put("updatedAt", System.currentTimeMillis());
            } catch (Exception ignored) {}
        }
    }

    private boolean installed(String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void appendMetadataList(StringBuilder out, String field, JSONArray values) {
        if (values == null) return;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < values.length(); i++) {
            String value = values.optString(i, "").trim();
            String identity = normalizeCompany(value);
            if (value.isEmpty() || identity.isEmpty() || !seen.add(identity)) continue;
            // Pegasus explicitly permits developer/publisher fields to appear
            // multiple times. Keep one company per field so legal commas in
            // names such as "Tecmo Co., Ltd." are never split incorrectly.
            out.append(field).append(": ").append(metadataSafe(value)).append('\n');
        }
    }

    private static GameSystems.SystemDef identify(File file, String extension) {
        try {
            String ext = extension.toLowerCase(Locale.US);
            GameSystems.SystemDef pathMatch = GameSystems.byPath(file);
            if (pathMatch != null) return pathMatch;
            long size = file.length();
            if (size < 32) return null;
            byte[] head = readRange(file, 0, 0x200);
            if ("nes".equals(ext) && starts(head, new byte[]{0x4e,0x45,0x53,0x1a})) return GameSystems.byFolder("nes");
            if (("unf".equals(ext) || "unif".equals(ext)) && starts(head, "UNIF".getBytes())) return GameSystems.byFolder("nes");
            if ("fds".equals(ext) && (starts(head, new byte[]{0x46,0x44,0x53,0x1a}) || size % 65500 == 0)) return GameSystems.byFolder("nes");
            if (("gb".equals(ext) || "gbc".equals(ext)) && matchesAt(head, 0x104, GB_LOGO)) {
                int cgb = head.length > 0x143 ? head[0x143] & 0xff : 0;
                return ("gbc".equals(ext) || cgb == 0x80 || cgb == 0xc0) ?
                        GameSystems.byFolder("gbc") : GameSystems.byFolder("gb");
            }
            if ("gba".equals(ext) && matchesAt(head, 0x04,
                    new byte[]{0x24,(byte)0xff,(byte)0xae,0x51,0x69,(byte)0x9a,(byte)0xa2,0x21}))
                return GameSystems.byFolder("gba");
            if ("nds".equals(ext) && size >= 0x4000 && printable(head, 0x0c, 4)) return GameSystems.byFolder("nds");
            if (("z64".equals(ext) || "n64".equals(ext) || "v64".equals(ext)) && n64Magic(head)) return GameSystems.byFolder("n64");
            if (("gen".equals(ext) || "md".equals(ext) || "bin".equals(ext)) && containsAt(head, 0x100, "SEGA")) return GameSystems.byFolder("megadrive");
            if ("gg".equals(ext) && gameGearMagic(file)) return GameSystems.byFolder("gamegear");
            if (("sfc".equals(ext) || "smc".equals(ext) || "fig".equals(ext)) && validSnes(file)) return GameSystems.byFolder("snes");
            if ("xci".equals(ext) && containsAt(readRange(file, 0x100, 0x20), 0, "HEAD")) return GameSystems.byFolder("switch");
            if ("nsp".equals(ext) && starts(head, "PFS0".getBytes())) return GameSystems.byFolder("switch");
            if (("3ds".equals(ext) || "cci".equals(ext)) && containsAt(head, 0x100, "NCSD")) return GameSystems.byFolder("n3ds");
            if ("cia".equals(ext) && little32(head, 0) >= 0x2020 && little32(head, 0) < 0x10000) return GameSystems.byFolder("n3ds");
            if ("vpk".equals(ext) && validVitaPackage(file)) return GameSystems.byFolder("psvita");
            if ("pbp".equals(ext) && starts(head, new byte[]{0x00,0x50,0x42,0x50})) return GameSystems.byFolder("psp");
            if ("cso".equals(ext) && starts(head, "CISO".getBytes())) return GameSystems.byFolder("psp");
            if ("pkg".equals(ext) && (starts(head, new byte[]{0x7f,0x50,0x4b,0x47}) || starts(head, "PKG".getBytes()))) return GameSystems.byFolder("ps3");
            if ("wbfs".equals(ext) && starts(head, "WBFS".getBytes())) return GameSystems.byFolder("wii");
            if ("rvz".equals(ext) && starts(head, "RVZ".getBytes())) {
                byte[] id = readRange(file, 88, 6);
                if (id.length == 6 && id[0] == 'G') return GameSystems.byFolder("gc");
                if (id.length == 6 && (id[0] == 'R' || id[0] == 'S'))
                    return GameSystems.byFolder("wii");
            }
            if ("wux".equals(ext) && starts(head, "WUX0".getBytes())) return GameSystems.byFolder("wiiu");
            if ("wua".equals(ext) && (starts(head, "WUA".getBytes()) || starts(head, "ZSTD".getBytes()))) return GameSystems.byFolder("wiiu");
            if ("iso".equals(ext)) return identifyIso(file);
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static GameSystems.SystemDef identifyIso(File file) throws Exception {
        byte[] head = readRange(file, 0, 4 * 1024 * 1024);
        if (containsAt(head, 0x18, new byte[]{0x5d,0x1c,(byte)0x9e,(byte)0xa3})) return GameSystems.byFolder("wii");
        if (containsAt(head, 0x1c, new byte[]{(byte)0xc2,0x33,(byte)0x9f,0x3d})) return GameSystems.byFolder("gc");
        String text = new String(head, StandardCharsets.ISO_8859_1);
        if (text.contains("PS3_GAME")) return GameSystems.byFolder("ps3");
        if (text.contains("PSP_GAME")) return GameSystems.byFolder("psp");
        if (text.contains("BOOT2") && text.contains("SYSTEM.CNF")) return GameSystems.byFolder("ps2");
        return null;
    }

    private static boolean validVitaPackage(File file) {
        try (ZipFile zip = new ZipFile(file)) {
            return zip.getEntry("sce_sys/param.sfo") != null;
        } catch (Exception ignored) { return false; }
    }

    private static boolean validSnes(File file) throws Exception {
        long size = file.length();
        if (size < 0x8000 || size > 16L * 1024L * 1024L) return false;
        int copier = size % 0x8000 == 512 ? 512 : 0;
        for (int offset : new int[]{0x7fc0 + copier, 0xffc0 + copier, 0x40ffc0 + copier}) {
            if (offset + 32 > size) continue;
            byte[] header = readRange(file, offset, 32);
            int complement = little16(header, 0x1c);
            int checksum = little16(header, 0x1e);
            if ((complement ^ checksum) == 0xffff && printableRatio(header, 0, 21) > 0.70) return true;
        }
        return false;
    }

    private static boolean gameGearMagic(File file) throws Exception {
        for (int offset : new int[]{0x1ff0, 0x3ff0, 0x7ff0}) {
            if (file.length() >= offset + 8 && containsAt(readRange(file, offset, 8), 0, "TMR SEGA")) return true;
        }
        return false;
    }

    private static File mediaRoot() {
        File storage = new File("/storage");
        File[] roots = storage.listFiles();
        if (roots != null) {
            for (File root : roots) {
                if (!root.isDirectory() || root.getName().equals("emulated") || root.getName().equals("self")) continue;
                File media = new File(root, "PegasusMedia");
                if ((media.isDirectory() || media.mkdirs()) && writable(media)) return media;
            }
        }
        File fallback = new File(Environment.getExternalStorageDirectory(), "PegasusMedia");
        fallback.mkdirs();
        return fallback;
    }

    private static JSONArray readRegistry() {
        try {
            if (!REGISTRY.isFile()) return new JSONArray();
            return new JSONArray(readText(REGISTRY));
        } catch (Exception ignored) { return new JSONArray(); }
    }

    private static void writeBackgroundProvenanceManifest(JSONArray registry, File mediaRoot) {
        LinkedHashMap<String, JSONObject> byRomPath = new LinkedHashMap<>();
        try {
            for (int index = 0; index < registry.length(); index++) {
                JSONObject row = registry.optJSONObject(index);
                if (row == null || row.optString("background").isEmpty()) continue;
                ensureBackgroundProvenance(row);
                putBackgroundProvenance(byRomPath, row.optString("file"),
                        row.optString("system"), row.optString("title"),
                        row.optString("background"), row.optString("backgroundSource"),
                        row.optString("backgroundSourceUrl"),
                        row.optString("backgroundTransform"));
            }
            // Include hand-authored/core collection metadata too. Registry
            // rows cover automatic imports, while Pegasus metafiles are the
            // final source of truth for the owner's entire collection.
            for (File metadata : metadataFiles()) {
                for (String stanza : splitStanzas(readText(metadata))) {
                    String romPath = field(stanza, "file");
                    String background = field(stanza, "assets.background");
                    if (romPath.isEmpty() || background.isEmpty()) continue;
                    GameSystems.SystemDef system = systemFromRomPath(romPath);
                    putBackgroundProvenance(byRomPath, romPath,
                            system == null ? "" : system.folder, field(stanza, "game"),
                            background, field(stanza, "x-background-source"),
                            field(stanza, "x-background-source-url"),
                            field(stanza, "x-background-transform"));
                }
            }
            JSONArray records = new JSONArray();
            for (JSONObject record : byRomPath.values()) records.put(record);
            writeJsonAtomic(new File(mediaRoot, "background-provenance.json"), records);
        } catch (Exception error) {
            Log.w(TAG, "Unable to write background provenance manifest", error);
        }
    }

    private static void putBackgroundProvenance(Map<String, JSONObject> records,
            String romPath, String system, String title, String background, String source,
            String sourceUrl, String transform) throws Exception {
        if (romPath.isEmpty() || background.isEmpty()) return;
        if (source.isEmpty()) source = inferBackgroundSource(background);
        JSONObject record = new JSONObject();
        record.put("romPath", romPath);
        record.put("system", system);
        record.put("title", cleanTitle(title));
        record.put("backgroundPath", background);
        record.put("source", source);
        record.put("sourceUrl", sourceUrl);
        record.put("transform", transform);
        record.put("filePresent", new File(background).isFile());
        records.put(canonical(romPath), record);
    }

    private static Set<String> registeredSources(JSONArray registry) {
        Set<String> result = new HashSet<>();
        for (int i = 0; i < registry.length(); i++) {
            JSONObject row = registry.optJSONObject(i);
            if (row != null) result.add(row.optString("sourceIdentity"));
        }
        return result;
    }

    private static JSONObject registeredGame(JSONArray registry, String identity) {
        for (int i = 0; i < registry.length(); i++) {
            JSONObject row = registry.optJSONObject(i);
            if (row != null && identity.equals(row.optString("sourceIdentity"))) return row;
        }
        return null;
    }

    private static JSONArray mergeRegistry(JSONArray registry, List<ImportedGame> games) {
        Map<String, JSONObject> byIdentity = new LinkedHashMap<>();
        for (int i = 0; i < registry.length(); i++) {
            JSONObject row = registry.optJSONObject(i);
            if (row != null) byIdentity.put(row.optString("sourceIdentity"), row);
        }
        for (ImportedGame game : games) byIdentity.put(game.sourceIdentity, game.toJson());
        JSONArray result = new JSONArray();
        for (JSONObject row : byIdentity.values()) result.put(row);
        return result;
    }

    private void setStatus(String state, double progress, String message, List<String> titles,
                           int added, boolean needsReload) {
        setDetailedStatus(state, progress, message, "", 0, 0, titles, added, needsReload);
    }

    private void setDetailedStatus(String state, double progress, String message, String detail,
                                   int current, int total, List<String> titles,
                                   int added, boolean needsReload) {
        JSONObject next = new JSONObject();
        try {
            next.put("state", state);
            next.put("progress", Math.max(0, Math.min(1, progress)));
            next.put("message", message);
            next.put("detail", detail == null ? "" : detail);
            next.put("current", Math.max(0, current));
            next.put("total", Math.max(0, total));
            next.put("titles", new JSONArray(titles));
            next.put("identified", titles.size());
            next.put("added", added);
            next.put("needsReload", needsReload);
            next.put("running", !"complete".equals(state) && !"error".equals(state) && !"idle".equals(state));
            next.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        synchronized (statusLock) { status = next; }
    }

    private static JSONObject idleStatus() {
        JSONObject value = new JSONObject();
        try {
            value.put("state", "idle"); value.put("progress", 0);
            value.put("message", "Library importer ready"); value.put("titles", new JSONArray());
            value.put("detail", ""); value.put("current", 0); value.put("total", 0);
            value.put("identified", 0); value.put("added", 0);
            value.put("needsReload", false); value.put("running", false);
        } catch (Exception ignored) {}
        return value;
    }

    private static final class Candidate {
        final File source;
        final String zipEntry;
        final GameSystems.SystemDef system;
        String title;
        final String identity;
        final boolean inPlace;
        Candidate(File source, String zipEntry, GameSystems.SystemDef system, String title, String identity) {
            this(source, zipEntry, system, title, identity, false);
        }
        Candidate(File source, String zipEntry, GameSystems.SystemDef system, String title,
                  String identity, boolean inPlace) {
            this.source = source; this.zipEntry = zipEntry; this.system = system;
            this.title = title; this.identity = identity; this.inPlace = inPlace;
        }
    }

    private static final class ImportedGame {
        final GameSystems.SystemDef system;
        final String sourceIdentity;
        final String title;
        final File rom;
        String boxArt = "";
        String background = "";
        String backgroundSource = "";
        String backgroundSourceUrl = "";
        String backgroundTransform = "";
        // Precomputed complementary accent and the wallpaper it came from.
        String accent = "";
        String accentSource = "";
        String video = "";
        String release = "";
        String metacriticSlug = "";
        String scoreSource = "";
        String gamerankingsUrl = "";
        String mobygamesUrl = "";
        String gamefaqsUrl = "";
        String userSources = "";
        String criticSources = "";
        final List<String> developers = new ArrayList<>();
        final List<String> publishers = new ArrayList<>();
        double user;
        double metacriticUser;
        double gamefaqsUser;
        double gamerankingsScore;
        double mobygamesScore;
        int critic;
        int metacriticCritic;
        int metacriticReviews;
        int gamerankingsReviews;
        boolean archived;
        boolean enriched;
        long addedAt;
        ImportedGame(Candidate candidate, File rom) {
            this.system = candidate.system; this.sourceIdentity = candidate.identity;
            this.title = candidate.title; this.rom = rom;
            this.addedAt = System.currentTimeMillis();
        }

        private ImportedGame(GameSystems.SystemDef system, String sourceIdentity,
                             String title, File rom) {
            this.system = system; this.sourceIdentity = sourceIdentity;
            this.title = title; this.rom = rom;
            this.addedAt = rom.isFile() && rom.lastModified() > 0L ?
                    rom.lastModified() : System.currentTimeMillis();
        }

        static ImportedGame fromJson(JSONObject value) {
            GameSystems.SystemDef system = GameSystems.byFolder(value.optString("system"));
            File rom = new File(value.optString("file"));
            if (system == null || !rom.isFile()) return null;
            ImportedGame game = new ImportedGame(system,
                    value.optString("sourceIdentity"), cleanTitle(value.optString("title")), rom);
            game.boxArt = value.optString("boxArt");
            game.background = value.optString("background");
            game.backgroundSource = value.optString("backgroundSource");
            game.backgroundSourceUrl = value.optString("backgroundSourceUrl");
            game.backgroundTransform = value.optString("backgroundTransform");
            game.accent = WallpaperAccent.sanitize(
                    value.optString(WallpaperAccent.REGISTRY_FIELD));
            game.accentSource = value.optString(WallpaperAccent.REGISTRY_SOURCE_FIELD);
            game.inferBackgroundProvenance();
            game.video = value.optString("video");
            game.release = value.optString("release");
            game.metacriticSlug = value.optString("metacriticSlug");
            game.scoreSource = value.optString("scoreSource");
            game.gamerankingsUrl = value.optString("gamerankingsUrl");
            game.mobygamesUrl = value.optString("mobygamesUrl");
            game.gamefaqsUrl = value.optString("gamefaqsUrl");
            game.userSources = value.optString("userSources");
            game.criticSources = value.optString("criticSources");
            game.user = value.optDouble("user", 0);
            game.metacriticUser = value.has("metacriticUser") ?
                    value.optDouble("metacriticUser", 0) : game.user;
            game.gamefaqsUser = value.optDouble("gamefaqsUser", 0);
            game.gamerankingsScore = value.optDouble("gamerankingsScore", 0);
            game.mobygamesScore = value.optDouble("mobygamesScore", 0);
            game.critic = value.optInt("critic", 0);
            game.metacriticCritic = value.optInt("metacriticCritic", 0);
            game.metacriticReviews = value.optInt("metacriticReviews", 0);
            game.gamerankingsReviews = value.optInt("gamerankingsReviews", 0);
            game.archived = value.optBoolean("archived", false);
            game.addedAt = value.optLong("addedAt", game.addedAt);
            game.enriched = value.optInt("enrichmentVersion", 0) >= 2;
            copyJsonStrings(value.optJSONArray("developers"), game.developers);
            copyJsonStrings(value.optJSONArray("publishers"), game.publishers);
            return game;
        }

        private static void copyJsonStrings(JSONArray values, List<String> destination) {
            if (values == null) return;
            for (int i = 0; i < values.length(); i++) {
                String value = values.optString(i, "").trim();
                if (!value.isEmpty()) destination.add(value);
            }
        }

        JSONObject toJson() {
            JSONObject value = new JSONObject();
            try {
                value.put("sourceIdentity", sourceIdentity); value.put("system", system.folder);
                value.put("title", title); value.put("file", rom.getAbsolutePath());
                value.put("addedAt", addedAt);
                value.put("boxArt", boxArt); value.put("background", background);
                value.put("backgroundSource", backgroundSource);
                value.put("backgroundSourceUrl", backgroundSourceUrl);
                value.put("backgroundTransform", backgroundTransform);
                value.put(WallpaperAccent.REGISTRY_FIELD, accent);
                value.put(WallpaperAccent.REGISTRY_SOURCE_FIELD, accentSource);
                value.put("video", video);
                value.put("user", user); value.put("critic", critic);
                value.put("metacriticUser", metacriticUser);
                value.put("gamefaqsUser", gamefaqsUser);
                value.put("release", release); value.put("metacriticSlug", metacriticSlug);
                value.put("scoreSource", scoreSource);
                value.put("userSources", userSources);
                value.put("criticSources", criticSources);
                value.put("metacriticCritic", metacriticCritic);
                value.put("metacriticReviews", metacriticReviews);
                value.put("gamerankingsScore", gamerankingsScore);
                value.put("gamerankingsReviews", gamerankingsReviews);
                value.put("gamerankingsUrl", gamerankingsUrl);
                value.put("mobygamesScore", mobygamesScore);
                value.put("mobygamesUrl", mobygamesUrl);
                value.put("gamefaqsUrl", gamefaqsUrl);
                value.put("enrichmentVersion", enriched ? 2 : 0);
                value.put("archived", archived);
                value.put("archiveReason", archived ? "missing-box-art" : "");
                value.put("forceInclude", false);
                value.put("developers", new JSONArray(developers));
                value.put("publishers", new JSONArray(publishers));
            } catch (Exception ignored) {}
            return value;
        }

        void inferBackgroundProvenance() {
            if (background.isEmpty() || !backgroundSource.isEmpty()) return;
            backgroundSource = inferBackgroundSource(background);
            if ((BACKGROUND_SOURCE_SCREENSHOT.equals(backgroundSource) ||
                    BACKGROUND_SOURCE_OFFICIAL.equals(backgroundSource)) &&
                    backgroundTransform.isEmpty()) backgroundTransform = BACKGROUND_TRANSFORM;
        }
    }

    private static final class CatalogMatch {
        final String url; final String title;
        CatalogMatch(String url, String title) { this.url = url; this.title = title; }
    }
    private static final class VideoMatch {
        final String url;
        VideoMatch(String url) { this.url = url; }
    }
    private static final class NintendoMedia {
        final String cover;
        final String background;
        final String video;
        NintendoMedia(String cover, String background, String video) {
            this.cover = cover == null ? "" : cover;
            this.background = background == null ? "" : background;
            this.video = video == null ? "" : video;
        }
    }
    private static final class GameRankingsRecord {
        final double score;
        final int reviews;
        final String year;
        final String id;
        final String url;
        GameRankingsRecord(double score, int reviews, String year, String id, String url) {
            this.score = score; this.reviews = reviews; this.year = year; this.id = id; this.url = url;
        }
    }
    private static final class MobyGamesRecord {
        final double score;
        final String year;
        final String developer;
        final String url;
        MobyGamesRecord(double score, String year, String developer, String url) {
            this.score = score; this.year = year; this.developer = developer; this.url = url;
        }
    }
    private static final class BundledGameRecord {
        final String title;
        final double critic;
        final double user;
        final String release;
        final String developer;
        final String publisher;
        final String criticSources;
        final String userSources;
        BundledGameRecord(String title, double critic, double user, String release,
                          String developer, String publisher, String criticSources,
                          String userSources) {
            this.title = title;
            this.critic = critic;
            this.user = user;
            this.release = release;
            this.developer = developer;
            this.publisher = publisher;
            this.criticSources = criticSources;
            this.userSources = userSources;
        }
    }

    // ----- Small, defensive IO/string helpers -----

    private static boolean isPartial(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".part") || lower.endsWith(".partial") || lower.endsWith(".crdownload") ||
                lower.endsWith(".tmp") || lower.endsWith(".download");
    }
    private static String downloadFingerprint() {
        List<String> parts = new ArrayList<>();
        for (File downloadRoot : downloadRoots()) {
            File[] entries = downloadRoot.listFiles();
            if (entries == null) continue;
            for (File file : entries) {
                if (file.isFile())
                    parts.add(canonical(file.getAbsolutePath()) + ":" + file.length() +
                            ":" + file.lastModified());
            }
        }
        Collections.sort(parts);
        return sha1(parts.toString());
    }

    /** Returns the standard internal Download folder and every readable
     * removable-storage Download/Downloads folder, with canonical deduping. */
    private static List<File> downloadRoots() {
        LinkedHashMap<String, File> roots = new LinkedHashMap<>();
        addDownloadRoot(roots, DOWNLOADS);

        File[] storageRoots = new File("/storage").listFiles();
        if (storageRoots != null) {
            List<File> sorted = new ArrayList<>();
            Collections.addAll(sorted, storageRoots);
            sorted.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File storageRoot : sorted) {
                String name = storageRoot.getName();
                if (!storageRoot.isDirectory() || "emulated".equals(name) || "self".equals(name))
                    continue;
                addDownloadRoot(roots, new File(storageRoot, "Download"));
                addDownloadRoot(roots, new File(storageRoot, "Downloads"));
            }
        }
        return new ArrayList<>(roots.values());
    }

    private static void addDownloadRoot(Map<String, File> roots, File directory) {
        if (directory == null || !directory.isDirectory() || !directory.canRead()) return;
        roots.put(canonical(directory.getAbsolutePath()), directory);
    }
    private static boolean isPotentialAmbiguous(String ext) {
        return "iso".equals(ext) || "bin".equals(ext) || "rvz".equals(ext);
    }
    private static String extension(String name) {
        int query = name.indexOf('?'); if (query >= 0) name = name.substring(0, query);
        int slash = name.lastIndexOf('/');
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot < slash ? "" : name.substring(dot + 1).toLowerCase(Locale.US);
    }
    private static String stem(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf(File.separatorChar));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.'); return dot > 0 ? name.substring(0, dot) : name;
    }
    private static String cleanTitle(String value) {
        String title = value.replaceAll("\\s*_+\\s*", ": ").trim();
        title = title.replaceAll("(?i)(?:\\s*[:\\-]\\s*)copy$", "").trim();
        // Switch dumps commonly append a 16-digit title ID and version tag.
        // They are identifiers, not part of the display title, and prevent
        // exact media/score matching if retained.
        title = title.replaceAll("(?i)[.\\s-]*[0-9a-f]{16}(?:[.\\s-]*v\\d+)?$", "")
                .replaceAll("(?i)\\s*\\[[0-9a-f]{16}\\]", "")
                .replaceAll("(?i)\\s*\\[v\\d+\\]", "")
                .replaceAll("(?i)\\s+v\\d+(?:[ .]\\d+)+$", "")
                .replaceAll("(?i)\\s+[—:-]\\s+disc\\s+\\d+(?:\\s+of\\s+\\d+)?$", "")
                .replaceAll("(?i)\\s*\\((?:USA|US|U|Europe|EU|E|Japan|JP|J|W|UE)\\b.*$", "")
                .trim();
        // Dot-separated scene/download names are filenames, not display
        // titles. Canonical catalog matching can restore legitimate internal
        // punctuation after this normalization.
        if (!title.contains(" ") && title.matches(".*[A-Za-z0-9]\\.[A-Za-z0-9].*"))
            title = title.replace('.', ' ');
        title = title.replaceFirst(
                "(?i)\\s*\\((?:19|20)[0-9x]{2}(?:[-.][0-9x]{1,2}){0,2}\\).*$", "");
        String previous;
        do {
            previous = title;
            title = title.replaceAll(
                    "(?i)\\s*[\\[(](?:USA|US|U|Europe|EU|E|Japan|JP|J|World|" +
                    "En(?:,[A-Za-z]+)*|Rev[^\\])]*|v?\\d+(?:[ .]\\d+)*|!|b|h|n|p|" +
                    "t[^\\])]*|Unl(?:icensed)?|Beta|Proto(?:type)?|Demo|" +
                    "Kiosk|Bonus Disc|Virtual Console|NTSC|PAL|" +
                    "Disc\\s+\\d+(?:\\s+of\\s+\\d+)?)[\\])]\\s*$",
                    "").trim();
        } while (!title.equals(previous));
        title = title.replaceAll("\\s+-\\s+", ": ").replaceAll("\\s+", " ").trim();
        Matcher embeddedArticle = Pattern.compile("^(.+), (The|A|An)(\\s*[:\\-].*)$",
                Pattern.CASE_INSENSITIVE).matcher(title);
        if (embeddedArticle.matches())
            title = embeddedArticle.group(2) + " " + embeddedArticle.group(1) +
                    embeddedArticle.group(3);
        Matcher article = Pattern.compile("^(.+), (The|A|An)$", Pattern.CASE_INSENSITIVE).matcher(title);
        if (article.matches()) title = article.group(2) + " " + article.group(1);
        Matcher zeldaSubtitle = Pattern.compile(
                "^(The Legend of Zelda) (Collector's Edition|Four Swords Adventures)$",
                Pattern.CASE_INSENSITIVE).matcher(title);
        if (zeldaSubtitle.matches())
            title = zeldaSubtitle.group(1) + ": " + zeldaSubtitle.group(2);
        return title.isEmpty() ? "Untitled Game" : title;
    }

    private static boolean looksLikeDumpTitle(String value) {
        return value != null && (value.matches("(?is).*\\((?:19|20)[0-9x]{2}.*") ||
                value.matches("(?is).*[\\[(](?:USA|US|U|Europe|EU|E|Japan|JP|J|World|" +
                        "Beta|Proto(?:type)?|Unl(?:icensed)?|Rev[^\\])]*|T-En[^\\])]*)" +
                        "[\\])].*"));
    }

    private static boolean isSwitchExtension(String extension) {
        return "nsp".equals(extension) || "xci".equals(extension);
    }

    private static boolean isSwitchSupplementalName(String value) {
        String normalized = value == null ? "" : value.replace('\\', '/').toLowerCase(Locale.US);
        if (normalized.matches(".*/(?:updates?|dlc|add-ons?)/.*")) return true;
        String words = normalized.replaceAll("[^a-z0-9]+", " ").trim();
        if (words.matches(".*(?:^| )(?:update|upd|dlc)(?: |$).*") ||
                words.contains("downloadable content")) return true;
        Matcher ids = Pattern.compile("(?i)([0-9a-f]{16})").matcher(value == null ? "" : value);
        while (ids.find()) {
            // Nintendo Switch update title IDs use the 0x800 program suffix.
            if (ids.group(1).toLowerCase(Locale.US).endsWith("800")) return true;
        }
        return false;
    }
    private static void addUnique(List<String> values, String value) {
        String clean = value == null ? "" : value.trim();
        String identity = normalizeCompany(clean);
        if (clean.isEmpty() || identity.isEmpty()) return;
        for (String existing : values)
            if (normalizeCompany(existing).equals(identity)) return;
        values.add(clean);
    }
    private static String normalizeCompany(String value) {
        String key = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.US)
                .replace("&", " and ").replaceAll("[^a-z0-9]+", " ").trim();
        for (int pass = 0; pass < 3; pass++)
            key = key.replaceAll("\\s+(incorporated|inc|corporation|corp|company|co|limited|ltd|pty|sa)$", "");
        return key;
    }
    private static String normalize(String value) {
        String title = decode(value);
        int slash = Math.max(title.lastIndexOf('/'), title.lastIndexOf(File.separatorChar));
        if (slash >= 0) title = title.substring(slash + 1);
        String lower = title.toLowerCase(Locale.US);
        String[] knownExtensions = new String[]{
                ".png", ".jpg", ".jpeg", ".webp", ".mp4", ".zip", ".7z",
                ".nes", ".unf", ".unif", ".fds", ".sfc", ".smc", ".fig",
                ".n64", ".z64", ".v64", ".gb", ".gbc", ".gba", ".nds",
                ".gg", ".gen", ".md", ".bin", ".cue", ".gdi", ".chd",
                ".iso", ".cso", ".rvz", ".wbfs", ".xci", ".nsp", ".wua",
                ".wux", ".3ds", ".3dsx", ".cia", ".cci", ".vpk", ".pbp"
        };
        for (String extension : knownExtensions) {
            if (lower.endsWith(extension)) {
                title = title.substring(0, title.length() - extension.length());
                break;
            }
        }
        String previous;
        do { previous = title; title = title.replaceAll("\\([^()]*\\)|\\[[^\\[\\]]*]", " "); }
        while (!title.equals(previous));
        title = Normalizer.normalize(title, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        title = title.toLowerCase(Locale.US).replace("&", " and ");
        return title.replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }
    private static int regionRank(String value) {
        String lower = decode(value).toLowerCase(Locale.US);
        if (lower.contains("(usa") || lower.contains("(us)")) return 0;
        if (lower.contains("(world")) return 1;
        if (lower.contains("(europe")) return 2;
        if (lower.contains("(japan")) return 4;
        return 3;
    }
    private static String safeFilename(String value) {
        String clean = value.replaceAll("[\\x00-\\x1f/:*?\"<>|]", " ").replaceAll("\\s+", " ").trim();
        return clean.length() > 180 ? clean.substring(0, 180).trim() : clean;
    }
    private static String metadataSafe(String value) { return value.replace('\n', ' ').replace('\r', ' ').trim(); }
    private static String canonical(String path) { try { return new File(path).getCanonicalPath(); } catch (Exception e) { return path; } }
    private static String decode(String value) { try { return URLDecoder.decode(value, "UTF-8"); } catch (Exception e) { return value; } }
    private static String encodePath(String value) { try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); } catch (Exception e) { return value; } }
    private static String encodeHref(String value) {
        String[] parts = value.split("/", -1); StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) { if (i > 0) out.append('/'); out.append(encodePath(decode(parts[i]))); }
        return out.toString();
    }
    private static String sha1(String value) {
        try { MessageDigest md = MessageDigest.getInstance("SHA-1"); byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (byte b : digest) out.append(String.format(Locale.US, "%02x", b)); return out.substring(0, 16);
        } catch (Exception e) { return Integer.toHexString(value.hashCode()); }
    }
    private static String format(double value) { return value == Math.rint(value) ? String.valueOf((long)value) : String.format(Locale.US, "%.1f", value); }
    private static String formatScore(double value) { String result=String.format(Locale.US,"%.2f",value); return result.replaceAll("0+$","").replaceAll("\\.$",""); }
    private static String join(List<String> values, String separator) { StringBuilder out=new StringBuilder(); for(String value:values){if(out.length()>0)out.append(separator);out.append(value);}return out.toString(); }
    private static String shortError(Throwable error) { String value = error.getMessage(); return value == null ? error.getClass().getSimpleName() : value; }

    private static byte[] readRange(File file, long offset, int length) throws Exception {
        byte[] data = new byte[length]; int total = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            long skipped = 0; while (skipped < offset) { long value = input.skip(offset - skipped); if (value <= 0) break; skipped += value; }
            while (total < length) { int count = input.read(data, total, length - total); if (count < 0) break; total += count; }
        }
        if (total == data.length) return data;
        byte[] shortData = new byte[total]; System.arraycopy(data, 0, shortData, 0, total); return shortData;
    }
    private static boolean starts(byte[] data, byte[] expected) { return matchesAt(data, 0, expected); }
    private static boolean containsAt(byte[] data, int offset, String value) { return matchesAt(data, offset, value.getBytes(StandardCharsets.ISO_8859_1)); }
    private static boolean containsAt(byte[] data, int offset, byte[] value) { return matchesAt(data, offset, value); }
    private static boolean matchesAt(byte[] data, int offset, byte[] expected) {
        if (offset < 0 || offset + expected.length > data.length) return false;
        for (int i = 0; i < expected.length; i++) if (data[offset + i] != expected[i]) return false;
        return true;
    }
    private static boolean n64Magic(byte[] h) { return starts(h,new byte[]{(byte)0x80,0x37,0x12,0x40}) || starts(h,new byte[]{0x37,(byte)0x80,0x40,0x12}) || starts(h,new byte[]{0x40,0x12,0x37,(byte)0x80}); }
    private static boolean printable(byte[] data, int offset, int length) { return printableRatio(data, offset, length) > 0.75; }
    private static double printableRatio(byte[] data, int offset, int length) { if (offset + length > data.length) return 0; int count=0; for(int i=offset;i<offset+length;i++){int c=data[i]&0xff;if(c>=0x20&&c<=0x7e)count++;} return count/(double)length; }
    private static int little16(byte[] data, int offset) { return offset+1<data.length ? (data[offset]&0xff)|((data[offset+1]&0xff)<<8) : 0; }
    private static int little32(byte[] data, int offset) { return offset+3<data.length ? (data[offset]&0xff)|((data[offset+1]&0xff)<<8)|((data[offset+2]&0xff)<<16)|((data[offset+3]&0xff)<<24) : 0; }

    private static void copy(File source, File target) throws Exception {
        target.getParentFile().mkdirs();
        try (InputStream in = new BufferedInputStream(new FileInputStream(source)); OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buffer = new byte[1024 * 1024]; int count; while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
        }
        if (target.length() != source.length()) throw new java.io.IOException("copy verification failed");
    }
    private static void extract(ZipFile zip, ZipEntry entry, File target) throws Exception {
        extract(zip, entry, target, Long.MAX_VALUE);
    }
    private static void extract(ZipFile zip, ZipEntry entry, File target, long limit)
            throws Exception {
        if (entry.getSize() > MAX_ARCHIVE_ENTRY_BYTES)
            throw new java.io.IOException("archive entry is too large");
        target.getParentFile().mkdirs();
        try (InputStream in = new BufferedInputStream(zip.getInputStream(entry)); OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buffer = new byte[1024 * 1024]; int count; long total=0;
            while (total < limit && (count=in.read(buffer, 0,
                    (int)Math.min(buffer.length, limit - total)))>=0){out.write(buffer,0,count);total+=count;}
            if (limit == Long.MAX_VALUE && entry.getSize() >= 0 && total != entry.getSize())
                throw new java.io.IOException("archive extraction verification failed");
        }
    }
    private static void extract(SevenZFile sevenZ, SevenZArchiveEntry entry, File target,
                                long limit) throws Exception {
        if (entry.getSize() < 0 || entry.getSize() > MAX_ARCHIVE_ENTRY_BYTES)
            throw new java.io.IOException("archive entry has an unsafe size");
        target.getParentFile().mkdirs();
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buffer = new byte[1024 * 1024]; int count; long total = 0;
            while (total < limit && (count = sevenZ.read(buffer, 0,
                    (int)Math.min(buffer.length, limit - total))) >= 0) {
                out.write(buffer, 0, count);
                total += count;
            }
            if (limit == Long.MAX_VALUE && total != entry.getSize())
                throw new java.io.IOException("7z extraction verification failed");
        }
    }
    private static void extractSevenZipEntry(File archive, String entryName, File target)
            throws Exception {
        try (SevenZFile sevenZ = new SevenZFile(archive, SEVEN_Z_OPTIONS)) {
            SevenZArchiveEntry entry;
            while ((entry = sevenZ.getNextEntry()) != null) {
                if (!entry.isDirectory() && entryName.equals(entry.getName())) {
                    extract(sevenZ, entry, target, Long.MAX_VALUE);
                    return;
                }
            }
        }
        throw new java.io.IOException("7z entry is missing");
    }
    private static boolean sameContent(File left, File right) {
        if (left.length() != right.length()) return false;
        try (InputStream a = new BufferedInputStream(new FileInputStream(left));
             InputStream b = new BufferedInputStream(new FileInputStream(right))) {
            byte[] ab = new byte[1024 * 1024], bb = new byte[1024 * 1024];
            while (true) {
                int an = a.read(ab), bn = b.read(bb);
                if (an != bn) return false;
                if (an < 0) return true;
                for (int i = 0; i < an; i++) if (ab[i] != bb[i]) return false;
            }
        } catch (Exception ignored) { return false; }
    }
    private static String readText(File file) throws Exception { StringBuilder out=new StringBuilder(); try(BufferedReader r=new BufferedReader(new FileReader(file))){String line;while((line=r.readLine())!=null)out.append(line).append('\n');} return out.toString(); }
    private static void writeTextAtomic(File target, String value) throws Exception {
        target.getParentFile().mkdirs();
        File temp = new File(target.getParentFile(), "." + target.getName() + ".writing");
        File backup = new File(target.getParentFile(), target.getName() + ".bak");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (backup.exists()) backup.delete();
        if (target.exists() && !target.renameTo(backup)) {
            temp.delete();
            throw new java.io.IOException("cannot back up " + target);
        }
        if (!temp.renameTo(target)) {
            if (backup.exists()) backup.renameTo(target);
            throw new java.io.IOException("cannot commit " + target);
        }
    }
    private static void writeCompatibilityMirror(File target, String value) {
        try {
            writeTextAtomic(target, value);
        } catch (Exception inaccessibleLegacyPackageDirectory) {
            // Android 11+ forbids writing another package's Android/data tree,
            // even with broad shared-storage access. The com.thorium.preview
            // metadata above is authoritative; this mirror exists only for
            // in-place migrations from the retired Pegasus package.
            Log.i(TAG, "Legacy Pegasus metadata mirror is unavailable: " + target);
        }
    }
    private static void writeJsonAtomic(File target, JSONArray value) throws Exception { writeTextAtomic(target, value.toString(2)+"\n"); }
    private static List<String> splitStanzas(String text) {
        List<String> values = new ArrayList<>();
        // Hand-authored and older generated Pegasus metadata commonly starts
        // the next game immediately after the previous asset field, without a
        // blank separator. Treat every column-zero `game:` as a record boundary
        // in addition to accepting conventional blank-line stanzas.
        for (String item : text.split("(?m)(?=^game:)|\\n\\s*\\n"))
            if (!item.trim().isEmpty()) values.add(item.trim());
        return values;
    }
    private static String joinStanzas(List<String> values) { StringBuilder out=new StringBuilder(); for(String value:values){if(out.length()>0)out.append("\n\n");out.append(value.trim());}return out.append('\n').toString(); }
    private static String field(String stanza, String key) { String prefix=key+":"; for(String line:stanza.split("\\n"))if(line.startsWith(prefix))return line.substring(prefix.length()).trim(); return ""; }
    private static String removeField(String stanza, String key) { String prefix=key+":"; StringBuilder out=new StringBuilder(); for(String line:stanza.split("\\n")){if(line.startsWith(prefix))continue;if(out.length()>0)out.append('\n');out.append(line);}return out.toString(); }
    private static GameSystems.SystemDef systemFromRomPath(String path) { String marker="/Games/"; int at=path.indexOf(marker); if(at<0)return null; String rest=path.substring(at+marker.length()); int slash=rest.indexOf('/'); return GameSystems.byFolder(slash<0?rest:rest.substring(0,slash)); }

    private static String cachedText(File cache, String url, long maxAge, long maxBytes) throws Exception {
        if (cache.isFile() && System.currentTimeMillis() - cache.lastModified() < maxAge) return readText(cache);
        byte[] bytes = fetchBytes(url, maxBytes); String value = new String(bytes, StandardCharsets.UTF_8); writeTextAtomic(cache, value); return value;
    }
    private static byte[] fetchBytes(String url, long maxBytes) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection(); connection.setConnectTimeout(15000);connection.setReadTimeout(45000);connection.setRequestProperty("User-Agent",USER_AGENT);connection.setInstanceFollowRedirects(true);
        if (url.contains("metacritic.com")) { connection.setRequestProperty("Origin", "https://www.metacritic.com"); connection.setRequestProperty("Referer", "https://www.metacritic.com/"); }
        int status=connection.getResponseCode();if(status<200||status>=300)throw new java.io.IOException("HTTP "+status);
        try(InputStream in=new BufferedInputStream(connection.getInputStream());ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[65536];int count;long total=0;while((count=in.read(buffer))>=0){total+=count;if(total>maxBytes)throw new java.io.IOException("response too large");out.write(buffer,0,count);}return out.toByteArray();}finally{connection.disconnect();}
    }
    private static boolean download(String url, File target, long maxBytes) throws Exception {
        if(target.isFile()&&target.length()>512)return true; target.getParentFile().mkdirs(); File part=new File(target.getAbsolutePath()+".part"); HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(60000);c.setRequestProperty("User-Agent",USER_AGENT);c.setInstanceFollowRedirects(true);int code=c.getResponseCode();if(code<200||code>=300){c.disconnect();return false;}long total=0;try(InputStream in=new BufferedInputStream(c.getInputStream());OutputStream out=new BufferedOutputStream(new FileOutputStream(part))){byte[] b=new byte[1024*1024];int n;while((n=in.read(b))>=0){total+=n;if(total>maxBytes)throw new java.io.IOException("download too large");out.write(b,0,n);}}finally{c.disconnect();}if(total<512){part.delete();return false;}if(target.exists())target.delete();return part.renameTo(target);
    }
    private static boolean hasPlatform(JSONArray platforms, String expected) { if(platforms==null)return false; for(int i=0;i<platforms.length();i++){JSONObject p=platforms.optJSONObject(i);if(p!=null&&p.optString("name").equalsIgnoreCase(expected))return true;}return false; }
    private static boolean writable(File directory) {
        File probe = new File(directory, ".thorium-write-test");
        try { if (!probe.createNewFile()) return false; return probe.delete(); }
        catch (Exception ignored) { return false; }
    }
    private static boolean recentlyMissed(File cacheRoot, GameSystems.SystemDef system, String title) { File f=new File(cacheRoot,"misses/"+system.folder+"/"+sha1(normalize(title)));return f.isFile()&&System.currentTimeMillis()-f.lastModified()<MISS_RETRY_MS; }
    private static void recordMiss(File cacheRoot, GameSystems.SystemDef system, String title) { try { File f=new File(cacheRoot,"misses/"+system.folder+"/"+sha1(normalize(title)));f.getParentFile().mkdirs();new FileOutputStream(f).close(); } catch(Exception ignored){} }
}
