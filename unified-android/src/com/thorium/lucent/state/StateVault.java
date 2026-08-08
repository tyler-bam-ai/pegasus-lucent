package com.thorium.lucent.state;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Crash-resilient immutable emulator state storage.
 *
 * A snapshot directory is fully written and synced before it becomes visible.
 * Quick Resume is only a tiny atomically replaced reference to one immutable
 * snapshot, so a process death cannot expose half of a new state.
 */
public final class StateVault {
    private static final String SNAPSHOTS = "snapshots";
    private static final String MANIFEST = "manifest.properties";
    private static final String STATE = "state.bin.gz";
    private static final String PREVIEW = "preview.webp";
    private static final String QUICK_REF = "quick-resume.ref";
    private static final long MAX_UNCOMPRESSED_STATE = 512L * 1024L * 1024L;

    private final File root;
    private final Clock clock;
    private final RetentionPolicy retention;

    public StateVault(File root) {
        this(root, Clock.SYSTEM, RetentionPolicy.DEFAULT);
    }

    public StateVault(File root, Clock clock, RetentionPolicy retention) {
        if (root == null || clock == null || retention == null)
            throw new IllegalArgumentException("StateVault dependencies cannot be null");
        this.root = root;
        this.clock = clock;
        this.retention = retention;
    }

    public synchronized StateSnapshot saveQuickResume(StateIdentity identity, byte[] state,
            byte[] webpScreenshot, long activePlayMillis) throws IOException {
        StateSnapshot snapshot = save(identity, SnapshotKind.QUICK_RESUME, state,
                webpScreenshot, activePlayMillis);
        File game = gameDirectory(identity);
        final byte[] reference = (snapshot.metadata.snapshotId + "\n")
                .getBytes(StandardCharsets.UTF_8);
        File temporary = new File(game, QUICK_REF + ".pending-" + UUID.randomUUID());
        AtomicFiles.writeSynced(temporary, new AtomicFiles.OutputWriter() {
            @Override public void write(OutputStream output) throws IOException {
                output.write(reference);
            }
        });
        AtomicFiles.replace(temporary, new File(game, QUICK_REF));
        prune(identity);
        return snapshot;
    }

    public synchronized StateSnapshot saveAutomatic(StateIdentity identity, byte[] state,
            byte[] webpScreenshot, long activePlayMillis) throws IOException {
        StateSnapshot result = save(identity, SnapshotKind.AUTOMATIC, state,
                webpScreenshot, activePlayMillis);
        prune(identity);
        return result;
    }

    public synchronized StateSnapshot saveRecovery(StateIdentity identity, byte[] state,
            byte[] webpScreenshot, long activePlayMillis) throws IOException {
        StateSnapshot result = save(identity, SnapshotKind.RECOVERY, state,
                webpScreenshot, activePlayMillis);
        prune(identity);
        return result;
    }

    public synchronized StateSnapshot saveBeforeEngineUpdate(StateIdentity identity, byte[] state,
            byte[] webpScreenshot, long activePlayMillis) throws IOException {
        StateSnapshot result = save(identity, SnapshotKind.PRE_UPDATE, state,
                webpScreenshot, activePlayMillis);
        prune(identity);
        return result;
    }

    public synchronized StateLoadResult loadQuickResume(StateIdentity expected) {
        File game = gameDirectory(expected);
        File reference = new File(game, QUICK_REF);
        try { AtomicFiles.recoverPrevious(reference); }
        catch (IOException failure) {
            return StateLoadResult.failure(StateLoadResult.Status.IO_ERROR, null,
                    failure.getMessage());
        }
        if (!reference.isFile())
            return StateLoadResult.failure(StateLoadResult.Status.NOT_FOUND, null, "No Quick Resume");
        try {
            String snapshotId = readUtf8(reference).trim();
            if (!safeSnapshotId(snapshotId))
                return StateLoadResult.failure(StateLoadResult.Status.CORRUPT, null,
                        "Invalid Quick Resume reference");
            return loadSnapshot(expected, new File(new File(game, SNAPSHOTS), snapshotId));
        } catch (IOException failure) {
            return StateLoadResult.failure(StateLoadResult.Status.IO_ERROR, null,
                    failure.getMessage());
        }
    }

