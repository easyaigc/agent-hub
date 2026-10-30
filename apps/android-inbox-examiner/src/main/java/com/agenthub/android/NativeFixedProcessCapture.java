package com.agenthub.android;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Owns one already-created fixed diagnostic child. No command/path/UID selection or shell execution. */
final class NativeFixedProcessCapture {
    static final String END="P9_PUBLIC_PROCESS_END_V2:";
    final NaturalProcessVisibility.Diagnostic kind;final String nonce;
    final NaturalProcessVisibility.ReadBudget budget;
    volatile boolean childExited,drainsStopped;volatile int exitStatus=Integer.MIN_VALUE;
    volatile byte[] stdout,stderr;volatile boolean verified;
    NativeFixedProcessCapture(NaturalProcessVisibility.Diagnostic kind,String nonce){this.kind=kind;this.nonce=NaturalProcessVisibility.nonce(nonce);budget=new NaturalProcessVisibility.ReadBudget(kind);}
    static long remaining(long end){long value=end-System.nanoTime();NaturalProcessVisibility.require(value>0,"capture deadline expired");return value;}
    private byte[] drain(InputStream source,boolean error)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try{byte[] b=new byte[4096];int n;while((n=source.read(b))!=-1){budget.read(error,n);out.write(b,0,n);}budget.eof(error);}
        finally{byte[] observed=out.toByteArray();if(error)stderr=observed;else stdout=observed;source.close();}
        return error?stderr:stdout;
    }
    static byte[] frame(NaturalProcessVisibility.Diagnostic kind,String nonce,byte[] body,int status)throws Exception{
        NaturalProcessVisibility.nonce(nonce);NaturalProcessVisibility.require(kind!=null,"fixed diagnostic kind");
        byte[] tail=("\n"+END+kind.name()+":"+nonce+":"+status+"\n").getBytes(StandardCharsets.UTF_8);
        NaturalProcessVisibility.require((long)body.length+tail.length<=NaturalProcessVisibility.budget(kind),"V2 framed stdout cap");
        byte[] bytes=Arrays.copyOf(body,body.length+tail.length);System.arraycopy(tail,0,bytes,body.length,tail.length);return bytes;
    }
    static NaturalProcessVisibility.Completed decode(NaturalProcessVisibility.Diagnostic kind,String nonce,byte[] framed,byte[] error,int status,boolean outEof,boolean errEof)throws Exception{
        NaturalProcessVisibility.nonce(nonce);NaturalProcessVisibility.require(kind!=null&&outEof&&errEof&&status==0,"V2 actual exit0 and independent EOF required");
        NaturalProcessVisibility.require(error.length==0&&(long)framed.length+error.length<=NaturalProcessVisibility.budget(kind),"V2 original stderr/combined cap");
        String text=NaturalProcessVisibility.utf8(framed),tail="\n"+END+kind.name()+":"+nonce+":0\n";int position=text.length()-tail.length()+1;
        NaturalProcessVisibility.require(text.endsWith(tail)&&text.indexOf(END)==position&&text.lastIndexOf(END)==position,"V2 unique exact final completion");
        String body=text.substring(0,text.length()-tail.length());
        NaturalProcessVisibility.require(!body.isEmpty()&&body.endsWith("\n")&&body.indexOf('\r')<0&&body.indexOf('\0')<0&&body.indexOf(NaturalProcessVisibility.END)<0,"V2 full strict diagnostic lines");
        return new NaturalProcessVisibility.Completed(kind,nonce,body);
    }
    NaturalProcessVisibility.Completed collect(Process owned,long deadline)throws Exception{
        // Work and cleanup share the caller's absolute phase deadline. Reserve cleanup inside it.
        final long workEnd=deadline-250_000_000L;
        ExecutorService workers=Executors.newFixedThreadPool(3,r->{Thread t=new Thread(r,"p9-fixed-diagnostic");t.setDaemon(true);return t;});
        CompletionService<Integer> done=new ExecutorCompletionService<>(workers);List<Future<Integer>> futures=new ArrayList<>();Throwable failure=null;
        try{
            remaining(workEnd);owned.getOutputStream().close();
            futures.add(done.submit(()->{stdout=drain(owned.getInputStream(),false);return 0;}));
            futures.add(done.submit(()->{stderr=drain(owned.getErrorStream(),true);return 1;}));
            futures.add(done.submit(()->{exitStatus=owned.waitFor();childExited=true;return 2;}));
            for(int n=0;n<3;n++){
                Future<Integer> next=done.poll(remaining(workEnd),TimeUnit.NANOSECONDS);NaturalProcessVisibility.require(next!=null,"V2 diagnostic work deadline");
                try{next.get();}catch(ExecutionException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw (Exception)cause;if(cause instanceof Error)throw (Error)cause;throw error;}
            }
            NaturalProcessVisibility.ReadStats counts=budget.stats();NaturalProcessVisibility.require(childExited&&stdout!=null&&stderr!=null&&counts.stdoutEof&&counts.stderrEof&&counts.stdout==stdout.length&&counts.stderr==stderr.length,"V2 both actual streams and child exit");
            NaturalProcessVisibility.Completed complete=decode(kind,nonce,frame(kind,nonce,stdout,exitStatus),stderr,exitStatus,counts.stdoutEof,counts.stderrEof);verified=true;return complete;
        }catch(Throwable error){failure=error;if(error instanceof Exception)throw (Exception)error;if(error instanceof Error)throw (Error)error;throw new AssertionError(error);}
        finally{
            Throwable cleanup=null;
            // Only the Process object created for this capture is terminated. No PID/name/global kill.
            try{if(owned.isAlive())owned.destroyForcibly();long left=deadline-System.nanoTime();childExited=!owned.isAlive()||(left>0&&owned.waitFor(left,TimeUnit.NANOSECONDS));if(!childExited)throw new IllegalStateException("V2 owned child exit unresolved");}catch(Throwable error){cleanup=error;}
            for(Closeable stream:new Closeable[]{owned.getOutputStream(),owned.getInputStream(),owned.getErrorStream()})try{stream.close();}catch(Throwable error){if(cleanup==null)cleanup=error;else cleanup.addSuppressed(error);}
            for(Future<?> f:futures)f.cancel(true);workers.shutdownNow();
            try{long left=deadline-System.nanoTime();drainsStopped=workers.isTerminated()||(left>0&&workers.awaitTermination(left,TimeUnit.NANOSECONDS));if(!drainsStopped)throw new IllegalStateException("V2 drain cleanup unresolved");}catch(Throwable error){if(cleanup==null)cleanup=error;else cleanup.addSuppressed(error);}
            if(System.nanoTime()>deadline&&cleanup==null)cleanup=new IllegalStateException("V2 capture/cleanup absolute deadline");
            if(cleanup!=null){verified=false;if(failure!=null)failure.addSuppressed(cleanup);else if(cleanup instanceof Exception)throw (Exception)cleanup;else throw new AssertionError(cleanup);}
        }
    }
}
