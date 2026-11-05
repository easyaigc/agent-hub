import { parentPort } from 'node:worker_threads';
import { inspectResponseDesktop } from './desktop-response-peer.js';

let closing = false,
  pending = 0;
parentPort!.on(
  'message',
  (message: {
    id: number;
    close?: boolean;
    peer?: { clientPort: number; listenerPort: number };
  }) => {
    if (message.close) {
      closing = true;
      if (!pending) parentPort!.close();
      return;
    }
    if (closing || !Number.isSafeInteger(message.id) || pending >= 4) {
      parentPort!.postMessage({ id: message.id, ok: false });
      return;
    }
    pending++;
    void inspectResponseDesktop(message.peer)
      .then(
        (value) => parentPort!.postMessage({ id: message.id, ok: true, value }),
        () => parentPort!.postMessage({ id: message.id, ok: false }),
      )
      .finally(() => {
        if (!--pending && closing) parentPort!.close();
      });
  },
);
