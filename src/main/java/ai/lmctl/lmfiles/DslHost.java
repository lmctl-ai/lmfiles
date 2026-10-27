package ai.lmctl.lmfiles;

import io.lmctl.lmcode.*;
import java.nio.file.*;
import java.util.*;

/** Explicit, reflection-free lmcode bindings for the local registry. */
public final class DslHost implements AutoCloseable {
  private final Registry vm=new Registry();
  private final Context context=new Context();
  private final List<Store> stores=new ArrayList<>();
  public DslHost(){
    context.memoryLimitBytes=Main.memoryBudget();
    for(HostApi.Binding b:HostApi.BINDINGS){
      Registry.Callback cb=(ctx,self,args)->{try{Value result=call(b,ctx,self,args);if(result.kind()==Value.Kind.STR)ctx.charge(result.asString().toString().length()*2L+32L);else if(result.kind()==Value.Kind.OBJ)ctx.charge(64);return result;}catch(VmException e){throw e;}catch(Exception e){throw VmException.host("registry: "+e.getMessage());}};
      if(b.type()==null)vm.function(b.name(),b.parameters(),b.returns(),cb); else vm.method(b.type(),b.name(),b.parameters(),b.returns(),cb);
    }
    vm.context("registry","Registry");
    context.setObject("registry","Registry",new Object());
    var signatures=new ArrayList<String>(HostApi.BINDINGS.stream().map(HostApi.Binding::signature).toList());
    signatures.add("context registry: Registry");
    vm.checkSchemaSignatures(signatures);
  }
  private static String str(Value v){return v.asString().toString();}
  private static int integer(Value v){long n=v.asInt();if(n<0||n>Integer.MAX_VALUE)throw VmException.host("integer out of range");return (int)n;}
  private static Store store(Value self){if(!(self.asObject() instanceof Store s))throw VmException.host("registry: operation requires an open Registry handle");return s;}
  private Value open(Context ctx,Value[] args){
    long cache=Long.parseLong(J.text(J.obj(str(args[2])),"cache_mb","8"));
    if(cache<1||cache>64)throw VmException.host("cache_mb must be 1..64");
    ctx.charge(cache*1024L*1024+65536);
    Store store=new Store(Path.of(str(args[0])),str(args[1]),str(args[2]));stores.add(store);
    return Value.object("Registry",store);
  }
  private Value call(HostApi.Binding b,Context ctx,Value self,Value[] a)throws Exception{
    if(b.type()==null){return open(ctx,a);}
    String n=b.name();
    if(b.type().equals("Registry")){
      if(n.equals("log_append"))return Value.ofString(PlanLog.append(Path.of(str(a[0])),str(a[1]),str(a[2])));
      if(n.equals("instruction_digest"))return Value.ofString(PlanLog.instructionDigest(J.obj(str(a[0]))));
      if(n.equals("unconfirmed"))return Value.ofString(PlanLog.unconfirmed(Path.of(str(a[0])),Path.of(str(a[1])),Path.of(str(a[2]))));
      if(n.equals("apply_records"))return Value.ofString(store(self).call("apply_records",vals(a)));
      if(n.equals("open")){return open(ctx,a);}Store s=store(self);return switch(n){
      case "close"-> {s.close();stores.remove(s);yield Value.none();}
      case "scan"->Value.ofString(s.call("scan",vals(a)));
      case "item_upsert","alias_add","alias_remove","observe","stage_record","stage_forget","item_tombstone","difference_index","renew","release","complete","doctor","export_records"->Value.ofString(s.call(n,vals(a)));
      case "query_subset","stage_difference"->Value.object("Cursor",s.query(n,vals(a)));
      case "claim"->Value.object("Cursor",s.query(n,vals(a)));
      case "snapshot"->Value.object("Snapshot",s.snapshot());
      case "begin"->Value.object("WriteBatch",s.begin());
      default->throw VmException.host("unsupported registry method: "+n);};}
    if(b.type().equals("Snapshot")){Store.Snapshot snap=(Store.Snapshot)self.asObject();return switch(n){case "close"->{snap.close();yield Value.none();}case "query"->Value.object("Cursor",snap.query(str(a[0]),str(a[1])));default->throw VmException.host("unsupported snapshot method");};}
    if(b.type().equals("WriteBatch")){Store.Batch x=(Store.Batch)self.asObject();return switch(n){case "add"->{x.add(str(a[0]));yield Value.none();}case "commit"->Value.ofString(x.commit());case "rollback"->{x.rollback();yield Value.none();}default->throw VmException.host("unsupported batch method");};}
    if(b.type().equals("Cursor")){Store.Cursor x=(Store.Cursor)self.asObject();return switch(n){case "next_json"->Value.ofString(x.nextJson(integer(a[0]),integer(a[1])));case "write_jsonl"->Value.ofString(x.writeJsonl(str(a[0]),integer(a[1]),integer(a[2])));case "close"->{x.close();yield Value.none();}case "rows"->{Store.Rows r=x.rows(integer(a[0]),integer(a[1]));ctx.charge(r.len()*64L);yield Value.object("Rows",r);}default->throw VmException.host("unsupported cursor method");};}
    if(b.type().equals("Rows")){Store.Rows x=(Store.Rows)self.asObject();return switch(n){case "len"->Value.ofInt(x.len());case "json_at"->Value.ofString(x.jsonAt(integer(a[0])));case "clear"->{x.clear();yield Value.none();}default->throw VmException.host("unsupported rows method");};}
    throw VmException.host("unsupported registry handle");
  }
  private static String[] vals(Value[] a){String[] out=new String[a.length];for(int i=0;i<a.length;i++)out[i]=a[i].kind()==Value.Kind.INT?Long.toString(a[i].asInt()):a[i].kind()==Value.Kind.BOOL?Boolean.toString(a[i].asBool()):str(a[i]);return out;}
  private Value text(String value){long bytes=value==null?0:value.length()*2L+32;context.charge(bytes);return Value.ofString(value==null?"":value);}
  public int run(Path bytecode,boolean dry)throws Exception{context.resetBudget();long max=Main.memoryBudget();long size=Files.size(bytecode);if(size>Math.min(max/4,64L*1024*1024))throw VmException.badBytecode("bytecode exceeds LMFILES_MAX_MEMORY_MB budget");Program p=Program.load(Files.readAllBytes(bytecode),HostApi.schemaBytes(),vm);if(dry)return Reports.ok("{\"dry_run\":true}");try{p.run(context,vm);return Reports.ok("null");}catch(VmException e){if(e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("memory"))System.err.println("lmfiles: LMFILES_MAX_MEMORY_MB memory budget exceeded");return Reports.error("run",e);}}
  public Registry registry(){return vm;}
  public void close(){for(Store s:List.copyOf(stores))try{s.close();}catch(RuntimeException ignored){}stores.clear();}
}
