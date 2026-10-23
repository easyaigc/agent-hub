import { createServer } from 'node:http';
import { execFileSync } from 'node:child_process';

// Synthetic reads only. No desktop history, keys or executable commands are exported.
export async function startNativeReadFixture(adb = null, serial = null, options = {}) {
  const requests = [];
  let endpoint;
  const target = {
    agentId: 'codex',
    hostId: 'fixture-android-host',
    workspaceId: 'fixture-workspace',
    runtimeId: 'fixture-history',
  };
  const project = (index) => ({
    id: 'project-' + index,
    hostId: target.hostId,
    name: index === 0 ? 'Agent Hub' : '项目 ' + index,
    roots: [],
  });
  const thread = (index) => ({
    id: 'thread-' + index,
    hostId: target.hostId,
    projectId: index < 7 ? 'project-0' : 'project-1',
    title: index === 0 ? '只读界面验收' : '历史对话 ' + index,
    state: index === 1 ? 'running' : index === 2 ? 'waiting-for-approval' : 'idle',
    hasUnreadTurn: index === 3,
  });
  const markdown =
    '# 原生 Markdown\n\n这是 **粗体和 *嵌套斜体***，含 `inline()` 与 [官方文档](https://developer.android.com)。\n\n> 引用正文\n\n1. 第一项\n2. 第二项\n   - 子列表\n\n| 功能 | 状态 | 长内容 |\n| --- | --- | --- |\n| 历史 | 可读取 | 表格支持横向滚动查看完整内容 |\n\n```kotlin\nval answer = "安卓🙂"\nval longLine = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"\n```\n\n~~旧状态~~ 与安全链接。';
  const messages = [
    { id: 'read-user', role: 'user', text: '请展示只读历史和代码。', runId: 'read-run' },
    {
      id: 'read-command',
      role: 'tool',
      kind: 'commandExecution',
      status: 'completed',
      text: '运行 pnpm check',
      detail: '所有检查通过\n合成输出，用于展示。',
      runId: 'read-run',
    },
    {
      id: 'read-diff',
      role: 'tool',
      kind: 'fileChange',
      status: 'completed',
      text: '更新示例文件',
      detail: 'diff --git a/demo.txt b/demo.txt\n@@ -1 +1 @@\n-旧内容\n+安卓原生内容',
      runId: 'read-run',
    },
    {
      id: 'read-compaction',
      role: 'tool',
      kind: 'contextCompaction',
      text: '上下文已压缩',
      status: 'completed',
      runId: 'read-run',
    },
    {
      id: 'read-assistant',
      role: 'assistant',
      kind: 'agentMessage',
      text: markdown,
      status: 'completed',
      replyPhase: 'final_answer',
      runId: 'read-run',
    },
  ].map((message) => ({ files: [], ...message }));
  const server = createServer((request, response) => {
    const url = new URL(request.url, 'http://fixture.invalid');
    const record = {
      method: request.method,
      path: url.pathname,
      query: Object.fromEntries(url.searchParams),
      status: null,
    };
    requests.push(record);
    const send = (status, data, headers = {}) => {
      record.status = status;
      response.writeHead(status, { 'Content-Type': 'application/json', ...headers });
      response.end(JSON.stringify(data));
    };
    if (request.method !== 'GET') {
      send(405, { error: 'readonly_only' });
      return;
    }
    if (url.pathname === '/fixture' && options.simulator) {
      execFileSync('xcrun', [
        'simctl',
        'ui',
        options.simulator,
        'content_size',
        url.searchParams.get('font') === 'large' ? 'extra-extra-extra-large' : 'large',
      ]);
      send(200, {
        endpoint,
        hostId: target.hostId,
        threadId: 'thread-0',
        threadTitle: '只读界面验收',
        token: 'synthetic-native-read-fixture-token',
        nonce: 'native-read-ui',
        secondEndpoint: '',
        secondToken: '',
        secondHostId: '',
        delayedEndpoint: '',
      });
      return;
    }
    if (url.pathname === '/v1/workspace') {
      const more = url.searchParams.has('projectCursor') || url.searchParams.has('threadCursor');
      send(200, {
        hostId: target.hostId,
        name: '合成历史电脑',
        connectionMode: 'history-only',
        readTarget: target,
        projects: (more ? [3, 4, 5] : [0, 1, 2]).map(project),
        threads: (more ? [7, 8, 9] : [0, 1, 2, 3, 4, 5, 6]).map(thread),
        nextProjectCursor: more ? null : 'projects-next',
        nextThreadCursor: more ? null : 'threads-next',
        capabilities: ['projects:read', 'threads:read', 'history:read'],
      });
    } else if (url.pathname === '/v1/history') {
      const id = url.searchParams.get('threadId');
      if (id === 'redirect') {
        send(307, { error: 'redirect' }, { Location: 'http://127.0.0.1:1/never-follow' });
        return;
      }
      if (id === 'failure') {
        send(503, { error: 'synthetic_failure' });
        return;
      }
      if (id === 'malformed') {
        send(200, {
          hostId: target.hostId,
          threadId: id,
          messages: [{ id: 'bad', role: 'assistant', text: false }],
        });
        return;
      }
      if (id === 'wrong-target') {
        send(200, {
          hostId: 'wrong-host',
          threadId: id,
          messages: [],
          nextCursor: null,
          readTarget: { ...target, hostId: 'wrong-host' },
        });
        return;
      }
      if (url.searchParams.get('hostId') !== target.hostId) {
        send(400, { error: 'invalid_target' });
        return;
      }
      const older = url.searchParams.has('cursor');
      send(200, {
        hostId: target.hostId,
        threadId: id,
        readTarget: target,
        messages: older
          ? [
              {
                id: 'older-user',
                role: 'user',
                text: '更早的消息🙂',
                files: [],
                runId: 'older-run',
              },
              {
                id: 'older-reply',
                role: 'assistant',
                text: '更早的回复',
                files: [],
                status: 'completed',
                runId: 'older-run',
              },
            ]
          : messages,
        nextCursor: older ? null : 'earlier-page',
      });
    } else send(404, { error: 'unknown_read' });
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(options.port ?? 0, '127.0.0.1', resolve);
  });
  const port = server.address().port;
  endpoint = 'http://127.0.0.1:' + port;
  if (adb) execFileSync(adb, ['-s', serial, 'reverse', 'tcp:' + port, 'tcp:' + port]);
  return {
    endpoint,
    requests,
    async close() {
      if (adb) execFileSync(adb, ['-s', serial, 'reverse', '--remove', 'tcp:' + port]);
      server.closeAllConnections();
      await new Promise((resolve) => server.close(resolve));
    },
  };
}
