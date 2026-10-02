export interface GeoJsonGeometry {
  type: 'Polygon';
  coordinates: number[][][];
}

export interface Area {
  id: string;
  workspaceId: string;
  name: string;
  geometry: GeoJsonGeometry;
  areaKm2: number;
  status: 'ACTIVE' | 'DELETED';
  currentVersion: number;
  createdAt: string;
}

export interface CreateAreaRequest {
  name: string;
  geometry: GeoJsonGeometry;
}

export interface UpdateAreaRequest {
  expectedVersion: number;
  name: string;
  geometry: GeoJsonGeometry;
}
