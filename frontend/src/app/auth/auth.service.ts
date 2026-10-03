import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { tap } from 'rxjs/operators';
import { environment } from '../../environments/environment';

export interface AuthResponse {
  token: string;
  user: { id: string; name: string; email: string };
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly TOKEN_KEY = 'snapland_dev_token';
  private readonly USER_KEY = 'snapland_user';

  constructor(private http: HttpClient) {}

  register(name: string, email: string): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>(`${environment.apiUrl}/auth/register`, { name, email })
      .pipe(tap((res) => { this.storeToken(res.token); this.storeUser(res.user); }));
  }

  login(email: string): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>(`${environment.apiUrl}/auth/login`, { email })
      .pipe(tap((res) => { this.storeToken(res.token); this.storeUser(res.user); }));
  }

  logout(): void {
    sessionStorage.removeItem(this.TOKEN_KEY);
    sessionStorage.removeItem(this.USER_KEY);
  }

  getToken(): string | null {
    return sessionStorage.getItem(this.TOKEN_KEY);
  }

  hasToken(): boolean {
    return !!this.getToken();
  }

  clearToken(): void {
    sessionStorage.removeItem(this.TOKEN_KEY);
  }

  getCurrentUserId(): string | null {
    const raw = sessionStorage.getItem(this.USER_KEY);
    if (!raw) return null;
    try {
      return JSON.parse(raw).id ?? null;
    } catch {
      return null;
    }
  }

  private storeToken(token: string): void {
    sessionStorage.setItem(this.TOKEN_KEY, token);
  }

  private storeUser(user: { id: string; name: string; email: string }): void {
    sessionStorage.setItem(this.USER_KEY, JSON.stringify(user));
  }
}
