package ai.lmctl.lmfiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.LinkOption;
import java.util.concurrent.TimeUnit;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Bounded-memory local ingestion helper.  It only reads the local tree and
 * records observations in a Store; it never applies a stage policy or contacts
 * a remote service.  The visitor is deliberately streaming so a large tree is
 * not materialised in a collection.
 */
public final class LocalScan {
  private LocalScan() {}

  public static String scan(Store store, Path root, String options) {
    Objects.requireNonNull(store, "store");
    long started = System.nanoTime();
    Path base = root.toAbsolutePath().normalize();
    if (!Files.isDirectory(base)) throw new IllegalArgumentException("scan root is not a directory");
    Map<String,Object> o = J.obj(options == null ? "{}" : options);
    long optionBytes=(options==null?2:options.getBytes(StandardCharsets.UTF_8).length);
    if(optionBytes>Math.min(1024L*1024L,Main.memoryBudget()/4L))throw new IllegalArgumentException("scan-options-exceed-LMFILES_MAX_MEMORY_MB");
    keys(o, "hash_algorithm", "always_hash", "include", "exclude", "max_files", "follow_links", "shard", "shards");
    String algorithm = J.text(o, "hash_algorithm", "SHA-256");
    try { MessageDigest.getInstance(algorithm); }
    catch (NoSuchAlgorithmException e) { throw new IllegalArgumentException("unsupported hash algorithm: " + algorithm); }
    boolean alwaysHash = booleanOption(o, "always_hash", false);
    boolean followLinks = booleanOption(o, "follow_links", false);
    long max = Long.parseLong(J.text(o, "max_files", Long.toString(Long.MAX_VALUE)));
    if (max < 0) throw new IllegalArgumentException("max_files must be non-negative");
    int shards = Integer.parseInt(J.text(o, "shards", "1"));
    int shard = Integer.parseInt(J.text(o, "shard", "0"));
    if (shards < 1 || shard < 0 || shard >= shards) throw new IllegalArgumentException("invalid shard/shards");
    String rootKey = rootKey(base);
    List<PathMatcher> includes = matchers(o.get("include"), base.getFileSystem());
    List<PathMatcher> excludes = matchers(o.get("exclude"), base.getFileSystem());
    Stats stats = new Stats();
    List<Store.ScanRecord> pending = new ArrayList<>(256);
    Set<FileVisitOption> visit = followLinks ? Set.of(FileVisitOption.FOLLOW_LINKS) : Set.of();
    try {
      Files.walkFileTree(base, visit, Integer.MAX_VALUE, new SimpleFileVisitor<>() {
        @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
          if (shards==1 || dir.equals(base)) return FileVisitResult.CONTINUE;
          String rel=slash(base.relativize(dir)); int slash=rel.indexOf('/'); String top=slash>0?rel.substring(0,slash):rel;
          return Math.floorMod(top.hashCode(),shards)==shard?FileVisitResult.CONTINUE:FileVisitResult.SKIP_SUBTREE;
        }
        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
          if (stats.seen >= max) return FileVisitResult.TERMINATE;
          if (!a.isRegularFile()) return FileVisitResult.CONTINUE;
          String rel = slash(base.relativize(file));
          if (!includes.isEmpty() && !matches(includes, rel)) return FileVisitResult.CONTINUE;
          if (matches(excludes, rel)) return FileVisitResult.CONTINUE;
          String shardKey=rel; int slash=rel.indexOf('/'); if(slash>0)shardKey=rel.substring(0,slash);
          if (Math.floorMod(shardKey.hashCode(), shards) != shard) return FileVisitResult.CONTINUE;
          Store.ScanRecord record=ingest(store, rootKey + ":" + rel, file, a, algorithm, alwaysHash, followLinks, stats);
          if(record!=null)pending.add(record);
          if (pending.size() >= 256) { store.scanBatch(pending); pending.clear(); }
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException e) { throw new UncheckedIOException(e); }
    if (!pending.isEmpty()) store.scanBatch(pending);
    long elapsedMs=(System.nanoTime()-started)/1_000_000L;
    return J.str(Map.of("status", "ok", "files", Long.toString(stats.seen),
      "changed", Long.toString(stats.changed), "hashed", Long.toString(stats.hashed),
      "bytes", Long.toString(stats.bytes), "elapsed_ms", Long.toString(elapsedMs), "root", base.toString()));
  }

  private static Store.ScanRecord ingest(Store store, String rel, Path file, BasicFileAttributes attrs,
                             String algorithm, boolean alwaysHash, boolean followLinks, Stats stats) {
    long size = attrs.size();
    String mtime = Long.toString(attrs.lastModifiedTime().toMillis());
    Map<String,Object> fields = new LinkedHashMap<>();
    String mtimeNs = Long.toString(attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS));
    fields.put("size", Long.toString(size)); fields.put("mtime", mtime); fields.put("mtime_ns", mtimeNs); fields.put("present", true);
    Object key = attrs.fileKey(); if (key != null) fields.put("file_id", key.toString());
    Map<String,Object> prior = store.lookupIdentity(rel);
    Map<String,Object> old = prior.get("observation") instanceof Map<?,?> p ? cast(p) : Map.of();
    boolean changed = !same(old, fields, "size", "mtime", "mtime_ns", "present", "file_id");
    stats.seen++; stats.bytes += size; if (changed) stats.changed++;
    boolean algorithmChanged=prior.get("observation") instanceof Map<?,?> && !algorithm.equals(String.valueOf(old.getOrDefault("algorithm", "")));
    if (!alwaysHash && !changed && !algorithmChanged && prior.containsKey("content_hash")) return null;
    Map<String,Object> observation = new LinkedHashMap<>(fields);
    if (alwaysHash || changed || algorithmChanged || !prior.containsKey("content_hash")) {
      String hash = digestStable(file, attrs, algorithm, followLinks);
      observation.put("hash", hash); observation.put("algorithm", algorithm); stats.hashed++;
    }
    return new Store.ScanRecord(rel, observation);
  }

