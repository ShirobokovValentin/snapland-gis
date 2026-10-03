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

export interface AreaVersionResponse {
  id: string;
  version: number;
  name: string;
  geometry: GeoJsonGeometry;
  areaKm2: number;
  status: 'ACTIVE' | 'DELETED';
  changeType: 'CREATED' | 'UPDATED' | 'DELETED' | 'RESTORED';
  createdBy: string;
  createdAt: string;
}

export interface DeletedArea {
  id: string;
  workspaceId: string;
  name: string;
  currentVersion: number;
  deletedAt: string;
}

export interface AreaEventMessage {
  type: 'AREA_CREATED' | 'AREA_UPDATED' | 'AREA_DELETED' | 'AREA_RESTORED' | 'AREA_PERMANENTLY_DELETED';
  workspaceId: string;
  timestamp: string;
  payload: Area;
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
