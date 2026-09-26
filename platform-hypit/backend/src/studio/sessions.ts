/**
 * C107-12 studio/sessions.ts — Studio session registry (broker side).
 *
 * A studio session reuses the ACTIVE preview-session binding rules
 * (project/run/revision) and adds: single-use ticket URLs, one ticket per
 * session, and same-run active-session reuse (同 Run 默认复用活跃会话).
 * Sessions expire; expired ones are reaped lazily on open.
 */
import { randomUUID } from "node:crypto";
import { DispatchError } from "../commands/dispatcher.ts";
import { issueStudioTicket, verifyStudioTicket, type StudioTicket, type UrlPolicyOptions } from "./url-policy.ts";

export type StudioSession = {
  readonly id: string;
  readonly projectId: string;
  readonly runFile: string;
  readonly revision: number;
  readonly readOnly: boolean;
  readonly createdAt: number;
  readonly expiresAt: number;
  ticketUsed: boolean;
  state: "active" | "closed";
};

const sessions = new Map<string, StudioSession>();

export function openStudioSession(
  options: UrlPolicyOptions,
  input: {
    readonly projectId: string;
    readonly runFile?: string;
    readonly revision?: number;
    readonly readOnly?: boolean;
    readonly ttlSeconds?: number;
  },
): { session: StudioSession; ticket: StudioTicket; reused: boolean } {
  // Same Run defaults to reusing the active session (契约 §6.2: 同 Run 默认复用).
  for (const existing of sessions.values()) {
    if (existing.projectId === input.projectId && existing.runFile === (input.runFile ?? "main.svrun")
      && existing.state === "active" && existing.expiresAt > Date.now()) {
      return { session: existing, ticket: issueStudioTicket(options, existing.id), reused: true };
    }
  }
  const ttl = input.ttlSeconds ?? 3600;
  const session: StudioSession = {
    id: `st-${randomUUID()}`,
    projectId: input.projectId,
    runFile: input.runFile ?? "main.svrun",
    revision: input.revision ?? 0,
    readOnly: input.readOnly ?? false,
    createdAt: Date.now(),
    expiresAt: Date.now() + ttl * 1000,
    ticketUsed: false,
    state: "active",
  };
  sessions.set(session.id, session);
  return { session, ticket: issueStudioTicket(options, session.id), reused: false };
}

/** Ticket redemption: single-use, expiry-checked, session must be live. */
export function redeemStudioTicket(
  options: UrlPolicyOptions,
  sessionId: string,
  ticket: string,
): StudioSession {
  verifyStudioTicket(options, sessionId, ticket);
  const session = sessions.get(sessionId);
  if (session === undefined || session.state !== "active") {
    throw new DispatchError("not_found", "studio session is not active");
  }
  if (session.expiresAt <= Date.now()) {
    session.state = "closed";
    sessions.delete(sessionId);
    throw new DispatchError("invalid_input", "studio session expired");
  }
  if (session.ticketUsed) {
    throw new DispatchError("invalid_input", "studio ticket already used");
  }
  session.ticketUsed = true;
  return session;
}

export function closeStudioSession(sessionId: string): { closed: true } {
  const session = sessions.get(sessionId);
  if (session === undefined) throw new DispatchError("not_found", "unknown studio session");
  session.state = "closed";
  sessions.delete(sessionId);
  return { closed: true };
}
