import { Component, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { WorkspaceService } from '../workspace.service';
import { AuthService } from '../../auth/auth.service';
import { Workspace } from '../workspace.model';

@Component({
  selector: 'app-workspace-list',
  imports: [FormsModule],
  templateUrl: './workspace-list.html',
  styleUrl: './workspace-list.css',
})
export class WorkspaceListComponent implements OnInit {
  workspaces = signal<Workspace[]>([]);
  loading = signal(false);
  error = signal('');

  newWorkspaceName = signal('');
  creating = signal(false);
  createError = signal('');

  deletingId = signal<string | null>(null);

  constructor(
    private workspaceService: WorkspaceService,
    private authService: AuthService,
    private router: Router,
  ) {}

  ngOnInit(): void {
    this.load();
  }

  get currentUserId(): string | null {
    return this.authService.getCurrentUserId();
  }

  load(): void {
    this.loading.set(true);
    this.error.set('');
    this.workspaceService.listWorkspaces().subscribe({
      next: (ws) => {
        this.workspaces.set(ws);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Failed to load workspaces.');
      },
    });
  }

  openWorkspace(id: string): void {
    this.router.navigate(['/workspaces', id, 'map']);
  }

  createWorkspace(): void {
    const name = this.newWorkspaceName().trim();
    if (!name) return;

    this.creating.set(true);
    this.createError.set('');
    this.workspaceService.createWorkspace(name).subscribe({
      next: (ws) => {
        this.workspaces.update((list) => [ws, ...list]);
        this.newWorkspaceName.set('');
        this.creating.set(false);
      },
      error: (err) => {
        this.creating.set(false);
        if (err.status === 400) {
          this.createError.set(err.error?.message ?? 'Invalid workspace name.');
        } else {
          this.createError.set('Failed to create workspace.');
        }
      },
    });
  }

  deleteWorkspace(ws: Workspace): void {
    const confirmed = confirm(`Delete workspace "${ws.name}"?\n\nThis will permanently delete all areas and their history.`);
    if (!confirmed) return;

    this.deletingId.set(ws.id);
    this.workspaceService.deleteWorkspace(ws.id).subscribe({
      next: () => {
        this.workspaces.update((list) => list.filter((w) => w.id !== ws.id));
        this.deletingId.set(null);
      },
      error: (err) => {
        this.deletingId.set(null);
        if (err.status === 403) {
          alert('You are not allowed to delete this workspace.');
        } else if (err.status === 404) {
          this.workspaces.update((list) => list.filter((w) => w.id !== ws.id));
        } else {
          alert('Failed to delete workspace.');
        }
      },
    });
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/auth']);
  }
}
