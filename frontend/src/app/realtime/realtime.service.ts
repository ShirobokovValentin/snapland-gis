import { Injectable } from '@angular/core';
import { Client } from '@stomp/stompjs';
import { AreaEventMessage } from '../area/area.model';
import { environment } from '../../environments/environment';

@Injectable({ providedIn: 'root' })
export class RealtimeService {
  private client: Client | null = null;

  connect(
    workspaceId: string,
    onEvent: (event: AreaEventMessage) => void,
    onReconnect: () => void,
  ): void {
    let firstConnect = true;
    this.client = new Client({
      brokerURL: environment.wsUrl,
      reconnectDelay: 3000,
      onConnect: () => {
        this.client!.subscribe(`/topic/workspace/${workspaceId}`, (message) => {
          const event: AreaEventMessage = JSON.parse(message.body);
          onEvent(event);
        });
        if (!firstConnect) {
          onReconnect();
        }
        firstConnect = false;
      },
    });
    this.client.activate();
  }

  disconnect(): void {
    this.client?.deactivate();
    this.client = null;
  }
}
