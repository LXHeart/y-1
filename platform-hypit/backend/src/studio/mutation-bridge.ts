/**
 * C107-12 studio/mutation-bridge.ts — server-side write-back relay (patch
 * 0002's consumer). The broker submits Studio mutations on behalf of an
 * authorized session using the per-session bridge token; the relay is
 * bounded (body size, rate), revision-checked (baseRevision must match the
 * session snapshot) and refuses mutations for read-only sessions.
 */
import { DispatchError } from "../commands/dispatcher.ts";

export type MutationRequest = {
  readonly sessionId: string;
  readonly revision: number;
  readonly baseRevision: number;
  readonly readOnly: boolean;
  readonly operations: readonly unknown[];
};

const MAX_OPERATIONS = 200;
const MAX_BYTES_PER_OPERATION = 256 * 1024;

export function assertBridgeMutation(request: MutationRequest): void {
  if (request.readOnly) {
    throw new DispatchError("invalid_input", "read-only sessions cannot mutate");
  }
  if (!Number.isSafeInteger(request.revision) || !Number.isSafeInteger(request.baseRevision)) {
    throw new DispatchError("invalid_input", "mutation needs integer revisions");
  }
  if (request.revision !== request.baseRevision) {
    // Same contract as upstream commitMutation: the Source changed outside Studio.
    throw new DispatchError("revision_conflict", "the Source changed outside Studio");
  }
  if (!Array.isArray(request.operations) || request.operations.length === 0
    || request.operations.length > MAX_OPERATIONS) {
    throw new DispatchError("invalid_input", `mutations need 1..${MAX_OPERATIONS} operations`);
  }
  for (const operation of request.operations) {
    const size = JSON.stringify(operation)?.length ?? 0;
    if (size > MAX_BYTES_PER_OPERATION) {
      throw new DispatchError("invalid_input", "mutation operation exceeds the byte budget");
    }
  }
}

/** Authorization header for the relayed call (bridge token, never logged). */
export function bridgeAuthorization(bridgeToken: string): string {
  if (bridgeToken.length < 32) {
    throw new DispatchError("invalid_input", "bridge token must be at least 32 characters");
  }
  return `Bearer ${bridgeToken}`;
}
