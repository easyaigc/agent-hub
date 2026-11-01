// Only the private channel module imports this clock. Native process/lock
// deadlines and the real Store retain their normal wall clock.
let at = Date.parse('2026-10-30T12:00:00.000Z');
export const now = () => at;
export function advance(milliseconds) {
  if (
    !Number.isSafeInteger(milliseconds) ||
    milliseconds < 0 ||
    !Number.isSafeInteger(at + milliseconds)
  )
    throw new Error('Invalid synthetic clock step');
  at += milliseconds;
  return at;
}
