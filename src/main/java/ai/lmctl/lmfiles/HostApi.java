package ai.lmctl.lmfiles;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Single declaration catalog for the lmcode host and generated schema. */
public final class HostApi {
  public record Binding(String type,String name,List<String> parameters,String returns) {
    public String signature(){return (type==null?"fn ":"method "+type+".")+name+"("+String.join(", ",parameters)+") -> "+returns;}
  }
  private static Binding fn(String n,String r,String...p){return new Binding(null,n,List.of(p),r);}
  private static Binding m(String t,String n,String r,String...p){return new Binding(t,n,List.of(p),r);}
  public static final List<Binding> BINDINGS=List.of(
    m("Registry","open","Registry","string","string","string"),
    m("Registry","close","void"),
    m("Registry","log_append","string","string","string","string"),
    m("Registry","instruction_digest","string","string"),
    m("Registry","unconfirmed","string","string","string","string"),
    m("Registry","apply_records","string","string","string","string"), m("Registry","item_upsert","string","string","string"),
    m("Registry","alias_add","string","string","string"), m("Registry","alias_remove","string","string","string"),
    m("Registry","observe","string","string","string"), m("Registry","query_subset","Cursor","string","string","string"),
    m("Registry","stage_record","string","string","string"), m("Registry","stage_forget","string","string","string","string","string"),
    m("Registry","item_tombstone","string","string","string"),
    m("Registry","difference_index","string","string","string","string"), m("Registry","stage_difference","Cursor","string","string","string"),
    m("Registry","claim","Cursor","string","string","int","int","string"),
    m("Registry","renew","string","string","int"), m("Registry","release","string","string"),
    m("Registry","complete","string","string","string","string"),
    m("Registry","snapshot","Snapshot"), m("Registry","begin","WriteBatch"),
    m("Registry","doctor","string","string"), m("Registry","export_records","string","string","string"),
    m("Snapshot","close","void"), m("Snapshot","query","Cursor","string","string"),
    m("WriteBatch","add","void","string"), m("WriteBatch","commit","string"), m("WriteBatch","rollback","void"),
    m("Cursor","next_json","string","int","int"), m("Cursor","write_jsonl","string","string","int","int"),
    m("Cursor","close","void"), m("Cursor","rows","Rows","int","int"),
    m("Rows","len","int"), m("Rows","json_at","string","int"), m("Rows","clear","void")
  );
  private HostApi(){}
  public static String signatures(){return BINDINGS.stream().map(Binding::signature).sorted().reduce("",(a,b)->a+b+"\n");}
  public static String schema(){
    Map<String,Object> fs=new TreeMap<>(),ts=new TreeMap<>(),ctx=new TreeMap<>();
    for(Binding b:BINDINGS){Map<String,Object> spec=new LinkedHashMap<>();spec.put("params",b.parameters());spec.put("returns",b.returns());
      @SuppressWarnings("unchecked") Map<String,Object> target=b.type()==null?fs:(Map<String,Object>)((Map<String,Object>)ts.computeIfAbsent(b.type(),k->new LinkedHashMap<>(Map.of("methods",new TreeMap<String,Object>())))).get("methods");
      @SuppressWarnings("unchecked") List<Object> list=(List<Object>)target.computeIfAbsent(b.name(),k->new ArrayList<>());list.add(spec);}
    ctx.put("registry","Registry");
    Map<String,Object> all=new LinkedHashMap<>();all.put("dsl.schema",2);all.put("name","lmfiles");all.put("functions",fs);all.put("types",ts);all.put("context",ctx);
    return J.str(all)+"\n";
  }
  public static byte[] schemaBytes(){return schema().getBytes(StandardCharsets.UTF_8);}
}
