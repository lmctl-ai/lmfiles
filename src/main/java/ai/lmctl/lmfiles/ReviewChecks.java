package ai.lmctl.lmfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Regression checks for the independent review fixes. */
public final class ReviewChecks {
    private static int checks;
    private ReviewChecks() {}

    private static void ok(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    private static void rejects(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message + " accepted");
        } catch (IllegalArgumentException | IllegalStateException expected) {
            checks++;
        }
    }

    private static Map<String, Object> object(String json) { return J.obj(json); }
    private static String status(String json) { return J.text(object(json), "status", ""); }
    private static String item(String json) { return J.text(object(json), "item", ""); }

    private static String stage(String name, String namespace, String status, String revision) {
        return J.str(Map.of("stage", name, "namespace", namespace, "status", status,
                "source_revision", revision));
    }

    private static String page(Store.Cursor cursor){try(cursor){return cursor.nextJson(100,1000000);}}
    private static Map<String,Object> first(String page){return io.lmctl.lmcode.Json.object(((List<?>)J.parse(page)).get(0));}
    public static int run(Path root) throws Exception {
        checks = 0;
        Files.createDirectories(root);
        Path db = root.resolve("review.mv");
        Files.deleteIfExists(db);
        Files.deleteIfExists(Path.of(db + ".lock"));

        try (Store store = new Store(db, "review", "{}")) {
            String id = item(store.call("item_upsert", "{\"id\":\"review-item\"}",
                    "{\"content_hash\":\"digest-1\",\"size\":\"10\"}"));

            // Observe fix: stat-only drift clears the old digest; a present transition
            // with a supplied digest also advances the revision.
            store.call("observe", id, "{\"present\":true,\"size\":\"10\",\"hash\":\"digest-1\"}");
            store.call("stage_record", id, "{\"stage\":\"published\",\"namespace\":\"site\",\"status\":\"ok\",\"source_revision\":\"1\",\"source_fingerprint\":{\"hash\":\"digest-1\"}}");
            String same = page(store.query("query_subset", "site", "[\"" + id + "\"]",
                    "{\"stage\":\"published\",\"namespace\":\"site\",\"projection\":\"last_success\"}"));
            ok("same-as-recorded".equals(first(same).get("state")), "baseline digest matches");
            store.call("observe", id, "{\"present\":true,\"size\":\"11\"}");
            String changed = page(store.query("query_subset", "site", "[\"" + id + "\"]",
                    "{\"stage\":\"published\",\"namespace\":\"site\",\"projection\":\"last_success\"}"));
            ok("changed".equals(first(changed).get("state")), "stat-only drift clears stale digest");
            rejects(() -> store.call("observe", id, "{\"present\":\"yes\"}"), "non-boolean present");

            // Identity aliases are reserved and cannot delete or shadow the primary index.
            rejects(() -> store.call("alias_add", id, "{\"kind\":\"identity\",\"namespace\":\"review\",\"value\":\"review-item\"}"), "reserved identity alias");
            ok(item(store.call("item_upsert", "{\"id\":\"review-item\"}", "{}")) .equals(id), "identity reservation preserves lookup");

            // A late successful receipt for an older revision remains last_success
            // even while the latest attempt is a newer failure.
            store.call("stage_record", id, stage("verify", "site", "failed", "2"));
            store.call("stage_record", id, stage("verify", "site", "ok", "1"));
            String success = page(store.query("query_subset", "site", "[\"" + id + "\"]",
                    "{\"stage\":\"verify\",\"namespace\":\"site\",\"projection\":\"last_success\"}"));
            ok(success.contains("\"source_revision\":\"1\""), "out-of-order last_success retained");

            // Normal compaction must keep a pinned snapshot readable.
            Store.Snapshot snapshot = store.snapshot();
            String before = page(snapshot.query("{\"all\":true}", "{}"));
            store.call("item_upsert", "{\"id\":\"after-snapshot\"}", "{}");
            store.call("compact", "{}");
            ok(before.equals(page(snapshot.query("{\"all\":true}", "{}"))), "pinned snapshot survives compaction");
            snapshot.close();

            String retention=J.text(J.obj(store.call("stats")),"retention_ms","");store.call("compact","{}");ok(retention.equals(J.text(J.obj(store.call("stats")),"retention_ms","")),"compaction restores configured retention");
            rejects(()->store.query("query_subset","review","x".repeat(1048577),"{}"),"query input budget");
            try(Store.Snapshot read=store.snapshot()){rejects(()->read.query("x".repeat(1048577),"{}"),"snapshot input budget");}
            rejects(()->store.call("difference_index","{\"stage\":\"a\",\"namespace\":\"n\",\"projection\":\"bad\"}","{\"stage\":\"b\",\"namespace\":\"n\"}","{}"),"index projection validation");
            rejects(()->store.call("export_records",root.resolve("not-created.jsonl").toString(),"{\"unknown\":true}"),"export option validation");
            rejects(()->store.call("apply_records","missing-plan","missing-results","{\"unknown\":true}"),"replay option validation before IO");
            rejects(() -> store.call("doctor", "{\"unknown\":true}"), "unknown doctor option");
        }

        // A torn final line is discarded before the next append, rather than fused
        // into the following JSON object.
        Path plan = root.resolve("append-plan.jsonl");
        String instruction = J.str(Map.of("v", 1, "kind", "instruction", "job", "review",
                "op", "put-a", "payload", Map.of("verb", "put", "item", "1", "stage", "published",
                        "target", "fake", "source_revision", "1", "record_projection", "record")));
        Files.writeString(plan, "{\"v\":1,\"kind\":", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        PlanLog.append(plan, instruction, "{}");
        String content = Files.readString(plan);
        ok(content.contains("\"op\":\"put-a\""), "append replaces torn tail");
        ok(!content.contains("kind\":{\""), "append does not fuse torn JSON");

        // Tagged path bytes reject null/non-string values and preserve a valid raw byte tag.
        Path bytes = root.resolve("path-bytes.jsonl");
        PlanLog.append(bytes, instruction.replace("put-a", "put-bytes").replace("payload", "raw_path"), "{}");
        ok(Files.readString(bytes).contains("put-bytes"), "valid journal append");
        Map<String, Object> badTag = new LinkedHashMap<>();
        badTag.put("base64", null);
        rejects(() -> {
            try { PlanLog.append(bytes, J.str(Map.of("path", badTag)), "{}"); }
            catch (Exception e) { throw new IllegalArgumentException(e); }
        }, "null path tag");

        return checks;
    }
}
