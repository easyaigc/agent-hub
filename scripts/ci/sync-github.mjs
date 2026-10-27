import { execFileSync } from 'node:child_process';

const args = process.argv.slice(2);
const dryRun = args.includes('--dry-run');
function option(name, fallback) {
  const index = args.indexOf(name);
  if (index < 0) return fallback;
  if (!args[index + 1] || args[index + 1].startsWith('--')) {
    throw new Error(`Missing value for ${name}`);
  }
  return args[index + 1];
}
const target = option('--target-url', 'git@github.com:easyaigc/agent-hub.git');
const sourceRef = option('--source-ref', 'refs/remotes/origin/main');
const git = (...arguments_) => execFileSync('git', arguments_, { encoding: 'utf8' }).trim();

// Fetch only the public parent; never push the private source's commit graph.
git('fetch', '--no-tags', target, 'refs/heads/main');
const parent = git('rev-parse', 'FETCH_HEAD^{commit}');
const source = git('rev-parse', '--verify', `${sourceRef}^{commit}`);
const tree = git('rev-parse', `${source}^{tree}`);
const previousSource = git('show', '-s', '--format=%B', parent).match(
  /^Gitea-Source-Commit: ([a-f0-9]{40})$/m,
)?.[1];
if (previousSource) {
  try {
    git('merge-base', '--is-ancestor', previousSource, source);
  } catch {
    throw new Error('Refusing to publish an older or unrelated source revision');
  }
}
if (tree === git('rev-parse', `${parent}^{tree}`)) {
  console.log(JSON.stringify({ status: 'unchanged', source, target: parent, tree }));
} else if (dryRun) {
  console.log(JSON.stringify({ status: 'preview', source, parent, tree }));
} else {
  const commit = execFileSync('git', ['commit-tree', tree, '-p', parent], {
    encoding: 'utf8',
    input: `chore: sync source from Gitea main\n\nGitea-Source-Commit: ${source}\n`,
    env: {
      ...process.env,
      GIT_AUTHOR_NAME: 'Agent Hub Sync',
      GIT_AUTHOR_EMAIL: 'agent-hub-sync@users.noreply.github.com',
      GIT_COMMITTER_NAME: 'Agent Hub Sync',
      GIT_COMMITTER_EMAIL: 'agent-hub-sync@users.noreply.github.com',
    },
  }).trim();
  // A concurrent destination update fails a normal push; no force or replay.
  git('push', '--porcelain', target, `${commit}:refs/heads/main`);
  const actual = git('ls-remote', target, 'refs/heads/main').split(/\s/)[0];
  if (actual !== commit) throw new Error('Destination changed during verification');
  console.log(JSON.stringify({ status: 'published', source, target: commit, tree }));
}
