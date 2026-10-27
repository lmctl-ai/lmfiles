package ai.lmctl.lmfiles;

import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;

import java.nio.file.*;
import java.util.*;

/** Deterministic format-1 -> format-2 upgrade checks; invoked by the test CLI. */
public final class StorageChecks {
    private static int assertions;
    private StorageChecks() {}

    private static void ok(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(String json) { return J.obj(json); }

    private static String status(String json) { return J.text(object(json), "status", ""); }

    /** Creates a deliberately old format-1 store without going through Store.open. */
    public static void createLegacy(Path file) throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        if(Files.exists(file)||Files.exists(Path.of(file+".lock")))throw new IllegalArgumentException("fixture path already exists");
        try (MVStore db = new MVStore.Builder().fileName(file.toString()).cacheSize(8).compress().autoCommitDisabled().open()) {
            MVMap<Long,byte[]> items = db.openMap("items");
            MVMap<Long,byte[]> events = db.openMap("events");
            MVMap<String,Long> aliases = db.openMap("aliases");
            MVMap<String,String> meta = db.openMap("meta");
            MVMap<String,String> dictionary = db.openMap("dictionary");
            db.openMap("differences"); db.openMap("frontier"); db.openMap("claims");
            db.openMap("active_claims"); db.openMap("replay");
            meta.put("format", "1");
            meta.put("epoch", "legacy-epoch");
            meta.put("next_item", "1");
            meta.put("next_event", "1");
            meta.put("next_dict", "2");
            meta.put("next_fence", "0");
            dictionary.put(J.str(List.of("published", "site", "")), "1");
            dictionary.put(J.str(List.of("verify", "site", "")), "2");
            meta.put("dict:1",J.str(List.of("published","site","")));meta.put("dict:2",J.str(List.of("verify","site","")));

            Map<String,Object> row = new LinkedHashMap<>();
            row.put("item", "1"); row.put("identity", "case"); row.put("revision", "2");
            row.put("content_hash", "new"); row.put("stages", new TreeMap<String,Object>());
            Map<String,Object> published = new LinkedHashMap<>();
            published.put("status", "ok"); published.put("source_revision", "2");
            published.put("source_fingerprint", Map.of("hash", "new"));
            published.put("last_success", new LinkedHashMap<>(published));
            Map<String,Object> verify = new LinkedHashMap<>();
            verify.put("status", "failed"); verify.put("source_revision", "2");
            verify.put("last_success", Map.of("status", "ok", "source_revision", "1"));
            @SuppressWarnings("unchecked") Map<String,Object> stages = (Map<String,Object>) row.get("stages");
            stages.put("1", published); stages.put("2", verify);
            items.put(1L, Store.encode(row));
            events.put(1L, Store.encode(Map.of("sequence", "1", "item", "1", "operation", "legacy")));
            aliases.put(J.str(List.of("identity", "legacy", "case")), 1L);
            aliases.put(J.str(List.of("url", "source", "https://fixture/case")), 1L);
            aliases.put(J.str(List.of("path", "local", Map.of("base64", "/w=="))), 1L);
            db.commit(); db.sync();
        }
    }

    private static Path backup(Path db) throws Exception {
        try (var stream = Files.list(db.getParent())) {
            return stream.filter(p -> p.getFileName().toString().startsWith(db.getFileName() + ".v1.bak"))
                    .sorted().findFirst().orElseThrow(() -> new AssertionError("upgrade backup missing"));
        }
    }

    private static String query(Store db, String ids, String options) {
        try (Store.Cursor c = db.query("query_subset", "legacy", ids, options)) {
            return c.nextJson(64, 1_000_000);
        }
    }

