import { desktopTurns } from '../../packages/codex-adapter/dist/desktop-ipc.js';

export function omitOldTurn(state, hiddenTurn) {
  return {
    ...state,
    turnHistory: undefined,
    turns: desktopTurns(state).filter((turn) => turn.turnId !== hiddenTurn),
  };
}
