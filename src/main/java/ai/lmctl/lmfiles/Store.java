package ai.lmctl.lmfiles;

import org.h2.mvstore.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import java.util.zip.*;

/** Single-writer local registry. All remote effects belong to the caller. */
public final class Store implements AutoCloseable {
    private static final int MAX_RECORD=1024*1024, MAX_BATCH=16*1024*1024;
    private final Path path;
    private final String namespace;
    private MVStore db;
    private LmfilesFaultFileStore faultFile;
    private FileChannel lockChannel;
    private FileLock lock;
    private MVMap<Long,byte[]> items,events;
    private MVMap<String,Long> aliases;
    private MVMap<String,String> meta,differences,frontier,claims,activeClaims,replay,dictionary,aliasFamilies,claimFences;
    private boolean closed;
    private long examined;
    private final LeaseClock clock;
    private long clockOwnerGeneration,clockAnchorMillis,clockAnchorNanos,clockAnchorEra;
    private boolean expiryObserved,leaseOwnerRegistered;
    private final Set<Snapshot> snapshots=Collections.newSetFromMap(new IdentityHashMap<>());

    public Store(Path path,String namespace,String options) {this(path,namespace,options,LeaseClock.SYSTEM);}
    Store(Path path,String namespace,String options,LeaseClock clock) {
        this.clock=Objects.requireNonNull(clock);
        this.path=path.toAbsolutePath().normalize();this.namespace=required(namespace,"namespace");
        Map<String,Object> opts=J.obj(options);keys(opts,"v","cache_mb");
        long cache=Long.parseLong(J.text(opts,"cache_mb","8"));
        if(cache<1||cache>64||cache*1024*1024>Main.memoryBudget()/2)throw new IllegalArgumentException("cache exceeds LMFILES_MAX_MEMORY_MB memory budget");
        try {
            Files.createDirectories(this.path.getParent());
            lockChannel=FileChannel.open(Path.of(this.path+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            try {lock=lockChannel.tryLock();}catch(OverlappingFileLockException e){throw new IllegalStateException("writer-busy");}
            if(lock==null)throw new IllegalStateException("writer-busy");
            // Default FileStore uses positional FileChannel I/O, never a mapped backend.
            String fault=System.getenv("LMFILES_KILL_POINT");
            var builder=new MVStore.Builder().cacheSize((int)cache).compress().autoCommitDisabled();
            if(Set.of("mid_commit","mid_compaction","disk_full").contains(fault==null?"":fault)){
                faultFile=new LmfilesFaultFileStore(fault);faultFile.open(this.path.toString(),false,null);builder.fileStore(faultFile);
            } else builder.fileName(this.path.toString());
            db=builder.open();
            MVMap<String,String> header=db.openMap("meta");
            if("1".equals(header.get("format"))){db=StorageFormat.upgrade(db,this.path);db.setCacheSize((int)cache);faultFile=null;}
            items=db.openMap("items");events=db.openMap("events");aliases=db.openMap("aliases");
            meta=db.openMap("meta");differences=db.openMap("differences");frontier=db.openMap("frontier");
            claims=db.openMap("claims");activeClaims=db.openMap("active_claims");replay=db.openMap("replay");dictionary=db.openMap("dictionary");
            aliasFamilies=db.openMap("alias_families");claimFences=db.openMap("claim_fences");
            if(!meta.containsKey("format")){if(!items.isEmpty()||!events.isEmpty()||!aliases.isEmpty()||!claims.isEmpty()||!differences.isEmpty())throw new IllegalStateException("unversioned-registry-is-not-empty");meta.put("format",StorageFormat.CURRENT);meta.put("epoch",UUID.randomUUID().toString());meta.put("next_item","0");meta.put("next_event","0");meta.put("next_dict","0");meta.put("next_fence","0");durable();}
            if(!StorageFormat.CURRENT.equals(meta.get("format")))throw new IllegalStateException("unsupported-registry-format");
            clockOwnerGeneration=0;
            clockAnchorEra=Long.parseLong(meta.getOrDefault("lease_clock_era","1"));
            clockAnchorMillis=Long.parseLong(meta.getOrDefault("lease_clock_ms",Long.toString(clock.wallMillis())));
            clockAnchorNanos=clock.nanos();
            // Read-only opens and queries do not advance lease clocks or generations.
            // A lease mutation registers and syncs its clock owner with the lease result.
        } catch(Exception e){cleanup();throw new IllegalStateException("cannot-open-registry: "+e.getMessage(),e);}
    }
    private static String required(String s,String field){if(s==null||s.isEmpty()||s.indexOf('\0')>=0)throw new IllegalArgumentException(field+" required, without NUL");return s;}
    private static void keys(Map<String,Object> obj,String... allowed){Set<String> names=Set.of(allowed);for(String k:obj.keySet())if(!names.contains(k))throw new IllegalArgumentException("unknown option: "+k);}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object x){if(!(x instanceof Map<?,?>))throw new IllegalArgumentException("expected object");return (Map<String,Object>)x;}
    private static String text(Map<String,Object> m,String k){return J.text(m,k,"");}
    private static String json(Object v){return J.str(v);}
    private static String ok(Object... fields){Map<String,Object> m=new LinkedHashMap<>();m.put("status","ok");for(int i=0;i<fields.length;i+=2)m.put((String)fields[i],fields[i+1]);return json(m);}
    private static String outcome(String status){return json(Map.of("status",status));}
    private void check(){if(closed)throw new IllegalStateException("registry-closed");}
    private long next(String field){long n=Math.addExact(Long.parseLong(meta.getOrDefault(field,"0")),1);meta.put(field,Long.toString(n));return n;}
    private static void kill(String point){if(point.equals(System.getenv("LMFILES_KILL_POINT")))Runtime.getRuntime().halt(77);}
    private void durable(){kill("before_commit");if(faultFile!=null&&!faultFile.point.equals("mid_compaction"))faultFile.armed=true;db.commit();db.sync();kill("after_commit");}
    private String mutate(Supplier<String> action){check();boolean registered=leaseOwnerRegistered;long owner=clockOwnerGeneration,era=clockAnchorEra,millis=clockAnchorMillis,nanos=clockAnchorNanos;try{String r=action.get();durable();return r;}catch(RuntimeException|Error e){db.rollback();leaseOwnerRegistered=registered;clockOwnerGeneration=owner;clockAnchorEra=era;clockAnchorMillis=millis;clockAnchorNanos=nanos;throw e;}}
    private Map<String,Object> row(String id){byte[] b=items.get(id(id));if(b==null)throw new IllegalArgumentException("unknown-item");return decode(b);}
    private static long id(String s){try{long id=Long.parseLong(s);if(id<=0)throw new NumberFormatException();return id;}catch(NumberFormatException e){throw new IllegalArgumentException("invalid-item-id");}}
    private void save(String id,Map<String,Object> r){StorageFormat.elide(r);items.put(id(id),encode(r));}
    private String aliasKey(Map<String,Object> a,boolean create){keys(a,"kind","namespace","value");String kind=required(text(a,"kind"),"alias kind");if("identity".equals(kind))throw new IllegalArgumentException("reserved-alias-kind");return StorageFormat.aliasKey(kind,required(text(a,"namespace"),"alias namespace"),Objects.requireNonNull(a.get("value"),"alias value"),aliasFamilies,meta,create);}
    private String stageId(String stage,String ns,String slot,boolean create){String k=json(List.of(required(stage,"stage"),required(ns,"namespace"),slot));String d=dictionary.get(k);if(d==null&&create){d=Long.toString(next("next_dict"));dictionary.put(k,d);meta.put("dict:"+d,k);}return d;}
    private static Map<String,Object> stages(Map<String,Object> row){return map(row.computeIfAbsent("stages",k->new TreeMap<String,Object>()));}
    private Map<String,Object> projection(Map<String,Object> row,Map<String,Object> selector){String d=stageId(text(selector,"stage"),text(selector,"namespace"),text(selector,"slot"),false);if(d==null)return Map.of();Object v=stages(row).get(d);if(v==null)return Map.of();Map<String,Object> result=map(v);String p=J.text(selector,"projection","latest_attempt");if(!Set.of("latest_attempt","last_success").contains(p))throw new IllegalArgumentException("invalid-projection");return p.equals("last_success")?success(result):result;}
    private static Map<String,Object> logicalRow(Map<String,Object> row){for(var e:stages(row).entrySet()){Map<String,Object> p=map(e.getValue());Map<String,Object> success=success(p);if(!success.isEmpty())p.put("last_success",success);}return row;}
    private static Map<String,Object> success(Map<String,Object> latest){if(latest.containsKey("last_success"))return map(latest.get("last_success"));return "ok".equals(text(latest,"status"))?new LinkedHashMap<>(latest):Map.of();}
    private void event(String item,String operation,Map<String,Object> data){long n=next("next_event");events.put(n,encode(Map.of("sequence",Long.toString(n),"item",item,"operation",operation,"committed_at",Instant.now().toString(),"data",data)));}

    private static void checkInputBudget(String... a){long limit=Math.min(MAX_RECORD,Main.memoryBudget()/4);for(String arg:a)if(arg!=null&&arg.getBytes(StandardCharsets.UTF_8).length>limit)throw new IllegalArgumentException("input-exceeds-LMFILES_MAX_MEMORY_MB");}
    public synchronized String call(String op,String... a){
        check();
        checkInputBudget(a);
        if(op.equals("doctor")){if(a.length>0)keys(J.obj(a[0]),"v");return doctor();}
        if(op.equals("replay_seen"))return json(Map.of("digest",replay.getOrDefault(a[0],"")));
        if(op.equals("export_records")){if(a.length>1)keys(J.obj(a[1]),"v");return export(Path.of(a[0]));}
        if(op.equals("compact")){kill("before_compaction");int prevRetention=db.getRetentionTime();if(faultFile!=null||snapshots.isEmpty())db.setRetentionTime(0);if(faultFile!=null)faultFile.armed=true;try{db.compactFile(1000);db.sync();}finally{db.setRetentionTime(prevRetention);}kill("after_compaction");return ok();}
        if(op.equals("stats"))return ok("examined",Long.toString(examined),"items",Long.toString(items.sizeAsLong()),"events",Long.toString(events.sizeAsLong()),"retention_ms",Integer.toString(db.getRetentionTime()));
        if(op.equals("apply_records")){if(a.length>2)keys(J.obj(a[2]),"v");try{return PlanLog.apply(this,Path.of(a[0]),Path.of(a[1]));}catch(IOException e){throw new UncheckedIOException(e);}}
        expiryObserved=false;String response=mutate(()->change(op,a));if(expiryObserved)kill("after_claim_expiry");if(op.equals("claim"))kill("after_claim_commit");return response;
    }
    private String change(String op,String... a){return switch(op){
        case "item_upsert" -> upsert(J.obj(a[0]),J.obj(a[1]));
        case "alias_add","alias_remove" -> alias(op,a[0],J.obj(a[1]));
        case "observe" -> observe(a[0],J.obj(a[1]));
        case "stage_record" -> record(a[0],J.obj(a[1]));
        case "stage_forget" -> forget(a[0],a[1],a[2],a.length>3?a[3]:"");
        case "item_tombstone" -> {var r=row(a[0]);r.put("tombstone",true);save(a[0],r);refresh(a[0],r);event(a[0],op,Map.of("note",a[1]));yield ok();}
        case "difference_index" -> index(J.obj(a[0]),J.obj(a[1]),J.obj(a[2]));
        case "claim" -> claim(J.obj(a[0]),a[1],Integer.parseInt(a[2]),Long.parseLong(a[3]),a[4]);
        case "renew","release","complete" -> finish(op,a);
        case "replay_mark" -> {String prev=replay.get(a[0]);if(prev!=null&&!prev.equals(a[1]))throw new IllegalArgumentException("replay-conflict");replay.put(a[0],a[1]);yield ok();}
        case "import_records" -> restore(Path.of(a[0]));
        default -> throw new IllegalArgumentException("unsupported-operation: "+op);
    };}
    private String upsert(Map<String,Object> identity,Map<String,Object> fields){
        keys(identity,"id","aliases");String external=required(text(identity,"id"),"identity.id");
        for(String k:List.of("item","revision","stages","identity","observation","tombstone"))if(fields.containsKey(k))throw new IllegalArgumentException("read-only field: "+k);
        String key=StorageFormat.aliasKey("identity",namespace,external,aliasFamilies,meta,true);Long number=aliases.get(key);Map<String,Object> r;
        if(number==null){number=next("next_item");r=new LinkedHashMap<>(fields);r.put("item",number.toString());r.put("identity",external);r.put("revision","1");r.put("stages",new TreeMap<String,Object>());aliases.put(key,number);}
        else {r=row(number.toString());String old=text(r,"content_hash"),fresh=text(fields,"content_hash");if(!fresh.isEmpty()&&!old.equals(fresh))r.put("revision",Long.toString(Long.parseLong(text(r,"revision"))+1));r.putAll(fields);}
        if(identity.containsKey("aliases")){Object value=identity.get("aliases");if(!(value instanceof List<?> list)||list.size()>64)throw new IllegalArgumentException("initial aliases must be bounded array");for(Object alias:list){String keyAlias=aliasKey(map(alias),true);Long old=aliases.get(keyAlias);if(old!=null&&!old.equals(number))throw new IllegalArgumentException("initial-alias-conflict");aliases.put(keyAlias,number);}}
        save(number.toString(),r);refresh(number.toString(),r);event(number.toString(),"item_upsert",Map.of("revision",text(r,"revision"),"aliases",identity.getOrDefault("aliases",List.of())));return ok("item",number.toString(),"revision",text(r,"revision"));
    }
    private String alias(String op,String item,Map<String,Object> a){row(item);String k=aliasKey(a,op.equals("alias_add"));if(k==null)return outcome("conflict");Long prev=aliases.get(k);if(op.equals("alias_add")){if(prev!=null&&prev!=id(item))return outcome("conflict");aliases.put(k,id(item));}else{if(prev==null||prev!=id(item))return outcome("conflict");aliases.remove(k);}event(item,op,a);return ok();}
    private String observe(String item,Map<String,Object> o){
        keys(o,"hash","size","mtime","mtime_ns","present","algorithm","ctime","file_id");
        if(o.containsKey("present")&&!(o.get("present") instanceof Boolean))throw new IllegalArgumentException("present must be boolean");
        var r=row(item);String h=text(o,"hash");Object prior=r.get("observation");
        boolean changed=!h.isEmpty()&&!h.equals(text(r,"content_hash"));
        if(prior instanceof Map<?,?> p&&o.containsKey("present")&&!Objects.equals(o.get("present"),p.get("present")))changed=true;
        if(h.isEmpty()&&prior instanceof Map<?,?> p)for(String f:List.of("size","mtime","mtime_ns","present"))if(o.containsKey(f)&&!Objects.equals(o.get(f),p.get(f)))changed=true;
        if(h.isEmpty()&&prior==null&&o.containsKey("size")&&r.containsKey("size")&&!text(o,"size").equals(text(r,"size")))changed=true;
        if(changed)r.put("revision",Long.toString(Long.parseLong(text(r,"revision"))+1));
        if(!h.isEmpty())r.put("content_hash",h);else if(changed)r.remove("content_hash");
        r.put("observation",o);save(item,r);refresh(item,r);event(item,"observe",o);return ok("revision",text(r,"revision"));
    }
    private String record(String item,Map<String,Object> result){
        var r=row(item);String stage=required(text(result,"stage"),"stage"),ns=required(text(result,"namespace"),"namespace"),status=text(result,"status");
        if(!Set.of("ok","failed","gone","skipped").contains(status))throw new IllegalArgumentException("invalid-status");
        String rev=required(text(result,"source_revision"),"source_revision");if(Long.parseLong(rev)<1||Long.parseLong(rev)>Long.parseLong(text(r,"revision")))throw new IllegalArgumentException("invalid-source-revision");
        if(result.containsKey("last_success"))throw new IllegalArgumentException("read-only-last-success");
        String d=stageId(stage,ns,text(result,"slot"),true);var all=stages(r);Map<String,Object> prev=all.containsKey(d)?map(all.get(d)):Map.of();
        var p=new LinkedHashMap<String,Object>(result);p.remove("stage");p.remove("namespace");p.remove("slot");p.putIfAbsent("timestamp",Instant.now().toString());Instant.parse(text(p,"timestamp"));
        Map<String,Object> success=success(prev);
        if(status.equals("ok")&&(success.isEmpty()||Long.parseLong(rev)>=Long.parseLong(text(success,"source_revision"))))success=new LinkedHashMap<>(p);
        if(!success.isEmpty()&&!json(success).equals(json(p)))p.put("last_success",success);
        // A late receipt is an event, never a replacement for a newer attempt.
        if(prev.isEmpty()||Long.parseLong(rev)>=Long.parseLong(text(prev,"source_revision")))all.put(d,p);
        else if(!success.isEmpty()){var keep=new LinkedHashMap<>(prev);keep.put("last_success",success);all.put(d,keep);}
        save(item,r);refresh(item,r);event(item,"stage_record",result);return ok("event",meta.get("next_event"));
    }
    private String forget(String item,String stage,String ns,String slot){var r=row(item);String d=stageId(stage,ns,slot,false);if(d!=null)stages(r).remove(d);save(item,r);refresh(item,r);event(item,"stage_forget",Map.of("stage",stage,"namespace",ns,"slot",slot));return ok();}
    private static void selector(Map<String,Object> q){keys(q,"stage","namespace","slot","status","projection");required(text(q,"stage"),"stage");required(text(q,"namespace"),"namespace");String status=text(q,"status");if(!status.isEmpty()&&!Set.of("ok","failed","gone","skipped").contains(status))throw new IllegalArgumentException("invalid-status");String proj=text(q,"projection");if(!proj.isEmpty()&&!Set.of("latest_attempt","last_success").contains(proj))throw new IllegalArgumentException("invalid-projection");}
    private String index(Map<String,Object> a,Map<String,Object> b,Map<String,Object> opts){
        selector(a);selector(b);keys(opts,"id","require_matching_source_revision");
        String id=J.text(opts,"id",PlanLog.sha(json(List.of(a,b,opts))));required(id,"index.id");
        Map<String,Object> spec=Map.of("a",a,"b",b,"match_revision",Boolean.TRUE.equals(opts.get("require_matching_source_revision")));
        String previous=differences.get(id);if(previous!=null){if(!previous.equals(json(spec)))throw new IllegalArgumentException("index-definition-conflict");return ok("difference",id,"ready",true);}
        differences.put(id,json(spec));for(var entry:items.entrySet())refreshOne(id,spec,Long.toString(entry.getKey()),decode(entry.getValue()));return ok("difference",id,"ready",true);
    }
    private boolean matches(Map<String,Object> r,Map<String,Object> q){Map<String,Object> p=projection(r,q);return !p.isEmpty()&&(text(q,"status").isEmpty()||text(q,"status").equals(text(p,"status")));}
    private boolean pending(Map<String,Object> r,Map<String,Object> spec){
        if(Boolean.TRUE.equals(r.get("tombstone")))return false;Map<String,Object> a=map(spec.get("a")),b=map(spec.get("b"));
        if(!matches(r,a))return false;
        if(!matches(r,b))return true;
        if(Boolean.TRUE.equals(spec.get("match_revision"))){String ar=text(projection(r,a),"source_revision"),br=text(projection(r,b),"source_revision");return !ar.equals(br)||!br.equals(text(r,"revision"));}
        return false;
    }
    private static String frontierKey(String index,String item){return index+"\0"+String.format(Locale.ROOT,"%020d",id(item));}
    private void refreshOne(String index,Map<String,Object> spec,String item,Map<String,Object> r){String k=frontierKey(index,item);if(pending(r,spec))frontier.put(k,item);else frontier.remove(k);}
    private void refresh(String item,Map<String,Object> r){for(var d:differences.entrySet())refreshOne(d.getKey(),J.obj(d.getValue()),item,r);}
    /** Nondecreasing logical time within an era; any observed wall backstep fences the old era. */
    private long leaseNow(){
        if(!leaseOwnerRegistered){clockOwnerGeneration=next("clock_owner_generation");leaseOwnerRegistered=true;}
        long wall=clock.wallMillis(),nano=clock.nanos();
        long lastWall=Long.parseLong(meta.getOrDefault("lease_last_wall_ms",Long.toString(wall)));
        long era=Long.parseLong(meta.getOrDefault("lease_clock_era","1"));
        if(wall<lastWall){era=Math.addExact(era,1);meta.put("lease_clock_era",Long.toString(era));meta.put("lease_clock_ms",Long.toString(wall));clockAnchorEra=era;clockAnchorMillis=wall;clockAnchorNanos=nano;}
        if(era!=clockAnchorEra){clockAnchorEra=era;clockAnchorMillis=Long.parseLong(meta.getOrDefault("lease_clock_ms",Long.toString(wall)));clockAnchorNanos=nano;}
        long elapsed=Math.max(0,(nano-clockAnchorNanos)/1000000);
        long logical=Math.max(wall,Math.max(Long.parseLong(meta.getOrDefault("lease_clock_ms",Long.toString(wall))),Math.addExact(clockAnchorMillis,elapsed)));
        meta.put("lease_last_wall_ms",Long.toString(wall));meta.put("lease_clock_ms",Long.toString(logical));meta.put("lease_clock_era",Long.toString(era));return logical;
    }
    private static long grantGeneration(String token,Map<String,Object> claim){String generation=J.text(claim,"owner_generation",token.substring(token.lastIndexOf(':')+1));long g=Long.parseLong(generation);if(g<1||!token.endsWith(":"+g))throw new IllegalStateException("claim-generation-corrupt");return g;}
    private String claim(Map<String,Object> sel,String work,int count,long lease,String owner){
        keys(sel,"difference");String index=required(text(sel,"difference"),"difference");if(!differences.containsKey(index))throw new IllegalArgumentException("index-not-ready");
        if(count<1||count>4096||lease<1||lease>86400000)throw new IllegalArgumentException("invalid-claim-count-or-lease");required(work,"work namespace");required(owner,"owner");
        long now=leaseNow(),until=Math.addExact(now,lease),wallUntil=Math.addExact(clock.wallMillis(),lease);
        List<Object> out=new ArrayList<>();String prefix=index+"\0";Iterator<String> it=frontier.keyIterator(prefix);
        while(it.hasNext()&&out.size()<count){String k=it.next();if(!k.startsWith(prefix))break;String item=frontier.get(k),ck=json(List.of(work,item)),oldToken=activeClaims.get(ck);
            if(oldToken!=null&&alive(oldToken))continue;
            long generation=next("next_fence");String token=UUID.randomUUID()+":"+generation;
            Map<String,Object> c=new LinkedHashMap<>(Map.of("token",token,"item",item,"source_revision",text(row(item),"revision"),"owner",owner,"work",work,"until",Long.toString(wallUntil),"lease_ms",Long.toString(lease)));
            c.put("deadline_ms",Long.toString(until));c.put("deadline_wall_ms",Long.toString(wallUntil));c.put("owner_generation",Long.toString(generation));c.put("clock_owner_generation",Long.toString(clockOwnerGeneration));c.put("clock_era",meta.get("lease_clock_era"));
            if(oldToken!=null)claims.remove(oldToken);claims.put(token,json(c));activeClaims.put(ck,token);claimFences.put(ck,Long.toString(generation));out.add(c);
        }
        return json(out);
    }
    private boolean alive(String token){
        String raw=claims.get(token);if(raw==null)return false;Map<String,Object> c=J.obj(raw);String ck=json(List.of(text(c,"work"),text(c,"item")));long generation=grantGeneration(token,c);
        if(!token.equals(activeClaims.get(ck))||!Long.toString(generation).equals(claimFences.getOrDefault(ck,Long.toString(generation))))return false;
        long now=leaseNow();long deadline=Long.parseLong(J.text(c,"deadline_ms",text(c,"until"))),wallDeadline=Long.parseLong(J.text(c,"deadline_wall_ms",text(c,"until")));
        if(!J.text(c,"clock_era","1").equals(meta.get("lease_clock_era"))||now>=deadline||clock.wallMillis()>=wallDeadline||clock.wallMillis()<Math.subtractExact(wallDeadline,Long.parseLong(text(c,"lease_ms")))){
            claims.remove(token);activeClaims.remove(ck);claimFences.put(ck,Long.toString(generation));expiryObserved=true;return false;
        }
        return true;
    }
    private String finish(String op,String... a){
        if(!alive(a[0]))return outcome("fenced-or-expired");var c=J.obj(claims.get(a[0]));String ck=json(List.of(text(c,"work"),text(c,"item")));
        if(op.equals("renew")){long lease=Long.parseLong(a[1]);if(lease<1||lease>86400000)throw new IllegalArgumentException("invalid-lease");long now=leaseNow();c.put("deadline_ms",Long.toString(Math.addExact(now,lease)));String wallUntil=Long.toString(Math.addExact(clock.wallMillis(),lease));c.put("deadline_wall_ms",wallUntil);c.put("until",wallUntil);c.put("lease_ms",Long.toString(lease));c.put("clock_owner_generation",Long.toString(clockOwnerGeneration));claims.put(a[0],json(c));return ok();}
        if(op.equals("complete")){
            if(a.length<3||!a[2].equals(text(c,"source_revision"))||!a[2].equals(text(row(text(c,"item")),"revision")))return outcome("conflict");
            var result=J.obj(a[1]);if(!a[2].equals(text(result,"source_revision")))return outcome("conflict");record(text(c,"item"),result);
        }
        claims.remove(a[0]);activeClaims.remove(ck);return ok();
    }

    public synchronized Cursor query(String operation,String... args){
        check();checkInputBudget(args);if(operation.equals("claim")){String r=call("claim",args);return new Cursor(((List<?>)J.parse(r)).iterator(),null);}
        Snapshot snapshot=snapshot();try{return snapshot.cursor(true,operation,args);}catch(RuntimeException e){snapshot.close();throw e;}
    }
    public synchronized Snapshot snapshot(){check();Snapshot s=new Snapshot();snapshots.add(s);return s;}
    public synchronized Batch begin(){check();return new Batch(this);}
    public final class Snapshot implements AutoCloseable {
        private final MVStore.TxCounter pin;
        private final MVMap<Long,byte[]> view;
        private final MVMap<String,String> fronts,defs;
        private final MVMap<String,Long> aliasView;
        private final MVMap<String,String> familyView;
        private boolean ended;
        Snapshot(){pin=db.registerVersionUsage();long version=db.getCurrentVersion();view=items.openVersion(version);fronts=frontier.openVersion(version);defs=differences.openVersion(version);aliasView=aliases.openVersion(version);familyView=aliasFamilies.openVersion(version);}
        public Cursor query(String query,String options){checkInputBudget(query,options);Map<String,Object> q=J.obj(query);keys(q,"ids","all");return cursor(false,Boolean.TRUE.equals(q.get("all"))?"select":"query_subset",namespace,json(q.getOrDefault("ids",List.of())),options);}
        Cursor cursor(boolean ownSnapshot,String op,String... a){
            if(ended)throw new IllegalStateException("snapshot-closed");Iterator<?> source;
            if(op.equals("select")){source=view.entrySet().stream().map(e->logicalRow(decode(e.getValue()))).iterator();}
            else if(op.equals("query_subset")){
                if(a.length<3)throw new IllegalArgumentException("query_subset requires namespace, identity array, options");
                Object raw=J.parse(a[1]);if(!(raw instanceof List<?> ids))throw new IllegalArgumentException("ids must be array");if(ids.size()>4096)throw new IllegalArgumentException("subset-too-large: use bounded batches");
                Map<String,Object> opts=J.obj(a[2]);keys(opts,"stage","namespace","slot","projection");
                if(opts.containsKey("projection")&&!Set.of("last_success","latest_attempt").contains(text(opts,"projection")))throw new IllegalArgumentException("invalid-projection");
                if(opts.containsKey("stage")){opts.putIfAbsent("namespace",a[0]);required(text(opts,"namespace"),"baseline namespace");}
                source=ids.stream().map(x->compare(x,view,aliasView,familyView,opts)).iterator();
            } else if(op.equals("stage_difference")){
                Map<String,Object> opts=J.obj(a[2]);keys(opts,"index","require_index","require_matching_source_revision");String name=required(text(opts,"index"),"index");
                String raw=defs.get(name);if(raw==null)throw new IllegalArgumentException("index-not-ready");var spec=J.obj(raw);
                if(!json(spec.get("a")).equals(json(J.obj(a[0])))||!json(spec.get("b")).equals(json(J.obj(a[1]))))throw new IllegalArgumentException("index-selector-mismatch");
                if(opts.containsKey("require_matching_source_revision")&&!Objects.equals(opts.get("require_matching_source_revision"),spec.get("match_revision")))throw new IllegalArgumentException("index-options-mismatch");
                String prefix=name+"\0";Iterator<String> keys=fronts.keyIterator(prefix);
                source=new Iterator<Object>(){String next;boolean ready;public boolean hasNext(){if(!ready&&keys.hasNext()){next=keys.next();ready=true;}return ready&&next.startsWith(prefix);}public Object next(){if(!hasNext())throw new NoSuchElementException();ready=false;examined++;return logicalRow(decode(view.get(id(fronts.get(next)))));}};
            } else throw new IllegalArgumentException("unsupported-query: "+op);
            Iterator<?> values=source;
            Iterator<Object> guarded=new Iterator<>(){private void valid(){if(ended)throw new IllegalStateException("snapshot-closed");}public boolean hasNext(){valid();return values.hasNext();}public Object next(){valid();return values.next();}};
            return new Cursor(guarded,ownSnapshot?this:null);
        }
        @Override public void close(){synchronized(Store.this){if(!ended){ended=true;db.deregisterVersionUsage(pin);snapshots.remove(this);}}}
    }
    private Object compare(Object identity,MVMap<Long,byte[]> view,MVMap<String,Long> aliasView,MVMap<String,String> familyView,Map<String,Object> opts){
        String resolved;
        if(identity instanceof Map<?,?> m){Map<String,Object> a=map(m);keys(a,"kind","namespace","value");String kind=required(text(a,"kind"),"alias kind");if(kind.equals("identity"))throw new IllegalArgumentException("reserved-alias-kind");String key=StorageFormat.aliasKey(kind,required(text(a,"namespace"),"alias namespace"),Objects.requireNonNull(a.get("value")),familyView,meta,false);Long found=key==null?null:aliasView.get(key);resolved=found==null?"":found.toString();}else resolved=String.valueOf(identity);
        byte[] bytes;try{bytes=resolved.isEmpty()?null:view.get(id(resolved));}catch(IllegalArgumentException e){bytes=null;}
        if(bytes==null)return Map.of("identity",identity,"state","unknown","reason","no-item");
        var r=decode(bytes);Map<String,Object> out=new LinkedHashMap<>(r);
        Map<String,Object> stageViews=new TreeMap<>();for(var stage:stages(r).entrySet()){String names=meta.get("dict:"+stage.getKey());Map<String,Object> logical=new LinkedHashMap<>(map(stage.getValue()));Map<String,Object> priorSuccess=success(logical);if(!priorSuccess.isEmpty())logical.put("last_success",priorSuccess);stageViews.put(names==null?stage.getKey():names,logical);}out.put("stages",stageViews);
        out.put("state","unknown");out.put("reason","no-baseline");out.put("basis","none");
        if(opts.containsKey("stage")) {
            Map<String,Object> selector=new LinkedHashMap<>(opts);selector.putIfAbsent("projection","last_success");Map<String,Object> base=projection(r,selector);out.put("recorded",base);
            if(!base.isEmpty()){
                Object observation=r.get("observation");
                if(observation instanceof Map<?,?> o&&Boolean.FALSE.equals(o.get("present"))){out.put("state","recorded-but-missing-locally");out.put("reason","observed-absence");}
                else if(!r.containsKey("content_hash")&&observation==null){out.put("reason","unobserved-local");}
                else {Map<String,Object> fingerprint=map(base.getOrDefault("source_fingerprint",Map.of()));boolean digests=!text(fingerprint,"hash").isEmpty()&&!text(r,"content_hash").isEmpty();boolean same=digests?text(fingerprint,"hash").equals(text(r,"content_hash")):text(base,"source_revision").equals(text(r,"revision"));out.put("state",same?"same-as-recorded":"changed");out.put("reason",digests?"digest-comparison":"revision-comparison");out.put("basis",digests?"digest":"revision");}
            }
        }
        return out;
    }
    public static final class Cursor implements AutoCloseable {
        private final Iterator<?> source;private final AutoCloseable owner;private Object pending;private boolean ended;private Rows reusable;
        Cursor(Iterator<?> source,AutoCloseable owner){this.source=source;this.owner=owner;}
        public String nextJson(int maxRows,int maxBytes){
            if(ended)throw new IllegalStateException("cursor-closed");if(maxRows<1||maxRows>4096||maxBytes<2||maxBytes>Math.min(MAX_BATCH,Main.memoryBudget()/4))throw new IllegalArgumentException("invalid-cursor-budget LMFILES_MAX_MEMORY_MB");
            StringBuilder out=new StringBuilder("[");int count=0,used=2;
            while(count<maxRows&&(pending!=null||source.hasNext())){if(pending==null)pending=source.next();String encoded=json(pending);int bytes=encoded.getBytes(StandardCharsets.UTF_8).length+(count==0?0:1);
                if(used+bytes>maxBytes){if(count==0)throw new IllegalArgumentException("row-exceeds-cursor-byte-budget");break;}
                if(count++>0)out.append(',');out.append(encoded);used+=bytes;pending=null;
            }
            return out.append(']').toString();
        }
        public Rows rows(int maxRows,int maxBytes){List<?> page=(List<?>)J.parse(nextJson(maxRows,maxBytes));if(reusable==null)reusable=new Rows();reusable.data=page;return reusable;}
        public String writeJsonl(String path,int rows,int bytes)throws IOException {long n=0;try(var out=Files.newBufferedWriter(Path.of(path))){while(true){List<?> page=(List<?>)J.parse(nextJson(rows,bytes));if(page.isEmpty())break;for(Object value:page){out.write(json(value));out.newLine();n++;}}}return ok("rows",Long.toString(n));}
        @Override public void close(){if(!ended){ended=true;if(reusable!=null)reusable.clear();if(owner!=null)try{owner.close();}catch(Exception e){throw new IllegalStateException(e);}}}
    }
    public static final class Rows {private List<?> data=List.of();public int len(){return data.size();}public String jsonAt(int index){return json(data.get(index));}public void clear(){data=List.of();}}
    public static final class Batch implements AutoCloseable {
        private final Store store;private final List<Map<String,Object>> operations=new ArrayList<>();private boolean ended;private int bytes;
        Batch(Store store){this.store=store;}
        public Batch add(String operation){if(ended)throw new IllegalStateException("batch-closed");bytes=Math.addExact(bytes,operation.getBytes(StandardCharsets.UTF_8).length);if(bytes>Math.min(MAX_BATCH,Main.memoryBudget()/4))throw new IllegalArgumentException("batch-exceeds-LMFILES_MAX_MEMORY_MB");var op=J.obj(operation);keys(op,"op","args");required(text(op,"op"),"op");if(!(op.get("args") instanceof List<?>))throw new IllegalArgumentException("operation args must be array");operations.add(op);return this;}
        public String commit(){if(ended)throw new IllegalStateException("batch-closed");synchronized(store){try{return store.mutate(()->{for(var op:operations){List<?> args=(List<?>)op.get("args");String result=store.change(text(op,"op"),args.stream().map(x->{if(!(x instanceof String))throw new IllegalArgumentException("batch args must be strings");return (String)x;}).toArray(String[]::new));Object receipt=J.parse(result);if(receipt instanceof Map<?,?> m&&m.containsKey("status")&&!"ok".equals(m.get("status")))throw new IllegalArgumentException("batch-operation-"+m.get("status"));}return ok();});}finally{ended=true;operations.clear();}}}
        public void rollback(){ended=true;operations.clear();}
        @Override public void close(){if(!ended)rollback();}
    }
    private String doctor(){if(items.sizeAsLong()!=Long.parseLong(meta.get("next_item"))||events.sizeAsLong()!=Long.parseLong(meta.get("next_event")))throw new IllegalStateException("record-sequence-gap");long rows=0;long pendingCount=0;for(var entry:items.entrySet()){Map<String,Object> r=decode(entry.getValue());if(!Long.toString(entry.getKey()).equals(text(r,"item")))throw new IllegalStateException("item-key-corrupt");for(var d:differences.entrySet()){boolean expected=pending(r,J.obj(d.getValue()));if(expected)pendingCount++;if(expected!=frontier.containsKey(frontierKey(d.getKey(),text(r,"item"))))throw new IllegalStateException("frontier-corrupt");}rows++;}if(pendingCount!=frontier.sizeAsLong())throw new IllegalStateException("orphan-frontier");for(var e:events.entrySet())if(!e.getKey().toString().equals(text(decode(e.getValue()),"sequence")))throw new IllegalStateException("event-sequence-corrupt");for(var family:aliasFamilies.entrySet()){long n=id(family.getValue());if(n>Long.parseLong(meta.getOrDefault("next_alias_family","0"))||!family.getKey().equals(meta.get("alias_family:"+n)))throw new IllegalStateException("alias-family-corrupt");}
        for(var a:aliases.entrySet()){if(!items.containsKey(a.getValue()))throw new IllegalStateException("dangling-alias");int separator=a.getKey().indexOf('\0');if(separator<1)throw new IllegalStateException("alias-key-corrupt");String family=a.getKey().substring(0,separator),pair=meta.get("alias_family:"+family);if(pair==null||!family.equals(aliasFamilies.get(pair)))throw new IllegalStateException("alias-family-missing");String value=a.getKey().substring(separator+1);if(!json(J.parse(value)).equals(value))throw new IllegalStateException("alias-value-corrupt");}return ok("items",Long.toString(rows),"events",Long.toString(events.sizeAsLong()),"examined",Long.toString(examined),"format",meta.get("format"),"alias_families",Long.toString(aliasFamilies.sizeAsLong()),"storage","MVStore positional I/O, compressed CRC32C binary records");}
    public boolean doctorStatus(){return "ok".equals(text(J.obj(doctor()),"status"));}
    private String export(Path out){
        try {
            if(out.toAbsolutePath().normalize().equals(path))throw new IllegalArgumentException("export-overwrites-db");
            long count=0;
            try(var w=Files.newBufferedWriter(out,StandardOpenOption.CREATE_NEW)){
                w.write(json(Map.of("kind","snapshot","format",2,"store_format",StorageFormat.CURRENT)));w.newLine();
                for(String name:new TreeSet<>(db.getMapNames())){MVMap<?,?> m=db.openMap(name);for(var e:m.entrySet()){
                    Object value=e.getValue();Map<String,Object> record=new TreeMap<>(Map.of("map",name,"key",e.getKey().toString(),"value",value instanceof byte[] bytes?Map.of("base64",Base64.getEncoder().encodeToString(bytes)):value));
                    record.put("checksum",PlanLog.sha(json(record)));w.write(json(record));w.newLine();count++;
                }}
                w.write(json(Map.of("kind","end","rows",Long.toString(count))));w.newLine();
            }
            try(var c=FileChannel.open(out,StandardOpenOption.WRITE)){c.force(true);}
            return ok("rows",Long.toString(count));
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
    private String restore(Path input){
        if(!items.isEmpty()||!events.isEmpty()||!aliases.isEmpty()||!differences.isEmpty()||!claims.isEmpty()||!replay.isEmpty())throw new IllegalArgumentException("import-requires-empty-registry");long rows=0;boolean end=false;
        try(var lines=Files.newBufferedReader(input)){
            String first=boundedLine(lines);if(first==null||!"snapshot".equals(text(J.obj(first),"kind"))||!Set.of("1","2").contains(J.text(J.obj(first),"format","")))throw new IllegalArgumentException("snapshot-header");
            String sourceFormat=J.text(J.obj(first),"store_format","1");if(!Set.of("1",StorageFormat.CURRENT).contains(sourceFormat))throw new IllegalArgumentException("snapshot-store-format");
            String line;while((line=boundedLine(lines))!=null){Map<String,Object> r=J.obj(line);
                if("end".equals(text(r,"kind"))){if(Long.parseLong(text(r,"rows"))!=rows||boundedLine(lines)!=null)throw new IllegalArgumentException("snapshot-count");end=true;break;}
                String checksum=text(r,"checksum");r.remove("checksum");if(!PlanLog.sha(json(r)).equals(checksum))throw new IllegalArgumentException("snapshot-checksum");
                String name=text(r,"map"),key=text(r,"key");Object v=r.get("value");switch(name){
                    case "items","events" -> {byte[] bytes=Base64.getDecoder().decode(text(map(v),"base64"));Map<String,Object> decoded=decode(bytes);if(name.equals("items")){StorageFormat.elide(decoded);bytes=encode(decoded);}(name.equals("items")?items:events).put(Long.parseLong(key),bytes);}
                    case "aliases" -> {if(sourceFormat.equals("1")){List<?> a=StorageFormat.legacyAlias(key);key=StorageFormat.aliasKey((String)a.get(0),(String)a.get(1),a.get(2),aliasFamilies,meta,true);}aliases.put(key,new java.math.BigDecimal(v.toString()).longValueExact());}
                    case "meta","differences","frontier","claims","active_claims","replay","dictionary","alias_families","claim_fences" -> {if(name.equals("meta")&&key.equals("format")){if(!sourceFormat.equals(v.toString()))throw new IllegalArgumentException("snapshot-format-mismatch");}else{MVMap<String,String> target=db.openMap(name);target.put(key,v.toString());}}
                    default -> throw new IllegalArgumentException("unknown-snapshot-map");
                }rows++;
            }
            if(!end)throw new IllegalArgumentException("snapshot-truncated");
        }catch(IOException e){throw new UncheckedIOException(e);}
        doctor();return ok("rows",Long.toString(rows));
    }
    private static String boundedLine(BufferedReader reader)throws IOException {StringBuilder out=new StringBuilder();int c;long limit=Math.min(2L*MAX_RECORD,Main.memoryBudget()/4);while((c=reader.read())!=-1){if(c=='\n')return out.toString();if(out.length()>=limit)throw new IllegalArgumentException("snapshot-line-exceeds-memory-budget");out.append((char)c);}return out.isEmpty()?null:out.toString();}
    static byte[] encode(Map<String,Object> map){try{byte[] raw=json(map).getBytes(StandardCharsets.UTF_8);if(raw.length>Math.min(MAX_RECORD,Main.memoryBudget()/4))throw new IllegalArgumentException("record-exceeds-memory-budget");var zipped=new ByteArrayOutputStream();try(var out=new DeflaterOutputStream(zipped)){out.write(raw);}byte[] data=zipped.toByteArray();CRC32C crc=new CRC32C();crc.update(data);ByteBuffer b=ByteBuffer.allocate(12+data.length);b.putInt(1).putInt(raw.length).putInt((int)crc.getValue()).put(data);return b.array();}catch(IOException e){throw new UncheckedIOException(e);}}
    static Map<String,Object> decode(byte[] bytes){try{if(bytes.length<12)throw new IllegalStateException("record-truncated");ByteBuffer b=ByteBuffer.wrap(bytes);if(b.getInt()!=1)throw new IllegalStateException("record-version");int length=b.getInt(),check=b.getInt();if(length<0||length>MAX_RECORD)throw new IllegalStateException("record-length");byte[] data=Arrays.copyOfRange(bytes,12,bytes.length);CRC32C crc=new CRC32C();crc.update(data);if(check!=(int)crc.getValue())throw new IllegalStateException("record-checksum");try(var in=new InflaterInputStream(new ByteArrayInputStream(data))){byte[] raw=in.readNBytes(length+1);if(raw.length!=length)throw new IllegalStateException("record-size");return J.obj(new String(raw,StandardCharsets.UTF_8));}}catch(IOException e){throw new IllegalStateException("record-corrupt",e);}}
    private void cleanup(){try{if(db!=null)db.closeImmediately();}catch(Exception ignored){}try{if(lock!=null)lock.release();}catch(Exception ignored){}try{if(lockChannel!=null)lockChannel.close();}catch(Exception ignored){}}
    @Override public synchronized void close(){if(!closed){for(Snapshot s:List.copyOf(snapshots))s.close();closed=true;try{if(db!=null)db.close();}finally{cleanup();}}}
}
