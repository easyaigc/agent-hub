import { parseArgs } from 'node:util';
import { readFile, writeFile, rename, mkdir, lstat, chmod } from 'node:fs/promises';
import { homedir } from 'node:os';
import { isAbsolute, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import https from 'node:https';
import { createHash, randomUUID, X509Certificate } from 'node:crypto';
import { createGunzip } from 'node:zlib';

const fail = (code) => {
  const error = new Error(code);
  error.code = code;
  throw error;
};
const requireValue = (value, code) => {
  if (!value) fail(code);
};
const digest = (value) => createHash('sha256').update(value).digest('hex');
const pinnedTlsOptions = async (pin) => {
  if (pin === undefined) return {};
  requireValue(
    pin !== null &&
      typeof pin === 'object' &&
      typeof pin.sha256 === 'string' &&
      /^[a-fA-F0-9]{64}$/.test(pin.sha256) &&
      typeof pin.certificatePath === 'string' &&
      isAbsolute(pin.certificatePath),
    'INVALID_TLS_PIN',
  );
  const stat = await lstat(pin.certificatePath);
  requireValue(
    stat.isFile() && !stat.isSymbolicLink() && stat.size <= 65536,
    'INVALID_TLS_PIN_CERTIFICATE',
  );
  const pem = await readFile(pin.certificatePath, 'utf8');
  let certificate;
  try {
    requireValue(
      /^\s*-----BEGIN CERTIFICATE-----\r?\n[A-Za-z0-9+/=\r\n]+-----END CERTIFICATE-----\s*$/.test(
        pem,
      ),
      'INVALID_TLS_PIN_CERTIFICATE',
    );
    certificate = new X509Certificate(pem);
    const now = Date.now();
    // This directory contract supplies one self-signed leaf as its own trust anchor.
    // OpenSSL need not verify a trust anchor's self-signature, so check it explicitly.
    requireValue(
      certificate.subject === certificate.issuer &&
        certificate.verify(certificate.publicKey) &&
        Date.parse(certificate.validFrom) <= now &&
        now <= Date.parse(certificate.validTo),
      'INVALID_TLS_PIN_CERTIFICATE',
    );
  } catch {
    fail('INVALID_TLS_PIN_CERTIFICATE');
  }
  const expected = pin.sha256.toLowerCase();
  requireValue(digest(certificate.raw) === expected, 'TLS_PIN_CERTIFICATE_MISMATCH');
  return {
    ca: pem,
    rejectUnauthorized: true,
    // A fresh agent prevents cross-Host reuse and resumed TLS sessions skipping the leaf check.
    agent: false,
    checkServerIdentity: (_hostname, peer) => {
      if (!Buffer.isBuffer(peer.raw) || digest(peer.raw) !== expected) {
        const error = new Error('TLS_PIN_MISMATCH');
        error.code = 'TLS_PIN_MISMATCH';
        return error;
      }
      // TLS has already verified the chain, signatures and validity. Only this exact
      // leaf replaces DNS/IP name matching, including explicitly pinned leaves without SAN.
    },
  };
};
const privatePath = async (path, directory = false) => {
  const stat = await lstat(path);
  requireValue(
    !stat.isSymbolicLink() && (directory ? stat.isDirectory() : stat.isFile()),
    'UNSAFE_PRIVATE_PATH',
  );
  if (process.platform !== 'win32')
    requireValue(
      !(stat.mode & 0o077) && stat.uid === process.getuid(),
      'UNSAFE_PRIVATE_PERMISSIONS',
    );
};
const atomic = async (path, value) => {
  const temporary = path + '.' + randomUUID() + '.tmp';
  await writeFile(temporary, JSON.stringify(value, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
  await rename(temporary, path);
};
const summary = (value) =>
  Object.fromEntries(
    [
      'hostId',
      'threadId',
      'clientMessageId',
      'clientOperationId',
      'projectId',
      'state',
      'phase',
      'turnId',
      'effort',
      'status',
      'error',
      'detached',
    ]
      .filter((key) => value?.[key] !== undefined)
      .map((key) => [key, value[key]]),
  );

export async function main(args = process.argv.slice(2), options = {}) {
  const root = options.directory ?? join(homedir(), '.config', 'agent-hub');
  const output = options.output ?? console.log;
  const { values, positionals } = parseArgs({
    args,
    allowPositionals: true,
    strict: true,
    options: {
      'host-id': { type: 'string' },
      'thread-id': { type: 'string' },
      'operation-id': { type: 'string' },
      'text-file': { type: 'string' },
      'project-id': { type: 'string' },
      'no-suitable-reason': { type: 'string' },
      effort: { type: 'string', default: 'xhigh' },
      help: { type: 'boolean' },
    },
  });
  const command = positionals[0];
  if (values.help) {
    output(
      'probe|workspace|history|send|receipt|open|open-status|create|create-status|create-submit --host-id ID [--thread-id ID] [--operation-id ID] [--text-file PATH] [--effort xhigh]',
    );
    return;
  }
  requireValue(
    positionals.length === 1 &&
      [
        'probe',
        'workspace',
        'history',
        'send',
        'receipt',
        'open',
        'open-status',
        'create',
        'create-status',
        'create-submit',
      ].includes(command),
    'UNKNOWN_NAMED_OPERATION',
  );
  requireValue(values['host-id'], 'EXPLICIT_HOST_ID_REQUIRED');
  await privatePath(root, true);
  const registryPath = join(root, 'hosts.json');
  await privatePath(registryPath);
  const registry = JSON.parse(await readFile(registryPath, 'utf8'));
  requireValue(
    registry.schemaVersion === 1 && Array.isArray(registry.hosts),
    'INCOMPATIBLE_REGISTRY',
  );
  const matches = registry.hosts.filter((host) => host.hostId === values['host-id']);
  requireValue(matches.length === 1, 'HOST_ID_NOT_UNIQUE_OR_UNKNOWN');
  const host = matches[0];
  requireValue(
    typeof host.clientKey === 'string' && host.clientKey.length >= 32 && !/\s/.test(host.clientKey),
    'INVALID_CLIENT_KEY',
  );
  const endpoint = new URL(host.endpoint);
  requireValue(
    endpoint.protocol === 'https:' &&
      !endpoint.username &&
      !endpoint.password &&
      !endpoint.search &&
      !endpoint.hash &&
      endpoint.pathname === '/',
    'HTTPS_ROOT_REQUIRED',
  );
  const tlsOptions = await pinnedTlsOptions(host.tlsPin);
  const request =
    options.transport ??
    ((path, body, authenticated = true) =>
      new Promise((resolve, reject) => {
        const bytes = body === undefined ? undefined : Buffer.from(JSON.stringify(body));
        let stream;
        const networkFailure = () => {
          clearTimeout(timer);
          const error = new Error('NETWORK_OUTCOME_UNKNOWN');
          error.code = 'NETWORK_OUTCOME_UNKNOWN';
          reject(error);
          stream?.destroy();
          request.destroy();
        };
        const request = https.request(
          new URL(path, endpoint),
          {
            ...tlsOptions,
            method: bytes === undefined ? 'GET' : 'POST',
            headers: {
              'Accept-Encoding': 'gzip',
              ...(authenticated ? { Authorization: 'Bearer ' + host.clientKey } : {}),
              ...(bytes === undefined
                ? {}
                : { 'Content-Type': 'application/json', 'Content-Length': bytes.length }),
            },
          },
          (response) => {
            let text = '';
            const gzip = response.headers['content-encoding']?.trim().toLowerCase() === 'gzip';
            stream = gzip ? createGunzip() : response;
            stream.setEncoding('utf8');
            stream.on('data', (part) => {
              text += part;
            });
            stream.on('end', () => {
              clearTimeout(timer);
              let value = null;
              try {
                value = JSON.parse(text);
              } catch {
                /* Invalid or proxy response. */
              }
              resolve({ http: response.statusCode, value });
            });
            response.on('error', networkFailure);
            if (gzip) {
              stream.on('error', networkFailure);
              response.pipe(stream);
            }
          },
        );
        const timer = setTimeout(networkFailure, 55000);
        request.on('error', networkFailure);
        // Native HTTPS verifies certificates and never follows redirects.
        request.end(bytes);
      }));
  const threadId = values['thread-id'] ?? host.coordinationThreadId;
  if (!['probe', 'workspace', 'create', 'create-status', 'create-submit'].includes(command))
    requireValue(
      typeof threadId === 'string' && threadId.length > 0,
      'EXPLICIT_OR_REGISTERED_THREAD_ID_REQUIRED',
    );
  const operationId = values['operation-id'];
  if (
    ['send', 'receipt', 'open', 'open-status', 'create', 'create-status', 'create-submit'].includes(
      command,
    )
  )
    requireValue(
      typeof operationId === 'string' && /^[a-zA-Z0-9_-]{8,100}$/.test(operationId),
      'STABLE_SAFE_OPERATION_ID_REQUIRED',
    );

  if (['receipt', 'open-status', 'create-status'].includes(command)) {
    const path =
      command === 'create-status'
        ? '/v1/desktop/creation-receipts?' +
          new URLSearchParams({ hostId: host.hostId, id: operationId })
        : command === 'receipt'
          ? '/v1/receipts?' + new URLSearchParams({ hostId: host.hostId, id: operationId })
          : '/v1/desktop/open-status?' +
            new URLSearchParams({ hostId: host.hostId, threadId, id: operationId });
    const response = await request(path);
    if (response.http === 200) {
      requireValue(
        response.value.hostId === host.hostId &&
          (command === 'create-status'
            ? response.value.clientOperationId === operationId
            : response.value.threadId === threadId),
        'RECEIPT_TARGET_MISMATCH',
      );
      if (command === 'receipt')
        requireValue(response.value.clientMessageId === operationId, 'RECEIPT_OPERATION_MISMATCH');
    }
    output(
      JSON.stringify({
        operation: command,
        operationId,
        hostId: host.hostId,
        threadId,
        http: response.http,
        result: summary(response.value),
        retryAllowed: false,
      }),
    );
    return;
  }

  let text;
  if (command === 'send' || command === 'create') {
    requireValue(values['text-file'], 'PRIVATE_TEXT_FILE_REQUIRED');
    requireValue(
      ['low', 'medium', 'high', 'xhigh', 'max', 'ultra'].includes(values.effort),
      'INVALID_EFFORT',
    );
    text = await readFile(values['text-file'], 'utf8');
    requireValue(text.trim().length > 0 && text.length <= 100000, 'INVALID_TASK_TEXT');
    requireValue(
      !registry.hosts.some((item) => item.clientKey && text.includes(item.clientKey)),
      'CREDENTIAL_IN_TASK_TEXT',
    );
  }
  let ledgerPath, saved;
  if (['send', 'open', 'create', 'create-submit'].includes(command)) {
    const directory = join(root, 'operations');
    await mkdir(directory, { recursive: true, mode: 0o700 });
    if (process.platform !== 'win32') await chmod(directory, 0o700);
    await privatePath(directory, true);
    ledgerPath = join(directory, operationId + '.json');
    try {
      await privatePath(ledgerPath);
      saved = JSON.parse(await readFile(ledgerPath, 'utf8'));
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
    }
    if (command === 'create-submit')
      requireValue(
        saved?.kind === 'create' && saved.hostId === host.hostId,
        'MATCHING_CREATE_INTENT_REQUIRED',
      );
    if (saved?.kind === 'create' && ['create', 'create-submit'].includes(command)) {
      requireValue(
        saved.hostId === host.hostId &&
          (command === 'create-submit' ||
            (saved.textSha256 === digest(text) &&
              saved.payload.effort === values.effort &&
              saved.payload.projectId === (values['project-id'] ?? null))),
        'OPERATION_ID_REUSED_WITH_DIFFERENT_INPUT',
      );
      const response = await request(
        '/v1/desktop/creation-receipts?' +
          new URLSearchParams({ hostId: host.hostId, id: operationId }),
      );
      if (response.http === 200)
        requireValue(
          response.value.hostId === host.hostId && response.value.clientOperationId === operationId,
          'RECEIPT_TARGET_MISMATCH',
        );
      saved.latestReceipt = { http: response.http, result: summary(response.value) };
      saved.checkedAt = new Date().toISOString();
      await atomic(ledgerPath, saved);
      if (
        command !== 'create-submit' ||
        saved.submitAttempted ||
        response.http !== 200 ||
        response.value.phase !== 'prepared'
      ) {
        output(
          JSON.stringify({
            operation: command,
            operationId,
            queriedExistingOperation: true,
            replayed: false,
            ...saved.latestReceipt,
          }),
        );
        return;
      }
    } else if (saved) {
      requireValue(
        saved.kind === command &&
          saved.hostId === host.hostId &&
          saved.threadId === threadId &&
          (command !== 'send' ||
            (saved.textSha256 === digest(text) && saved.payload.effort === values.effort)),
        'OPERATION_ID_REUSED_WITH_DIFFERENT_INPUT',
      );
      const path =
        command === 'send'
          ? '/v1/receipts?' + new URLSearchParams({ hostId: host.hostId, id: operationId })
          : '/v1/desktop/open-status?' +
            new URLSearchParams({ hostId: host.hostId, threadId, id: operationId });
      const response = await request(path);
      if (response.http === 200)
        requireValue(
          response.value.hostId === host.hostId && response.value.threadId === threadId,
          'RECEIPT_TARGET_MISMATCH',
        );
      saved.latestReceipt = { http: response.http, result: summary(response.value) };
      saved.checkedAt = new Date().toISOString();
      await atomic(ledgerPath, saved);
      output(
        JSON.stringify({
          operation: command,
          operationId,
          hostId: host.hostId,
          threadId,
          queriedExistingOperation: true,
          replayed: false,
          ...saved.latestReceipt,
        }),
      );
      return;
    }
  }
  const health = await request('/healthz');
  requireValue(
    health.http === 200 && health.value?.service === 'host-agent' && health.value.status === 'ok',
    'AUTHENTICATED_HEALTH_UNAVAILABLE',
  );
  const workspace = await request('/v1/workspace');
  requireValue(workspace.http === 200, 'WORKSPACE_RUNTIME_UNAVAILABLE');
  requireValue(
    workspace.value.hostId === host.hostId && workspace.value.connectionMode === 'desktop-runtime',
    'HOST_ID_OR_RUNTIME_MISMATCH',
  );
  if (command === 'probe') {
    const unauthenticated = await request('/healthz', undefined, false);
    requireValue(unauthenticated.http === 401, 'AUTHENTICATION_NOT_ENFORCED');
    output(
      JSON.stringify({
        operation: command,
        hostId: host.hostId,
        name: host.name,
        endpoint: endpoint.origin,
        authenticated: true,
        hostIdentityMatched: true,
        connectionMode: workspace.value.connectionMode,
        capabilities: workspace.value.capabilities,
      }),
    );
    return;
  }
  if (command === 'workspace') {
    output(
      JSON.stringify({
        operation: command,
        hostId: host.hostId,
        connectionMode: workspace.value.connectionMode,
        projects: workspace.value.projects.map((project) => ({
          id: project.id,
          name: project.name,
        })),
        threads: workspace.value.threads.map((thread) => ({
          id: thread.id,
          title: thread.title,
          projectId: thread.projectId,
          projectIds: thread.projectIds,
          state: thread.state,
          archived: thread.archived ?? thread.isArchived ?? null,
        })),
      }),
    );
    return;
  }
  if (command === 'create' || command === 'create-submit') {
    requireValue(
      workspace.value.capabilities.includes('desktop:creations'),
      'NAMED_DESKTOP_CREATION_UNAVAILABLE',
    );
    if (!saved) {
      requireValue(
        values['no-suitable-reason']?.trim(),
        'CURRENT_CATALOG_SELECTION_REASON_REQUIRED',
      );
      const projectId = values['project-id'] ?? null;
      requireValue(
        projectId === null
          ? workspace.value.capabilities.includes('desktop:creations:projectless')
          : workspace.value.projects.some((project) => project.id === projectId),
        'EXACT_PROJECT_UNAVAILABLE',
      );
      const payload = {
        hostId: host.hostId,
        clientOperationId: operationId,
        projectId,
        text,
        attachments: [],
        effort: values.effort,
      };
      saved = {
        kind: 'create',
        hostId: host.hostId,
        operationId,
        textSha256: digest(text),
        payload,
        selectionReason: values['no-suitable-reason'],
        catalogCheckedAt: new Date().toISOString(),
        catalogThreadIds: workspace.value.threads.map((thread) => thread.id),
        status: 'prepare-intent',
        createdAt: new Date().toISOString(),
      };
      await writeFile(ledgerPath, JSON.stringify(saved, null, 2) + '\n', {
        mode: 0o600,
        flag: 'wx',
      });
      let prepared;
      try {
        prepared = await request('/v1/desktop/creations', payload);
      } catch {
        saved.status = 'prepare-unknown';
        await atomic(ledgerPath, saved);
        output(JSON.stringify({ operation: command, operationId, status: saved.status }));
        return;
      }
      saved.prepareResponse = { http: prepared.http, result: summary(prepared.value) };
      await atomic(ledgerPath, saved);
      if (
        prepared.http !== 200 ||
        prepared.value?.hostId !== host.hostId ||
        prepared.value?.clientOperationId !== operationId ||
        prepared.value.phase !== 'prepared'
      ) {
        saved.status = 'query-preparation';
        await atomic(ledgerPath, saved);
        output(
          JSON.stringify({
            operation: command,
            operationId,
            status: saved.status,
            ...saved.prepareResponse,
          }),
        );
        return;
      }
    }
    saved.submitAttempted = true;
    saved.status = 'submit-intent';
    await atomic(ledgerPath, saved);
    try {
      const response = await request('/v1/desktop/creations/submit', {
        hostId: host.hostId,
        clientOperationId: operationId,
      });
      if (response.http === 200)
        requireValue(
          response.value?.hostId === host.hostId &&
            response.value?.clientOperationId === operationId,
          'CREATION_TARGET_MISMATCH',
        );
      saved.response = { http: response.http, result: summary(response.value) };
      saved.status = response.http === 200 ? 'submitted' : 'submit-unknown';
    } catch {
      saved.status = 'submit-unknown';
    }
    saved.checkedAt = new Date().toISOString();
    await atomic(ledgerPath, saved);
    output(
      JSON.stringify({
        operation: command,
        operationId,
        status: saved.status,
        replayed: false,
        ...saved.response,
      }),
    );
    return;
  }
  // A stored hint never substitutes for this fresh project/chat catalog.
  requireValue(
    workspace.value.threads.some(
      (thread) =>
        thread.id === threadId &&
        thread.archived !== true &&
        thread.isArchived !== true &&
        !thread.archivedAt,
    ),
    'SELECT_CURRENT_UNARCHIVED_THREAD_OR_CREATE',
  );
  const history = await request(
    '/v1/history?' + new URLSearchParams({ hostId: host.hostId, threadId, window: 'latest-v1' }),
  );
  requireValue(
    history.http === 200 &&
      history.value.hostId === host.hostId &&
      history.value.threadId === threadId,
    'THREAD_ID_OR_HISTORY_MISMATCH',
  );
  const runtime = history.value.runtime;
  if (command === 'history') {
    output(
      JSON.stringify({
        operation: command,
        hostId: host.hostId,
        threadId,
        ready: history.value.ready,
        canControl: runtime?.canControl,
        state: runtime?.state,
        turnId: runtime?.turnId,
        generationSettings: runtime?.generationSettings,
      }),
    );
    return;
  }
  if (command === 'send')
    requireValue(history.value.ready && runtime?.canControl, 'VERIFIED_ORIGINAL_OWNER_REQUIRED');
  if (command === 'open')
    requireValue(
      workspace.value.capabilities.includes('desktop:open'),
      'NAMED_DESKTOP_OPEN_UNAVAILABLE',
    );
  const payload =
    command === 'send'
      ? {
          hostId: host.hostId,
          threadId,
          clientMessageId: operationId,
          text,
          attachmentIds: [],
          mode: runtime.state === 'idle' ? 'next-turn' : 'queue',
          effort: values.effort,
        }
      : { hostId: host.hostId, threadId, clientOperationId: operationId };
  saved = {
    kind: command,
    hostId: host.hostId,
    threadId,
    operationId,
    ...(text === undefined ? {} : { textSha256: digest(text) }),
    payload,
    status: 'intent-saved',
    createdAt: new Date().toISOString(),
  };
  // Exclusive creation prevents simultaneous invocations from submitting twice.
  await writeFile(ledgerPath, JSON.stringify(saved, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
  try {
    const response = await request(
      command === 'send' ? '/v1/replies' : '/v1/desktop/open',
      payload,
    );
    if (response.http === 200)
      requireValue(
        response.value.hostId === host.hostId && response.value.threadId === threadId,
        'SUBMISSION_TARGET_MISMATCH',
      );
    saved.response = { http: response.http, result: summary(response.value) };
    saved.status = response.http === 200 ? 'submitted' : 'query-before-retry';
  } catch {
    saved.status = 'uncertain-query-required';
  }
  saved.checkedAt = new Date().toISOString();
  await atomic(ledgerPath, saved);
  output(
    JSON.stringify({
      operation: command,
      operationId,
      hostId: host.hostId,
      threadId,
      status: saved.status,
      ...saved.response,
      replayed: false,
      retryAllowed: false,
    }),
  );
  if (saved.status !== 'submitted') return 2;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url))
  main()
    .then((code) => {
      if (typeof code === 'number') process.exitCode = code;
    })
    .catch((error) => {
      console.error(
        JSON.stringify({
          status: 'failed',
          error: typeof error.code === 'string' ? error.code : 'LOCAL_OPERATION_FAILED',
        }),
      );
      process.exitCode = 1;
    });
