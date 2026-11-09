import type { ChildProcess } from 'node:child_process';

/** Retire only the child handle we spawned, and confirm both exit and stdio close.
 * A sent signal (child.killed) is not an exit acknowledgement. Never replay RPCs.
 */
export function retireMetadataProcess(
  child: ChildProcess,
  closed: Promise<unknown>,
): Promise<void> {
  child.stdin?.end();
  child.kill('SIGTERM');
  return new Promise<void>((resolve, reject) => {
    const kill = setTimeout(() => {
      if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
    }, 1000);
    const deadline = setTimeout(() => {
      clearTimeout(kill);
      reject(new Error('Metadata process close unconfirmed'));
    }, 5000);
    void closed.then(() => {
      clearTimeout(kill);
      clearTimeout(deadline);
      resolve();
    });
  });
}
