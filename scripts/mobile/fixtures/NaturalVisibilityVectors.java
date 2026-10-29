package com.agenthub.android;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Executes the exact observer parsers on synthetic JVM vectors; never runs a diagnostic or device. */
public final class NaturalVisibilityVectors {
    interface Action {void run()throws Exception;}
    static int passed;
    static void check(boolean value){if(!value)throw new AssertionError("visibility vector");}
    static void ok(Action a)throws Exception{a.run();passed++;}
    static void reject(Action a)throws Exception{try{a.run();}catch(Exception expected){passed++;return;}throw new AssertionError("unsafe visibility accepted");}
    static final String APP=NaturalProcessVisibility.APP,SELF=NaturalProcessVisibility.SELF,OBSERVER=NaturalProcessVisibility.OBSERVER;
    static final String PS="UID PID NAME\n0 1 init\n1000 99 system_server\n10123 321 observer\n";
    static final String HEAD="ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n\n  Active instrumentation:\n";
    static String record(int index,String pkg,String clazz,int uid,int pid,String process){
        return "    Instrumentation #"+index+": ActiveInstrumentation{a {"+pkg+"/"+clazz+"} 1 procs}\n"
            +"      mClass=ComponentInfo{"+pkg+"/"+clazz+"} mFinished=false\n      mRunningProcesses:\n"
            +"        #0: ProcessRecord{b "+pid+":"+process+"/u0a"+(uid-10000)+"}\n"
            +"      mTargetProcesses=["+process+"]\n      mTargetInfo=ApplicationInfo{c "+process+"}\n"
            +"        packageName="+process+"\n        uid="+uid+" flags=0x0 privateFlags=0x0 theme=0x0\n"
            +"mHasBackgroundActivityStartsPermission=false\n      mArguments=Bundle[{}]\n";
    }
    static final String OWN=record(0,SELF,OBSERVER,10123,321,SELF);
    static final String NONCE="11111111-2222-4333-8444-555555555555",OTHER="99999999-2222-4333-8444-555555555555";
    static byte[] framed(NaturalProcessVisibility.Diagnostic kind,String nonce,String body,String status){return (body+"\n"+NaturalProcessVisibility.END+kind.name()+":"+nonce+":"+status+"\n").getBytes(StandardCharsets.UTF_8);}
    static NaturalProcessVisibility.Completed capture(NaturalProcessVisibility.Diagnostic kind,String raw)throws Exception{return NaturalProcessVisibility.complete(kind,NONCE,framed(kind,NONCE,raw,"0"),new byte[0]);}
    static NaturalProcessVisibility.Ps ps(String raw)throws Exception{return NaturalProcessVisibility.ps(capture(NaturalProcessVisibility.Diagnostic.PS,raw),10123,321,10124);}
    static NaturalProcessVisibility.Am am(String raw)throws Exception{return NaturalProcessVisibility.am(capture(NaturalProcessVisibility.Diagnostic.AM,raw),10123,321,10124,654);}
    static NaturalProcessVisibility.Completed shellModel(NaturalProcessVisibility.Diagnostic kind,String body,int status,boolean error)throws Exception{
        // Host POSIX shell only. Fixed fake functions prevent executing either real diagnostic.
        String function=kind==NaturalProcessVisibility.Diagnostic.PS?"ps":"dumpsys";
        String fake=function+"() { printf '%s' '"+body+"'; "+(error?"printf 'synthetic diagnostic error\\n' >&2; ":"")+"return "+status+"; }\n";
        java.lang.Process p=new ProcessBuilder("/bin/sh").start();
        try(java.io.OutputStream in=p.getOutputStream()){in.write(fake.getBytes(StandardCharsets.UTF_8));in.write(NaturalProcessVisibility.script(kind,NONCE));}
        byte[] out=p.getInputStream().readAllBytes(),err=p.getErrorStream().readAllBytes();int exit=p.waitFor();check(exit==status);
        return NaturalProcessVisibility.complete(kind,NONCE,out,err);
    }
    public static void main(String[] args)throws Exception {
        ok(()->check(ps(PS).targetPids.isEmpty()));
        ok(()->{var p=ps(PS+"10124 655 child\n10124 654 writer\n");p.requireWriter(10124,654);check(p.targetPids.equals(Arrays.asList(654,655))&&p.systemServerPid==99);});
        reject(()->ps("UID PID NAME\n"));
        reject(()->ps(PS.replace("10123 321 observer\n","")));
        reject(()->ps(PS.replace("10123 321","10125 321")));
        reject(()->ps(PS.replace("1000 99 system_server\n","")));
        reject(()->ps(PS.replace("1000 99 system_server","10123 99 system_server")));
        reject(()->ps(PS.replace("system_server","fake_server")));
        reject(()->ps(PS+"10124 321 duplicate\n"));
        reject(()->ps(PS+"10124 unknown bad\n"));
        reject(()->ps(PS+"10124 9999999999 overflow\n"));
        reject(()->ps(PS+"10124 654"));
        reject(()->ps(PS.replace("UID PID NAME","USER PID NAME")));
        reject(()->ps(PS.replace("\n","\r\n")));
        reject(()->ps(PS).requireWriter(10124,654));
        reject(()->ps(PS+"10125 654 foreign\n").requireWriter(10124,654));
        ok(()->check(capture(NaturalProcessVisibility.Diagnostic.PS,PS).body.equals(PS)));
        reject(()->NaturalProcessVisibility.complete(NaturalProcessVisibility.Diagnostic.PS,NONCE,new byte[]{(byte)0xc3,10},new byte[0]));
        reject(()->capture(NaturalProcessVisibility.Diagnostic.PS,"unterminated"));
        reject(()->NaturalProcessVisibility.complete(NaturalProcessVisibility.Diagnostic.PS,NONCE,new byte[NaturalProcessVisibility.BUDGET+1],new byte[0]));
        ok(()->check(!am(HEAD+OWN).targetPresent));
        String writer=record(1,APP+".test","androidx.test.runner.AndroidJUnitRunner",10124,654,APP);
        ok(()->{var a=am(HEAD+OWN+writer);check(a.targetPresent&&a.writerPresent);});
        reject(()->am(HEAD));
        reject(()->am(HEAD+record(0,APP+".test","androidx.test.runner.AndroidJUnitRunner",10124,654,APP)));
        reject(()->am(HEAD+OWN.replace(OBSERVER,"com.agenthub.android.UnknownObserver")));
        reject(()->am(HEAD+OWN.replace("uid=10123 ","uid=10125 ")));
        reject(()->am(HEAD+OWN.replace("321:","322:")));
        reject(()->am(HEAD+OWN.replace("} 1 procs","} FINISHED 1 procs").replace("mFinished=false","mFinished=true")));
        reject(()->am(HEAD+OWN.replace("ApplicationInfo{c "+SELF+"}","ApplicationInfo{c "+APP+"}")));
        reject(()->am(HEAD+OWN.replace("        #0: ProcessRecord{b 321:"+SELF+"/u0a123}\n","")));
        reject(()->am(HEAD+OWN.replace("      mArguments=Bundle[{}]\n","")));
        reject(()->am(HEAD+OWN.replace("Instrumentation #0:","Instrumentation #1:")));
        reject(()->am(HEAD+OWN+record(1,SELF,OBSERVER,10123,321,SELF)));
        reject(()->am(HEAD+OWN+writer.substring(0,writer.length()-10)));
        reject(()->am(HEAD+OWN+"unknown tail\n"));
        reject(()->am(HEAD+OWN.replace("mTargetProcesses=["+SELF+"]","mTargetProcesses=["+APP+"]")));
        reject(()->am(HEAD+OWN.replace("      mTargetProcesses=","        #1: malformed hidden row\n      mTargetProcesses=")));
        reject(()->am(HEAD+OWN+"      mArguments=duplicate\n"));
        reject(()->am(HEAD+OWN+"\n  UID states:\n*** SERVICE activity DUMP TIMEOUT EXPIRED ***\n"));
        reject(()->am(HEAD+OWN.replace("/u0a123}","/u0a124}")));
        ok(()->{var a=am(HEAD+OWN+"\n  UID states:\n    unrelated package "+APP+"\n");check(!a.targetPresent);});
        ok(()->{var a=am(HEAD+OWN+writer.replace("654:","655:"));check(a.targetPresent&&!a.writerPresent);});
        ok(()->check(NaturalProcessVisibility.Diagnostic.values().length==2&&NaturalProcessVisibility.Diagnostic.PS.command.equals("ps -A -n -o UID,PID,NAME")&&NaturalProcessVisibility.Diagnostic.AM.command.equals("dumpsys activity processes")));
        var kind=NaturalProcessVisibility.Diagnostic.PS;byte[] fullPs=framed(kind,NONCE,PS+"10124 654 writer\n","0");
        // A complete positive self/system prefix is still incomplete without this invocation's terminal witness.
        reject(()->NaturalProcessVisibility.complete(kind,NONCE,Arrays.copyOf(fullPs,PS.getBytes(StandardCharsets.UTF_8).length),new byte[0]));
        byte[] fullAm=framed(NaturalProcessVisibility.Diagnostic.AM,NONCE,HEAD+OWN+writer,"0");
        reject(()->NaturalProcessVisibility.complete(NaturalProcessVisibility.Diagnostic.AM,NONCE,Arrays.copyOf(fullAm,(HEAD+OWN).getBytes(StandardCharsets.UTF_8).length),new byte[0]));
        reject(()->NaturalProcessVisibility.complete(kind,NONCE,PS.getBytes(StandardCharsets.UTF_8),new byte[0]));
        reject(()->NaturalProcessVisibility.complete(kind,NONCE,framed(kind,OTHER,PS,"0"),new byte[0]));
        reject(()->NaturalProcessVisibility.complete(kind,NONCE,framed(NaturalProcessVisibility.Diagnostic.AM,NONCE,PS,"0"),new byte[0]));
        reject(()->NaturalProcessVisibility.complete(kind,NONCE,framed(kind,NONCE,PS,"1"),new byte[0]));
        reject(()->NaturalProcessVisibility.complete(kind,NONCE,framed(kind,NONCE,PS,"127"),new byte[0]));
        reject(()->NaturalProcessVisibility.complete(kind,NONCE,framed(kind,NONCE,PS,"00"),new byte[0]));
        reject(()->NaturalProcessVisibility.complete(kind,NONCE,(new String(fullPs,StandardCharsets.UTF_8)+"trailing\n").getBytes(StandardCharsets.UTF_8),new byte[0]));
        reject(()->NaturalProcessVisibility.complete(kind,NONCE,framed(kind,NONCE,new String(fullPs,StandardCharsets.UTF_8),"0"),new byte[0]));
        reject(()->NaturalProcessVisibility.complete(kind,NONCE,Arrays.copyOf(fullPs,fullPs.length-1),new byte[0]));
        reject(()->NaturalProcessVisibility.complete(kind,NONCE,fullPs,"Permission denied\n".getBytes(StandardCharsets.UTF_8)));
        reject(()->NaturalProcessVisibility.complete(kind,NONCE,new byte[NaturalProcessVisibility.BUDGET],new byte[]{1}));
        reject(()->NaturalProcessVisibility.script(kind,"bad'; arbitrary command"));
        ok(()->{String script=new String(NaturalProcessVisibility.script(kind,NONCE),StandardCharsets.UTF_8);check(script.startsWith(kind.command+"\n")&&script.contains(NaturalProcessVisibility.END+"PS:"+NONCE+":%s"));});
        ok(()->check(am(HEAD+OWN).sectionBoundary.equals("verified-command-end")));
        ok(()->check(am(HEAD+OWN+"\n  UID states:\n    metadata\n").sectionBoundary.equals("next-global-section")));
        reject(()->NaturalProcessVisibility.ps(capture(NaturalProcessVisibility.Diagnostic.AM,PS),10123,321,10124));
        reject(()->NaturalProcessVisibility.am(capture(kind,HEAD+OWN),10123,321,10124,654));
        ok(()->check(NaturalProcessVisibility.ps(shellModel(kind,PS,0,false),10123,321,10124).targetPids.isEmpty()));
        ok(()->check(NaturalProcessVisibility.am(shellModel(NaturalProcessVisibility.Diagnostic.AM,HEAD+OWN+writer,0,false),10123,321,10124,654).writerPresent));
        reject(()->shellModel(kind,PS,7,false));
        reject(()->shellModel(kind,PS,0,true));
        System.out.println("Natural visibility parser auxiliary: "+passed+" passed; synthetic JVM only; no Android/diagnostic/native proof");
    }
}
