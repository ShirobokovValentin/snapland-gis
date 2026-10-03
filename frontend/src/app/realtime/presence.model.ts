export interface UserPresence {
  id: string;
  name: string;
}

export interface PresenceEventMessage {
  type: 'PRESENCE_JOINED' | 'PRESENCE_LEFT';
  user: UserPresence;
}
