package ai.lmctl.lmfiles;

import org.h2.mvstore.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;

/** Guarded synthetic correctness campaign; never a release/large-tier guarantee. */
public final class ScaleChecks {
  private static final int BATCH=512, SEED=70231;
  private static final long MIN_FREE=40_000_000_000L, MAX_DIR=25_000_000_000L, RESERVE=64L*1024*1024;
  private static final String NS="scale", INDEX="scale-frontier";
  private static final String A=J.str(Map.of("stage","fetched","namespace",NS,"status","ok","projection","latest_attempt"));
  private static final String B=J.str(Map.of("stage","published","namespace",NS,"status","ok","projection","latest_attempt"));
  private final Path root,db,manifest;
  private final List<Integer> updates=new ArrayList<>();
  private final Map<Integer,Integer> claimed=new HashMap<>();
  private int built,crashNote,claimTotal,crashTotal;
  private double v2PerItem,v3PerItem;
  private long assertions;
  private ScaleChecks(Path root)throws Exception {
    this.root=root.toAbsolutePath().normalize();db=this.root.resolve("state.mv");manifest=this.root.resolve("expected.json");
    if(!this.root.getParent().equals(Path.of("/tmp"))||!this.root.getFileName().toString().startsWith("lmfiles-scale-")||Files.isSymbolicLink(this.root))throw new IllegalArgumentException("dedicated /tmp/lmfiles-scale-* directory required");
    if(!Files.isRegularFile(this.root.resolve(".lmfiles-scale-marker"),LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("scale fixture marker missing");
    if(Files.exists(manifest)){Map<String,Object> m=J.obj(Files.readString(manifest));built=Integer.parseInt(J.text(m,"built","0"));crashNote=Integer.parseInt(J.text(m,"crash_note","0"));claimTotal=Integer.parseInt(J.text(m,"claim_total","0"));crashTotal=Integer.parseInt(J.text(m,"crash_total","0"));v2PerItem=Double.parseDouble(J.text(m,"v2_per_item","0"));v3PerItem=Double.parseDouble(J.text(m,"v3_per_item","0"));for(Object n:(List<?>)m.get("updates"))updates.add(Integer.parseInt(n.toString()));for(var e:io.lmctl.lmcode.Json.object(m.get("claimed")).entrySet())claimed.put(Integer.parseInt(e.getKey()),Integer.parseInt(e.getValue().toString()));}
    guard("open");
  }
  private long bytes()throws IOException {final long[] n={0};Files.walkFileTree(root,new SimpleFileVisitor<>(){@Override public FileVisitResult visitFile(Path p,BasicFileAttributes a)throws IOException{if(a.isSymbolicLink())throw new IOException("fixture symlink refused");n[0]=Math.addExact(n[0],((a.size()+4095)/4096)*4096);return FileVisitResult.CONTINUE;}});return n[0];}
  private void guard(String phase)throws IOException {long free=Files.getFileStore(Path.of("/")).getUsableSpace(),used=bytes();if(free<MIN_FREE||used>MAX_DIR)throw new IOException("hard-disk-guard: free="+free+" dir="+used+" phase="+phase);}
  private boolean headroom(long extra)throws IOException {guard("headroom");return bytes()+extra+RESERVE<=MAX_DIR && Files.getFileStore(Path.of("/")).getUsableSpace()-extra-RESERVE>=MIN_FREE;}
  private void save()throws IOException {Map<String,String> claims=new TreeMap<>();claimed.forEach((k,v)->claims.put(k.toString(),v.toString()));Path tmp=root.resolve("expected.tmp");Files.writeString(tmp,J.str(Map.of("built",Integer.toString(built),"updates",updates.stream().map(Object::toString).toList(),"claimed",claims,"crash_note",Integer.toString(crashNote),"v2_per_item",Double.toString(v2PerItem),"v3_per_item",Double.toString(v3PerItem),"claim_total",Integer.toString(claimTotal),"crash_total",Integer.toString(crashTotal))));Files.move(tmp,manifest,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
  private static void emit(Map<String,Object> row){System.out.println(J.str(row));System.out.flush();}
  private void progress(String phase,long done)throws IOException{guard(phase);emit(Map.of("event","progress","phase",phase,"done",done,"dir_bytes",bytes(),"free_bytes",Files.getFileStore(Path.of("/")).getUsableSpace()));}
  private void ok(boolean value,String why){if(!value)throw new AssertionError(why);assertions++;}
  private Store open(){return new Store(db,NS,"{\"cache_mb\":64}");}
  private static String identity(int i){return "synthetic-item-"+i;}
  private static String id(int i){return Integer.toString(i+1);}
  private static String hash(int i,int generation){return PlanLog.sha("synthetic-value:"+i+":"+generation);}
  private static String size(int i,int generation){return Integer.toString(128+i%31+generation%7);}
  private static String alias(int i){return J.str(Map.of("kind","synthetic","namespace",NS,"value","alias-"+i));}
  private static boolean selected(int i,int count){return i<count && ((long)i*7919+SEED)%count<count/100;}
  private static String stage(String name,String status,Expected e,int i,boolean claim){return J.str(Map.of("stage",name,"namespace",NS,"status",status,"source_revision",Integer.toString(e.rev),"source_fingerprint",Map.of("hash",hash(i,e.gen),"size",size(i,e.gen)),"metadata",name.equals("published")?Map.of("generation",Integer.toString(e.gen),"claimed",claim):Map.of("generation",Integer.toString(e.gen))));}
  private static void add(Store.Batch b,String op,String...args){b.add(J.str(Map.of("op",op,"args",List.of(args))));}
  private static final class Expected {int rev=1,gen=0,pubRev=1,pubGen=0,successRev=1,successGen=0;String pub="ok";boolean pubClaim;}
  private Expected expected(int i){Expected e=new Expected();if(i%1000==0){e.pub="none";e.pubRev=e.successRev=0;}
    for(int count:updates){if(selected(i,count)){int previousRev=e.rev,previousGen=e.gen;e.rev++;e.gen=count;e.pubRev=e.rev;e.pubGen=e.gen;e.pub=i%3==0?"failed":"ok";e.successRev=e.pub.equals("ok")?e.rev:previousRev;e.successGen=e.pub.equals("ok")?e.gen:previousGen;e.pubClaim=false;}
      if(Objects.equals(claimed.get(i),count)){e.pub="ok";e.pubRev=e.successRev=e.rev;e.pubGen=e.successGen=e.gen;e.pubClaim=true;}}
    return e;
  }
  private long expectedEvents(int count){long total=5L*count-(count+999)/1000+claimTotal+crashTotal;
    for(int n:updates){total+=5L*(n/100);long inverse=java.math.BigInteger.valueOf(7919).modInverse(java.math.BigInteger.valueOf(n)).longValueExact();for(int j=0;j<n/100;j++){long i=inverse*Math.floorMod(j-SEED,n)%n;if(i%3!=0)total++;}}
    return total;
  }
  private void counts(Store s,int count){Map<String,Object> stat=J.obj(s.call("stats"));ok(Integer.toString(count).equals(stat.get("items")),"exact item count");ok(Long.toString(2L*count).equals(stat.get("aliases")),"exact identity and user-alias count");ok(Long.toString(expectedEvents(count)).equals(stat.get("events")),"exact independently expected audit event count");ok("0".equals(stat.get("claims")),"no abandoned claims");}
  private boolean pending(int i){return !expected(i).pub.equals("ok");}
  private String subset(Store s,Object identity,String projection){try(Store.Cursor c=s.query("query_subset",NS,J.str(List.of(identity)),J.str(Map.of("stage","published","namespace",NS,"projection",projection)))){return c.nextJson(1,1048576);}}
  private static Map<String,Object> first(String page){return io.lmctl.lmcode.Json.object(((List<?>)J.parse(page)).get(0));}
  private void row(Map<String,Object> r,int i,Expected e,boolean publicView){
    ok(id(i).equals(J.text(r,"item","")),"item id "+i);ok(identity(i).equals(J.text(r,"identity","")),"identity "+i);
    ok(Integer.toString(e.rev).equals(J.text(r,"revision","")),"revision "+i);ok(hash(i,e.gen).equals(r.get("content_hash")),"content hash "+i);
    ok(size(i,e.gen).equals(J.text(r,"size","")),"size "+i);ok(Integer.toString(e.gen).equals(r.get("attribute")),"attribute "+i);
    Map<String,Object> obs=io.lmctl.lmcode.Json.object(r.get("observation"));ok(J.str(obs).equals(J.str(Map.of("hash",hash(i,e.gen),"size",size(i,e.gen),"mtime",Integer.toString(e.gen),"present",true,"algorithm","SHA-256"))),"exact observation "+i);
    if(i==0&&crashNote>0)ok(("confirmed-"+crashNote).equals(r.get("crash_note")),"confirmed crash marker retained");
    Map<String,Object> stages=io.lmctl.lmcode.Json.object(r.get("stages"));String fk=publicView?J.str(List.of("fetched",NS,"")):"1",pk=publicView?J.str(List.of("published",NS,"")):"2";
    checkStage(io.lmctl.lmcode.Json.object(stages.get(fk)),"ok",e.rev,e.gen,i,false,false);
    if(e.pub.equals("none"))ok(!stages.containsKey(pk),"missing initial published projection "+i);else {Map<String,Object> p=io.lmctl.lmcode.Json.object(stages.get(pk));checkStage(p,e.pub,e.pubRev,e.pubGen,i,e.pubClaim,true);checkStage(p.containsKey("last_success")?io.lmctl.lmcode.Json.object(p.get("last_success")):p,"ok",e.successRev,e.successGen,i,e.pub.equals("ok")&&e.pubClaim,true);}
  }
  private void checkStage(Map<String,Object> p,String status,int rev,int gen,int i,boolean claim,boolean published){ok(status.equals(p.get("status")),"stage status "+i);ok(Integer.toString(rev).equals(p.get("source_revision")),"stage revision "+i);ok(J.str(p.get("source_fingerprint")).equals(J.str(Map.of("hash",hash(i,gen),"size",size(i,gen)))),"stage fingerprint "+i);ok(J.str(p.get("metadata")).equals(J.str(published?Map.of("generation",Integer.toString(gen),"claimed",claim):Map.of("generation",Integer.toString(gen)))),"exact stage metadata "+i);Instant.parse((String)p.get("timestamp"));assertions++;}
  private SortedSet<Integer> samples(int count){SortedSet<Integer> set=new TreeSet<>();for(int j=0;j<Math.min(count,10000);j++)set.add((int)(((long)j*104729+SEED)%count));for(int n:new int[]{0,1,511,512,513,999,1000,count-2,count-1})if(n>=0&&n<count)set.add(n);for(int n:updates)for(int i:new int[]{n-1,n,n+1})if(i>=0&&i<count)set.add(i);return set;}
  private void build(int count)throws Exception {if(count<built)throw new IllegalArgumentException("ramp must increase");try(Store s=open()){s.call("difference_index",A,B,"{\"id\":\"scale-frontier\",\"require_matching_source_revision\":true}");
    for(int offset=built;offset<count;offset+=BATCH){guard("build-batch");try(Store.Batch b=s.begin()){for(int i=offset;i<Math.min(count,offset+BATCH);i++){Expected e=new Expected();add(b,"item_upsert",J.str(Map.of("id",identity(i))),J.str(Map.of("content_hash",hash(i,0),"size",size(i,0),"attribute","0")));add(b,"alias_add",id(i),alias(i));add(b,"observe",id(i),J.str(Map.of("hash",hash(i,0),"size",size(i,0),"mtime","0","present",true,"algorithm","SHA-256")));add(b,"stage_record",id(i),stage("fetched","ok",e,i,false));if(i%1000!=0)add(b,"stage_record",id(i),stage("published","ok",e,i,false));}b.commit();}if((offset-built)%(32*BATCH)==0)progress("build",Math.min(count,offset+BATCH));}built=count;save();}}
  private Map<String,Object> verify(int count)throws Exception {ok(built==count,"exact built manifest");long sampled=0,subsetCount=0,frontierCount=0;try(Store s=open()){
    counts(s,count);for(int i:samples(count)){if(sampled%BATCH==0)guard("sample");Expected e=expected(i);Map<String,Object> byIdentity=s.lookupIdentity(identity(i));row(byIdentity,i,e,false);Map<String,Object> byAlias=first(subset(s,J.obj(alias(i)),"latest_attempt"));row(byAlias,i,e,true);ok(id(i).equals(byAlias.get("item")),"alias identity agreement");
      Map<String,Object> last=first(subset(s,id(i),"last_success"));String wantedLast=e.pub.equals("none")?"unknown":e.successRev==e.rev?"same-as-recorded":"changed";
      ok(wantedLast.equals(last.get("state")),"last-success subset verdict "+i);sampled++;}
    ok(s.lookupIdentity("synthetic-unknown").isEmpty(),"unknown identity");ok("unknown".equals(first(subset(s,"999999999999","latest_attempt")).get("state")),"unknown numeric id");ok("unknown".equals(first(subset(s,Map.of("kind","synthetic","namespace",NS,"value","unknown"),"latest_attempt")).get("state")),"unknown alias");
    List<Integer> requested=new ArrayList<>(samples(count));
    for(int offset=0;offset<requested.size();offset+=1024){guard("query-subset");List<String> ids=new ArrayList<>();for(int k=offset;k<Math.min(requested.size(),offset+1024);k++)ids.add(id(requested.get(k)));
      try(Store.Cursor c=s.query("query_subset",NS,J.str(ids),"{\"stage\":\"published\",\"namespace\":\"scale\",\"projection\":\"latest_attempt\"}")){
        String page;while(!(page=c.nextJson(256,4194304)).equals("[]")){for(Object value:(List<?>)J.parse(page)){Map<String,Object> r=io.lmctl.lmcode.Json.object(value);int i=Integer.parseInt((String)r.get("item"))-1;
          ok(i==requested.get((int)subsetCount),"exact ordered subset identity");Expected e=expected(i);String wanted=e.pub.equals("none")?"unknown":"same-as-recorded";
          ok(wanted.equals(r.get("state")),"subset digest verdict "+i);subsetCount++;}}}}

    int next=0;try(Store.Cursor c=s.query("stage_difference",A,B,"{\"index\":\"scale-frontier\",\"require_index\":true,\"require_matching_source_revision\":true}")){String page;while(!(page=c.nextJson(256,4194304)).equals("[]")){guard("frontier");for(Object value:(List<?>)J.parse(page)){while(next<count&&!pending(next))next++;Map<String,Object> r=io.lmctl.lmcode.Json.object(value);ok(next<count&&id(next).equals(r.get("item")),"frontier exact independently computed membership");next++;frontierCount++;}}}while(next<count&&!pending(next))next++;ok(next==count,"frontier contains all expected entries");ok(subsetCount==requested.size(),"exact requested subset count");}
    return Map.of("samples",sampled,"subset_rows",subsetCount,"frontier_rows",frontierCount);
  }
  private Map<String,Object> update(int count)throws Exception {if(updates.contains(count))throw new IllegalStateException("step already updated");long n=0,kept=0;try(Store s=open()){
    for(int offset=0;offset<count;offset+=BATCH){guard("update-batch");List<Integer> chosen=new ArrayList<>();try(Store.Batch b=s.begin()){for(int i=offset;i<Math.min(count,offset+BATCH);i++)if(selected(i,count)){Expected old=expected(i),fresh=expected(i);fresh.rev++;fresh.gen=count;chosen.add(i);add(b,"stage_record",id(i),stage("published","ok",old,i,false));add(b,"item_upsert",J.str(Map.of("id",identity(i))),J.str(Map.of("content_hash",hash(i,count),"size",size(i,count),"attribute",Integer.toString(count))));add(b,"observe",id(i),J.str(Map.of("hash",hash(i,count),"size",size(i,count),"mtime",Integer.toString(count),"present",true,"algorithm","SHA-256")));add(b,"stage_record",id(i),stage("fetched","ok",fresh,i,false));add(b,"stage_record",id(i),stage("published","failed",fresh,i,false));}if(!chosen.isEmpty())b.commit();}
      for(int i:chosen){Expected old=expected(i);Map<String,Object> r=first(subset(s,id(i),"latest_attempt"));Map<String,Object> p=io.lmctl.lmcode.Json.object(r.get("recorded"));ok("failed".equals(p.get("status")),"failed attempt retained");ok(Integer.toString(old.rev+1).equals(r.get("revision"))&&Integer.toString(old.rev+1).equals(p.get("source_revision")),"exact updated content and attempt revision");ok(hash(i,count).equals(r.get("content_hash"))&&Integer.toString(count).equals(r.get("attribute")),"exact updated hash and attribute");Map<String,Object> success=io.lmctl.lmcode.Json.object(p.get("last_success"));ok(Integer.toString(old.rev).equals(success.get("source_revision"))&&"ok".equals(success.get("status")),"failed after success keeps previous successful revision");kept++;}
      try(Store.Batch b=s.begin()){for(int i:chosen)if(i%3!=0){Expected fresh=expected(i);fresh.rev++;fresh.gen=count;add(b,"stage_record",id(i),stage("published","ok",fresh,i,false));}if(!chosen.isEmpty())b.commit();}
      for(int i:chosen){Expected e=expected(i);int oldRev=e.rev,oldGen=e.gen;e.rev++;e.gen=count;e.pubRev=e.rev;e.pubGen=count;e.pub=i%3==0?"failed":"ok";e.successRev=e.pub.equals("ok")?e.rev:oldRev;e.successGen=e.pub.equals("ok")?count:oldGen;e.pubClaim=false;row(first(subset(s,id(i),"latest_attempt")),i,e,true);}
      n+=chosen.size();if(offset%131072==0)progress("update",n);}
    }ok(n==count/100,"exact pseudorandom one percent");updates.add(count);save();return Map.of("updated",n,"failed_after_success_verified",kept);
  }
  private Map<String,Object> claim(int count)throws Exception {long n=0;try(Store s=open()){List<?> rows;try(Store.Cursor c=s.query("claim","{\"difference\":\"scale-frontier\"}","scale-workers","128","600000","synthetic-worker")){rows=(List<?>)J.parse(c.nextJson(128,4194304));}for(Object value:rows){guard("claim-complete");Map<String,Object> r=io.lmctl.lmcode.Json.object(value);int i=Integer.parseInt((String)r.get("item"))-1;ok(pending(i),"claimed item belongs to expected frontier");Expected e=expected(i);String receipt=s.call("complete",(String)r.get("token"),stage("published","ok",e,i,true),Integer.toString(e.rev));ok("ok".equals(J.obj(receipt).get("status")),"claim completion");ok("fenced-or-expired".equals(J.obj(s.call("complete",(String)r.get("token"),stage("published","ok",e,i,true),Integer.toString(e.rev))).get("status")),"stale complete fenced");claimed.put(i,count);n++;}}claimTotal+=n;save();return Map.of("claimed_and_completed",n);}
  private void crashPrepare(int count)throws Exception {try(Store s=open()){s.call("item_upsert",J.str(Map.of("id",identity(0))),J.str(Map.of("crash_note","confirmed-"+count)));}crashNote=count;crashTotal++;save();}
  private void crash(int count)throws Exception {try(Store s=open();Store.Batch b=s.begin()){add(b,"item_upsert",J.str(Map.of("id",identity(0))),"{\"crash_note\":\"unconfirmed\"}");for(int i=0;i<512;i++)add(b,"item_upsert",J.str(Map.of("id","phantom-"+count+"-"+i)),J.str(Map.of("payload","x".repeat(512))));b.commit();}throw new AssertionError("SIGKILL injection did not pause at write boundary");}
  private Map<String,Object> recover(int count)throws Exception {try(Store s=open()){ok(Long.toString(count).equals(J.obj(s.call("stats")).get("items")),"no phantom item count");ok(("confirmed-"+count).equals(s.lookupIdentity(identity(0)).get("crash_note")),"no acknowledged record lost");for(int i:new int[]{0,255,511})ok(s.lookupIdentity("phantom-"+count+"-"+i).isEmpty(),"no phantom identity after kill");ok("unknown".equals(first(subset(s,Integer.toString(count+1),"latest_attempt")).get("state")),"no phantom numeric id");}return Map.of("confirmed_records_retained",true,"phantoms",0);}
  private Map<String,Object> upgrade(int count)throws Exception {
    long source=Files.size(db);
    long extra=source*6;
    if(v2PerItem>0&&v3PerItem>0){long v2=(long)Math.ceil(v2PerItem*count*1.25),v3=(long)Math.ceil(v3PerItem*count*1.25);extra=Math.max(source+v2,2*v2+v3)-source;}
    if(!headroom(extra))return Map.of("status","skipped_headroom","source_bytes",source,"conservative_extra_bytes",extra);

    Path v2=root.resolve("upgrade-format2.mv");if(Files.exists(v2))throw new IllegalStateException("upgrade fixture already exists");
    try(MVStore old=new MVStore.Builder().fileName(db.toString()).readOnly().open();MVStore out=new MVStore.Builder().fileName(v2.toString()).cacheSize(64).compress().autoCommitDisabled().open()){
      for(String name:old.getMapNames()){long n=0;if(Set.of("items","events").contains(name)){MVMap<Long,byte[]> src=old.openMap(name),dst=out.openMap(name);for(var e:src.entrySet()){byte[] value=e.getValue();if(name.equals("events"))value=Store.encode(Store.decode(value));dst.put(e.getKey(),value);if(++n%BATCH==0){guard("format2-copy");out.commit();}if(n%131072==0)progress("format2-copy-"+name,n);}}
        else if(name.equals("aliases")){MVMap<String,Long> src=old.openMap(name),dst=out.openMap(name);for(var e:src.entrySet()){dst.put(e.getKey(),e.getValue());if(++n%BATCH==0){guard("format2-aliases");out.commit();}}}
        else {MVMap<String,String> src=old.openMap(name),dst=out.openMap(name);for(var e:src.entrySet()){dst.put(e.getKey(),e.getValue());if(++n%BATCH==0){guard("format2-map");out.commit();}}}}
      out.<String,String>openMap("meta").put("format","2");out.commit();out.sync();
    }
    long format2Bytes=Files.size(v2);guard("before-real-upgrade");Files.delete(db);Files.move(v2,db,StandardCopyOption.ATOMIC_MOVE);
    try(Store s=open()){ok("3".equals(J.obj(s.call("doctor")).get("format")),"real format2 to3 upgraded and doctor passed");}
    Path backup=Path.of(db+".v2.bak");try(MVStore raw=new MVStore.Builder().fileName(backup.toString()).readOnly().open()){ok("2".equals(raw.<String,String>openMap("meta").get("format")),"retained released format2 backup");ok(raw.<Long,byte[]>openMap("items").sizeAsLong()==count,"backup exact count");for(int i:samples(count)){guard("upgrade-backup-samples");row(Store.decode(raw.<Long,byte[]>openMap("items").get((long)i+1)),i,expected(i),false);}}
    Map<String,Object> checked=verify(count);Files.delete(backup);v2PerItem=(double)format2Bytes/count;v3PerItem=(double)Files.size(db)/count;save();return Map.of("upgraded_items",count,"backup_verified",true,"format2_bytes",format2Bytes,"post_upgrade",checked);
  }
  public static void run(Path root,String phase,int count)throws Exception {if(count<100||count>20_000_000)throw new IllegalArgumentException("count must be100..20000000");ScaleChecks t=new ScaleChecks(root);long start=System.nanoTime();Map<String,Object> detail=new LinkedHashMap<>();switch(phase){case "build"->t.build(count);case "verify","reopen"->detail.putAll(t.verify(count));case "update"->detail.putAll(t.update(count));case "claim"->detail.putAll(t.claim(count));case "crash_prepare"->t.crashPrepare(count);case "crash"->t.crash(count);case "recover"->detail.putAll(t.recover(count));case "doctor"->{try(Store s=t.open()){detail.put("doctor",J.obj(s.call("doctor")));}}case "compact"->{long source=Files.size(t.db);if(!t.headroom(Math.multiplyExact(source,2)))detail.putAll(Map.of("status","skipped_headroom","source_bytes",source,"conservative_extra_bytes",source*2));else{try(Store s=t.open()){s.call("compact");}detail.putAll(t.verify(count));}}case "upgrade"->detail.putAll(t.upgrade(count));default->throw new IllegalArgumentException("unknown scale phase");}try(Store checked=t.open()){t.counts(checked,count);detail.put("exact_counts",J.obj(checked.call("stats")));}t.guard("phase-finished");detail.putAll(Map.of("event","result","phase",phase,"status",detail.getOrDefault("status","ok"),"count",count,"assertions",t.assertions,"seconds",(System.nanoTime()-start)/1e9,"dir_bytes",t.bytes(),"db_bytes",Files.exists(t.db)?Files.size(t.db):0));emit(detail);}
}
