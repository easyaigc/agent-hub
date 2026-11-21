import type { IncomingMessage, ServerResponse } from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import type { RuntimeTarget } from '@codex-manage/protocol';
import { RuntimeError } from '@codex-manage/codex-adapter';
import type { PrivateConnections } from './private-connections.js';

/** This tests the actual Host access-key bytes, never a binding's historical hostKeyAuthorized bit. */
export function privateConnectionHostKeyAuthorized(supplied: Buffer, expected: Buffer): boolean {
  return supplied.length === expected.length && timingSafeEqual(supplied, expected);
}
export async function privateConnectionManagementRoute(
  request: IncomingMessage,
  response: ServerResponse,
  url: URL,
  options: {
    hostAccessKeyAuthenticated: boolean;
    connections: PrivateConnections;
    target: RuntimeTarget;
    body: (request: IncomingMessage, limit: number) => Promise<Buffer>;
  },
): Promise<boolean> {
  if (!['/v1/private-connections/status', '/v1/private-connections/revoke'].includes(url.pathname))
    return false;
  if (!options.hostAccessKeyAuthenticated)
    throw new RuntimeError(403, '此操作仅允许原 Host 管理密钥');
  const status = url.pathname.endsWith('/status');
  const method = status ? 'GET' : 'POST';
  if (request.method !== method) {
    response.setHeader('Allow', method);
    throw new RuntimeError(405, '无效设备绑定管理方法');
  }
  let result;
  if (status) {
    const keys = [...url.searchParams.keys()];
    const operation = url.searchParams.has('clientOperationId');
    const expected = ['target', operation ? 'clientOperationId' : 'publicKeySha256'];
    if (
      keys.length !== expected.length ||
      keys.some((key) => !expected.includes(key)) ||
      expected.some((key) => url.searchParams.getAll(key).length !== 1)
    )
      throw new RuntimeError(400, '无效设备绑定查询参数');
    let target: unknown;
    try {
      target = JSON.parse(url.searchParams.get('target')!);
    } catch {
      throw new RuntimeError(400, '无效连接目标');
    }
    result = operation
      ? options.connections.operation(
          {
            target,
            clientOperationId: url.searchParams.get('clientOperationId'),
          },
          options.target,
        )
      : options.connections.status(
          { target, publicKeySha256: url.searchParams.get('publicKeySha256') },
          options.target,
        );
  } else {
    if ([...url.searchParams.keys()].length) throw new RuntimeError(400, '设备撤销不接受查询参数');
    let input: unknown;
    try {
      input = JSON.parse(
        new TextDecoder('utf-8', { fatal: true }).decode(await options.body(request, 8192)),
      );
    } catch (error) {
      if (error instanceof RuntimeError) throw error;
      throw new RuntimeError(400, '无效设备撤销正文');
    }
    result = options.connections.revoke(input, options.target);
  }
  response.setHeader('Cache-Control', 'no-store');
  response.writeHead(200, {
    'Content-Type': 'application/json; charset=utf-8',
  });
  response.end(JSON.stringify(result));
  return true;
}
