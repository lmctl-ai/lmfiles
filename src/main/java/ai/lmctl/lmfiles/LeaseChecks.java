package ai.lmctl.lmfiles;

import java.nio.file.*;
import java.util.*;

/** Deterministic cross-owner expiry tests; no sleeps or production clock override. */
public final class LeaseChecks {
    private LeaseChecks(){}
    static final class Clock implements LeaseClock {
        long wall=1000000,nano=1000000000;
        public long wallMillis(){return wall;}public long nanos(){return nano;}
        void advance(long ms){wall+=ms;nano+=ms*1000000;}
    }
    private static int checks;
    private static void ok(boolean b,String why){if(!b)throw new AssertionError(why);checks++;}
    private static String status(String json){return J.text(J.obj(json),"status","");}
    private static String record(){return J.str(Map.of("stage","cleaned","namespace","fixture","status","ok","source_revision","1"));}
    static void seed(Path db,LeaseClock clock){try(Store s=new Store(db,"leases","{}",clock)){s.call("item_upsert","{\"id\":\"fixture\"}","{}");s.call("stage_record","1","{\"stage\":\"fetched\",\"namespace\":\"fixture\",\"status\":\"ok\",\"source_revision\":\"1\"}");s.call("difference_index","{\"stage\":\"fetched\",\"namespace\":\"fixture\",\"status\":\"ok\"}","{\"stage\":\"cleaned\",\"namespace\":\"fixture\",\"status\":\"ok\"}","{\"id\":\"pending\"}");}}
    private static Map<String,Object> claim(Store s,String owner){List<?> values=(List<?>)J.parse(s.call("claim","{\"difference\":\"pending\"}","workers","1","1000",owner));return values.isEmpty()?Map.of():io.lmctl.lmcode.Json.object(values.get(0));}
    private static String token(Map<String,Object> grant){return J.text(grant,"token","");}
    public static int run(Path root)throws Exception {
        checks=0;Files.createDirectories(root);Clock c=new Clock();Path db=root.resolve("owners.mv");seed(db,c);
        Map<String,Object> original;
        try(Store s=new Store(db,"leases","{}",c)){original=claim(s,"a");ok(!token(original).isEmpty(),"initial grant");ok(original.containsKey("deadline_wall_ms"),"wall deadline persisted in receipt");}
        long ownerGeneration=Long.parseLong(J.text(original,"owner_generation",""));
        c.advance(200);
        try(Store s=new Store(db,"leases","{}",c)){ok(claim(s,"b").isEmpty(),"unexpired grant survives reopen");}
        c.advance(200);
        try(Store s=new Store(db,"leases","{}",c)){ok(claim(s,"b").isEmpty(),"reopen does not revoke a live owner");}
        c.advance(601);
        Map<String,Object> replacement;
        try(Store s=new Store(db,"leases","{}",c)){
            ok(status(s.call("renew",token(original),"1000")).equals("fenced-or-expired"),"reopens do not restart expiry");
            replacement=claim(s,"b");ok(!replacement.isEmpty(),"expired claim reclaimed");
            ok(Long.parseLong(J.text(replacement,"owner_generation",""))>ownerGeneration,"persisted grant generation increases");
        }
        try(Store s=new Store(db,"leases","{}",c)){
            ok(status(s.call("complete",token(original),record(),"1")).equals("fenced-or-expired"),"stale holder cannot complete replacement");
            ok(status(s.call("complete",token(replacement),record(),"1")).equals("ok"),"valid holder can complete across owners");
            ok(status(s.call("complete",token(replacement),record(),"1")).equals("fenced-or-expired"),"completion is single-use");
        }
        // Renewal has a new absolute deadline, not a process-relative timer.
        c=new Clock();Path renewed=root.resolve("renewed.mv");seed(renewed,c);Map<String,Object> g;
        try(Store s=new Store(renewed,"leases","{}",c)){g=claim(s,"a");}
        c.advance(400);
        try(Store s=new Store(renewed,"leases","{}",c)){ok(status(s.call("renew",token(g),"1000")).equals("ok"),"renew before deadline");}
        c.advance(800);
        try(Store s=new Store(renewed,"leases","{}",c)){ok(claim(s,"b").isEmpty(),"renewed absolute deadline retained");}
        c.advance(201);
        try(Store s=new Store(renewed,"leases","{}",c)){ok(!claim(s,"b").isEmpty(),"renewed grant eventually expires after reopen");}
        // Backward wall steps revoke old clock eras, rather than extending old grants.
        c=new Clock();Path backward=root.resolve("backward.mv");seed(backward,c);
        try(Store s=new Store(backward,"leases","{}",c)){g=claim(s,"a");}
        c.wall-=5000;c.nano+=1000000;
        try(Store s=new Store(backward,"leases","{}",c)){
            ok(status(s.call("complete",token(g),record(),"1")).equals("fenced-or-expired"),"backward wall clock fences old era");
            replacement=claim(s,"b");ok(!replacement.isEmpty(),"fresh era can grant work");
        }
        c.advance(1001);
        try(Store s=new Store(backward,"leases","{}",c)){ok(status(s.call("complete",token(replacement),record(),"1")).equals("fenced-or-expired"),"new era has its own bounded deadline");}
        // A forward jump is early expiry; reversing it cannot resurrect a token.
        c=new Clock();Path forward=root.resolve("forward.mv");seed(forward,c);
        try(Store s=new Store(forward,"leases","{}",c)){g=claim(s,"a");c.wall+=5000;ok(status(s.call("complete",token(g),record(),"1")).equals("fenced-or-expired"),"forward wall jump expires");}
        c.wall-=5000;
        try(Store s=new Store(forward,"leases","{}",c)){ok(status(s.call("renew",token(g),"1000")).equals("fenced-or-expired"),"wall reversal cannot resurrect retired token");}
        // Monotonic progress enforces a timeout even while wall time stalls in a live owner.
        c=new Clock();Path mono=root.resolve("monotonic.mv");seed(mono,c);
        try(Store s=new Store(mono,"leases","{}",c)){g=claim(s,"a");c.nano+=1001000000;ok(status(s.call("complete",token(g),record(),"1")).equals("fenced-or-expired"),"monotonic fallback with frozen wall time");}
        // Failed completion must not consume the owner's still-live claim.
        c=new Clock();Path conflict=root.resolve("conflict.mv");seed(conflict,c);
        try(Store s=new Store(conflict,"leases","{}",c)){g=claim(s,"a");ok(status(s.call("complete",token(g),record(),"2")).equals("conflict"),"revision conflict is not expiry");ok(status(s.call("complete",token(g),record(),"1")).equals("ok"),"owner retains claim after revision conflict");}
        // Released claims have until/lease_ms but no persisted era/deadline metadata.
        for(boolean skew:new boolean[]{false,true}){
            c=new Clock();Path legacy=root.resolve(skew?"legacy-skew.mv":"legacy-expiry.mv");seed(legacy,c);
            try(Store s=new Store(legacy,"leases","{}",c)){g=claim(s,"old");}
            try(var raw=org.h2.mvstore.MVStore.open(legacy.toString())){
                org.h2.mvstore.MVMap<String,String> claims=raw.openMap("claims");var old=J.obj(claims.get(token(g)));
                for(String field:List.of("deadline_ms","deadline_wall_ms","owner_generation","clock_owner_generation","clock_era"))old.remove(field);
                old.put("owner_epoch","released-owner");old.put("deadline_ns","1234567890");claims.put(token(g),J.str(old));
                org.h2.mvstore.MVMap<String,String> meta=raw.openMap("meta");for(String key:new ArrayList<>(meta.keySet()))if(key.startsWith("lease_")||key.equals("clock_owner_generation"))meta.remove(key);
                raw.<String,String>openMap("claim_fences").clear();raw.commit();raw.sync();
            }
            if(skew)c.wall-=500;else c.advance(1001);
            try(Store s=new Store(legacy,"leases","{}",c)){ok(status(s.call("complete",token(g),record(),"1")).equals("fenced-or-expired"),skew?"legacy grant issued in future is fenced":"legacy absolute until survives reopen without timer reset");}
        }
        return checks;
    }
}
