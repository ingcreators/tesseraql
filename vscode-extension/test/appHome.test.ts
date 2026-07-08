import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { test } from 'node:test';
import { findAppHomes, homeOf, isAppHome } from '../src/core/appHome';

function tempDir(): string {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'tql-ext-'));
}

function makeApp(dir: string): void {
  fs.mkdirSync(path.join(dir, 'config'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'config', 'tesseraql.yml'), 'app:\n  id: demo\n');
}

test('a workspace folder holding config/tesseraql.yml is an app home', () => {
  const root = tempDir();
  makeApp(root);
  assert.equal(isAppHome(root), true);
  assert.deepEqual(findAppHomes([root]), [root]);
});

test('direct child directories are discovered, junk directories skipped', () => {
  const root = tempDir();
  const app = path.join(root, 'shop');
  makeApp(app);
  makeApp(path.join(root, 'node_modules'));
  fs.mkdirSync(path.join(root, 'plain'));
  assert.deepEqual(findAppHomes([root]), [app]);
});

test('a folder without the marker is not an app home', () => {
  const root = tempDir();
  fs.mkdirSync(path.join(root, 'config'));
  assert.equal(isAppHome(root), false);
  assert.deepEqual(findAppHomes([root]), []);
});

test('homeOf maps files to their containing app home', () => {
  const root = tempDir();
  const app = path.join(root, 'shop');
  makeApp(app);
  assert.equal(homeOf(path.join(app, 'web', 'get.yml'), [app]), app);
  assert.equal(homeOf(path.join(root, 'elsewhere.yml'), [app]), undefined);
  assert.equal(homeOf(path.join(app + 'pping', 'web', 'get.yml'), [app]), undefined);
});
