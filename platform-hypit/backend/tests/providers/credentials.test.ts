// credentials.test.ts — C107-07 (task-107) credential store surface (K12.10/TC107-07-03).
//
// env is read-only, the file store enforces its private modes upstream and
// answers resolve/put/delete, status never exposes the secret, and unknown
// stores report unconfigured. The OS keychain is never touched here: only the
// file/env stores run real assertions; os/platform membership is structural.
import { strict as assert } from "node:assert";
import { mkdtempSync, readdirSync, readFileSync, rmSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { openCredentialStores } from "../../src/providers/credentials.ts";

const distributionRoot = join(import.meta.dirname, "../../../.generated/hypit");

test("credentials: file store put/resolve/delete with upstream 0700/0600 enforcement", async () => {
  const hostRoot = mkdtempSync(join(tmpdir(), "hypit-cred-"));
  try {
    const stores = await openCredentialStores(distributionRoot, hostRoot);
    const ref = { store: "file", key: "hypihub.default.apiKey" };
    assert.equal((await stores.status(ref)).configured, false, "empty store reports unconfigured");

    await stores.put(ref, "secret-value-1");
    const status = await stores.status(ref);
    assert.equal(status.configured, true);
    assert.equal(status.credentialType, "api-key");
    assert.ok(!JSON.stringify(status).includes("secret-value-1"), "status never carries the secret");

    const resolved = await stores.composite.resolve(ref);
    assert.equal(resolved?.secret, "secret-value-1", "composite resolves through the file store");

    const directory = join(hostRoot, "credentials");
    const dirStat = statSync(directory);
    assert.equal(dirStat.mode & 0o077, 0, "credentials directory is owner-private (0700)");
    const storedFiles = readdirSync(directory).filter((name) => !name.startsWith("."));
    assert.equal(storedFiles.length, 1, "one file per key");
    const fileStat = statSync(join(directory, storedFiles[0]!));
    assert.equal(fileStat.mode & 0o077, 0, "credential file is owner-only (0600)");
    const envelope = JSON.parse(readFileSync(join(directory, storedFiles[0]!), "utf8")) as Record<string, unknown>;
    assert.equal(typeof envelope.secret, "string", "upstream file envelope shape");

    assert.equal(await stores.remove(ref), true);
    assert.equal((await stores.status(ref)).configured, false);
    assert.equal(await stores.remove(ref), false, "second delete is an honest false");
  } finally {
    rmSync(hostRoot, { recursive: true, force: true });
  }
});

test("credentials: env store resolves read-only; writes are refused", async (t) => {
  const hostRoot = mkdtempSync(join(tmpdir(), "hypit-cred-"));
  try {
    const stores = await openCredentialStores(distributionRoot, hostRoot);
    const name = `HYPIT_TEST_CRED_${String(Date.now())}`;
    process.env[name] = "env-secret";
    t.after(() => {
      delete process.env[name];
    });
    const ref = { store: "env", key: name };
    const status = await stores.status(ref);
    assert.equal(status.configured, true);
    assert.equal(status.credentialType, "api-key");
    assert.equal(stores.writable(ref), false, "env is never writable through this surface");
    await assert.rejects(() => stores.put(ref, "x"), /read-only/u);
  } finally {
    rmSync(hostRoot, { recursive: true, force: true });
  }
});

test("credentials: oauth envelopes report credentialType oauth2 with expiry, secret stays hidden", async () => {
  const hostRoot = mkdtempSync(join(tmpdir(), "hypit-cred-"));
  try {
    const stores = await openCredentialStores(distributionRoot, hostRoot);
    const ref = { store: "file", key: "hypihub.default.oauth" };
    const envelope = JSON.stringify({
      format: "hypit.oauth2-credential@1",
      accessToken: "access-token-value", // secret-scan: allow
      refreshToken: "refresh-token-value", // secret-scan: allow
      expiresAt: 4102444800000,
    });
    await stores.put(ref, envelope);
    const status = await stores.status(ref);
    assert.equal(status.credentialType, "oauth2");
    assert.equal(status.expiresAt, 4102444800000);
    assert.ok(!JSON.stringify(status).includes("access-token-value"));
  } finally {
    rmSync(hostRoot, { recursive: true, force: true });
  }
});

test("credentials: unknown store names report unconfigured without throwing", async () => {
  const hostRoot = mkdtempSync(join(tmpdir(), "hypit-cred-"));
  try {
    const stores = await openCredentialStores(distributionRoot, hostRoot);
    const status = await stores.status({ store: "nope", key: "x" });
    assert.equal(status.configured, false);
    assert.equal(status.credentialType, "none");
  } finally {
    rmSync(hostRoot, { recursive: true, force: true });
  }
});
