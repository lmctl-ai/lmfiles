package ai.lmctl.lmfiles;

import org.h2.mvstore.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Bounded shadow-file migrations; old bytes stay intact until atomic replacement. */
final class StorageFormat {
    static final String CURRENT="3";
    private static final Set<String> BYTES=Set.of("items","events");
    private static final Set<String> STRINGS=Set.of("meta","differences","frontier","claims","active_claims","replay","dictionary","alias_families","claim_fences");
    private StorageFormat(){}
    static MVStore upgrade(MVStore old,Path path)throws IOException {
        if(Main.memoryBudget()<32L*1024*1024)throw new IOException("format upgrade requires LMFILES_MAX_MEMORY_MB >= 32");
        long size=Files.size(path);if(Files.getFileStore(path.getParent()).getUsableSpace()<Math.addExact(Math.multiplyExact(size,2),16L*1024*1024))throw new IOException("insufficient disk for format upgrade and backup");
        Set<String> allowed=new HashSet<>(STRINGS);allowed.addAll(BYTES);allowed.add("aliases");
        for(String name:old.getMapNames())if(!allowed.contains(name))throw new IOException("unknown format-1 map: "+name);
        String source=old.<String,String>openMap("meta").getOrDefault("format","1");
        if(!Set.of("1","2").contains(source))throw new IOException("unsupported-registry-format");
        Path tmp=Files.createTempFile(path.getParent(),path.getFileName()+".upgrade-v3-",".tmp"),backup=backupName(path,source);
        MVStore shadow=null;boolean replaced=false;
        try {
            shadow=new MVStore.Builder().fileName(tmp.toString()).cacheSize(8).compress().autoCommitDisabled().open();
            int count=0;long buffered=0;
            for(String name:STRINGS){MVMap<String,String> src=old.openMap(name),dst=shadow.openMap(name);for(var e:src.entrySet()){dst.put(e.getKey(),e.getValue());buffered+=e.getKey().toString().length()*2L+e.getValue().toString().length()*2L;if(++count%256==0||buffered>=4194304){shadow.commit();buffered=0;}}}
            for(String name:BYTES){MVMap<Long,byte[]> src=old.openMap(name),dst=shadow.openMap(name);for(var e:src.entrySet()){Map<String,Object> row=Store.decode(e.getValue());if(name.equals("items"))elide(row);byte[] encoded=name.equals("events")?Store.encodeEvent(row):Store.encode(row);dst.put(e.getKey(),encoded);buffered+=encoded.length;if(++count%256==0||buffered>=4194304){shadow.commit();buffered=0;}}}
            MVMap<String,String> families=shadow.openMap("alias_families"),meta=shadow.openMap("meta");MVMap<String,Long> src=old.openMap("aliases"),dst=shadow.openMap("aliases");
            for(var e:src.entrySet()){if(source.equals("1")){List<?> a=legacyAlias(e.getKey());dst.put(aliasKey((String)a.get(0),(String)a.get(1),a.get(2),families,meta,true),e.getValue());}else dst.put(e.getKey(),e.getValue());buffered+=e.getKey().length()*2L;if(++count%256==0||buffered>=4194304){shadow.commit();buffered=0;}}
            if(source.equals("2")){for(var e:old.<String,String>openMap("alias_families").entrySet()){families.put(e.getKey(),e.getValue());meta.put("alias_family:"+e.getValue(),e.getKey());}for(var e:old.<String,String>openMap("meta").entrySet())if(e.getKey().startsWith("alias_family:")||e.getKey().equals("next_alias_family"))meta.put(e.getKey(),e.getValue());}
            meta.put("format",CURRENT);meta.put("upgrade_backup",backup.toString());shadow.commit();shadow.sync();shadow.close();shadow=null;
            old.sync();old.close();
            Files.copy(path,backup,StandardCopyOption.COPY_ATTRIBUTES);forceFile(backup);forceDirectory(path.getParent());
            kill("before_upgrade_swap");
            Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);replaced=true;forceDirectory(path.getParent());
            kill("after_upgrade_swap");
            return new MVStore.Builder().fileName(path.toString()).cacheSize(8).compress().autoCommitDisabled().open();
        } finally {if(shadow!=null)shadow.closeImmediately();if(!replaced)Files.deleteIfExists(tmp);}
    }
    static void elide(Map<String,Object> row){Object s=row.get("stages");if(s instanceof Map<?,?> stages)for(Object v:stages.values()){Map<String,Object> latest=io.lmctl.lmcode.Json.object(v);Object success=latest.get("last_success");if(success!=null){var own=new LinkedHashMap<>(latest);own.remove("last_success");if("ok".equals(own.get("status"))&&J.str(own).equals(J.str(success)))latest.remove("last_success");}}}
    static List<?> legacyAlias(String key){Object parsed=J.parse(key);if(!(parsed instanceof List<?> l)||l.size()!=3||!(l.get(0) instanceof String)||!(l.get(1) instanceof String)||l.get(2)==null)throw new IllegalArgumentException("invalid format-1 alias");return l;}
    /** identity is allowed here only for internal primary keys; Store validates user aliases. */
    static String aliasKey(String kind,String ns,Object value,MVMap<String,String> families,MVMap<String,String> meta,boolean create){
        String pair=J.str(List.of(kind,ns)),family=families.get(pair);
        if(family==null&&create){family=Long.toString(Math.addExact(Long.parseLong(meta.getOrDefault("next_alias_family","0")),1));families.put(pair,family);meta.put("next_alias_family",family);meta.put("alias_family:"+family,pair);}
        return family==null?null:family+"\0"+J.str(value);
    }
    private static Path backupName(Path path,String source){Path backup=path.resolveSibling(path.getFileName()+".v"+source+".bak");while(Files.exists(backup))backup=path.resolveSibling(path.getFileName()+".v"+source+"-"+UUID.randomUUID()+".bak");return backup;}
    private static void forceFile(Path path)throws IOException{try(FileChannel c=FileChannel.open(path,StandardOpenOption.WRITE)){c.force(true);}}
    private static void forceDirectory(Path path)throws IOException{try(FileChannel c=FileChannel.open(path,StandardOpenOption.READ)){c.force(true);}catch(IOException|UnsupportedOperationException e){if(!System.getProperty("os.name").startsWith("Windows"))throw new IOException("directory sync required for upgrade",e);}}
    private static void kill(String point){if(point.equals(System.getenv("LMFILES_KILL_POINT")))Runtime.getRuntime().halt(77);}
}
