import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { Workspace } from './workspace.model';

@Injectable({ providedIn: 'root' })
export class WorkspaceService {
  private readonly base = environment.apiUrl;

  constructor(private http: HttpClient) {}

  listWorkspaces(): Observable<Workspace[]> {
    return this.http.get<Workspace[]>(`${this.base}/workspaces`);
  }

  createWorkspace(name: string): Observable<Workspace> {
    return this.http.post<Workspace>(`${this.base}/workspaces`, { name });
  }

  deleteWorkspace(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/workspaces/${id}`);
  }
}
