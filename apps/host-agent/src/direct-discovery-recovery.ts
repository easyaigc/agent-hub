/** Retry only failed startup. An established listener remains owned by its service. */
export class DirectDiscoveryRecovery<T extends { close(): void }> {
  private closed = false;
  private current: T | undefined;
  private initial: Promise<void> | undefined;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private failures = 0;

  constructor(
    private readonly open: () => Promise<T>,
    private readonly ready: (value: T) => void,
    private readonly failed: (error: unknown, attempt: number) => void,
  ) {}

  start(): Promise<void> {
    return (this.initial ??= this.attempt());
  }

  private async attempt(): Promise<void> {
    if (this.closed) return;
    let value: T;
    try {
      value = await this.open();
    } catch (error) {
      if (this.closed) return;
      this.failures++;
      // Diagnostic sinks cannot prevent recovery or create concurrent attempts.
      try {
        this.failed(error, this.failures);
      } catch {
        /* The next startup attempt still owns recovery. */
      }
      if (this.closed) return;
      const delay = Math.min(30_000, 1000 * 2 ** Math.min(this.failures - 1, 5));
      this.timer = setTimeout(() => {
        this.timer = undefined;
        void this.attempt();
      }, delay);
      this.timer.unref();
      return;
    }
    if (this.closed) {
      try {
        value.close();
      } catch {
        /* Shutdown has already detached this late result. */
      }
      return;
    }
    this.current = value;
    try {
      this.ready(value);
    } catch {
      /* An unavailable diagnostic sink does not invalidate the live listener. */
    }
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    clearTimeout(this.timer);
    this.timer = undefined;
    const current = this.current;
    this.current = undefined;
    current?.close();
  }
}
