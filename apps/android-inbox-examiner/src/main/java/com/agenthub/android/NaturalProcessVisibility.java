package com.agenthub.android;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.util.*;
import java.util.regex.*;

/** Fixed acceptance diagnostics and parsers. No Android, process execution or configurable commands. */
final class NaturalProcessVisibility {
    static final int PS_BUDGET=262144, AM_BUDGET=1048576;
    // Historical PS alias for older auxiliary vectors; production captures select by closed kind.
    static final int BUDGET=PS_BUDGET;
    static int budget(Diagnostic kind){require(kind!=null,"fixed diagnostic kind");switch(kind){case PS:return PS_BUDGET;case AM:return AM_BUDGET;default:throw new Refused("unbudgeted diagnostic kind");}}
    static final class ReadStats {
        final int stdout,stderr,cap;final boolean stdoutEof,stderrEof;
        private ReadStats(int stdout,int stderr,int cap,boolean stdoutEof,boolean stderrEof){this.stdout=stdout;this.stderr=stderr;this.cap=cap;this.stdoutEof=stdoutEof;this.stderrEof=stderrEof;}
    }
    static final class ReadBudget {
        final int cap;private int stdout,stderr;private boolean stdoutEof,stderrEof;
        ReadBudget(Diagnostic kind){cap=budget(kind);}
        synchronized void read(boolean error,int n){require(n>0&&n<=4096,"bounded diagnostic read chunk");if(error)stderr+=n;else stdout+=n;require((long)stdout+stderr<=cap,"combined diagnostic budget");}
        synchronized void eof(boolean error){if(error)stderrEof=true;else stdoutEof=true;}
        synchronized ReadStats stats(){return new ReadStats(stdout,stderr,cap,stdoutEof,stderrEof);}
    }
    static final String END="P9_DIAGNOSTIC_END_V1:";
    static final String APP="com.easyai.agenthub.android.acceptance", SELF=APP+".coldexaminer";
    static final String OBSERVER="com.agenthub.android.OwnedNaturalNoticeObserver";
    enum Diagnostic {
        PS("ps -A -n -o UID,PID,NAME"), AM("dumpsys activity processes");
        final String command;
        Diagnostic(String command){this.command=command;}
    }
    static final class Completed {
        final Diagnostic kind;final String nonce,body;
        Completed(Diagnostic kind,String nonce,String body){this.kind=kind;this.nonce=nonce;this.body=body;}
    }
    static String nonce(String value){require(value!=null && UUID.fromString(value).toString().equals(value),"canonical diagnostic nonce");return value;}
    static byte[] script(Diagnostic kind,String value){
        nonce(value);require(kind!=null,"fixed diagnostic kind");
        // Exactly one enum command. Nonce is canonical UUID data, never a command/parameter selector.
        return (kind.command+"\np9_diag_status=$?\nprintf '\\n"+END+kind.name()+":"+value+":%s\\n' \"$p9_diag_status\"\nexit \"$p9_diag_status\"\n").getBytes(StandardCharsets.UTF_8);
    }
    static final class Refused extends IllegalArgumentException {Refused(String code){super(code);}}
    static void require(boolean v,String code){if(!v)throw new Refused(code);}
    static String utf8(byte[] value)throws Exception {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString();
    }
    static Completed complete(Diagnostic kind,String value,byte[] stdout,byte[] stderr)throws Exception {
        nonce(value);require(kind!=null && (long)stdout.length+stderr.length<=budget(kind),"diagnostic combined budget");
        require(stderr.length==0,"diagnostic stderr");String text=utf8(stdout),tail="\n"+END+kind.name()+":"+value+":0\n";
        int position=text.length()-tail.length()+1;
        require(text.endsWith(tail) && text.indexOf(END)==position && text.lastIndexOf(END)==position,"diagnostic missing/duplicate/wrong completion or nonzero exit");
        String body=text.substring(0,text.length()-tail.length());
        require(!body.isEmpty() && body.endsWith("\n") && body.indexOf('\r')<0 && body.indexOf('\0')<0,"diagnostic complete lines");return new Completed(kind,value,body);
    }
    private static int number(String value,boolean positive){
        require(value.matches(positive?"[1-9][0-9]{0,9}":"0|[1-9][0-9]{0,9}"),"diagnostic canonical number");
        long n=Long.parseLong(value);require(n<=Integer.MAX_VALUE,"diagnostic numeric bound");return (int)n;
    }
    static final class Ps {
        final List<Integer> targetPids;final int systemServerPid;private final Map<Integer,Integer> pidUids;
        Ps(List<Integer> targetPids,int systemServerPid,Map<Integer,Integer> pidUids){this.targetPids=Collections.unmodifiableList(targetPids);this.systemServerPid=systemServerPid;this.pidUids=pidUids;}
        void requireWriter(int uid,int pid){require(pidUids.get(pid)!=null && pidUids.get(pid)==uid,"original writer PID/UID not visible at custody");}
    }
    static Ps ps(Completed capture,int selfUid,int selfPid,int targetUid){
        require(capture!=null && capture.kind==Diagnostic.PS,"verified PS completion required");String raw=capture.body;
        require(raw.getBytes(StandardCharsets.UTF_8).length<=PS_BUDGET && raw.endsWith("\n") && raw.indexOf('\r')<0 && raw.indexOf('\0')<0,"ps bound/termination");
        String[] lines=raw.split("\n",-1);require(lines.length>=4 && lines[0].trim().matches("UID\\s+PID\\s+NAME"),"exact ps columns");
        require(selfUid!=targetUid && selfPid>0,"independent observer identity");
        Map<Integer,Integer> pids=new HashMap<>();List<Integer> targets=new ArrayList<>();int system=0;
        for(int i=1;i<lines.length-1;i++){
            String[] cells=lines[i].trim().split("\\s+",3);require(cells.length==3 && !cells[2].isEmpty() && cells[2].length()<=512 && cells[2].chars().noneMatch(c->c<32||c==127),"ps row schema");
            int uid=number(cells[0],false),pid=number(cells[1],true);require(pids.put(pid,uid)==null,"duplicate ps PID");
            if(uid==targetUid)targets.add(pid);
            if(uid==1000 && uid!=selfUid && uid!=targetUid && cells[2].equals("system_server")){require(system==0,"ambiguous system_server");system=pid;}
        }
        require(pids.get(selfPid)!=null && pids.get(selfPid)==selfUid,"current observer PID/UID missing");
        require(system>0,"cross-UID system_server missing");Collections.sort(targets);return new Ps(targets,system,pids);
    }
    static final class Am {final boolean targetPresent,writerPresent;final String sectionBoundary;Am(boolean targetPresent,boolean writerPresent,String sectionBoundary){this.targetPresent=targetPresent;this.writerPresent=writerPresent;this.sectionBoundary=sectionBoundary;}}
    private static String uidLabel(int uid){int app=uid%100000;require(uid>=10000 && app>=10000 && app<20000,"unsupported instrumentation UID shape");return "u"+(uid/100000)+"a"+(app-10000);}
    private static Matcher one(String body,String regex,String code){Matcher m=Pattern.compile(regex,Pattern.MULTILINE).matcher(body);require(m.find(),code);Matcher duplicate=Pattern.compile(regex,Pattern.MULTILINE).matcher(body);duplicate.find();require(!duplicate.find(),code+" duplicate");return m;}
    static Am am(Completed capture,int selfUid,int selfPid,int targetUid,int writerPid){return am(capture,selfUid,selfPid,targetUid,writerPid,OBSERVER);}
    static Am am(Completed capture,int selfUid,int selfPid,int targetUid,int writerPid,String observer){
        require(Arrays.asList(OBSERVER,"com.agenthub.android.OwnedPublicProcessNaturalObserver","com.agenthub.android.OwnedPublicProcessDiagnosticObserver").contains(observer),"fixed self observer component");
        require(capture!=null && capture.kind==Diagnostic.AM,"verified AM completion required");String raw=capture.body;
        require(raw.getBytes(StandardCharsets.UTF_8).length<=AM_BUDGET && raw.endsWith("\n") && raw.indexOf('\r')<0 && raw.indexOf('\0')<0,"AM bound/termination");
        require(!raw.contains("DUMP TIMEOUT") && !raw.contains("Permission Denial") && !raw.contains("Failure dumping") && !raw.contains("Error dumping"),"AM diagnostic incomplete/restricted");
        String header="ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n";require(raw.startsWith(header),"exact global AM header");
        String title="  Active instrumentation:\n";int start=raw.indexOf(title);require(start>=header.length() && raw.indexOf(title,start+title.length())<0,"unique active instrumentation section");
        String rest=raw.substring(start+title.length());Matcher nextSection=Pattern.compile("(?m)^  [^ \\n]").matcher(rest);
        boolean hasNextSection=nextSection.find();if(hasNextSection)rest=rest.substring(0,nextSection.start());
        String[] blocks=rest.split("(?m)(?=^    Instrumentation #)");
        boolean self=false,target=false,writer=false;int records=0;
        for(String block:blocks){
            if(block.trim().isEmpty())continue;
            Matcher summary=one(block,"^    Instrumentation #([0-9]+): ActiveInstrumentation\\{[a-f0-9]+ \\{([^/} ]+)/([^} ]+)\\}( FINISHED)? ([0-9]+) procs\\}$","AM record summary");
            require(block.startsWith(summary.group()),"unknown AM prefix");require(number(summary.group(1),false)==records++ && records<=256,"AM record index/bound");
            Matcher clazz=one(block,"^      mClass=ComponentInfo\\{([^/} ]+)/([^} ]+)\\} mFinished=(true|false)$","AM component");
            String pkg=clazz.group(1),name=clazz.group(2);if(name.startsWith("."))name=pkg+name;
            String summaryName=summary.group(3);if(summaryName.startsWith("."))summaryName=summary.group(2)+summaryName;
            require(pkg.equals(summary.group(2)) && name.equals(summaryName) && (summary.group(4)!=null)==clazz.group(3).equals("true"),"AM component/summary mismatch");
            Matcher info=one(block,"^      mTargetInfo=ApplicationInfo\\{[a-f0-9]+ ([^} ]+)\\}$","AM target info");
            Matcher uid=one(block,"^        uid=([0-9]+)(?: .*)?$","AM owner UID");int owner=number(uid.group(1),false);
            Matcher running=one(block,"^      mRunningProcesses:$","AM running section"),targets=one(block,"^      mTargetProcesses=\\[([^\\r\\n]*)\\]$","AM target processes");
            require(clazz.start()<running.start() && running.end()<targets.start() && targets.end()<info.start() && info.end()<uid.start(),"AM section order");
            String runningRows=block.substring(running.end()+1,targets.start());
            int count=0;boolean ownProcess=false,writerProcess=false;
            for(String row:runningRows.split("\n")){
                if(row.isEmpty())continue;Matcher processes=Pattern.compile("        #([0-9]+): ProcessRecord\\{[a-f0-9]+ ([0-9]+):([^/} ]+)/([^} ]+)\\}").matcher(row);require(processes.matches(),"AM malformed running row");
                require(number(processes.group(1),false)==count++,"AM process sequence");int pid=number(processes.group(2),true);
                if(pid==selfPid && processes.group(3).equals(SELF) && processes.group(4).equals(uidLabel(selfUid)))ownProcess=true;
                if(pid==writerPid && processes.group(3).equals(APP) && processes.group(4).equals(uidLabel(targetUid)))writerProcess=true;
            }
            require(count==number(summary.group(5),false),"AM process count/malformed row");
            Matcher arguments=one(block,"^      mArguments=.*$","AM arguments tail");String trimmed=block.replaceFirst("\\s+$","");int last=trimmed.lastIndexOf('\n');require(last>=0 && trimmed.substring(last+1).equals(arguments.group()),"AM incomplete record tail");
            require(!block.contains("DUMP TIMEOUT") && !block.contains("Permission Denial") && !block.contains("..."),"AM restricted/truncated");
            if(pkg.equals(SELF) && name.equals(observer)){
                require(!self && clazz.group(3).equals("false") && owner==selfUid && info.group(1).equals(SELF) && ownProcess && count==1 && targets.group(1).equals(SELF),"current self instrumentation owner/process");self=true;
            }
            if(owner==targetUid || info.group(1).equals(APP)){
                require(owner==targetUid && info.group(1).equals(APP),"AM target owner mismatch");target=true;
                if(writerProcess && clazz.group(3).equals("false") && pkg.equals(APP+".test") && name.equals("androidx.test.runner.AndroidJUnitRunner"))writer=true;
            }
        }
        require(self && records>0,"current self instrumentation missing");return new Am(target,writer,hasNextSection?"next-global-section":"verified-command-end");
    }
}
