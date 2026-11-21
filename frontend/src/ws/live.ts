/**
 * Live updates: STOMP over plain WebSocket at `/ws` (no SockJS); the UI only subscribes.
 *   /topic/alerts/{teamId} — AlertSummary on create and every change (Role 2)
 *   /topic/unrouted        — AlertSummary of UNROUTED alerts (Role 2)
 *   /topic/simulator       — Delivery, every delivery attempt (Role 1)
 * Subscriptions are kept in a registry and re-created after every reconnect.
 * When logged in, CONNECT carries `Authorization: Bearer …` (read fresh on every (re)connect); a login or logout
 * reconnects with the new identity.
 */
import { Client, type StompSubscription } from '@stomp/stompjs';
import { session } from '../auth/session';

export type LiveStatus = 'connecting' | 'live' | 'down';
type Handler = (body: unknown) => void;

interface Entry { dest: string; handler: Handler; sub: StompSubscription | null }

class LiveClient {
  private client: Client | null = null;
  private entries = new Set<Entry>();
  private statusListeners = new Set<(s: LiveStatus) => void>();
  private _status: LiveStatus = 'connecting';
  private connectFailed = false;

  get status() { return this._status; }

  private setStatus(s: LiveStatus) {
    this._status = s;
    this.statusListeners.forEach(l => l(s));
  }

  onStatus(l: (s: LiveStatus) => void): () => void {
    this.statusListeners.add(l);
    l(this._status);
    return () => this.statusListeners.delete(l);
  }

  start() {
    if (this.client) return;
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    this.client = new Client({
      brokerURL: `${proto}://${location.host}/ws`,
      reconnectDelay: 3000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      beforeConnect: async () => {
        if (!this.client) return;
        // an expired access token would make CONNECT fail; refresh first when logged in
        if (session.loggedIn && this.connectFailed) await session.refresh();
        this.client.connectHeaders = session.accessToken ? { Authorization: `Bearer ${session.accessToken}` } : {};
        this.connectFailed = false;
      },
      onConnect: () => {
        this.entries.forEach(e => this.attach(e));
        this.setStatus('live');
      },
      onWebSocketClose: () => {
        this.entries.forEach(e => { e.sub = null; });
        this.setStatus(this._status === 'live' ? 'connecting' : 'down');
      },
      onStompError: () => {
        this.connectFailed = true;
        this.setStatus('down');
      },
    });
    this.client.activate();
  }

  /** Reconnect, e.g. after login or logout, so the server sees the current identity. */
  restart() {
    const old = this.client;
    this.client = null;
    if (old) void old.deactivate();
    this.start();
  }

  private attach(e: Entry) {
    if (!this.client?.connected) return;
    e.sub = this.client.subscribe(e.dest, msg => {
      try { e.handler(JSON.parse(msg.body)); } catch { /* ignore malformed frames */ }
    });
  }

  subscribe(dest: string, handler: Handler): () => void {
    const e: Entry = { dest, handler, sub: null };
    this.entries.add(e);
    this.attach(e);
    return () => {
      this.entries.delete(e);
      try { e.sub?.unsubscribe(); } catch { /* connection already gone */ }
    };
  }
}

export const live = new LiveClient();
