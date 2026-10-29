package com.agenthub.android;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Synthetic actual parser/counter boundaries only. No real process, AM dump or Android proof. */
public final class NaturalDiagnosticCapacityVectors {
    static final NaturalProcessVisibility.Diagnostic PS=NaturalProcessVisibility.Diagnostic.PS,AM=NaturalProcessVisibility.Diagnostic.AM;
    static final String N=NaturalVisibilityVectors.NONCE;
    interface Action {void run()throws Exception;}
    static int passed;
    static void ok(Action a)throws Exception{a.run();passed++;}
    static void refuse(Action a)throws Exception{try{a.run();}catch(Exception expected){passed++;return;}throw new AssertionError("unsafe diagnostic accepted");}
    static void check(boolean v){if(!v)throw new AssertionError("capacity vector");}
    static byte[] frame(NaturalProcessVisibility.Diagnostic k,String body){return NaturalVisibilityVectors.framed(k,N,body,"0");}
    static byte[] amSize(int size){String base=NaturalVisibilityVectors.HEAD+NaturalVisibilityVectors.OWN+"  Other global section:\n";int fixed=frame(AM,base+"\n").length;check(size>=fixed);return frame(AM,base+"x".repeat(size-fixed)+"\n");}
    static byte[] psSize(int size){StringBuilder body=new StringBuilder(NaturalVisibilityVectors.PS);int remaining=size-frame(PS,body.toString()).length,pid=1000;while(remaining>520){String row="0 "+(pid++)+" "+"x".repeat(256)+"\n";body.append(row);remaining-=row.length();}String prefix="0 "+pid+" ";int name=remaining-prefix.length()-1;check(name>0&&name<=512);body.append(prefix).append("x".repeat(name)).append("\n");byte[] out=frame(PS,body.toString());check(out.length==size);return out;}
    static NaturalProcessVisibility.Completed complete(NaturalProcessVisibility.Diagnostic k,byte[] out,byte[] err)throws Exception{return NaturalProcessVisibility.complete(k,N,out,err);}
    static void read(NaturalProcessVisibility.ReadBudget b,boolean error,int bytes){while(bytes>0){int n=Math.min(bytes,4096);b.read(error,n);bytes-=n;}}
    public static void main(String[] args)throws Exception{
        ok(()->check(NaturalProcessVisibility.budget(PS)==262144&&NaturalProcessVisibility.budget(AM)==1048576));
        refuse(()->NaturalProcessVisibility.budget(null));
        ok(()->check(!NaturalProcessVisibility.am(complete(AM,amSize(401177),new byte[0]),10123,321,10124,654).targetPresent));
        ok(()->check(!NaturalProcessVisibility.am(complete(AM,amSize(1048576),new byte[0]),10123,321,10124,654).targetPresent));
        refuse(()->complete(AM,amSize(1048577),new byte[0]));
        refuse(()->complete(PS,amSize(300000),new byte[0]));
        ok(()->check(NaturalProcessVisibility.ps(complete(PS,psSize(262144),new byte[0]),10123,321,10124).targetPids.isEmpty()));
        refuse(()->complete(PS,psSize(262145),new byte[0]));
        ok(()->{byte[] out=frame(PS,NaturalVisibilityVectors.PS);String body=NaturalVisibilityVectors.PS+"10124 654 writer\n";check(!NaturalProcessVisibility.ps(complete(PS,out,new byte[0]),10123,321,10124).targetPids.contains(654));check(NaturalProcessVisibility.ps(complete(PS,frame(PS,body),new byte[0]),10123,321,10124).targetPids.contains(654));});
        ok(()->{String own=NaturalVisibilityVectors.OWN.replace("Bundle[{}]","Bundle[{"+"x".repeat(400000)+"}]");String writer=NaturalVisibilityVectors.record(1,NaturalProcessVisibility.APP+".test","androidx.test.runner.AndroidJUnitRunner",10124,654,NaturalProcessVisibility.APP);var a=NaturalProcessVisibility.am(complete(AM,frame(AM,NaturalVisibilityVectors.HEAD+own+writer),new byte[0]),10123,321,10124,654);check(a.targetPresent&&a.writerPresent);});
        refuse(()->complete(AM,Arrays.copyOf(amSize(500000),499950),new byte[0]));
        refuse(()->complete(AM,frame(AM,NaturalVisibilityVectors.HEAD+NaturalVisibilityVectors.OWN+NaturalProcessVisibility.END+"AM:"+N+":0\n"),new byte[0]));
        refuse(()->complete(AM,NaturalVisibilityVectors.framed(AM,N,NaturalVisibilityVectors.HEAD+NaturalVisibilityVectors.OWN,"7"),new byte[0]));
        refuse(()->complete(AM,NaturalVisibilityVectors.framed(AM,N,NaturalVisibilityVectors.HEAD+NaturalVisibilityVectors.OWN,"00"),new byte[0]));
        refuse(()->{byte[] out=amSize(500000);out[1000]=(byte)0xff;complete(AM,out,new byte[0]);});
        refuse(()->complete(AM,amSize(1048575),new byte[]{1}));
        refuse(()->complete(AM,amSize(1048576),new byte[]{1}));
        refuse(()->complete(AM,amSize(500000),new byte[]{0}));
        refuse(()->complete(AM,amSize(500000),new byte[]{10}));
        refuse(()->{byte[] out=amSize(500000);byte[] extra=Arrays.copyOf(out,out.length+1);extra[extra.length-1]=10;complete(AM,extra,new byte[0]);});
        refuse(()->NaturalProcessVisibility.ps(complete(AM,amSize(500000),new byte[0]),10123,321,10124));
        for(var kind:new NaturalProcessVisibility.Diagnostic[]{PS,AM}){
            ok(()->{var b=new NaturalProcessVisibility.ReadBudget(kind);read(b,false,b.cap);b.eof(false);b.eof(true);var s=b.stats();check(s.stdout==b.cap&&s.stderr==0&&s.stdoutEof&&s.stderrEof);});
            refuse(()->{var b=new NaturalProcessVisibility.ReadBudget(kind);read(b,false,b.cap);b.read(true,1);});
            ok(()->{var b=new NaturalProcessVisibility.ReadBudget(kind);read(b,false,b.cap-1);try{b.read(true,2);throw new AssertionError("cap bypass");}catch(NaturalProcessVisibility.Refused expected){}var s=b.stats();check(s.stdout+s.stderr==b.cap+1&&!s.stdoutEof&&!s.stderrEof);});
            ok(()->{var b=new NaturalProcessVisibility.ReadBudget(kind);var error=new java.util.concurrent.atomic.AtomicReference<Throwable>();Thread a=new Thread(()->{try{read(b,false,b.cap/2);}catch(Throwable x){error.compareAndSet(null,x);}});Thread e=new Thread(()->{try{read(b,true,b.cap/2);}catch(Throwable x){error.compareAndSet(null,x);}});a.start();e.start();a.join();e.join();check(error.get()==null&&b.stats().stdout+b.stats().stderr==b.cap);});
            ok(()->{var b=new NaturalProcessVisibility.ReadBudget(kind);var failures=new java.util.concurrent.atomic.AtomicInteger();var unexpected=new java.util.concurrent.atomic.AtomicReference<Throwable>();Thread a=new Thread(()->{try{read(b,false,b.cap/2+4096);}catch(NaturalProcessVisibility.Refused x){failures.incrementAndGet();}catch(Throwable x){unexpected.set(x);}});Thread e=new Thread(()->{try{read(b,true,b.cap/2+4096);}catch(NaturalProcessVisibility.Refused x){failures.incrementAndGet();}catch(Throwable x){unexpected.set(x);}});a.start();e.start();a.join();e.join();var stats=b.stats();check(unexpected.get()==null&&failures.get()>=1&&stats.stdout+stats.stderr>b.cap&&!stats.stdoutEof&&!stats.stderrEof);});
        }
        refuse(()->new NaturalProcessVisibility.ReadBudget(AM).read(false,4097));
        System.out.println("Diagnostic capacity auxiliary: "+passed+" passed; synthetic JVM only; no Android/native proof");
    }
}
