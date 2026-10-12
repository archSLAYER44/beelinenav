package dev.guts.beelinenav;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Mapbox Vector Tile reader, enough to pull road lines out of the OpenMapTiles
 * "transportation" layer that Ola Maps serves. Hand-rolled protobuf: no libraries.
 */
final class Mvt {
    static final class Road {
        final int level;          // 1 main roads, 2 secondary/tertiary, 3 minor streets, 4 service lanes
        final double[] lat, lon;  // degrees

        Road(int level, double[] lat, double[] lon) {
            this.level = level;
            this.lat = lat;
            this.lon = lon;
        }
    }

    /** Roads in one tile; z, x, y locate the tile for the coordinate conversion. */
    static List<Road> roads(byte[] tile, int z, int x, int y) {
        List<Road> out = new ArrayList<>();
        Reader t = new Reader(tile, 0, tile.length);
        while (t.more()) {
            int tag = t.varint();
            if (tag == (3 << 3 | 2)) readLayer(t.sub(), z, x, y, out);   // Tile.layers
            else t.skip(tag & 7);
        }
        return out;
    }

    private static void readLayer(Reader l, int z, int x, int y, List<Road> out) {
        String name = "";
        List<String> keys = new ArrayList<>(), values = new ArrayList<>();
        List<Reader> features = new ArrayList<>();
        int extent = 4096;
        while (l.more()) {
            int tag = l.varint();
            switch (tag >>> 3) {
                case 1: name = l.string(); break;
                case 2: features.add(l.sub()); break;
                case 3: keys.add(l.string()); break;
                case 4: values.add(value(l.sub())); break;
                case 5: extent = l.varint(); break;
                default: l.skip(tag & 7);
            }
        }
        if (!name.equals("transportation")) return;
        for (Reader f : features) readFeature(f, keys, values, extent, z, x, y, out);
    }

    private static void readFeature(Reader f, List<String> keys, List<String> values, int extent,
                                    int z, int x, int y, List<Road> out) {
        int type = 0;
        int[] tags = null, geom = null;
        while (f.more()) {
            int tag = f.varint();
            switch (tag >>> 3) {
                case 2: tags = f.packed(); break;
                case 3: type = f.varint(); break;
                case 4: geom = f.packed(); break;
                default: f.skip(tag & 7);
            }
        }
        if (type != 2 || geom == null || tags == null) return;   // lines only
        int level = 0;
        for (int i = 0; i + 1 < tags.length; i += 2) {
            if (tags[i] < keys.size() && tags[i + 1] < values.size() && keys.get(tags[i]).equals("class")) {
                level = level(values.get(tags[i + 1]));
            }
        }
        if (level == 0) return;

        // Geometry commands: MoveTo (1) starts a line, LineTo (2) extends it; coordinates are zigzag deltas
        double n = 1 << z;
        List<double[]> lat = new ArrayList<>(), lon = new ArrayList<>();
        long cx = 0, cy = 0;
        for (int i = 0; i < geom.length; ) {
            int cmd = geom[i] & 7, count = geom[i] >>> 3;
            i++;
            if (cmd == 1) {
                lat.add(new double[0]);
                lon.add(new double[0]);
            }
            if (cmd != 1 && cmd != 2) continue;
            for (int k = 0; k < count && i + 1 < geom.length; k++, i += 2) {
                cx += zigzag(geom[i]);
                cy += zigzag(geom[i + 1]);
                double fx = (x + (double) cx / extent) / n, fy = (y + (double) cy / extent) / n;
                int last = lat.size() - 1;
                if (last < 0) continue;
                lat.set(last, append(lat.get(last), Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * fy))))));
                lon.set(last, append(lon.get(last), fx * 360 - 180));
            }
        }
        for (int k = 0; k < lat.size(); k++) {
            if (lat.get(k).length >= 2) out.add(new Road(level, lat.get(k), lon.get(k)));
        }
    }

    private static int level(String cls) {
        if (cls == null) return 0;
        switch (cls) {
            case "motorway": case "trunk": case "primary":
            case "motorway_link": case "trunk_link": case "primary_link": return 1;
            case "secondary": case "tertiary": case "secondary_link": case "tertiary_link": return 2;
            case "minor": case "residential": case "unclassified": case "living_street": case "busway": return 3;
            case "service": return 4;
            default: return 0;   // paths, tracks, rail, ferries, piers
        }
    }

    private static String value(Reader v) {
        String s = null;
        while (v.more()) {
            int tag = v.varint();
            if (tag >>> 3 == 1 && (tag & 7) == 2) s = v.string(); else v.skip(tag & 7);
        }
        return s;
    }

    private static long zigzag(int v) {
        return (v >>> 1) ^ -(v & 1);
    }

    private static double[] append(double[] a, double v) {
        double[] b = java.util.Arrays.copyOf(a, a.length + 1);
        b[a.length] = v;
        return b;
    }

    /** Cursor over one protobuf message. */
    private static final class Reader {
        private final byte[] b;
        private int p;
        private final int end;

        Reader(byte[] b, int p, int end) {
            this.b = b;
            this.p = p;
            this.end = end;
        }

        boolean more() { return p < end; }

        int varint() {
            int v = 0, shift = 0;
            while (p < end) {
                int c = b[p++] & 0xFF;
                if (shift < 32) v |= (c & 0x7F) << shift;
                if (c < 0x80) break;
                shift += 7;
            }
            return v;
        }

        Reader sub() {
            int len = varint();
            Reader r = new Reader(b, p, Math.min(end, p + len));
            p = r.end;
            return r;
        }

        String string() {
            int len = varint();
            String s = new String(b, p, Math.min(len, end - p), StandardCharsets.UTF_8);
            p += len;
            return s;
        }

        int[] packed() {
            Reader r = sub();
            int[] tmp = new int[(r.end - r.p) + 1];
            int n = 0;
            while (r.more()) tmp[n++] = r.varint();
            return java.util.Arrays.copyOf(tmp, n);
        }

        void skip(int wireType) {
            switch (wireType) {
                case 0: varint(); break;
                case 1: p += 8; break;
                case 2: p += varint(); break;
                case 5: p += 4; break;
                default: p = end;
            }
        }
    }
}
