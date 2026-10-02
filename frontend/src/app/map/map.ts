import {
  AfterViewInit,
  ChangeDetectorRef,
  Component,
  ElementRef,
  OnDestroy,
  ViewChild,
} from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import * as L from 'leaflet';
import 'leaflet-draw';
import { AreaService } from '../area/area.service';
import { Area, AreaEventMessage, AreaVersionResponse } from '../area/area.model';
import { RealtimeService } from '../realtime/realtime.service';
import { environment } from '../../environments/environment';

@Component({
  selector: 'app-map',
  imports: [FormsModule, DecimalPipe, DatePipe],
  templateUrl: './map.html',
  styleUrl: './map.css',
})
export class MapComponent implements AfterViewInit, OnDestroy {
  @ViewChild('mapRef') mapRef!: ElementRef<HTMLDivElement>;

  areaName = '';
  previewAreaKm2 = 0;
  saving = false;
  pendingLayer: L.Polygon | null = null;

  editingArea: Area | null = null;
  editName = '';
  editLayer: L.Polygon | null = null;
  editSaving = false;
  deleteSaving = false;

  deletedArea: Area | null = null;
  restoreSaving = false;
  private deletedLayer: L.Polygon | null = null;

  editVersionWarning = false;

  historyArea: Area | null = null;
  historyVersions: AreaVersionResponse[] = [];
  historyLoading = false;
  historySaving = false;
  historyExpectedVersion = 0;
  selectedHistoryVersion: AreaVersionResponse | null = null;
  private previewLayer: L.Polygon | null = null;
  private previewItems!: L.FeatureGroup;

  private map!: L.Map;
  private drawnItems!: L.FeatureGroup;
  private editItems!: L.FeatureGroup;
  private persistedItems!: L.FeatureGroup;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private editHandler: any = null;

  constructor(
    private areaService: AreaService,
    private realtimeService: RealtimeService,
    private cdr: ChangeDetectorRef,
  ) {}

  ngAfterViewInit(): void {
    this.initMap();
    this.connectToRealtime();
  }

  ngOnDestroy(): void {
    this.editHandler?.disable();
    this.map?.remove();
    this.realtimeService.disconnect();
  }

  saveArea(): void {
    if (!this.pendingLayer || !this.areaName.trim()) return;

    const latlngs = (this.pendingLayer.getLatLngs() as L.LatLng[][])[0];
    this.saving = true;

    this.areaService.createArea(this.areaName.trim(), latlngs).subscribe({
      next: (area) => {
        this.saving = false;
        this.clearPending();
        this.addPersistedArea(area);
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.saving = false;
        console.error('Failed to save area', err);
        this.cdr.detectChanges();
        alert('Failed to save area. See console for details.');
      },
    });
  }

  cancelDraw(): void {
    this.clearPending();
  }

  saveEdit(): void {
    if (!this.editingArea || !this.editLayer || !this.editName.trim()) return;

    if (this.editHandler) {
      this.editHandler.save();
      this.editHandler.disable();
      this.editHandler = null;
    }

    const latlngs = (this.editLayer.getLatLngs() as L.LatLng[][])[0];
    this.editSaving = true;

    this.areaService
      .updateArea(this.editingArea.id, this.editingArea.currentVersion, this.editName.trim(), latlngs)
      .subscribe({
        next: (area) => {
          this.editSaving = false;
          this.clearEdit();
          this.addPersistedArea(area);
          this.cdr.detectChanges();
        },
        error: (err) => {
          this.editSaving = false;
          this.cdr.detectChanges();
          if (err.status === 409) {
            alert('This area was modified by another user. Reloading the latest version.');
            this.clearEdit();
            this.loadViewport();
          } else {
            console.error('Failed to update area', err);
            alert('Failed to update area. See console for details.');
          }
        },
      });
  }

  cancelEdit(): void {
    if (this.editHandler) {
      this.editHandler.revertLayers();
      this.editHandler.disable();
      this.editHandler = null;
    }
    this.clearEdit();
  }

  deleteArea(): void {
    if (!this.editingArea) return;

    const area = this.editingArea;
    if (this.editHandler) {
      this.editHandler.revertLayers();
      this.editHandler.disable();
      this.editHandler = null;
    }

    this.deleteSaving = true;
    this.areaService.deleteArea(area.id, area.currentVersion).subscribe({
      next: (deleted) => {
        this.deleteSaving = false;
        this.clearEdit();
        const coords = deleted.geometry.coordinates[0];
        const latlngs = coords.map((c) => L.latLng(c[1], c[0]));
        const grayPolygon = L.polygon(latlngs, { color: '#999999', weight: 2, opacity: 0.5, fillOpacity: 0.1 });
        this.persistedItems.addLayer(grayPolygon);
        this.deletedArea = deleted;
        this.deletedLayer = grayPolygon;
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.deleteSaving = false;
        this.cdr.detectChanges();
        if (err.status === 409) {
          alert('This area was modified by another user. Reloading the latest version.');
          this.clearEdit();
          this.loadViewport();
        } else {
          console.error('Failed to delete area', err);
          alert('Failed to delete area. See console for details.');
        }
      },
    });
  }

