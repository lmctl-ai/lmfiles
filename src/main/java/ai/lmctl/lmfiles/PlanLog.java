package ai.lmctl.lmfiles;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.CRC32C;
import org.h2.mvstore.*;

/** Optional local journal codec. Never executes a verb or infers an outcome. */
public final class PlanLog {
    public static final int MAX_LINE=65536;
    private PlanLog() {}
    public static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String instructionDigest(Map<String,Object> instruction) { return sha(J.str(object(instruction.get("payload")))); }
    public static String append(Path path,String line,String options) throws IOException {
        if(options!=null&&!options.isEmpty()){Map<String,Object> o=J.obj(options);for(String k:o.keySet())if(!k.equals("v"))throw new IllegalArgumentException("unknown option: "+k);}
        Map<String,Object> m=J.obj(line); m.remove("checksum"); validatePaths(m);
        m.put("checksum",crc(J.str(m))); byte[] data=(J.str(m)+"\n").getBytes(StandardCharsets.UTF_8);
        if(data.length>MAX_LINE)throw new IllegalArgumentException("journal-line-too-large");
        if(path.toAbsolutePath().getParent()!=null)Files.createDirectories(path.toAbsolutePath().getParent());
        try(FileChannel c=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE);FileLock ignored=c.lock()) {
            long size=c.size();
            if(size>0) {
                int window=(int)Math.min(size,MAX_LINE+1L);long start=size-window;
                ByteBuffer tail=ByteBuffer.allocate(window);c.read(tail,start);tail.flip();
                if(tail.get(window-1)!='\n') {
                    int lastNl=-1;for(int i=window-1;i>=0;i--)if(tail.get(i)=='\n'){lastNl=i;break;}
                    if(lastNl<0&&size>MAX_LINE)throw new IllegalArgumentException("journal-line-too-large");
                    c.truncate(lastNl<0?0:start+lastNl+1);
                }
            }
            c.position(c.size());
            ByteBuffer b=ByteBuffer.wrap(data);while(b.hasRemaining())c.write(b);c.force(true);
            String point="instruction".equals(m.get("kind"))?"after_plan_sync":"after_result_sync";
            if(point.equals(System.getenv("LMFILES_KILL_POINT")))Runtime.getRuntime().halt(77);
        }
        return J.str(Map.of("status","ok","durable",true,"bytes",Integer.toString(data.length)));
    }
    private static String crc(String data) {var crc=new CRC32C();crc.update(data.getBytes(StandardCharsets.UTF_8));return String.format(Locale.ROOT,"%08x",crc.getValue());}
    private interface LineVisitor {void accept(Map<String,Object> line) throws IOException;}
    /** Ignores a torn final line, rejects an oversized or corrupt complete line. */
    private static void read(Path path,LineVisitor visitor) throws IOException {
        try(InputStream in=new BufferedInputStream(Files.newInputStream(path))) {
            var buf=new ByteArrayOutputStream(); int b;
            while((b=in.read())!=-1) {
                if(buf.size()+1>MAX_LINE)throw new IllegalArgumentException("journal-line-too-large");
                if(b!='\n') {buf.write(b);continue;}
                String text;
                try {text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(buf.toByteArray())).toString();}
                catch(CharacterCodingException e){throw new IllegalArgumentException("journal-invalid-utf8");}
                buf.reset();Map<String,Object> m=J.obj(text);String checksum=J.text(m,"checksum","");m.remove("checksum");
                if(!crc(J.str(m)).equals(checksum))throw new IllegalArgumentException("journal-checksum-mismatch");
                if(!"1".equals(number(m.get("v"))))throw new IllegalArgumentException("journal-version");
                validatePaths(m);visitor.accept(m);
            }
        }
    }
    private static String number(Object n) {return n instanceof Number?new java.math.BigDecimal(n.toString()).stripTrailingZeros().toPlainString():String.valueOf(n);}
    @SuppressWarnings("unchecked") private static void validatePaths(Object v) {
        if(v instanceof Map<?,?> map) {
            if(map.containsKey("base64") || map.containsKey("utf8")) {
                if(map.size()!=1)throw new IllegalArgumentException("path-encoding-tag");
                byte[] bytes;
                if(map.containsKey("base64")) {
                    if(!(map.get("base64") instanceof String b)||b.isEmpty())throw new IllegalArgumentException("invalid-path-base64");
                    try{bytes=Base64.getDecoder().decode(b);}catch(IllegalArgumentException e){throw new IllegalArgumentException("invalid-path-base64");}
                    if(!Base64.getEncoder().encodeToString(bytes).equals(b))throw new IllegalArgumentException("noncanonical-path-base64");
                    try {StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));throw new IllegalArgumentException("valid-utf8-path-requires-utf8-tag");}catch(CharacterCodingException expected){}
                } else {
                    if(!(map.get("utf8") instanceof String u)||u.isEmpty())throw new IllegalArgumentException("invalid-utf8-path");
                    try {ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(u));bytes=new byte[encoded.remaining()];encoded.get(bytes);}catch(CharacterCodingException e){throw new IllegalArgumentException("invalid-utf8-path");}
                }
                for(byte b:bytes)if(b==0)throw new IllegalArgumentException("path-nul");
            } else for(Object x:map.values())validatePaths(x);
        } else if(v instanceof Collection<?> list)for(Object x:list)validatePaths(x);
    }
    private static Map<String,Object> object(Object o){return io.lmctl.lmcode.Json.object(o);}
    private static String key(Map<String,Object> m){String job=J.text(m,"job","");String op=J.text(m,"op","");if(job.isEmpty()||op.isEmpty())throw new IllegalArgumentException("journal-job-op-required");return J.str(List.of(job,op));}
    private static final class Index implements AutoCloseable {
        final Path path;final MVStore db;final MVMap<String,String> plans,results,completed;int buffered;
        Index(Path plan,Path result) throws IOException {
            path=Files.createTempFile("lmfiles-plan-index-",".mv");
            int cache=(int)Math.max(1,Math.min(8,Main.memoryBudget()/(4L*1024*1024)));
            db=new MVStore.Builder().fileName(path.toString()).cacheSize(cache).autoCommitDisabled().open();
            plans=db.openMap("plans");results=db.openMap("results");completed=db.openMap("completed");
            try {
                read(plan,m->{if(!"instruction".equals(m.get("kind")))throw new IllegalArgumentException("plan-kind");String k=key(m),v=J.str(m);String old=plans.putIfAbsent(k,v);if(old!=null&&!old.equals(v))throw new IllegalArgumentException("plan-op-conflict");if(++buffered%64==0)db.commit();});
                read(result,m->{check(m);});
                db.commit();
            } catch(RuntimeException|IOException e){close();throw e;}
        }
        void check(Map<String,Object> m) {
            if(++buffered%64==0)db.commit();
            if(!"result".equals(m.get("kind")))throw new IllegalArgumentException("result-kind");
            String plan=plans.get(key(m));if(plan==null)throw new IllegalArgumentException("out-of-plan-result");
            Map<String,Object> payload=object(m.get("payload"));
            if(!instructionDigest(J.obj(plan)).equals(J.text(payload,"instruction_digest","")))throw new IllegalArgumentException("instruction-digest-mismatch");
            String id=J.text(m,"result_id","");if(id.isEmpty())throw new IllegalArgumentException("result-id-required");
            String rkey=J.str(List.of(J.text(m,"job",""),id));String value=J.str(m);String old=results.putIfAbsent(rkey,value);
            if(old!=null&&!old.equals(value))throw new IllegalArgumentException("result-id-conflict");
            if(Boolean.TRUE.equals(payload.get("confirmed"))) {
                Map<String,Object> projection=object(payload.get("projection"));bindProjection(J.obj(plan),projection);
                if(Boolean.TRUE.equals(m.get("completes_instruction"))) {
                    String completion=J.str(projection);String prev=completed.putIfAbsent(key(m),completion);
                    if(prev!=null&&!prev.equals(completion))throw new IllegalArgumentException("completion-conflict");
                }
            }
        }
        @Override public void close(){db.close();try{Files.deleteIfExists(path);}catch(IOException e){throw new UncheckedIOException(e);}}
    }
    private static void bindProjection(Map<String,Object> instruction,Map<String,Object> projection) {
        Map<String,Object> plan=object(instruction.get("payload"));
        String kind=J.text(projection,"kind","");
        if(!Set.of("record","stage_record","forget","stage_forget","none").contains(kind))throw new IllegalArgumentException("projection-kind");
        if(!"none".equals(kind)) {
            String expected=J.text(plan,"record_projection","");
            if(!expected.equals(kind) && !(expected.equals("record")&&kind.equals("stage_record")) && !(expected.equals("forget")&&kind.equals("stage_forget")))throw new IllegalArgumentException("projection-outside-plan");
            for(String field:List.of("item","stage","source_revision"))if(!J.text(plan,field,"").equals(J.text(projection,field,"")))throw new IllegalArgumentException("projection-"+field+"-mismatch");
            if(!J.text(plan,"target",J.text(plan,"namespace","")).equals(J.text(projection,"target",J.text(projection,"namespace",""))))throw new IllegalArgumentException("projection-target-mismatch");
            if(plan.containsKey("source_fingerprint") && !J.str(plan.get("source_fingerprint")).equals(J.str(projection.get("source_fingerprint"))))throw new IllegalArgumentException("projection-fingerprint-mismatch");
        }
    }
    public static String apply(Store store,Path plan,Path results) throws IOException {
        long[] applied={0},duplicates={0};
        try(Index index=new Index(plan,results)) {
            read(results,m->{
                Map<String,Object> p=object(m.get("payload"));if(!Boolean.TRUE.equals(p.get("confirmed")))return;
                String key=J.str(List.of(J.text(m,"job",""),J.text(m,"result_id",""))),digest=sha(J.str(m));
                String old=J.text(J.obj(store.call("replay_seen",key)),"digest","");
                if(!old.isEmpty()&&!old.equals(digest))throw new IllegalArgumentException("persisted-result-conflict");
            });
            read(results,m->{
                index.check(m);Map<String,Object> p=object(m.get("payload"));if(!Boolean.TRUE.equals(p.get("confirmed")))return;
                String key=J.str(List.of(J.text(m,"job",""),J.text(m,"result_id",""))),digest=sha(J.str(m));
                Map<String,Object> seen=J.obj(store.call("replay_seen",key));String old=J.text(seen,"digest","");
                if(!old.isEmpty()){if(!old.equals(digest))throw new IllegalArgumentException("persisted-result-conflict");duplicates[0]++;return;}
                Map<String,Object> projection=object(p.get("projection"));String kind=J.text(projection,"kind","");
                try(Store.Batch batch=store.begin()) {
                    if(kind.equals("record")||kind.equals("stage_record")) {
                        var result=new LinkedHashMap<String,Object>(projection);result.remove("kind");result.remove("item");result.put("namespace",J.text(projection,"namespace",J.text(projection,"target","")));result.remove("target");
                        batch.add(J.str(Map.of("op","stage_record","args",List.of(J.text(projection,"item",""),J.str(result)))));
                    } else if(kind.equals("forget")||kind.equals("stage_forget"))batch.add(J.str(Map.of("op","stage_forget","args",List.of(J.text(projection,"item",""),J.text(projection,"stage",""),J.text(projection,"namespace",J.text(projection,"target","")),J.text(projection,"slot","")))));
                    batch.add(J.str(Map.of("op","replay_mark","args",List.of(key,digest))));batch.commit();
                }
                applied[0]++;
            });
        }
        return J.str(Map.of("status","ok","applied",applied[0],"duplicates",duplicates[0]));
    }
    public static String unconfirmed(Path plan,Path results,Path output) throws IOException {
        Path target=output.toAbsolutePath().normalize();
        if(target.equals(plan.toAbsolutePath().normalize())||target.equals(results.toAbsolutePath().normalize()) || Files.exists(target)&&(Files.isSameFile(target,plan)||Files.isSameFile(target,results)))throw new IllegalArgumentException("output-overwrites-input");
        long[] count={0};Files.createDirectories(target.getParent());Path temp=Files.createTempFile(target.getParent(),".remaining-",".jsonl");
        try {
            try(Index index=new Index(plan,results)) {
                read(plan,m->{if(!index.completed.containsKey(key(m))){append(temp,J.str(m),"{}");count[0]++;}});
            }
            try(FileChannel channel=FileChannel.open(temp,StandardOpenOption.WRITE)){channel.force(true);}
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {Files.deleteIfExists(temp);}
        return J.str(Map.of("status","ok","unconfirmed",count[0]));
    }
}
