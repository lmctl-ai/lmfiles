package ai.lmctl.lmfiles;

import java.nio.file.*;
import java.util.*;

public final class PlanLogChecks {
    private PlanLogChecks(){}
    private static int checks;
    private static void ok(boolean b,String m){if(!b)throw new AssertionError(m);checks++;}
    private static void rejects(Runnable r,String m){try{r.run();throw new AssertionError("accepted "+m);}catch(IllegalArgumentException expected){checks++;}}
    private static Map<String,Object> instruction(String op,String item){return Map.of("v",1,"kind","instruction","job","fixture","op",op,"payload",Map.of("verb","put","item",item,"stage","published","target","fake-s3","source_revision","1","record_projection","record"));}
    private static Map<String,Object> receipt(Map<String,Object> plan,String resultId,String item,String digest){return Map.of("v",1,"kind","result","job","fixture","op",plan.get("op"),"result_id",resultId,"completes_instruction",true,"payload",Map.of("instruction_digest",digest,"confirmed",true,"projection",Map.of("kind","record","item",item,"stage","published","target","fake-s3","status","ok","source_revision","1","metadata",Map.of("fixture",true))));}
    public static int run(Path work)throws Exception {
        checks=0;Files.createDirectories(work);Path db=work.resolve("journal.mv"),plan=work.resolve("plan.jsonl"),result=work.resolve("results.jsonl"),remain=work.resolve("pending.jsonl");
        try(Store s=new Store(db,"checks","{}")) {
            String item=J.text(J.obj(s.call("item_upsert","{\"id\":\"case-journal\"}","{}")),"item","");
            Map<String,Object> p=instruction("put-a",item);PlanLog.append(plan,J.str(p),"{}");
            Files.createFile(result);PlanLog.unconfirmed(plan,result,remain);ok(Files.size(remain)>0,"plan alone not success");
            Map<String,Object> r=receipt(p,"receipt-a",item,PlanLog.instructionDigest(p));PlanLog.append(result,J.str(r),"{}");
            ok(J.text(J.obj(PlanLog.apply(s,plan,result)),"applied","").equals("1"),"apply confirmed");
            ok(J.text(J.obj(PlanLog.apply(s,plan,result)),"duplicates","").equals("1"),"dedup");
            PlanLog.unconfirmed(plan,result,remain);ok(Files.size(remain)==0,"confirmed removes intent");
            Files.writeString(result,"{\"torn\":",StandardOpenOption.APPEND);ok(J.text(J.obj(PlanLog.apply(s,plan,result)),"duplicates","").equals("1"),"torn final line tolerated");
            Path bad=work.resolve("bad.jsonl");PlanLog.append(bad,J.str(receipt(p,"bad-digest",item,"wrong")),"{}");
            rejects(()->{try{PlanLog.apply(s,plan,bad);}catch(java.io.IOException e){throw new RuntimeException(e);}},"wrong digest");
            Path outside=work.resolve("outside.jsonl");var rogue=instruction("outside",item);PlanLog.append(outside,J.str(receipt(rogue,"outside",item,PlanLog.instructionDigest(rogue))),"{}");
            rejects(()->{try{PlanLog.apply(s,plan,outside);}catch(java.io.IOException e){throw new RuntimeException(e);}},"out of plan");
            Path corrupt=work.resolve("corrupt.jsonl");Files.writeString(corrupt,Files.readString(plan).replace("fixture","changed"));
            rejects(()->{try{PlanLog.unconfirmed(corrupt,outside,remain);}catch(java.io.IOException e){throw new RuntimeException(e);}},"CRC");
            Path bytes=work.resolve("bytes.jsonl");var raw=new LinkedHashMap<String,Object>(p);raw.put("raw_path",Map.of("base64","/w=="));PlanLog.append(bytes,J.str(raw),"{}");ok(Files.readString(bytes).contains("/w=="),"non UTF8 bytes preserved");
            rejects(()->{try{PlanLog.append(bytes,J.str(Map.of("path",Map.of("base64","YQ=="))),"{}");}catch(java.io.IOException e){throw new RuntimeException(e);}},"noncanonical path tag");
            rejects(()->{try{PlanLog.append(bytes,J.str(Map.of("large","x".repeat(65536))),"{}");}catch(java.io.IOException e){throw new RuntimeException(e);}},"line bound");
        }
        return checks;
    }
}
