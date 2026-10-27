package ai.lmctl.lmfiles;
import java.nio.file.*;import java.util.*;
/** Independent semantic regressions for the 1A core. */
public final class CoreChecks {
 private CoreChecks(){} private static int n;
 private static void ok(boolean b,String m){if(!b)throw new AssertionError(m);n++;}
 private static Map<String,Object> m(String s){return J.obj(s);} private static String st(String s){return J.text(m(s),"status","");}
 private static String id(String s){return J.text(m(s),"item","");}
 private static String page(Store.Cursor c){try{return c.nextJson(100,1_000_000);}finally{c.close();}}
 private static void reject(Runnable r,String name){try{r.run();throw new AssertionError(name+" accepted");}catch(IllegalArgumentException|IllegalStateException e){n++;}}
 private static String sr(String stage,String ns,String status,String rev){return "{\"stage\":\""+stage+"\",\"namespace\":\""+ns+"\",\"status\":\""+status+"\",\"source_revision\":\""+rev+"\"}";}
 public static int run(Path root)throws Exception{
  n=0;Files.createDirectories(root);Path db=root.resolve("core.mv.db");Files.deleteIfExists(db);Files.deleteIfExists(root.resolve("core.mv.db.lock"));
  try(Store s=new Store(db,"checks","{}")){
   reject(()->s.call("item_upsert","{}","{}"),"empty identity");
   String one=id(s.call("item_upsert","{\"id\":\"law-1\"}","{\"title\":\"fixture\"}"));ok(!one.isEmpty(),"dense item");
   reject(()->s.call("alias_add","999","{\"kind\":\"url\",\"namespace\":\"source\",\"value\":\"u\"}"),"unknown alias");
   String a="{\"kind\":\"url\",\"namespace\":\"source\",\"value\":\"https://fixture/law-1\"}";ok("ok".equals(st(s.call("alias_add",one,a))),"alias add");String two=id(s.call("item_upsert","{\"id\":\"law-2\"}","{}"));ok("conflict".equals(st(s.call("alias_add",two,a))),"alias collision");ok("conflict".equals(st(s.call("alias_remove",two,a))),"wrong owner remove");ok("ok".equals(st(s.call("alias_remove",one,a))),"owner remove");
   s.call("alias_add",one,a);
   try(Store.Batch b=s.begin()){b.add(J.str(Map.of("op","item_upsert","args",List.of("{\"id\":\"law-1\"}","{\"title\":\"must-rollback\"}"))));b.add(J.str(Map.of("op","alias_add","args",List.of(two,a))));reject(b::commit,"batch conflict");}
   ok(!page(s.query("query_subset","checks","[\"1\"]","{}")).contains("must-rollback"),"conflicting batch all-or-nothing");
   s.call("observe",one,"{\"present\":true,\"size\":10,\"hash\":\"aaa\"}");reject(()->s.call("item_upsert","{\"id\":\"law-1\"}","{\"item\":\"999\",\"revision\":\"999\"}"),"immutable revision");reject(()->s.call("stage_record",one,"{\"stage\":\"fetched\",\"namespace\":\"source\",\"status\":\"bad\",\"source_revision\":\"1\"}"),"invalid status");reject(()->s.call("stage_record",one,"{\"stage\":\"fetched\",\"namespace\":\"source\",\"status\":\"ok\"}"),"missing revision");
   s.call("stage_record",one,sr("fetched","source","ok","2"));String d=s.call("difference_index","{\"stage\":\"fetched\",\"namespace\":\"source\",\"status\":\"ok\"}","{\"stage\":\"cleaned\",\"namespace\":\"clean\",\"status\":\"ok\"}","{\"id\":\"fetch-clean\",\"require_matching_source_revision\":true}");ok("ok".equals(st(d)),"named index");
   String A="{\"stage\":\"fetched\",\"namespace\":\"source\",\"status\":\"ok\"}",B="{\"stage\":\"cleaned\",\"namespace\":\"clean\",\"status\":\"ok\"}";
   ok(!page(s.query("stage_difference",A,B,"{\"index\":\"fetch-clean\",\"require_index\":true}")).equals("[]"),"pending frontier");s.call("stage_record",one,sr("cleaned","clean","ok","2"));ok("[]".equals(page(s.query("stage_difference",A,B,"{\"index\":\"fetch-clean\",\"require_index\":true}"))),"frontier complete");s.call("stage_record",one,sr("cleaned","clean","failed","2"));ok(!page(s.query("stage_difference",A,B,"{\"index\":\"fetch-clean\",\"require_index\":true}")).equals("[]"),"failed latest reopens");s.call("observe",one,"{\"present\":true,\"size\":11,\"hash\":\"bbb\"}");ok(!page(s.query("stage_difference",A,B,"{\"index\":\"fetch-clean\",\"require_index\":true,\"require_matching_source_revision\":true}")).equals("[]"),"source drift pending");
   reject(()->page(s.query("stage_difference","{\"stage\":\"other\"}",B,"{\"index\":\"fetch-clean\",\"require_index\":true}")),"index selector mismatch");reject(()->page(s.query("stage_difference",A,B,"{\"index\":\"missing\",\"require_index\":true}")),"missing required index");
   String cp=page(s.query("claim","{\"difference\":\"fetch-clean\"}","worker","1","5","w"));ok(!cp.equals("[]"),"claim");Map<String,Object> claimRow=(Map<String,Object>)((List<?>)J.parse(cp)).get(0);String token=J.text(claimRow,"token","");ok(!token.isEmpty()&&!J.text(claimRow,"source_revision","").isEmpty(),"claim row token and revision");Thread.sleep(15);ok("fenced-or-expired".equals(st(s.call("renew",token,"1"))),"expired renew");ok("fenced-or-expired".equals(st(s.call("complete",token,sr("cleaned","clean","ok","2"),"2"))),"expired complete");
   String subset=page(s.query("query_subset","checks","[\""+one+"\",\"missing\"]","{\"stage\":\"cleaned\",\"namespace\":\"clean\",\"projection\":\"last_success\"}"));ok(subset.contains("unknown"),"unknown subset verdict");s.call("observe",one,"{\"present\":false,\"size\":0,\"hash\":\"bbb\"}");String absent=page(s.query("query_subset","checks","[\""+one+"\"]","{\"stage\":\"cleaned\",\"namespace\":\"clean\",\"projection\":\"last_success\"}"));ok(absent.contains("recorded-but-missing-locally"),"observed absence verdict");reject(()->s.query("query_subset","checks","[\""+one+"\"]","{\"stage\":\"cleaned\",\"namespace\":\"clean\",\"projection\":\"bad\"}"),"invalid projection");
   ok("ok".equals(st(s.call("alias_add",two,"{\"kind\":\"path\",\"namespace\":\"local\",\"value\":\"x\"}"))),"second alias");ok("ok".equals(st(s.call("alias_add",two,"{\"kind\":\"path\",\"namespace\":\"local\",\"value\":\"x\"}"))),"idempotent alias");
   ok("ok".equals(st(s.call("stage_record",two,sr("parsed","transform","ok","1")))),"parsed stage");ok("ok".equals(st(s.call("stage_record",two,sr("archived","retention","skipped","1")))),"skipped stage");ok("ok".equals(st(s.call("stage_record",two,sr("deleted","retention","gone","1")))),"gone stage");ok("ok".equals(st(s.call("stage_record",two,sr("failed","retention","failed","1")))),"failed stage");
   String twoView=page(s.query("query_subset","checks","[\""+two+"\"]","{}"));ok(twoView.contains("parsed"),"stage view includes parsed");ok(twoView.contains("archived"),"stage view includes archived");ok(twoView.contains("deleted"),"stage view includes deleted");ok(twoView.contains("failed"),"stage view includes failed");
   reject(()->s.call("observe","9999","{\"present\":true}"),"unknown observation");reject(()->s.call("stage_forget","9999","x","y",""),"unknown stage item");
   reject(()->s.query("query_subset","checks","[\""+one+"\"]","{\"stage\":\"cleaned\",\"namespace\":\"clean\",\"projection\":\"last_success\"}").nextJson(0,100),"zero cursor rows");reject(()->s.query("query_subset","checks","[\""+one+"\"]","{\"stage\":\"cleaned\",\"namespace\":\"clean\",\"projection\":\"last_success\"}").nextJson(1,2),"oversize cursor row");
   String cp2=page(s.query("claim","{\"difference\":\"fetch-clean\"}","worker","1","1000","w2"));ok(!cp2.equals("[]"),"reissued claim");Map<String,Object> cr2=(Map<String,Object>)((List<?>)J.parse(cp2)).get(0);String t2=J.text(cr2,"token","");ok("fenced-or-expired".equals(st(s.call("complete",token,sr("cleaned","clean","ok","3"),J.text(cr2,"source_revision","")))),"old token fenced after reissue");ok("conflict".equals(st(s.call("complete",t2,sr("cleaned","clean","ok","future"),J.text(cr2,"source_revision","")))),"future source revision conflict");
   try(Store.Batch b=s.begin()){b.add("{\"op\":\"item_upsert\",\"args\":[\"{\\\"id\\\":\\\"txn-item\\\"}\",\"{}\"]}");b.add("{\"op\":\"malformed\",\"args\":[]}");reject(b::commit,"malformed batch");}ok(page(s.query("query_subset","checks","[\"txn-item\"]","{}")).contains("unknown"),"batch rollback verdict");String next=id(s.call("item_upsert","{\"id\":\"after-batch\"}","{}"));ok(next.equals("3"),"sequence restored after abort");Store.Snapshot snap=s.snapshot();String before=page(snap.query("{\"all\":true}","{}"));s.call("item_upsert","{\"id\":\"after-snapshot\"}","{}");ok(before.equals(page(snap.query("{\"all\":true}","{}"))),"snapshot stable");snap.close();ok("ok".equals(st(s.call("doctor"))),"doctor");
  }
  try(Store s=new Store(db,"checks","{}")){ok(!page(s.query("query_subset","checks","[\"1\"]","{}")).equals("[]"),"reopen");}
  Path snapshot=root.resolve("snapshot.jsonl");Files.deleteIfExists(snapshot);
  try(Store s=new Store(db,"checks","{}")){ok(st(s.call("export_records",snapshot.toString(),"{}")).equals("ok"),"snapshot export");}
  Path restored=root.resolve("restored.mv");Files.deleteIfExists(restored);
  try(Store s=new Store(restored,"checks","{}")){ok(st(s.call("import_records",snapshot.toString())).equals("ok"),"snapshot import");ok(J.text(m(s.call("doctor")),"items","").equals("4"),"all snapshot items restored");}
  Path truncated=root.resolve("truncated.jsonl");String exported=Files.readString(snapshot);Files.writeString(truncated,exported.substring(0,exported.lastIndexOf("{\"kind\":\"end\"")));
  Path rejected=root.resolve("rejected.mv");Files.deleteIfExists(rejected);
  try(Store s=new Store(rejected,"checks","{}")){reject(()->s.call("import_records",truncated.toString()),"truncated snapshot");ok(J.text(m(s.call("doctor")),"items","").equals("0"),"failed import rolls back");}
  byte[] original;
  try(var raw=org.h2.mvstore.MVStore.open(db.toString())){org.h2.mvstore.MVMap<Long,byte[]> rows=raw.openMap("items");original=rows.get(1L).clone();byte[] corrupt=original.clone();corrupt[corrupt.length-1]^=1;rows.put(1L,corrupt);raw.commit();raw.sync();}
  try(Store s=new Store(db,"checks","{}")){reject(()->s.call("doctor"),"row CRC detects corruption");}
  try(var raw=org.h2.mvstore.MVStore.open(db.toString())){org.h2.mvstore.MVMap<Long,byte[]> rows=raw.openMap("items");rows.put(1L,original);raw.commit();raw.sync();}
  return n;
 }
}
