package ai.lmctl.lmfiles;

import io.lmctl.lmcode.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class Main {
  public static final String VERSION="0.1.0";
  private Main(){}
  public static long memoryBudget(){String v=System.getenv("LMFILES_MAX_MEMORY_MB");if(v==null)return 256L*1024*1024;try{long n=Long.parseLong(v);if(n<=0)throw new NumberFormatException();return Math.multiplyExact(n,1024L*1024);}catch(Exception e){throw new IllegalArgumentException("LMFILES_MAX_MEMORY_MB must be positive whole MiB");}}
  public static void main(String[] args){int code;try{code=run(args);}catch(OutOfMemoryError e){System.err.println("lmfiles: LMFILES_MAX_MEMORY_MB memory budget exceeded");code=3;}catch(VmException e){System.err.println("lmfiles: "+e.getMessage());code=3;}catch(IllegalArgumentException e){System.err.println("lmfiles: "+e.getMessage());code=2;}catch(Exception e){String m=e.getMessage();System.err.println("lmfiles: "+(m==null?"registry operation failed ("+e.getClass().getSimpleName()+")":m));code=3;}if(code!=0)System.exit(code);}
  static int run(String[] args)throws Exception{
    memoryBudget();
    if(args.length==0||args[0].equals("--help")){usage();return args.length==0?2:0;}
    switch(args[0]){
      case "--version"-> {System.out.println("lmfiles "+VERSION);return 0;}
      case "--dsl-schema"-> {System.out.print(HostApi.schema());return 0;}
      case "--dsl-signatures"-> {try(DslHost h=new DslHost()){System.out.println(String.join("\n",h.registry().signatures()));}return 0;}
      case "--notices"-> {try(var in=Main.class.getResourceAsStream("/THIRD_PARTY_NOTICES")){if(in==null)throw new IllegalStateException("missing embedded THIRD_PARTY_NOTICES");in.transferTo(System.out);}return 0;}
      case "--self-test"-> {Path p=args.length>1?Path.of(args[1]):Files.createTempDirectory("lmfiles-selftest-");System.out.println(J.str(Map.of("ok",true,"assertions",CoreChecks.run(p))));return 0;}
      case "--dsl"->{Path b=null;boolean dry=false;for(int i=1;i<args.length;i++){if(args[i].equals("--dry-run"))dry=true;else if(b==null)b=Path.of(args[i]);else throw new IllegalArgumentException("usage: --dsl BYTECODE [--dry-run]");}if(b==null)throw new IllegalArgumentException("--dsl requires bytecode");try(DslHost h=new DslHost()){return h.run(b,dry);}}
      case "--request"->{if(args.length<3)throw new IllegalArgumentException("--request DB OP [ARGS]");try(Store s=new Store(Path.of(args[1]),"cli","{}")){String[] a=Arrays.copyOfRange(args,3,args.length);System.out.println(s.call(args[2],a));}return 0;}
      case "--query"->{if(args.length<3)throw new IllegalArgumentException("--query DB OP [ARGS]");try(Store s=new Store(Path.of(args[1]),"cli","{}")){String[] a=Arrays.copyOfRange(args,3,args.length);try(Store.Cursor c=s.query(args[2],a)){String page;while(!(page=c.nextJson(4096,16*1024*1024)).equals("[]")){System.out.println(page);}}}return 0;}
      case "--apply-records"->{if(args.length!=4)throw new IllegalArgumentException("--apply-records DB PLAN RESULTS");try(Store s=new Store(Path.of(args[1]),"cli","{}")){System.out.println(PlanLog.apply(s,Path.of(args[2]),Path.of(args[3])));}return 0;}
      case "--unconfirmed"->{if(args.length!=5)throw new IllegalArgumentException("--unconfirmed DB PLAN RESULTS OUTPUT");System.out.println(PlanLog.unconfirmed(Path.of(args[2]),Path.of(args[3]),Path.of(args[4])));return 0;}
      case "--log-append"->{if(args.length!=3)throw new IllegalArgumentException("--log-append FILE LINE_JSON");System.out.println(PlanLog.append(Path.of(args[1]),args[2],"{}"));return 0;}
      case "--journal-test"->{if(args.length!=2)throw new IllegalArgumentException("--journal-test DIR");System.out.println(J.str(Map.of("ok",true,"assertions",PlanLogChecks.run(Path.of(args[1])))));return 0;}
      case "--benchmark"->{if(args.length!=3)throw new IllegalArgumentException("--benchmark DB COUNT");System.out.println(Bench.run(Path.of(args[1]),Integer.parseInt(args[2])));return 0;}
      case "--hold-lock"->{if(args.length!=3)throw new IllegalArgumentException("--hold-lock DB MILLISECONDS");try(Store s=new Store(Path.of(args[1]),"lock-test","{}")){System.out.println("ready");System.out.flush();Thread.sleep(Long.parseLong(args[2]));}return 0;}
      default->throw new IllegalArgumentException("unknown option; run lmfiles --help");
    }
  }
  private static void usage(){System.out.println("lmfiles "+VERSION+" — local file lifecycle registry\nlmfiles --dsl BYTECODE [--dry-run]\nlmfiles --dsl-schema | --dsl-signatures | --self-test [DIR]\nlmfiles --request DB OP [ARGS...] | --query DB OP [ARGS...]\nLMFILES_MAX_MEMORY_MB: positive whole MiB, default 256; no network operations.");}
}