  restoreDeletedArea(): void {
    if (!this.deletedArea) return;

    const area = this.deletedArea;
    const targetVersion = area.currentVersion - 1;
    this.restoreSaving = true;

    this.areaService.restoreArea(area.id, targetVersion, area.currentVersion).subscribe({
      next: (restored) => {
        this.restoreSaving = false;
        this.clearDeletedArea();
        this.addPersistedArea(restored);
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.restoreSaving = false;
        this.cdr.detectChanges();
        if (err.status === 409) {
          alert('This area was modified by another user. Reloading the latest version.');
          this.clearDeletedArea();
          this.loadViewport();
        } else {
          console.error('Failed to restore area', err);
          alert('Failed to restore area. See console for details.');
        }
      },
    });
  }

  dismissDeleted(): void {
    this.clearDeletedArea();
  }

  openHistory(area: Area): void {
    this.clearPending();
    this.clearEdit();
    this.clearDeletedArea();
    this.historyArea = area;
    this.historyExpectedVersion = area.currentVersion;
    this.historyVersions = [];
    this.historyLoading = true;
    this.selectedHistoryVersion = null;
    this.cdr.detectChanges();

    this.areaService.getAreaVersions(area.id).subscribe({
      next: (versions) => {
        this.historyVersions = versions;
        this.historyLoading = false;
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.historyLoading = false;
        console.error('Failed to load version history', err);
        this.cdr.detectChanges();
        alert('Failed to load version history. See console for details.');
      },
    });
  }

  closeHistory(): void {
    this.clearHistory();
  }

  selectHistoryVersion(version: AreaVersionResponse): void {
    this.selectedHistoryVersion = version;
    this.clearPreview();
    const coords = version.geometry.coordinates[0];
    const latlngs = coords.map((c) => L.latLng(c[1], c[0]));
    this.previewLayer = L.polygon(latlngs, {
      color: '#ff9900',
      weight: 2,
      opacity: 0.8,
      dashArray: '6 4',
      fillOpacity: 0.15,
    });
    this.previewItems.addLayer(this.previewLayer);
    this.cdr.detectChanges();
  }

  restoreHistoricalVersion(version: AreaVersionResponse): void {
    if (!this.historyArea) return;
    const confirmed = confirm(
      `Restore to version ${version.version} ("${version.name}")?\n\n` +
      `This creates a new version of the area — previous history is preserved.`,
    );
    if (!confirmed) return;

    const areaId = this.historyArea.id;
    const expectedVersion = this.historyExpectedVersion;
    this.historySaving = true;
    this.cdr.detectChanges();

    this.areaService.restoreArea(areaId, version.version, expectedVersion).subscribe({
      next: () => {
        this.historySaving = false;
        this.clearHistory();
        this.loadViewport();
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.historySaving = false;
        this.cdr.detectChanges();
        if (err.status === 409) {
          alert('This area was modified by another user. Reloading the latest version.');
          this.clearHistory();
          this.loadViewport();
        } else {
          console.error('Failed to restore version', err);
          alert('Failed to restore version. See console for details.');
        }
      },
    });
  }

