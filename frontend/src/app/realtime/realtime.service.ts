import { Injectable } from '@angular/core';
import { Client } from '@stomp/stompjs';
import { AreaEventMessage } from '../area/area.model';
import { PresenceEventMessage } from './presence.model';
import { environment } from '../../environments/environment';
import { AuthService } from '../auth/auth.service';

@Injectable({ providedIn: 'root' })
export class RealtimeService {
  private client: Client | null = null;
  private onOffline: (() => void) | null = null;

  constructor(private authService: AuthService) {}

  connect(
    workspaceId: string,
    onEvent: (event: AreaEventMessage) => void,
    onReconnect: () => void,
    onStatusChange: (status: 'connected' | 'reconnecting') => void,
    onPresenceEvent: (event: PresenceEventMessage) => void,
  ): void {
    this.onOffline = () => onStatusChange('reconnecting');
    window.addEventListener('offline', this.onOffline);

    let firstConnect = true;
    this.client = new Client({
      brokerURL: environment.wsUrl,
      reconnectDelay: 3000,
      heartbeatIncoming: 5000,
      heartbeatOutgoing: 5000,
      beforeConnect: () => {
        const token = this.authService.getToken();
        if (!token) {
          // Token was cleared (e.g. by the REST 401 interceptor after the JWT
          // expired, or by logout). Defer deactivation so we do not call
          // deactivate() synchronously inside the beforeConnect callback.
          setTimeout(() => this.client?.deactivate(), 0);
          return;
        }
        // Refresh connectHeaders with the current token before every
        // connection attempt so reconnects always use the live JWT.
        this.client!.connectHeaders = { Authorization: `Bearer ${token}`, 'workspace-id': workspaceId };
      },
      onConnect: () => {
        this.client!.subscribe(`/topic/workspace/${workspaceId}`, (message) => {
          const event: AreaEventMessage = JSON.parse(message.body);
          onEvent(event);
        });
        this.client!.subscribe(`/topic/workspace/${workspaceId}/presence`, (message) => {
          const event: PresenceEventMessage = JSON.parse(message.body);
          onPresenceEvent(event);
        });
        onStatusChange('connected');
        if (!firstConnect) {
          onReconnect();
        }
        firstConnect = false;
      },
      onWebSocketClose: () => {
        // Only signal reconnecting if the client is still active.
        // When disconnect() is called intentionally, this.client is set to
        // null before the WebSocket close event fires, so the guard below
        // prevents a spurious 'reconnecting' status update.
        if (this.client !== null) {
          onStatusChange('reconnecting');
        }
      },
      onStompError: (frame) => {
        console.error('STOMP protocol error', frame.headers['message'], frame.body);
      },
    });
    this.client.activate();
  }

  disconnect(): void {
    if (this.onOffline) {
      window.removeEventListener('offline', this.onOffline);
      this.onOffline = null;
    }
    this.client?.deactivate();
    this.client = null;
  }
}
