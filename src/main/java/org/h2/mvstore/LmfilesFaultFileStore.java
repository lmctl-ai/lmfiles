package org.h2.mvstore;

import org.h2.mvstore.SingleFileStore;
import org.h2.mvstore.SFChunk;
import java.nio.ByteBuffer;
import java.util.Map;

/** Deterministic local test faults at actual H2 write boundaries; never enabled by default. */
public final class LmfilesFaultFileStore extends SingleFileStore {
    public boolean armed;
    public final String point;
    public LmfilesFaultFileStore(String point){super(Map.of());this.point=point;}
    @Override protected void writeFully(SFChunk chunk,long position,ByteBuffer data){
        if(armed){
            armed=false;
            if(point.equals("disk_full"))throw new IllegalStateException("injected-disk-full");
            ByteBuffer prefix=data.duplicate();prefix.limit(prefix.position()+Math.max(1,prefix.remaining()/2));
            super.writeFully(chunk,position,prefix);super.sync();
            if(point.equals("scale_sigkill")){
                System.out.println("{\"event\":\"kill_ready\",\"point\":\"half_written_commit\"}");System.out.flush();
                try{Thread.sleep(60000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                Runtime.getRuntime().halt(78);
            }
            Runtime.getRuntime().halt(77);
        }
        super.writeFully(chunk,position,data);
    }
}
