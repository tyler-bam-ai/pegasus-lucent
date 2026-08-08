package com.thorium.preview.game;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Resolves the validated per-engine system directory a Phase 3 native adapter
 * is handed as {@code lucent_native_load_request.system_directory}.
 *
 * This mirrors {@link LibretroEngineSpec}'s {@code installSystem}/
 * {@code installRuntime} contract: the engine root is always the app-private
 * {@code engine-system/<engineId>} directory, and any user-supplied runtime
 * input is copied INTO that private root before the engine reads it. Nothing
 * here is ever bundled in the APK.
 *
 * Eden's adapter calls {@code Common::FS::SetAppDirectory(system_directory)} and
 * fails closed without it, so for Switch the root must additionally be populated
 * with the console keys and system firmware the user already owns. Those live
 * outside the app (the reference layout is {@code /storage/emulated/0/Games/
 * switch/Keys/} plus a {@code Firmware*.zip} beside it) and are discovered by a
 * bounded, case-insensitive scan of the same volume roots Phase 2 uses for BIOS
 * files. When they are missing this throws rather than letting the engine boot
 * into an undecryptable state that would look like a hang.
 */
final class NativeAdapterSystemDirectory {
    private static final String TAG = "LucentPhase3System";

    /* Layout Eden derives from the app directory (Common::FS::GetYuzuPath). */
    private static final String KEYS_DIRECTORY = "keys";
    private static final String FIRMWARE_DIRECTORY = "nand/system/Contents/registered";
    private static final String REQUIRED_KEY = "prod.keys";
    private static final String OPTIONAL_KEY = "title.keys";
    private static final String FIRMWARE_MARKER = ".lucent-firmware-source";
    private static final int MAX_SCAN_DEPTH = 3;

    private NativeAdapterSystemDirectory() {}

    /**
     * Creates (and, where a profile requires it, populates) the engine's
     * private system root.
     *
     * @param requiredFirmware the adapter's own {@code required_firmware} count.
     *        Zero means the engine declares no user-supplied inputs and only the
     *        empty private root is needed.
     * @throws IllegalStateException when the root cannot be created, or when an
     *         engine that requires user-supplied keys/firmware has none.
     */
    static File resolve(Context context, String engineId, String systemId,
                        int requiredFirmware) throws Exception {
        if (context == null) throw new IllegalArgumentException("context is required");
        String engine = normalize(engineId);
        if (engine.isEmpty()) throw new IllegalArgumentException("engine id is required");
        File root = new File(context.getDir("engine-system", Context.MODE_PRIVATE), engine);
        if (!root.isDirectory() && !root.mkdirs())
            throw new IllegalStateException("Cannot create adapter system directory");
        if (requiredFirmware <= 0) return root;
        if (!isSwitchProfile(engine, systemId))
            // Fail closed: an adapter that declares user-supplied firmware but
            // has no audited resolution profile must not boot with an empty root.
            throw new IllegalStateException(
                    "No user firmware profile is available for " + engine);
        installSwitchRuntimeInputs(root);
        return root;
    }

    /** True when the private root already holds everything Eden needs to boot. */
    static boolean hasSwitchRuntimeInputs(File root) {
        return root != null && isReadableFile(new File(root, KEYS_DIRECTORY + "/" + REQUIRED_KEY))
                && countFirmware(new File(root, FIRMWARE_DIRECTORY)) > 0;
    }

    private static boolean isSwitchProfile(String engineId, String systemId) {
        return "eden".equals(engineId) && "switch".equals(normalize(systemId));
    }

    private static void installSwitchRuntimeInputs(File root) throws Exception {
        installKeys(root);
        installFirmware(root);
        if (!hasSwitchRuntimeInputs(root))
            throw new IllegalStateException(
                    "Switch keys and system firmware are required but were not installed");
    }

    private static void installKeys(File root) throws Exception {
        File keys = new File(root, KEYS_DIRECTORY);
        boolean haveRequired = isReadableFile(new File(keys, REQUIRED_KEY));
        if (!haveRequired) {
            File source = findUserFile(REQUIRED_KEY);
            if (source == null) throw new IllegalStateException(
                    "A user-supplied " + REQUIRED_KEY + " is required; place it in " +
                    "Games/switch/Keys on device storage");
            if (!keys.isDirectory() && !keys.mkdirs())
                throw new IllegalStateException("Cannot create adapter key directory");
            copyFile(source, new File(keys, REQUIRED_KEY));
        }
        // title.keys is optional: many titles decrypt from prod.keys alone, so a
        // missing one must not block a launch that would otherwise work.
        if (!isReadableFile(new File(keys, OPTIONAL_KEY))) {
            File optional = findUserFile(OPTIONAL_KEY);
            if (optional != null) {
                if (!keys.isDirectory() && !keys.mkdirs())
                    throw new IllegalStateException("Cannot create adapter key directory");
                copyFile(optional, new File(keys, OPTIONAL_KEY));
            }
        }
    }

