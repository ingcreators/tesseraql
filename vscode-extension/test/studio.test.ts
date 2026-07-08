import assert from 'node:assert/strict';
import { test } from 'node:test';
import { studioSourceUrl } from '../src/core/studio';

test('studio source links address the app-relative path', () => {
  assert.equal(studioSourceUrl('http://localhost:8080', 'web/items/get.yml'),
      'http://localhost:8080/_tesseraql/studio/ui/source?path=web%2Fitems%2Fget.yml');
});

test('trailing slashes on the base URL collapse', () => {
  assert.equal(studioSourceUrl('http://localhost:8080//', 'web/get.yml'),
      'http://localhost:8080/_tesseraql/studio/ui/source?path=web%2Fget.yml');
});
