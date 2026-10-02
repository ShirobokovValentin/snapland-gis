import { Routes } from '@angular/router';
import { MapComponent } from './map/map';
import { AuthComponent } from './auth/auth';
import { authGuard } from './auth/auth.guard';

export const routes: Routes = [
  { path: '', redirectTo: '/map', pathMatch: 'full' },
  { path: 'auth', component: AuthComponent },
  { path: 'map', component: MapComponent, canActivate: [authGuard] },
];
