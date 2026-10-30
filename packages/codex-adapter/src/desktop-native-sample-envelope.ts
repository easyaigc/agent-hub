type Direction = 'observeOutbound' | 'observeInbound';
type Envelope = { id?: unknown; method?: unknown; hasMethod: boolean };

// The stdio tap already bounds complete lines at 32 MiB. Large history bodies
// must not be decoded into another object graph merely to identify their RPC.
// Decode only bounded top-level keys and routing strings; nested payload text
// cannot select a request. An ambiguous envelope retains the fail-closed path.
function largeEnvelope(line: string): Envelope | undefined {
  if (line.length > 32 * 1024 * 1024) return undefined;
  let index = 0;
  const whitespace = () => {
    while ([32, 9, 10, 13].includes(line.charCodeAt(index))) index++;
  };
  const string = (): boolean => {
    if (line[index++] !== '"') return false;
    while (index < line.length) {
      const code = line.charCodeAt(index++);
      if (code === 34) return true;
      if (code < 32) return false;
      if (code !== 92) continue;
      const escape = line[index++];
      if (escape === 'u') {
        for (let digit = 0; digit < 4; digit++) {
          const hex = line.charCodeAt(index++);
          if (!((hex >= 48 && hex <= 57) || (hex >= 65 && hex <= 70) || (hex >= 97 && hex <= 102)))
            return false;
        }
      } else if (!escape || !'"\\/bfnrt'.includes(escape)) return false;
    }
    return false;
  };
  const value = (): boolean => {
    if (line[index] === '"') return string();
    if (line[index] === '{' || line[index] === '[') {
      const stack = [line[index++]!];
      while (stack.length && index < line.length) {
        const character = line[index];
        if (character === '"') {
          if (!string()) return false;
          continue;
        }
        index++;
        if (character === '{' || character === '[') {
          if (stack.length >= 128) return false;
          stack.push(character);
        } else if (character === '}' || character === ']') {
          if (stack.pop() !== (character === '}' ? '{' : '[')) return false;
        }
      }
      return stack.length === 0;
    }
    const start = index;
    while (index < line.length && line[index] !== ',' && line[index] !== '}') index++;
    if (index - start > 128) return false;
    try {
      const primitive: unknown = JSON.parse(line.slice(start, index));
      return primitive === null || typeof primitive === 'number' || typeof primitive === 'boolean';
    } catch {
      return false;
    }
  };
  whitespace();
  if (line[index++] !== '{') return undefined;
  whitespace();
  const envelope: Envelope = { hasMethod: false };
  const keys = new Set<string>();
  while (line[index] !== '}') {
    const start = index;
    if (!string() || index - start > 1024 || keys.size >= 64) return undefined;
    const key: unknown = JSON.parse(line.slice(start, index));
    if (typeof key !== 'string' || keys.has(key)) return undefined;
    keys.add(key);
    whitespace();
    if (line[index++] !== ':') return undefined;
    whitespace();
    const valueStart = index;
    const routingString = (key === 'id' || key === 'method') && line[index] === '"';
    if (!value()) return undefined;
    if (key === 'id' || key === 'method') {
      if (routingString && index - valueStart > 1024) return undefined;
      const routingValue: unknown = routingString
        ? JSON.parse(line.slice(valueStart, index))
        : null;
      if (key === 'id') envelope.id = routingValue;
      else {
        envelope.method = routingValue;
        envelope.hasMethod = true;
      }
    }
    whitespace();
    if (line[index] === '}') break;
    if (line[index++] !== ',') return undefined;
    whitespace();
    if (line[index] === '}') return undefined;
  }
  if (line[index++] !== '}') return undefined;
  whitespace();
  return index === line.length ? envelope : undefined;
}

/** Route original frames by their envelope, never a substring in user history. */
export function isOriginalNativeSampleFrame(line: string, direction: Direction): boolean {
  let envelope: Envelope | undefined;
  if (Buffer.byteLength(line) <= 128 * 1024) {
    try {
      const frame: unknown = JSON.parse(line);
      if (!frame || typeof frame !== 'object' || Array.isArray(frame)) return false;
      const value = frame as Record<string, unknown>;
      envelope = { id: value.id, method: value.method, hasMethod: Object.hasOwn(value, 'method') };
    } catch {
      return line.includes('thread/sample');
    }
  } else {
    try {
      envelope = largeEnvelope(line);
    } catch {
      // No parser exception or payload is exposed. The capture boundary rejects
      // an oversized/ambiguous native frame without accepting a result body.
      return true;
    }
    if (!envelope) return true;
  }
  return direction === 'observeOutbound'
    ? envelope.method === 'thread/sample' || envelope.method === 'thread/sample/cancel'
    : !envelope.hasMethod &&
        typeof envelope.id === 'string' &&
        envelope.id.startsWith('thread/sample:');
}
