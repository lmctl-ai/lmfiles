package ai.lmctl.lmfiles;

import io.lmctl.lmcode.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class Main {
  public static final String VERSION="0.1.3";
  private Main(){}

  static long memoryBudget(){
    String value=System.getenv("LMFILES_MAX_MEMORY_MB");
    if(value==null)return Long.MAX_VALUE;
    try{long n=Long.parseLong(value);if(n<=0)throw new NumberFormatException();return Math.multiplyExact(n,1024L*1024L);}
    catch(Exception e){throw new IllegalArgumentException("LMFILES_MAX_MEMORY_MB must be positive whole MiB");}
  }

  public static void main(String[] args){
    System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out),true,java.nio.charset.StandardCharsets.UTF_8));
    System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err),true,java.nio.charset.StandardCharsets.UTF_8));
    int status;
    try{status=run(args);}
    catch(OutOfMemoryError e){status=Reports.failureFrame("run",new VmException("RUN_HOST","host ran out of memory"));}
    catch(VmException e){status=Reports.failureFrame("app",e);}
    catch(IllegalArgumentException e){status=Reports.failureFrame("cli",new VmException("CLI_USAGE",e.getMessage()));}
    catch(Exception e){status=Reports.failureFrame("run",new VmException("RUN_HOST",e.getMessage()==null?"registry operation failed":e.getMessage()));}
    if(status!=0)System.exit(status);
  }

  static int run(String[] args)throws Exception{
    for(String arg:args)if(Set.of("--first","--last","--dry-run","--cli-schema","--dsl","--dsl-schema","--dsl-signatures").contains(arg))
      return Reports.failureFrame("cli",new VmException("CLI_USAGE","retired option is not supported: "+arg));
    if(args.length==0||args[0].equals("--help")){usage();return args.length==0?2:0;}
    memoryBudget();
    if(args.length==1&&args[0].equals("--version")){System.out.println("lmfiles "+VERSION);return 0;}
    if(args.length==1&&args[0].equals("--notices")){try(InputStream in=Main.class.getResourceAsStream("/THIRD_PARTY_NOTICES")){if(in==null)throw new IOException("notices missing");in.transferTo(System.out);}return 0;}
    if(args[0].equals("--lmcode-schema")){
      if(args.length>2||(args.length==2&&!args[1].equals("--json")))return Reports.failureFrame("cli",new VmException("CLI_USAGE","usage: --lmcode-schema [--json]"));
      System.out.write(HostApi.schemaBytes());return 0;
    }
    if(args.length==1&&args[0].equals("--lmcode-signatures")){
      try(DslHost host=new DslHost()){
        String signatures=String.join("\n",host.registry().signatures().stream().sorted().toList())+"\n";
        System.out.write(signatures.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      return 0;
    }
    if(args.length==1&&args[0].equals("--self-test")){System.out.println(J.str(Map.of("ok",true,"assertions",CoreChecks.run(Files.createTempDirectory("lmfiles-selftest-")))));return 0;}
    if(args.length==2&&args[0].equals("--self-test")){System.out.println(J.str(Map.of("ok",true,"assertions",CoreChecks.run(Path.of(args[1])))));return 0;}
    if(args.length==2&&args[0].equals("--journal-test")){System.out.println(J.str(Map.of("ok",true,"assertions",PlanLogChecks.run(Path.of(args[1])))));return 0;}
    if(args.length==2&&args[0].equals("--lease-test")){System.out.println(J.str(Map.of("ok",true,"assertions",LeaseChecks.run(Path.of(args[1])))));return 0;}
    if(args.length==2&&args[0].equals("--review-test")){System.out.println(J.str(Map.of("ok",true,"assertions",ReviewChecks.run(Path.of(args[1])))));return 0;}
    if(args.length==2&&args[0].equals("--scan-test")){System.out.println(ScanChecks.runReport(Path.of(args[1])));return 0;}
    if(args.length==2&&args[0].equals("--storage-test")){System.out.println(J.str(Map.of("ok",true,"assertions",StorageChecks.run(Path.of(args[1])))));return 0;}
    if(args.length==2&&args[0].equals("--make-v1-fixture")){StorageChecks.createLegacy(Path.of(args[1]));System.out.println("{\"status\":\"ok\"}");return 0;}
    if(args.length==4&&args[0].equals("--scale-phase")){ScaleChecks.run(Path.of(args[1]),args[2],Integer.parseInt(args[3]));return 0;}
    if(args.length==3&&args[0].equals("--benchmark")){System.out.println(Bench.run(Path.of(args[1]),Integer.parseInt(args[2])));return 0;}
    if(args.length==3&&args[0].equals("--hold-lock")){try(Store s=new Store(Path.of(args[1]),"lock-test","{}")){System.out.println("ready");System.out.flush();Thread.sleep(Long.parseLong(args[2]));}return 0;}
    if(args.length>=3&&args[0].equals("--request")){try(Store s=new Store(Path.of(args[1]),"cli","{}")){System.out.println(s.call(args[2],Arrays.copyOfRange(args,3,args.length)));}return 0;}
    if(args.length>=3&&args[0].equals("--query")){try(Store s=new Store(Path.of(args[1]),"cli","{}")){try(Store.Cursor c=s.query(args[2],Arrays.copyOfRange(args,3,args.length))){String page;while(!(page=c.nextJson(4096,Integer.MAX_VALUE)).equals("[]"))System.out.println(page);}}return 0;}
    if(args.length==4&&args[0].equals("--apply-records")){try(Store s=new Store(Path.of(args[1]),"cli","{}")){System.out.println(PlanLog.apply(s,Path.of(args[2]),Path.of(args[3])));}return 0;}
    if(args.length==5&&args[0].equals("--unconfirmed")){System.out.println(PlanLog.unconfirmed(Path.of(args[2]),Path.of(args[3]),Path.of(args[4])));return 0;}
    if(args.length==3&&args[0].equals("--log-append")){System.out.println(PlanLog.append(Path.of(args[1]),args[2],"{}"));return 0;}

    List<String> files=new ArrayList<>();
    for(int i=0;i<args.length;){
      if(!args[i].equals("--lmcode")||i+1>=args.length||args[i+1].startsWith("--"))
        return Reports.failureFrame("cli",new VmException("CLI_USAGE","expected --lmcode FILE"));
      files.add(args[i+1]);i+=2;
    }
    InvocationResults results;
    try{results=InvocationResults.fromCommand(files);}
    catch(VmException e){return Reports.failureFrame("cli",e);}

    try(DslHost host=new DslHost()){
      byte[] schema=HostApi.schemaBytes();ResultContract contract=ResultContract.fromSchema(schema);
      for(InvocationResults.Registration registration:results.registrations()){
        int index=registration.index();String phase="load";
        try{
          results.select(index,host.registry(),contract);
          Program program=host.load(Path.of(registration.file()));
          phase="run";host.execute(program,results.dry());
          results.complete(index);
        }catch(VmException e){results.fail(index,phase,e);break;}
        catch(IOException e){results.fail(index,phase,new VmException(phase.equals("load")?"LOAD_BAD_BYTECODE":"RUN_HOST",e.getMessage()==null?"input failed":e.getMessage()));break;}
        catch(OutOfMemoryError e){results.fail(index,"run",new VmException("RUN_HOST","host ran out of memory"));break;}
      }
      return results.writeFrame(System.out);
    }
  }

  private static void usage(){System.out.println("lmfiles "+VERSION+" — local file lifecycle registry\nlmfiles --lmcode FILE [--lmcode FILE ...]\nlmfiles --lmcode-schema [--json] | --lmcode-signatures | --self-test [DIR]\nlmfiles --request DB OP [ARGS...] | --query DB OP [ARGS...]\nLMFILES_MAX_MEMORY_MB: optional explicit VM memory limit; no network operations.");}
}