    private static void installFirmware(File root) throws Exception {
        File registered = new File(root, FIRMWARE_DIRECTORY);
        File archive = findFirmwareArchive();
        String identity = archive == null ? "" : archiveIdentity(archive);
        File marker = new File(root, FIRMWARE_MARKER);
        String installed = isReadableFile(marker) ? readAscii(marker).trim() : "";
        if (countFirmware(registered) > 0 &&
                (identity.isEmpty() || identity.equals(installed))) return;
        if (archive == null) throw new IllegalStateException(
                "A user-supplied Switch firmware archive is required; place " +
                "Firmware.zip in Games/switch on device storage");
        if (!registered.isDirectory() && !registered.mkdirs())
            throw new IllegalStateException("Cannot create adapter firmware directory");
        int extracted = extractFirmware(archive, registered);
        if (extracted <= 0) throw new IllegalStateException(
                "The Switch firmware archive contained no installable content");
        writeAscii(marker, identity);
        Log.i(TAG, "Installed user Switch firmware files=" + extracted +
                " source=" + archive.getName());
    }

    /**
     * Copies every NCA out of a user firmware archive into the private
     * registered directory. Only the entry's BASE name is ever used, so a
     * hostile archive cannot traverse out of the destination.
     *
     * A NAND-derived archive may store one NCA as a directory of numbered
     * fragments ({@code <id>.nca/00}, {@code /01}, ...). Those are concatenated
     * in name order into the single file Eden expects, so a split title is not
     * silently truncated to its last fragment.
     */
    private static int extractFirmware(File archive, File registered) throws Exception {
        int extracted = 0;
        ZipFile zip = new ZipFile(archive);
        try {
            java.util.LinkedHashMap<String, List<ZipEntry>> grouped =
                    new java.util.LinkedHashMap<>();
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String target = firmwareEntryName(entry);
                if (target == null) continue;
                List<ZipEntry> fragments = grouped.get(target);
                if (fragments == null) {
                    fragments = new ArrayList<>();
                    grouped.put(target, fragments);
                }
                fragments.add(entry);
            }
            for (java.util.Map.Entry<String, List<ZipEntry>> group : grouped.entrySet()) {
                File destination = new File(registered, group.getKey());
                if (!registered.equals(destination.getParentFile())) continue;
                List<ZipEntry> fragments = group.getValue();
                java.util.Collections.sort(fragments, (left, right) ->
                        left.getName().compareTo(right.getName()));
                long total = 0;
                for (ZipEntry fragment : fragments) total += Math.max(0, fragment.getSize());
                if (destination.isFile() && total > 0 && destination.length() == total) {
                    extracted++;
                    continue;
                }
                writeFragments(zip, fragments, destination);
                extracted++;
            }
        } finally { zip.close(); }
        return extracted;
    }

    /**
     * A firmware archive stores each title either as a flat {@code <id>.nca} or
     * as a directory {@code <id>.nca/00}. Both land under the single registered
     * name Eden scans for.
     */
    private static String firmwareEntryName(ZipEntry entry) {
        if (entry == null || entry.isDirectory()) return null;
        String name = entry.getName().replace('\\', '/');
        String base = name.substring(name.lastIndexOf('/') + 1);
        if (base.toLowerCase(Locale.US).endsWith(".nca")) return base;
        String parent = name.lastIndexOf('/') <= 0 ? "" :
                name.substring(0, name.lastIndexOf('/'));
        String parentBase = parent.substring(parent.lastIndexOf('/') + 1);
        if (parentBase.toLowerCase(Locale.US).endsWith(".nca")) return parentBase;
        return null;
    }

    private static int countFirmware(File registered) {
        if (registered == null || !registered.isDirectory()) return 0;
        File[] entries = registered.listFiles();
        if (entries == null) return 0;
        int count = 0;
        for (File entry : entries)
            if (entry.isFile() && entry.length() > 0 &&
                    entry.getName().toLowerCase(Locale.US).endsWith(".nca")) count++;
        return count;
    }

    /** Cheap, stable identity for a multi-hundred-megabyte user archive. */
    private static String archiveIdentity(File archive) {
        return archive.getName().toLowerCase(Locale.US) + ":" + archive.length() +
                ":" + archive.lastModified();
    }

    private static File findUserFile(String fileName) {
        for (File root : searchRoots()) {
            File match = findByName(root, fileName, 0);
            if (match != null) return match;
        }
        return null;
    }

    private static File findFirmwareArchive() {
        File best = null;
        for (File root : searchRoots()) {
            File match = findFirmwareArchive(root, 0);
            // Prefer the largest candidate: a complete firmware set is far
            // larger than a partial or single-title archive.
            if (match != null && (best == null || match.length() > best.length()))
                best = match;
        }
        return best;
    }

    private static File findByName(File root, String fileName, int depth) {
        if (root == null || depth > MAX_SCAN_DEPTH || !root.isDirectory()) return null;
        File[] entries = sorted(root.listFiles());
        if (entries == null) return null;
        for (File entry : entries)
            if (entry.isFile() && entry.getName().equalsIgnoreCase(fileName) &&
                    entry.length() > 0 && entry.canRead()) return entry;
        for (File entry : entries) if (entry.isDirectory()) {
            File match = findByName(entry, fileName, depth + 1);
            if (match != null) return match;
        }
        return null;
    }

    private static File findFirmwareArchive(File root, int depth) {
        if (root == null || depth > MAX_SCAN_DEPTH || !root.isDirectory()) return null;
        File[] entries = sorted(root.listFiles());
        if (entries == null) return null;
        File best = null;
        for (File entry : entries) {
            if (!entry.isFile() || !entry.canRead()) continue;
            String name = entry.getName().toLowerCase(Locale.US);
            if (!name.startsWith("firmware") || !name.endsWith(".zip")) continue;
            if (best == null || entry.length() > best.length()) best = entry;
        }
        if (best != null) return best;
        for (File entry : entries) if (entry.isDirectory()) {
            File match = findFirmwareArchive(entry, depth + 1);
            if (match != null && (best == null || match.length() > best.length()))
                best = match;
        }
        return best;
    }

    /**
     * The volume roots a user's Switch keys/firmware realistically live under.
     * This deliberately mirrors Phase 2's BIOS search so one documented layout
     * serves every engine.
     */
    private static List<File> searchRoots() {
        ArrayList<File> result = new ArrayList<>();
        ArrayList<File> volumes = new ArrayList<>();
        volumes.add(Environment.getExternalStorageDirectory());
        File storage = new File("/storage");
        File[] children = storage.listFiles(File::isDirectory);
        if (children != null) for (File child : children) {
            String name = child.getName();
            if (!"emulated".equals(name) && !"self".equals(name) &&
                    !volumes.contains(child)) volumes.add(child);
        }
        for (File volume : volumes) {
            if (volume == null) continue;
            addDirectory(result, volume, "Games/switch");
            addDirectory(result, volume, "ROMs/switch");
            addDirectory(result, volume, "BIOS/switch");
            addDirectory(result, volume, "Games");
            addDirectory(result, volume, "ROMs");
        }
        return result;
    }

    /**
     * Resolves a relative path case-insensitively. External volumes are not
     * reliably case-folding, so "Games/switch" must also find "games/Switch".
     */
    private static void addDirectory(List<File> result, File volume, String relative) {
        File current = volume;
        for (String segment : relative.split("/")) {
            File match = new File(current, segment);
            if (!match.isDirectory()) {
                match = null;
                File[] entries = sorted(current.listFiles());
                if (entries != null) for (File entry : entries)
                    if (entry.isDirectory() && entry.getName().equalsIgnoreCase(segment)) {
                        match = entry;
                        break;
                    }
            }
            if (match == null) return;
            current = match;
        }
        if (current.isDirectory() && !result.contains(current)) result.add(current);
    }

    private static File[] sorted(File[] entries) {
        if (entries == null) return null;
        Arrays.sort(entries, (left, right) ->
                left.getName().compareToIgnoreCase(right.getName()));
        return entries;
    }

    private static boolean isReadableFile(File file) {
        return file != null && file.isFile() && file.length() > 0 && file.canRead();
    }

    private static void copyFile(File source, File destination) throws Exception {
        InputStream input = new java.io.FileInputStream(source);
        try {
            writeStream(input, destination);
        } finally { input.close(); }
    }

    private static void writeStream(InputStream input, File destination) throws Exception {
        List<InputStream> single = new ArrayList<>();
        single.add(input);
        writeAll(single, destination);
    }

    private static void writeFragments(ZipFile zip, List<ZipEntry> fragments,
                                       File destination) throws Exception {
        List<InputStream> streams = new ArrayList<>();
        try {
            for (ZipEntry fragment : fragments) streams.add(zip.getInputStream(fragment));
            writeAll(streams, destination);
        } finally {
            for (InputStream stream : streams)
                try { stream.close(); } catch (Exception ignored) {}
        }
    }

    /** Writes through a temporary file so an interrupted copy is never read back. */
    private static void writeAll(List<InputStream> inputs, File destination)
            throws Exception {
        File pending = new File(destination.getParentFile(),
                destination.getName() + ".pending");
        FileOutputStream output = new FileOutputStream(pending);
        try {
            byte[] buffer = new byte[256 * 1024];
            for (InputStream input : inputs) {
                int count;
                while ((count = input.read(buffer)) >= 0)
                    if (count > 0) output.write(buffer, 0, count);
            }
            output.flush();
            output.getFD().sync();
        } finally { output.close(); }
        if (destination.exists() && !destination.delete()) {
            pending.delete();
            throw new IllegalStateException("Cannot replace " + destination.getName());
        }
        if (!pending.renameTo(destination)) {
            pending.delete();
            throw new IllegalStateException("Cannot install " + destination.getName());
        }
    }

    private static String readAscii(File file) throws Exception {
        InputStream input = new java.io.FileInputStream(file);
        try {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0)
                if (count > 0) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.US_ASCII);
        } finally { input.close(); }
    }

    private static void writeAscii(File file, String value) throws Exception {
        FileOutputStream output = new FileOutputStream(file);
        try {
            output.write(value.getBytes(StandardCharsets.US_ASCII));
            output.flush();
            output.getFD().sync();
        } finally { output.close(); }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }
}
