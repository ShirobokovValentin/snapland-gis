import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { tap, map } from 'rxjs/operators';
import { environment } from '../../environments/environment';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly TOKEN_KEY = 'snapland_dev_token';

  constructor(private http: HttpClient) {}

  bootstrap(): Observable<void> {
    if (localStorage.getItem(this.TOKEN_KEY)) return of(undefined);
    return this.http
      .post<{ token: string }>(`${environment.apiUrl}/dev/token`, {})
      .pipe(
        tap(({ token }) => localStorage.setItem(this.TOKEN_KEY, token)),
        map(() => undefined),
      );
  }

  getToken(): string | null {
    return localStorage.getItem(this.TOKEN_KEY);
  }
}
