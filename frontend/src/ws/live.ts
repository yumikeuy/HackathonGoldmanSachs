/**
 * Live updates: STOMP over plain WebSocket at `/ws` (no SockJS); the UI only subscribes.
 *   /topic/alerts/{teamId} — AlertSummary on create and every change (Role 2)
 *   /topic/unrouted        — AlertSummary of UNROUTED alerts (Role 2)
 *   /topic/simulator       — Delivery, every delivery attempt (Role 1)
 * Subscriptions are kept in a registry and re-created after every reconnect.
 */
import { Client, type StompSubscription } from '@stomp/stompjs';

export type LiveStatus = 'connecting' | 'live' | 'down';
type Handler = (body: unknown) => void;

interface Entry { dest: string; handler: Handler; sub: StompSubscription | null }

class LiveClient {
  private client: Client | null = null;
  private entries = new Set<Entry>();
  private statusListeners = new Set<(s: LiveStatus) => void>();
  private _status: LiveStatus = 'connecting';

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
      onConnect: () => {
        this.entries.forEach(e => this.attach(e));
        this.setStatus('live');
      },
      onWebSocketClose: () => {
        this.entries.forEach(e => { e.sub = null; });
        this.setStatus(this._status === 'live' ? 'connecting' : 'down');
      },
      onStompError: () => this.setStatus('down'),
    });
    this.client.activate();
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