  private static boolean same(Map<String,Object> a, Map<String,Object> b, String... keys) {
    for (String k : keys) if (!Objects.equals(String.valueOf(a.get(k)), String.valueOf(b.get(k)))) return false;
    return true;
  }

  private static String digestStable(Path file, BasicFileAttributes before, String algorithm, boolean followLinks) {
    try {
      MessageDigest md = MessageDigest.getInstance(algorithm);
      try (var in = Files.newInputStream(file)) {
        byte[] buf = new byte[64 * 1024]; int n;
        while ((n = in.read(buf)) >= 0) { if (n > 0) md.update(buf, 0, n); }
      }
      StringBuilder out = new StringBuilder(md.getDigestLength() * 2);
      for (byte b : md.digest()) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
      BasicFileAttributes after = followLinks ? Files.readAttributes(file, BasicFileAttributes.class)
          : Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()) || !Objects.equals(before.fileKey(), after.fileKey()))
        throw new IllegalStateException("file-changed-during-scan");
      return out.toString();
    } catch (NoSuchAlgorithmException e) { throw new IllegalArgumentException("unsupported hash algorithm: " + algorithm); }
      catch (IOException e) { throw new UncheckedIOException(e); }
  }

  private static String rootKey(Path root) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(root.toString().getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder(digest.length * 2);
      for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
      return out.toString();
    } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
  }

  private static List<PathMatcher> matchers(Object raw, FileSystem fs) {
    if (raw == null) return List.of();
    if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("include/exclude must be arrays");
    List<PathMatcher> out = new ArrayList<>();
    for (Object value : list) {
      if (!(value instanceof String pattern) || pattern.indexOf('\0') >= 0) throw new IllegalArgumentException("glob must be a string without NUL");
      out.add(fs.getPathMatcher("glob:" + pattern));
    }
    return out;
  }
  private static boolean matches(List<PathMatcher> ms, String rel) {
    Path p = Path.of(rel); for (PathMatcher m : ms) if (m.matches(p)) return true; return false;
  }
  private static String slash(Path path) { return path.toString().replace(path.getFileSystem().getSeparator(), "/"); }
  @SuppressWarnings("unchecked") private static Map<String,Object> cast(Map<?,?> map) { return (Map<String,Object>)(Map<?,?>)map; }
  private static void keys(Map<String,Object> o, String... allowed) { Set<String> set = Set.of(allowed); for (String k : o.keySet()) if (!set.contains(k)) throw new IllegalArgumentException("unknown scan option: " + k); }
  private static boolean booleanOption(Map<String,Object> o, String key, boolean fallback) {
    if (!o.containsKey(key)) return fallback;
    Object value=o.get(key); if (!(value instanceof Boolean)) throw new IllegalArgumentException(key+" must be boolean");
    return (Boolean)value;
  }
  private static final class Stats { long seen, changed, hashed, bytes; }
}
