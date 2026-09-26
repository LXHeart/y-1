// credentials.ts — C107-07 (task-107) credential stores for provider secrets.
//
// K12.10: secrets live only in private stores — env is read-only, the file
// store enforces 0700/0600 upstream, the OS store uses the platform keychain,
// and the platform store routes darwin/win32 to the OS store and linux to the
// file store. This module opens the four native stores, resolves CredentialRefs
// for the trusted side only, and answers status questions without ever
// returning the secret itself (GET status, logs and exports stay clean).
import { installEngineResolution } from "../engine/hypit-bootstrap.ts";
import type {
  CompositeCredentialStore,
  CredentialRef,
  CredentialValue,
  WritableCredentialStore,
} from "@hypit/runtime";

export type CredentialSlotStatus = {
  readonly store: string;
  readonly key: string;
  /** false = nothing resolvable in that store for this ref (never why). */
  readonly configured: boolean;
  /** "oauth2" when the stored secret is an upstream OAuth envelope. */
  readonly credentialType: "api-key" | "oauth2" | "none";
  /** Expiry from the envelope when present; never the secret. */
  readonly expiresAt?: number;
};

export type CredentialStores = {
  readonly composite: CompositeCredentialStore;
  readonly stores: ReadonlyMap<string, WritableCredentialStore | { resolve(ref: CredentialRef): Promise<CredentialValue | undefined> }>;
  status(ref: CredentialRef): Promise<CredentialSlotStatus>;
  put(ref: CredentialRef, secret: string): Promise<void>;
  remove(ref: CredentialRef): Promise<boolean>;
  writable(ref: CredentialRef): boolean;
};

const OAUTH_ENVELOPE_PREFIX = "hypit.oauth2-credential@1";

type LoadedStores = {
  readonly FileCredentialStore: new (directory: string) => WritableCredentialStore;
  readonly OsCredentialStore: new (options?: { readonly service?: string }) => WritableCredentialStore;
  readonly PlatformCredentialStore: new (options: { readonly directory: string; readonly service?: string }) => WritableCredentialStore;
  readonly EnvironmentCredentialStore: new () => { resolve(ref: CredentialRef): Promise<CredentialValue | undefined> };
  readonly CompositeCredentialStore: new (stores: readonly unknown[]) => CompositeCredentialStore;
  readonly decodeOAuth2Credential: (secret: string) => { expiresAt?: number } | undefined;
};

let loadedStores: Promise<LoadedStores> | undefined;

async function loadStores(distributionRoot: string): Promise<LoadedStores> {
  if (loadedStores === undefined) {
    loadedStores = (async () => {
      await installEngineResolution(distributionRoot);
      const file = await import("@hypit/credential-store-file");
      const os = await import("@hypit/credential-store-os");
      const platform = await import("@hypit/credential-store-platform");
      const env = await import("@hypit/credential-store-env");
      const runtime = await import("@hypit/runtime");
      return {
        FileCredentialStore: file.FileCredentialStore,
        OsCredentialStore: os.OsCredentialStore,
        PlatformCredentialStore: platform.PlatformCredentialStore,
        EnvironmentCredentialStore: env.EnvironmentCredentialStore,
        CompositeCredentialStore: runtime.CompositeCredentialStore,
        decodeOAuth2Credential: runtime.decodeOAuth2Credential,
      } as LoadedStores;
    })();
  }
  return await loadedStores;
}

/**
 * Open the four native stores rooted under one host state directory.
 *
 * @param hostStateRoot private per-host state (the file store directory lives
 *        at `<hostStateRoot>/credentials`, matching the upstream default).
 */
export async function openCredentialStores(distributionRoot: string, hostStateRoot: string): Promise<CredentialStores> {
  const loaded = await loadStores(distributionRoot);
  const fileStore = new loaded.FileCredentialStore(`${hostStateRoot}/credentials`);
  const osStore = new loaded.OsCredentialStore();
  const platformStore = new loaded.PlatformCredentialStore({ directory: `${hostStateRoot}/credentials` });
  const envStore = new loaded.EnvironmentCredentialStore();
  const stores = new Map<string, WritableCredentialStore | { resolve(ref: CredentialRef): Promise<CredentialValue | undefined> }>([
    ["file", fileStore],
    ["os", osStore],
    ["platform", platformStore],
    ["env", envStore],
  ]);
  const writableByName = new Map<string, WritableCredentialStore>([
    ["file", fileStore],
    ["os", osStore],
    ["platform", platformStore],
  ]);
  const composite = new loaded.CompositeCredentialStore([fileStore, osStore, platformStore, envStore]);

  async function status(ref: CredentialRef): Promise<CredentialSlotStatus> {
    const store = stores.get(ref.store);
    if (store === undefined) {
      return { store: ref.store, key: ref.key, configured: false, credentialType: "none" };
    }
    let value: CredentialValue | undefined;
    try {
      value = await store.resolve(ref);
    } catch {
      // A store that cannot answer (locked keychain, unreadable file) is not
      // configured from the caller's perspective; the raw error stays in logs.
      value = undefined;
    }
    if (value === undefined) {
      return { store: ref.store, key: ref.key, configured: false, credentialType: "none" };
    }
    const envelope = value.secret.startsWith("{") && value.secret.includes(OAUTH_ENVELOPE_PREFIX)
      ? loaded.decodeOAuth2Credential(value.secret)
      : undefined;
    if (envelope !== undefined) {
      return {
        store: ref.store,
        key: ref.key,
        configured: true,
        credentialType: "oauth2",
        ...(envelope.expiresAt === undefined ? {} : { expiresAt: envelope.expiresAt }),
      };
    }
    return { store: ref.store, key: ref.key, configured: true, credentialType: "api-key" };
  }

  return {
    composite,
    stores,
    status,
    async put(ref: CredentialRef, secret: string): Promise<void> {
      const writableStore = writableByName.get(ref.store);
      if (writableStore === undefined) {
        throw new Error(`credential store "${ref.store}" is read-only here; use file/os/platform`);
      }
      await writableStore.put(ref, { secret });
    },
    async remove(ref: CredentialRef): Promise<boolean> {
      const writableStore = writableByName.get(ref.store);
      if (writableStore === undefined) {
        throw new Error(`credential store "${ref.store}" is read-only here; use file/os/platform`);
      }
      return await writableStore.delete(ref);
    },
    writable(ref: CredentialRef): boolean {
      return writableByName.has(ref.store);
    },
  };
}
