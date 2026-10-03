import { Routes } from '@angular/router';
import { MapComponent } from './map/map';
import { AuthComponent } from './auth/auth';
import { WorkspaceListComponent } from './workspace/workspace-list/workspace-list';
import { authGuard } from './auth/auth.guard';

export const routes: Routes = [
  { path: '', redirectTo: '/workspaces', pathMatch: 'full' },
  { path: 'auth', component: AuthComponent },
  { path: 'workspaces', component: WorkspaceListComponent, canActivate: [authGuard] },
  { path: 'workspaces/:workspaceId/map', component: MapComponent, canActivate: [authGuard] },
];
