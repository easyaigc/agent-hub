import { Worker } from 'node:worker_threads';
import { getWindowsProcessGate, registerWindowsProcessWorker } from './windows-process.js';
import type { ResponsePeerIdentity } from './desktop-response-peer.js';

/** Native process/pipe queries stay off the Host and forwarding event loop. */
export class DesktopResponsePeerClient {
  private worker: Worker | undefined;
  private sequence = 0;
  private closed = false;
  private pending = new Map<
    number,
    { resolve: (value: ResponsePeerIdentity) => void; reject: () => void; timer: NodeJS.Timeout }
  >();
  inspect(peer?: { clientPort: number; listenerPort: number }): Promise<ResponsePeerIdentity> {
    if (this.closed || this.pending.size >= 4)
      return Promise.reject(new Error('Response peer busy'));
    if (!this.worker) {
      const worker = new Worker(new URL('./desktop-response-peer-worker.js', import.meta.url), {
        workerData: { windowsProcessGate: getWindowsProcessGate() },
      });
      registerWindowsProcessWorker(worker);
      this.worker = worker;
      worker.on('message', (message: { id: number; ok: boolean; value: ResponsePeerIdentity }) => {
        const call = this.pending.get(message.id);
        if (!call) return;
        this.pending.delete(message.id);
        clearTimeout(call.timer);
        if (message.ok) call.resolve(message.value);
        else call.reject();
      });
      const unavailable = () => {
        for (const call of this.pending.values()) {
          clearTimeout(call.timer);
          call.reject();
        }
        this.pending.clear();
      };
      worker.on('error', unavailable);
      worker.once('exit', () => {
        unavailable();
        if (this.worker === worker) this.worker = undefined;
      });
    }
    const id = ++this.sequence;
    return new Promise((resolve, reject) => {
      const fail = () => reject(new Error('Original response peer unavailable'));
      const timer = setTimeout(() => {
        this.pending.delete(id);
        fail();
      }, 7000);
      this.pending.set(id, { resolve, reject: fail, timer });
      this.worker!.postMessage({ id, ...(peer ? { peer } : {}) });
    });
  }
  close(): void {
    this.closed = true;
    for (const call of this.pending.values()) {
      clearTimeout(call.timer);
      call.reject();
    }
    this.pending.clear();
    // Native queries have a six-second process timeout; close waits for those
    // bounded, read-only children before this worker releases its port.
    this.worker?.postMessage({ close: true });
  }
}