    public synchronized StateLoadResult loadSnapshot(StateIdentity expected, StateSnapshot snapshot) {
        return snapshot == null
                ? StateLoadResult.failure(StateLoadResult.Status.NOT_FOUND, null, "Snapshot missing")
                : loadSnapshot(expected, snapshot.directory);
    }

    public synchronized List<StateSnapshot> list(StateIdentity identity) {
        List<StateSnapshot> result = new ArrayList<>();
        File snapshots = new File(gameDirectory(identity), SNAPSHOTS);
        File[] directories = snapshots.listFiles();
        if (directories != null) for (File directory : directories) {
            if (!directory.isDirectory() || directory.getName().startsWith(".pending-")) continue;
            try {
                SnapshotMetadata metadata = readMetadata(directory);
                if (metadata.identity.matches(identity))
                    result.add(new StateSnapshot(metadata, directory));
            } catch (IOException ignored) {
                // Corrupt entries are retained for recovery and omitted from the UI.
            }
        }
        Collections.sort(result, new Comparator<StateSnapshot>() {
            @Override public int compare(StateSnapshot a, StateSnapshot b) {
                return Long.compare(b.metadata.createdAtMillis, a.metadata.createdAtMillis);
            }
        });
        return result;
    }

    private StateSnapshot save(final StateIdentity identity, final SnapshotKind kind,
            final byte[] state, final byte[] screenshot, final long activePlayMillis)
            throws IOException {
        if (identity == null || kind == null || state == null || state.length == 0)
            throw new IllegalArgumentException("Identity, kind and non-empty state are required");
        if (state.length > MAX_UNCOMPRESSED_STATE)
            throw new IOException("State exceeds the 512 MiB safety limit");
        if (activePlayMillis < 0) throw new IllegalArgumentException("activePlayMillis is negative");

        File game = gameDirectory(identity);
        File snapshots = new File(game, SNAPSHOTS);
        ensureDirectory(snapshots);
        final long now = clock.wallTimeMillis();
        final String id = now + "-" + UUID.randomUUID().toString();
        final String sha256 = Digests.sha256(state);
        final SnapshotMetadata metadata = new SnapshotMetadata(id, kind, identity, now,
                activePlayMillis, state.length, sha256, Digests.crc32(state),
                screenshot != null && screenshot.length > 0);
        File pending = new File(snapshots, ".pending-" + id);
        File published = new File(snapshots, id);
        if (!pending.mkdir()) throw new IOException("Cannot create " + pending);
        boolean complete = false;
        try {
            AtomicFiles.writeSynced(new File(pending, STATE), new AtomicFiles.OutputWriter() {
                @Override public void write(OutputStream output) throws IOException {
                    GZIPOutputStream gzip = new GZIPOutputStream(output);
                    gzip.write(state);
                    gzip.finish();
                }
            });
            if (metadata.hasScreenshot) {
                AtomicFiles.writeSynced(new File(pending, PREVIEW), new AtomicFiles.OutputWriter() {
                    @Override public void write(OutputStream output) throws IOException {
                        output.write(screenshot);
                    }
                });
            }
            AtomicFiles.writeSynced(new File(pending, MANIFEST), new AtomicFiles.OutputWriter() {
                @Override public void write(OutputStream output) throws IOException {
                    metadata.write(output);
                }
            });
            // Read it back before publication; a visible directory is always complete.
            SnapshotMetadata verified = readMetadata(pending);
            if (!id.equals(verified.snapshotId)) throw new IOException("Manifest verification failed");
            AtomicFiles.publishDirectory(pending, published);
            complete = true;
            return new StateSnapshot(metadata, published);
        } finally {
            if (!complete) AtomicFiles.deleteTree(pending);
        }
    }

