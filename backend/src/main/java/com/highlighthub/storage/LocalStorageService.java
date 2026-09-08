package com.highlighthub.storage;

import com.highlighthub.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Local-disk storage. Keys are logical paths like "original/{mediaId}/source".
 * The physical root lives only on the server side.
 */
@Service
public class LocalStorageService implements StorageService {

    private final Path root;

    public LocalStorageService(@Value("${highlight-hub.storage.root}") String rootDir) {
        this.root = Path.of(rootDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create storage root " + root, e);
        }
    }

    @Override
    public String normalizeKey(AssetType type, String relativeName) {
        String name = relativeName.replace('\\', '/');
        if (name.startsWith("/") || name.contains("..") || name.contains("\0")) {
            throw BusinessException.badRequest("invalid storage key segment");
        }
        return type.dir() + "/" + name;
    }

    private Path pathOf(String key) {
        if (key == null || key.isBlank() || key.contains("..") || key.contains("\0")
                || key.startsWith("/") || key.contains("\\") && key.contains("..")) {
            throw BusinessException.badRequest("invalid storage key");
        }
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw BusinessException.badRequest("storage key escapes root");
        }
        return p;
    }

    @Override
    public long put(String key, InputStream in, long expectedSizeIfExists) {
        Path target = pathOf(key);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".part-" + java.util.UUID.randomUUID());
            MessageDigest digest;
            try (OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return Files.size(target);
        } catch (IOException e) {
            throw new BusinessException("STORAGE_WRITE_FAILED", 500, "storage write failed: " + e.getMessage());
        }
    }

    @Override
    public InputStream open(String key) {
        try {
            return Files.newInputStream(pathOf(key));
        } catch (IOException e) {
            throw BusinessException.notFound("stored object not found: " + key);
        }
    }

    @Override
    public InputStream openRange(String key, long start, long length) {
        Path p = pathOf(key);
        if (!Files.exists(p)) throw BusinessException.notFound("stored object not found: " + key);
        try {
            RandomAccessFile raf = new RandomAccessFile(p.toFile(), "r");
            raf.seek(start);
            return new InputStream() {
                private long remaining = length;
                private boolean closed = false;

                @Override
                public int read() throws IOException {
                    if (remaining <= 0) return -1;
                    int b = raf.read();
                    if (b >= 0) remaining--;
                    return b;
                }

                @Override
                public int read(byte[] buf, int off, int len) throws IOException {
                    if (remaining <= 0) return -1;
                    int n = raf.read(buf, off, (int) Math.min(len, remaining));
                    if (n > 0) remaining -= n;
                    return n;
                }

                @Override
                public void close() throws IOException {
                    if (!closed) {
                        closed = true;
                        raf.close();
                    }
                }
            };
        } catch (IOException e) {
            throw new BusinessException("STORAGE_READ_FAILED", 500, "storage read failed: " + e.getMessage());
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(pathOf(key));
    }

    @Override
    public long size(String key) {
        try {
            return Files.size(pathOf(key));
        } catch (IOException e) {
            throw BusinessException.notFound("stored object not found: " + key);
        }
    }

    @Override
    public void delete(String key) {
        Path p = pathOf(key);
        try {
            Files.deleteIfExists(p);
            // remove now-empty parent dirs (bounded: up to the root)
            Path parent = p.getParent();
            while (parent != null && !parent.equals(root)) {
                try (Stream<Path> list = Files.list(parent)) {
                    if (list.findAny().isPresent()) break;
                }
                Files.deleteIfExists(parent);
                parent = parent.getParent();
            }
        } catch (IOException e) {
            throw new BusinessException("STORAGE_DELETE_FAILED", 500, "storage delete failed: " + e.getMessage());
        }
    }

    @Override
    public String resolveLocalPath(String key) {
        return pathOf(key).toString();
    }

    /** Stream a bounded copy: returns bytes written, or -1 when the limit is exceeded. */
    public long putLimited(String key, InputStream in, long maxBytes) throws IOException {
        Path target = pathOf(key);
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part-" + java.util.UUID.randomUUID());
        long written = 0;
        try (OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                written += n;
                if (written > maxBytes) {
                    out.close();
                    Files.deleteIfExists(tmp);
                    return -1;
                }
                out.write(buf, 0, n);
            }
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return written;
    }

    /** Begin a staged write; finalize with commitWritten(). */
    public OutputStream openForWrite(String key) throws IOException {
        Path target = pathOf(key);
        Files.createDirectories(target.getParent());
        return Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** Finalize a staged write and report its size. */
    public long commitWritten(String key) {
        Path target = pathOf(key);
        try {
            return Files.size(target);
        } catch (IOException e) {
            throw new BusinessException("STORAGE_WRITE_FAILED", 500, "staged object missing: " + key);
        }
    }

    /** Move a stored object to a new key (used by upload merge). */
    public void move(String fromKey, String toKey) {
        Path from = pathOf(fromKey);
        Path to = pathOf(toKey);
        try {
            Files.createDirectories(to.getParent());
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new BusinessException("STORAGE_MOVE_FAILED", 500, "storage move failed: " + e.getMessage());
        }
    }

    /** Directory-level cleanup helper (used by the cleanup task). */
    public void deletePrefix(String prefix) {
        Path dir = pathOf(prefix);
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // retried by later cleanup runs
                }
            });
        } catch (IOException ignored) {
            // retried by later cleanup runs
        }
    }
}
