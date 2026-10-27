package ai.lmctl.lmfiles;

import java.nio.file.*;
import java.util.*;

/** Reproducible local synthetic 1A qualification, not a 100M-scale claim. */
public final class Bench {
    private Bench(){}
    public static String run(Path path,int count) throws Exception {
        if(count<100 || count>1_000_000)throw new IllegalArgumentException("benchmark count must be 100..1000000");
        if(Files.exists(path))throw new IllegalArgumentException("benchmark path must be empty");
        long start=System.nanoTime();
        String a=J.str(Map.of("stage","s0","namespace","fixture","status","ok","projection","last_success"));
        String b=J.str(Map.of("stage","s1","namespace","fixture","status","ok","projection","last_success"));
        String index;
        try(Store s=new Store(path,"benchmark","{}")) {
            for(int offset=0;offset<count;offset+=128) {
                try(Store.Batch batch=s.begin()) {
                    for(int i=offset;i<Math.min(count,offset+128);i++) {
                        String id=Integer.toString(i+1);
                        var initialAliases=new ArrayList<Object>();
                        for(int n=0;n<3;n++)initialAliases.add(Map.of("kind","alias-"+n,"namespace","fixture","value","https://synthetic.invalid/cases/2026/section/"+i+"/artifact-"+n));
                        batch.add(J.str(Map.of("op","item_upsert","args",List.of(J.str(Map.of("id","case-"+i,"aliases",initialAliases)),J.str(Map.of("content_hash",PlanLog.sha("synthetic-case-"+i),"size","1024"))))));
                        for(int stage=0;stage<5;stage++)batch.add(J.str(Map.of("op","stage_record","args",List.of(id,J.str(Map.of("stage","s"+stage,"namespace","fixture","status","ok","source_revision","1","metadata",Map.of("fixture",true)))))));
                    }
                    batch.commit();
                }
            }
            var options=Map.of("id","bench-pending","require_matching_source_revision",true);
            index=J.text(J.obj(s.call("difference_index",a,b,J.str(options))),"difference","");
            try(Store.Batch batch=s.begin()) {
                for(int i=0;i<Math.max(1,count/100);i++)batch.add(J.str(Map.of("op","stage_forget","args",List.of(Integer.toString(i+1),"s1","fixture",""))));
                batch.commit();
            }
        }
        long buildNs=System.nanoTime()-start,queryNs,subsetNs;int subsetRows=0;int pending=0;String doctor;
        try(Store s=new Store(path,"benchmark","{}")) {
            long t=System.nanoTime();
            try(Store.Cursor c=s.query("stage_difference",a,b,J.str(Map.of("index",index,"require_index",true,"require_matching_source_revision",true)))) {
                while(true){Object page=J.parse(c.nextJson(256,1048576));List<?> rows=(List<?>)page;if(rows.isEmpty())break;pending+=rows.size();}
            }
            queryNs=System.nanoTime()-t;
            long subsetStart=System.nanoTime();
            for(int offset=0;offset<Math.min(count,1000);offset+=256){var ids=new ArrayList<String>();for(int n=offset;n<Math.min(Math.min(count,1000),offset+256);n++)ids.add(Integer.toString((int)((long)n*7919%count)+1));try(Store.Cursor c=s.query("query_subset","benchmark",J.str(ids),"{}")){while(true){List<?> rows=(List<?>)J.parse(c.nextJson(256,1048576));if(rows.isEmpty())break;subsetRows+=rows.size();}}}
            subsetNs=System.nanoTime()-subsetStart;doctor=s.call("doctor","{}");
        }
        if(pending!=Math.max(1,count/100))throw new IllegalStateException("benchmark frontier wrong: "+pending);
        long bytes=Files.size(path);
        Map<String,Object> report=new LinkedHashMap<>();report.put("subset_queries",subsetRows);report.put("subset_query_ms",subsetNs/1e6);report.putAll(Map.of("profile","three explicit aliases plus identity index, five stages, six events/item before pending updates; compact synthetic metadata", "items",count,"disk_bytes",Long.toString(bytes),"bytes_per_item",(double)bytes/count,"build_seconds",buildNs/1e9,"pending",pending,"reopen_query_ms",queryNs/1e6,"doctor",J.parse(doctor),"scope","local measured workload, not 100M qualification"));return J.str(report);
    }
}
