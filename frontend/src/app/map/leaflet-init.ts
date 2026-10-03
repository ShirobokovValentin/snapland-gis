// esbuild (used by Angular's production build) creates the L namespace by
// snapshotting leaflet's module.exports at the point where
// `import * as L from 'leaflet'` appears in map.ts.  leaflet-draw is a plain
// IIFE that adds Draw, Control.Draw, etc. to window.L (the same exports
// object) at runtime.  If the snapshot is taken before the IIFE runs, Draw
// is absent from the namespace and L.Draw is undefined.
//
// Importing this shim as the *first* import in map.ts forces esbuild to run
// both leaflet (which sets window.L = exports) and leaflet-draw (which
// augments that object) as *dependencies* of map.ts — i.e., before map.ts's
// own initialisation code, where the namespace binding is created.  By then
// exports.Draw already exists, so esbuild's __toESM helper includes a getter
// for it in the namespace.
import 'leaflet';
import 'leaflet-draw';
