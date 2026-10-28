package ai.lmctl.lmfiles;

import java.nio.file.*;
import java.util.*;

/** Deterministic local-only scan checks, using small synthetic trees. */
public final class ScanChecks {
  private ScanChecks() {}
  private static int n;
  private static final int FILES=1000;
  private static void ok(boolean value, String message) { if (!value) throw new AssertionError(message); n++; }
  private static Map<String,Object> obj(String json) { return J.obj(json); }
  private static void reject(Runnable run, String message) { try { run.run(); throw new AssertionError(message); } catch (IllegalArgumentException|IllegalStateException e) { n++; } }
  private static void rejectRuntime(Runnable run, String message) { try { run.run(); throw new AssertionError(message); } catch (RuntimeException e) { n++; } }
  private static String rootKey(Path root) {
    try {
      byte[] d=java.security.MessageDigest.getInstance("SHA-256").digest(root.toAbsolutePath().normalize().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder s=new StringBuilder(); for(byte b:d)s.append(String.format(Locale.ROOT,"%02x",b&255)); return s.toString();
    } catch(Exception e){throw new AssertionError(e);}
  }

  public static int run(Path root) throws Exception {
    n = 0; Files.createDirectories(root);
    Path tree = root.resolve("tree"); Path db = root.resolve("scan.mv");
    Files.createDirectories(tree.resolve("a")); Files.createDirectories(tree.resolve("b"));
    for (int i = 0; i < FILES; i++) {
      Path file = (i & 1) == 0 ? tree.resolve("a").resolve("f-" + i + ".txt") : tree.resolve("b").resolve("f-" + i + ".txt");
      Files.writeString(file, "fixture-" + (i % 31), StandardOpenOption.CREATE_NEW);
    }
    try (Store store = new Store(db, "scan", "{}")) {
      Map<String,Object> first = obj(LocalScan.scan(store, tree, "{\"hash_algorithm\":\"SHA-256\"}"));
      ok("ok".equals(J.text(first, "status", "")), "first scan status");
      ok(Integer.toString(FILES).equals(J.text(first, "files", "")), "first scan count");
      ok(Long.parseLong(J.text(first, "hashed", "0")) == FILES, "initial scan hashes files");
      Map<String,Object> second = obj(LocalScan.scan(store, tree, "{}"));
      ok(Integer.toString(FILES).equals(J.text(second, "files", "")), "repeat scan count");
      ok(Long.parseLong(J.text(second, "hashed", "-1")) == 0, "repeat scan avoids hashes");
      ok(Long.parseLong(J.text(second, "changed", "-1")) == 0, "repeat scan no changes");
      String itemId=rootKey(tree)+":a/f-0.txt";
      Map<String,Object> stable=store.lookupIdentity(itemId); String stableRevision=J.text(stable,"revision",""); String stableHash=J.text(stable,"content_hash","");
      Map<String,Object> forced=obj(LocalScan.scan(store, tree, "{\"include\":[\"a/f-0.txt\"],\"always_hash\":true}"));
      ok(Long.parseLong(J.text(forced,"hashed","0"))==1,"always hash");
      Map<String,Object> afterForced=store.lookupIdentity(itemId); ok(stableRevision.equals(J.text(afterForced,"revision","")),"always hash keeps revision"); ok(stableHash.equals(J.text(afterForced,"content_hash","")),"always hash keeps digest");
      ok(afterForced.get("observation") instanceof Map<?,?> && "SHA-256".equals(J.text((Map<String,Object>)afterForced.get("observation"),"algorithm","")),"algorithm recorded");
      String stableItem=J.text(afterForced,"item","");
      store.call("stage_record",stableItem,J.str(Map.of("stage","published","namespace","synthetic-s3","status","ok","source_revision",stableRevision,"source_fingerprint",Map.of("hash",stableHash))));
      Path touched=tree.resolve("a/f-0.txt");
      Files.setLastModifiedTime(touched,java.nio.file.attribute.FileTime.fromMillis(Files.getLastModifiedTime(touched).toMillis()+2000));
      Map<String,Object> touchScan=obj(LocalScan.scan(store,tree,"{\"include\":[\"a/f-0.txt\"]}"));
      ok("1".equals(J.text(touchScan,"hashed","")),"touch rechecks bytes after stat drift");
      ok(stableRevision.equals(J.text(store.lookupIdentity(itemId),"revision","")),"touch with equal digest keeps content revision");
      try(Store.Cursor cursor=store.query("query_subset","synthetic-s3",J.str(List.of(stableItem)),"{\"stage\":\"published\",\"namespace\":\"synthetic-s3\",\"projection\":\"last_success\"}")){
        Map<String,Object> verdict=io.lmctl.lmcode.Json.object(((List<?>)J.parse(cursor.nextJson(1,1048576))).get(0));
        ok("same-as-recorded".equals(J.text(verdict,"state","")),"touch yields an empty content-based upload plan");
      }
      Path changed = tree.resolve("a/f-0.txt"); Files.writeString(changed, "changed", StandardOpenOption.TRUNCATE_EXISTING);
      Map<String,Object> third = obj(LocalScan.scan(store, tree, "{\"include\":[\"a/*.txt\"]}"));
      ok(Long.parseLong(J.text(third, "changed", "0")) == 1, "changed file detected");
      ok(Long.parseLong(J.text(third, "hashed", "0")) == 1, "changed subset hashes changed file");
      Map<String,Object> changedAlgorithm=obj(LocalScan.scan(store, tree, "{\"include\":[\"a/f-0.txt\"],\"hash_algorithm\":\"SHA-1\"}"));
      ok(Long.parseLong(J.text(changedAlgorithm,"hashed","0"))==1,"algorithm change rehashes: "+changedAlgorithm);
      String eventsBefore=J.text(obj(store.call("stats")),"events",""); LocalScan.scan(store,tree,"{\"hash_algorithm\":\"SHA-1\",\"include\":[\"a/f-0.txt\"]}");
      ok(eventsBefore.equals(J.text(obj(store.call("stats")),"events","")),"unchanged scan emits no events");
      Map<String,Object> excluded = obj(LocalScan.scan(store, tree, "{\"exclude\":[\"a/*.txt\"]}"));
      ok(Long.parseLong(J.text(excluded, "files", "0")) == FILES/2, "exclude glob");
      reject(() -> LocalScan.scan(store, tree, "{\"unknown\":true}"), "unknown scan option");
      reject(() -> LocalScan.scan(store, tree, "{\"always_hash\":\"yes\"}"), "non-boolean scan option");
      reject(() -> LocalScan.scan(store, tree, "{\"shard\":2,\"shards\":2}"), "invalid shard");
      Path other= root.resolve("other"); Files.createDirectories(other); Files.writeString(other.resolve("f-0.txt"),"other");
      Map<String,Object> otherScan=obj(LocalScan.scan(store, other, "{}")); ok(Long.parseLong(J.text(otherScan,"files","0"))==1,"second root scan");
      ok(!store.lookupIdentity(rootKey(other)+":f-0.txt").isEmpty(),"root-scoped identity");
      try { Files.createSymbolicLink(tree.resolve("link-to-f0"), changed); Map<String,Object> links=obj(LocalScan.scan(store, tree, "{}")); ok(Long.parseLong(J.text(links,"files","0"))==FILES,"symlink not followed"); }
      catch (UnsupportedOperationException ignored) { n++; }
      try { Map<String,Object> links=obj(LocalScan.scan(store, tree, "{\"follow_links\":true,\"include\":[\"link-to-f0\"]}")); ok(Long.parseLong(J.text(links,"files","0"))==1,"symlink follow mode"); }
      catch (UnsupportedOperationException ignored) { n++; }
      Files.deleteIfExists(tree.resolve("b/f-1.txt")); LocalScan.scan(store,tree,"{}");
      ok(!store.lookupIdentity(rootKey(tree)+":b/f-1.txt").isEmpty(),"missing files remain recorded");
      try {
        Path partial=root.resolve("partial"); Files.createDirectories(partial);
        for(int i=0;i<300;i++)Files.writeString(partial.resolve("p"+i),"p"+i,StandardOpenOption.CREATE_NEW);
        long beforePartial=Long.parseLong(J.text(obj(store.call("stats")),"items","0"));
        LocalScan.scan(store,partial,"{\"max_files\":256}");
        ok(Long.parseLong(J.text(obj(store.call("stats")),"items","0"))==beforePartial+256,"one committed batch before failure");
        Files.createSymbolicLink(partial.resolve("zz-loop"),partial);
        rejectRuntime(() -> LocalScan.scan(store,partial,"{\"follow_links\":true}"),"loop failure expected");
        ok(Long.parseLong(J.text(obj(store.call("stats")),"items","0"))>=beforePartial+256,"committed batch survives failed rescan");
      } catch (UnsupportedOperationException|FileSystemException ignored) { n++; }
    }
    Path small=root.resolve("small"); Files.createDirectories(small);
    for(int i=0;i<10;i++)Files.writeString(small.resolve("s"+i),"s"+i,StandardOpenOption.CREATE_NEW);
    Path shardDb=root.resolve("shards.mv");
    try(Store shardStore=new Store(shardDb,"scan","{}")){
      Map<String,Object> left=obj(LocalScan.scan(shardStore,small,"{\"shards\":2,\"shard\":0}"));
      Map<String,Object> right=obj(LocalScan.scan(shardStore,small,"{\"shards\":2,\"shard\":1}"));
      ok(Long.parseLong(J.text(left,"files","0"))+Long.parseLong(J.text(right,"files","0"))==10,"shard partition covers tree");
    }
    try(Store bad=new Store(root.resolve("bad-shards.mv"),"scan","{}")){ reject(() -> LocalScan.scan(bad, small, "{\"shards\":0}"), "invalid shard count"); }
    try(Store bad=new Store(root.resolve("bad-root.mv"),"scan","{}")){ reject(() -> LocalScan.scan(bad, root.resolve("missing"), "{}"), "missing scan root"); }
    return n;
  }
  public static String runReport(Path root) throws Exception {
    int assertions=run(root);
    return J.str(Map.of("ok",true,"assertions",assertions,"files",FILES));
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("scan checks require a directory");
    System.out.println(J.str(Map.of("ok", true, "assertions", run(Path.of(args[0])))));
  }
}
