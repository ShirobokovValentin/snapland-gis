import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import * as L from 'leaflet';
import { environment } from '../../environments/environment';
import { Area, AreaVersionResponse, CreateAreaRequest, DeletedArea, UpdateAreaRequest } from './area.model';

@Injectable({ providedIn: 'root' })
export class AreaService {
  private readonly base = environment.apiUrl;

  constructor(private http: HttpClient) {}

  createArea(workspaceId: string, name: string, latlngs: L.LatLng[]): Observable<Area> {
    const coordinates = this.toClosedRing(latlngs);
    const body: CreateAreaRequest = {
      name,
      geometry: { type: 'Polygon', coordinates: [coordinates] },
    };
    return this.http.post<Area>(
      `${this.base}/workspaces/${workspaceId}/areas`,
      body,
    );
  }

  updateArea(workspaceId: string, areaId: string, expectedVersion: number, name: string, latlngs: L.LatLng[]): Observable<Area> {
    const coordinates = this.toClosedRing(latlngs);
    const body: UpdateAreaRequest = {
      expectedVersion,
      name,
      geometry: { type: 'Polygon', coordinates: [coordinates] },
    };
    return this.http.put<Area>(
      `${this.base}/workspaces/${workspaceId}/areas/${areaId}`,
      body,
    );
  }

  deleteArea(workspaceId: string, areaId: string, expectedVersion: number): Observable<Area> {
    return this.http.delete<Area>(
      `${this.base}/workspaces/${workspaceId}/areas/${areaId}`,
      { params: { expectedVersion: expectedVersion.toString() } },
    );
  }

  restoreArea(workspaceId: string, areaId: string, targetVersion: number, expectedVersion: number): Observable<Area> {
    return this.http.post<Area>(
      `${this.base}/workspaces/${workspaceId}/areas/${areaId}/versions/${targetVersion}/restore`,
      { expectedVersion },
    );
  }

  getAreaVersions(workspaceId: string, areaId: string): Observable<AreaVersionResponse[]> {
    return this.http.get<AreaVersionResponse[]>(
      `${this.base}/workspaces/${workspaceId}/areas/${areaId}/versions`,
    );
  }

  getDeletedAreas(workspaceId: string): Observable<DeletedArea[]> {
    return this.http.get<DeletedArea[]>(
      `${this.base}/workspaces/${workspaceId}/areas/deleted`,
    );
  }

  permanentDeleteArea(workspaceId: string, areaId: string, expectedVersion: number): Observable<Area> {
    return this.http.delete<Area>(
      `${this.base}/workspaces/${workspaceId}/areas/${areaId}/permanent`,
      { params: { expectedVersion: expectedVersion.toString() } },
    );
  }

  getAreas(workspaceId: string, bounds: L.LatLngBounds): Observable<Area[]> {
    const bbox = `${bounds.getWest()},${bounds.getSouth()},${bounds.getEast()},${bounds.getNorth()}`;
    return this.http.get<Area[]>(
      `${this.base}/workspaces/${workspaceId}/areas`,
      { params: { bbox } },
    );
  }

  private toClosedRing(latlngs: L.LatLng[]): number[][] {
    const coords = latlngs.map((ll) => [ll.lng, ll.lat]);
    const first = coords[0];
    const last = coords[coords.length - 1];
    if (first[0] !== last[0] || first[1] !== last[1]) {
      coords.push([first[0], first[1]]);
    }
    return coords;
  }
}
