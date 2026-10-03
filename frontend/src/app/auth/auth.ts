import { ChangeDetectorRef, Component } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from './auth.service';

@Component({
  selector: 'app-auth',
  imports: [FormsModule],
  templateUrl: './auth.html',
  styleUrl: './auth.css',
})
export class AuthComponent {
  mode: 'login' | 'register' = 'login';

  name = '';
  email = '';
  error = '';
  loading = false;

  constructor(
    private authService: AuthService,
    private router: Router,
    private cdr: ChangeDetectorRef,
  ) {}

  submit(): void {
    this.error = '';
    this.loading = true;

    const obs =
      this.mode === 'register'
        ? this.authService.register(this.name.trim(), this.email.trim())
        : this.authService.login(this.email.trim());

    obs.subscribe({
      next: () => {
        this.loading = false;
        this.router.navigate(['/workspaces']);
      },
      error: (err) => {
        this.loading = false;
        if (err.status === 409) {
          this.error = 'An account with this email already exists.';
        } else if (err.status === 404) {
          this.error = 'No account found with this email.';
        } else if (err.status === 400) {
          this.error = err.error?.message ?? 'Invalid request.';
        } else {
          this.error = 'Something went wrong. Please try again.';
        }
        this.cdr.detectChanges();
      },
    });
  }

  switchMode(): void {
    this.mode = this.mode === 'login' ? 'register' : 'login';
    this.error = '';
  }
}