  private initMap(): void {
    this.map = L.map(this.mapRef.nativeElement, { doubleClickZoom: false }).setView([31.5, 34.8], 8);

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '© OpenStreetMap contributors',
    }).addTo(this.map);

    this.drawnItems = new L.FeatureGroup().addTo(this.map);
    this.editItems = new L.FeatureGroup().addTo(this.map);
    this.persistedItems = new L.FeatureGroup().addTo(this.map);
    this.previewItems = new L.FeatureGroup().addTo(this.map);

    const drawControl = new L.Control.Draw({
      draw: {
        polygon: {},
        polyline: false,
        rectangle: false,
        circle: false,
        marker: false,
        circlemarker: false,
      },
      edit: { featureGroup: this.editItems, edit: false, remove: false },
    });
    this.map.addControl(drawControl);

    this.map.on(L.Draw.Event.CREATED, (e: L.LeafletEvent) => {
      const event = e as L.DrawEvents.Created;
      const layer = event.layer as L.Polygon;
      this.drawnItems.clearLayers();
      this.drawnItems.addLayer(layer);
      const latlngs = (layer.getLatLngs() as L.LatLng[][])[0];
      this.pendingLayer = layer;
      this.areaName = '';
      this.previewAreaKm2 = computeGeodesicAreaKm2(latlngs);
      this.cdr.detectChanges();
    });

    this.map.on('moveend zoomend', () => this.loadViewport());
    this.loadViewport();
  }

  private loadViewport(): void {
    const bounds = this.map.getBounds();
    this.areaService.getAreas(bounds).subscribe({
      next: (areas) => {
        this.persistedItems.clearLayers();
        areas.forEach((area) => {
          if (area.id === this.editingArea?.id || area.id === this.deletedArea?.id) return;
          this.addPersistedArea(area);
        });
      },
      error: (err) => console.error('Failed to load areas', err),
    });
  }

  private connectToRealtime(): void {
    this.realtimeService.connect(
      environment.workspaceId,
      (event) => this.handleRealtimeEvent(event),
      () => this.loadViewport(),
    );
  }

  private handleRealtimeEvent(event: AreaEventMessage): void {
    if (event.type === 'AREA_UPDATED' && this.editingArea?.id === event.payload.id) {
      this.editVersionWarning = true;
      this.cdr.detectChanges();
    }
    this.loadViewport();
  }

  private addPersistedArea(area: Area): void {
    const coords = area.geometry.coordinates[0];
    const latlngs = coords.map((c) => L.latLng(c[1], c[0]));
    const polygon = L.polygon(latlngs, { color: '#0066cc', weight: 2 });

    const editBtn = `<button class="popup-edit-btn" data-area-id="${area.id}">Edit</button>`;
    const historyBtn = `<button class="popup-history-btn" data-area-id="${area.id}">History</button>`;
    polygon.bindPopup(
      `<strong>${area.name}</strong><br>${area.areaKm2.toFixed(3)} km²<br><div class="popup-actions">${editBtn}${historyBtn}</div>`,
    );

    polygon.on('popupopen', () => {
      const edit = document.querySelector<HTMLButtonElement>(
        `.popup-edit-btn[data-area-id="${area.id}"]`,
      );
      if (edit) {
        edit.onclick = () => {
          polygon.closePopup();
          this.startEdit(area, polygon);
        };
      }
      const history = document.querySelector<HTMLButtonElement>(
        `.popup-history-btn[data-area-id="${area.id}"]`,
      );
      if (history) {
        history.onclick = () => {
          polygon.closePopup();
          this.openHistory(area);
        };
      }
    });

    this.persistedItems.addLayer(polygon);
  }

  private startEdit(area: Area, polygon: L.Polygon): void {
    this.clearPending();
    this.clearDeletedArea();
    this.clearHistory();
    this.editItems.clearLayers();

    const coords = area.geometry.coordinates[0];
    const latlngs = coords.map((c) => L.latLng(c[1], c[0]));
    const editableLayer = L.polygon(latlngs, { color: '#ff6600', weight: 2 });

    this.editItems.addLayer(editableLayer);
    this.persistedItems.removeLayer(polygon);

    this.editingArea = area;
    this.editLayer = editableLayer;
    this.editName = area.name;

    this.editHandler = new (L as any).EditToolbar.Edit(this.map, {
      featureGroup: this.editItems,
    });
    this.editHandler.enable();

    this.cdr.detectChanges();
  }

  private clearPending(): void {
    this.drawnItems.clearLayers();
    this.pendingLayer = null;
    this.areaName = '';
    this.previewAreaKm2 = 0;
  }

  private clearEdit(): void {
    this.editItems.clearLayers();
    this.editingArea = null;
    this.editLayer = null;
    this.editName = '';
    this.editSaving = false;
    this.deleteSaving = false;
    this.editVersionWarning = false;
    this.cdr.detectChanges();
  }

  private clearDeletedArea(): void {
    if (this.deletedLayer) {
      this.persistedItems.removeLayer(this.deletedLayer);
      this.deletedLayer = null;
    }
    this.deletedArea = null;
    this.restoreSaving = false;
    this.cdr.detectChanges();
  }

  private clearPreview(): void {
    if (this.previewLayer) {
      this.previewItems.removeLayer(this.previewLayer);
      this.previewLayer = null;
    }
  }

  private clearHistory(): void {
    this.clearPreview();
    this.historyArea = null;
    this.historyVersions = [];
    this.historyLoading = false;
    this.historySaving = false;
    this.historyExpectedVersion = 0;
    this.selectedHistoryVersion = null;
    this.cdr.detectChanges();
  }
}

function computeGeodesicAreaKm2(latlngs: L.LatLng[]): number {
  const R = 6371.0;
  const n = latlngs.length;
  if (n < 3) return 0;
  let area = 0;
  for (let i = 0; i < n; i++) {
    const j = (i + 1) % n;
    const lon1 = (latlngs[i].lng * Math.PI) / 180;
    const lon2 = (latlngs[j].lng * Math.PI) / 180;
    const lat1 = (latlngs[i].lat * Math.PI) / 180;
    const lat2 = (latlngs[j].lat * Math.PI) / 180;
    area += (lon2 - lon1) * (Math.sin(lat1) + Math.sin(lat2));
  }
  return Math.abs((area * R * R) / 2);
}