    private StateLoadResult loadSnapshot(StateIdentity expected, File directory) {
        if (directory == null || !directory.isDirectory())
            return StateLoadResult.failure(StateLoadResult.Status.NOT_FOUND, null, "Snapshot missing");
        StateSnapshot snapshot = null;
        try {
            SnapshotMetadata metadata = readMetadata(directory);
            snapshot = new StateSnapshot(metadata, directory);
            if (!expected.matches(metadata.identity))
                return StateLoadResult.failure(StateLoadResult.Status.IDENTITY_MISMATCH, snapshot,
                        "ROM, engine, state format, or firmware changed");
            if (metadata.uncompressedBytes <= 0 || metadata.uncompressedBytes > MAX_UNCOMPRESSED_STATE)
                return StateLoadResult.failure(StateLoadResult.Status.CORRUPT, snapshot,
                        "Invalid state size");
            byte[] state = gunzip(new File(directory, STATE), metadata.uncompressedBytes);
            if (state.length != metadata.uncompressedBytes ||
                    !metadata.stateSha256.equals(Digests.sha256(state)) ||
                    metadata.stateCrc32 != Digests.crc32(state))
                return StateLoadResult.failure(StateLoadResult.Status.CORRUPT, snapshot,
                        "State checksum mismatch");
            return StateLoadResult.ok(snapshot, state);
        } catch (IOException failure) {
            return StateLoadResult.failure(StateLoadResult.Status.CORRUPT, snapshot,
                    failure.getMessage());
        }
    }

    private void prune(StateIdentity identity) {
        List<StateSnapshot> snapshots = list(identity);
        String protectedId = readQuickId(gameDirectory(identity));
        List<RetentionPolicy.Candidate> candidates = new ArrayList<>();
        for (StateSnapshot snapshot : snapshots)
            candidates.add(new RetentionPolicy.Candidate(snapshot.metadata.snapshotId,
                    snapshot.metadata.createdAtMillis, AtomicFiles.size(snapshot.directory),
                    snapshot.metadata.kind != SnapshotKind.QUICK_RESUME));
        Set<String> deletions = retention.selectForDeletion(candidates, protectedId);
        for (StateSnapshot snapshot : snapshots)
            if (deletions.contains(snapshot.metadata.snapshotId))
                AtomicFiles.deleteTree(snapshot.directory);
        AtomicFiles.syncDirectory(new File(gameDirectory(identity), SNAPSHOTS));
    }

    private File gameDirectory(StateIdentity identity) {
        return new File(root, identity.storageKey());
    }

    private static SnapshotMetadata readMetadata(File directory) throws IOException {
        InputStream input = new FileInputStream(new File(directory, MANIFEST));
        try { return SnapshotMetadata.read(input); }
        finally { input.close(); }
    }

    private static byte[] gunzip(File file, long expectedBytes) throws IOException {
        InputStream input = new GZIPInputStream(new FileInputStream(file), 32 * 1024);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(expectedBytes, 1024 * 1024));
            byte[] buffer = new byte[32 * 1024];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) continue;
                total += count;
                if (total > expectedBytes || total > MAX_UNCOMPRESSED_STATE)
                    throw new IOException("Expanded state exceeds manifest size");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally { input.close(); }
    }

    private static String readQuickId(File game) {
        try {
            File reference = new File(game, QUICK_REF);
            AtomicFiles.recoverPrevious(reference);
            return readUtf8(reference).trim();
        }
        catch (IOException ignored) { return null; }
    }

    private static String readUtf8(File file) throws IOException {
        InputStream input = new FileInputStream(file);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) output.write(buffer, 0, count);
                if (output.size() > 1024) throw new IOException("Reference is too large");
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } finally { input.close(); }
    }

    private static boolean safeSnapshotId(String id) {
        return id != null && id.matches("[0-9]+-[0-9a-fA-F-]{36}");
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot create " + directory);
    }
}