    private static String recorded(String page,String field){Map<String,Object> row=io.lmctl.lmcode.Json.object(((List<?>)J.parse(page)).get(0));return J.text(io.lmctl.lmcode.Json.object(row.get("recorded")),field,"");}
    public static int run(Path root) throws Exception {
        assertions = 0;
        Files.createDirectories(root);
        Path db = root.resolve("legacy.mv");
        createLegacy(db);
        Path legacyExport=root.resolve("format1.jsonl");legacyExport(db,legacyExport);

        try (Store store = new Store(db, "legacy", "{}")) {
            ok("2".equals(J.text(object(store.call("doctor")),"format","")), "upgrade sets format 2");
            ok("3".equals(J.text(object(store.call("doctor")),"alias_families","")), "all three families interned");
            String url = query(store, "[{\"kind\":\"url\",\"namespace\":\"source\",\"value\":\"https://fixture/case\"}]", "{}");
            ok(url.contains("case"), "URL alias resolves");
            String bytes = query(store, "[{\"kind\":\"path\",\"namespace\":\"local\",\"value\":{\"base64\":\"/w==\"}}]", "{}");
            ok(bytes.contains("case"), "structured byte alias resolves");
            String prior = query(store, "[\"1\"]", "{\"stage\":\"verify\",\"namespace\":\"site\",\"projection\":\"last_success\"}");
            ok("1".equals(recorded(prior,"source_revision"))&&"ok".equals(recorded(prior,"status")), "prior success retained under failed latest attempt");
            String implicit = query(store, "[\"1\"]", "{\"stage\":\"published\",\"namespace\":\"site\",\"projection\":\"last_success\"}");
            ok("2".equals(recorded(implicit,"source_revision"))&&"ok".equals(recorded(implicit,"status")), "equal last-success elided as implicit latest success");
            ok("1".equals(J.text(object(store.call("item_upsert","{\"id\":\"case\"}","{}")),"item","")),"primary identity preserved");
        }
        try(MVStore raw=new MVStore.Builder().fileName(db.toString()).readOnly().open()){
            Map<String,Object> physical=Store.decode(raw.<Long,byte[]>openMap("items").get(1L));
            Map<String,Object> summaries=io.lmctl.lmcode.Json.object(physical.get("stages"));
            ok(!io.lmctl.lmcode.Json.object(summaries.get("1")).containsKey("last_success"),"equal success elided on disk");
            ok(io.lmctl.lmcode.Json.object(summaries.get("2")).containsKey("last_success"),"distinct success retained on disk");
            ok(raw.getMapNames().contains("alias_families"),"family dictionary persisted");
        }

        Path firstBackup = backup(db);
        try (MVStore old = new MVStore.Builder().fileName(firstBackup.toString()).readOnly().open()) {
            ok("1".equals(old.<String,String>openMap("meta").get("format")), "backup remains format 1");
        }
        long backupCount;
        try (var s = Files.list(db.getParent())) { backupCount = s.filter(p -> p.getFileName().toString().startsWith(db.getFileName() + ".v1.bak")).count(); }
        try (Store ignored = new Store(db, "legacy", "{}")) { }
        try (var s = Files.list(db.getParent())) {
            ok(s.filter(p -> p.getFileName().toString().startsWith(db.getFileName() + ".v1.bak")).count() == backupCount, "reopen does not repeat upgrade backup");
        }

        Path export = root.resolve("format2.jsonl");
        try (Store store = new Store(db, "legacy", "{}")) { ok(status(store.call("export_records", export.toString(), "{}" )).equals("ok"), "format 2 export"); }
        Path restored = root.resolve("restored.mv");
        try (Store store = new Store(restored, "legacy", "{}")) {
            ok(status(store.call("import_records", export.toString())).equals("ok"), "format 2 import");
            ok(status(store.call("doctor")).equals("ok"), "imported format 2 doctor");
        }

        Path restoredLegacy=root.resolve("restored-legacy.mv");
        try(Store store=new Store(restoredLegacy,"legacy","{}")){
            ok(status(store.call("import_records",legacyExport.toString())).equals("ok"),"format 1 snapshot upgraded on import");
            ok(query(store,"[{\"kind\":\"path\",\"namespace\":\"local\",\"value\":{\"base64\":\"/w==\"}}]","{}").contains("case"),"legacy snapshot byte alias preserved");
            ok(status(store.call("doctor")).equals("ok"),"legacy imported doctor");
        }
        Path bad = root.resolve("unknown-map.mv"); createLegacy(bad);
        try (MVStore raw = MVStore.open(bad.toString())) { raw.openMap("unknown").put("x", "y"); raw.commit(); raw.sync(); }
        try { new Store(bad, "legacy", "{}"); throw new AssertionError("unknown map accepted"); }
        catch (IllegalStateException expected) { assertions++; }
        ok("1".equals(readMeta(bad, "format")), "failed upgrade leaves original format 1");
        return assertions;
    }

    private static void legacyExport(Path input,Path output)throws Exception {
        long rows=0;try(MVStore raw=new MVStore.Builder().fileName(input.toString()).readOnly().open();var writer=Files.newBufferedWriter(output)){
            writer.write(J.str(Map.of("kind","snapshot","format",1))+"\n");
            for(String name:new TreeSet<>(raw.getMapNames())){MVMap<?,?> map=raw.openMap(name);for(var entry:map.entrySet()){Object value=entry.getValue();var row=new TreeMap<String,Object>(Map.of("map",name,"key",entry.getKey().toString(),"value",value instanceof byte[] bytes?Map.of("base64",Base64.getEncoder().encodeToString(bytes)):value));row.put("checksum",PlanLog.sha(J.str(row)));writer.write(J.str(row)+"\n");rows++;}}
            writer.write(J.str(Map.of("kind","end","rows",Long.toString(rows)))+"\n");
        }
    }
    private static String readMeta(Path file, String key) {
        try (MVStore db = new MVStore.Builder().fileName(file.toString()).readOnly().open()) { return db.<String,String>openMap("meta").get(key); }
    }
}
