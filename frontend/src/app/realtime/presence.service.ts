import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { UserPresence } from './presence.model';

@Injectable({ providedIn: 'root' })
export class PresenceService {
  private readonly base = environment.apiUrl;

  constructor(private http: HttpClient) {}

  getPresence(workspaceId: string): Observable<UserPresence[]> {
    return this.http.get<UserPresence[]>(
      `${this.base}/workspaces/${workspaceId}/presence`,
    );
  }
}
