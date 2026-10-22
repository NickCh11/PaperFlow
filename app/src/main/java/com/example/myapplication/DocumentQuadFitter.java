package com.example.myapplication;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Independently authored supported-line geometry for an unsigned raw boundary response plane.
 * Coordinates are analysis pixels until the final normalized TL/TR/BR/BL result.
 * Enumeration is our own bounded search: 32 measured lines, 128 opposite-line pairs,
 * paired in descending segment-length order. Equal scores retain the first candidate.
 * It does not reproduce an undocumented vendor enumeration or use vendor model assets.
 */
final class DocumentQuadFitter {
    private DocumentQuadFitter() { }
    private static final int LINE_LIMIT = 32, PAIR_LIMIT = 128;

    /** Returns null when no supported convex quadrilateral exists; never mutates source. */
    static float[] fit(byte[] source, int width, int height) {
        if (source == null || width < 5 || height < 5
                || (long) width * height != source.length) return null;
        byte[] map = source.clone();
        for (int x = 0; x < width; x++) {
            map[x] = 103; map[(height - 1) * width + x] = 103;
        }
        for (int y = 0; y < height; y++) {
            map[y * width] = 103; map[y * width + width - 1] = 103;
        }
        List<DocumentEdgeLines.Line> measured = new ArrayList<>(DocumentEdgeLines.extract(map, width, height));
        measured.sort(Comparator.comparingDouble((DocumentEdgeLines.Line l) -> l.length).reversed());
        if (measured.size() > LINE_LIMIT) measured = measured.subList(0, LINE_LIMIT);
        List<DocumentEdgeLines.Line> lines = DocumentEdgeLines.withImageBorders(measured, width, height);
        ArrayList<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) for (int j = i + 1; j < lines.size(); j++) {
            DocumentEdgeLines.Line a = lines.get(i), b = lines.get(j);
            if (Math.abs(a.a * b.a + a.b * b.b) < Math.sqrt(.5)) continue;
            // Duplicate gradient sides must not consume the bounded pair list.
            if (a.distance((b.x1+b.x2)*.5, (b.y1+b.y2)*.5) < 12
                    && b.distance((a.x1+a.x2)*.5, (a.y1+a.y2)*.5) < 12) continue;
            pairs.add(new Pair(i, j, a.length + b.length));
        }
        pairs.sort(Comparator.comparingDouble((Pair p) -> p.rank).reversed());
        if (pairs.size() > PAIR_LIMIT) pairs.subList(PAIR_LIMIT, pairs.size()).clear();
        float[] neighborhood = neighborhoodWeights(map, width, height);
        double bestScore = -1;
        DocumentEdgeLines.Line[] winner = null;
        double[][] winningPoints = null;
        for (int i = 0; i < pairs.size(); i++) for (int j = i + 1; j < pairs.size(); j++) {
            Pair p = pairs.get(i), q = pairs.get(j);
            if (p.a == q.a || p.a == q.b || p.b == q.a || p.b == q.b) continue;
            DocumentEdgeLines.Line a = lines.get(p.a), b = lines.get(p.b);
            DocumentEdgeLines.Line c = lines.get(q.a), d = lines.get(q.b);
            if (Math.abs(a.a*c.a+a.b*c.b) > Math.sqrt(.5)) continue;
            DocumentEdgeLines.Line[] candidate = {a, c, b, d};
            double[][] corners = intersections(candidate);
            if (!valid(corners, width, height)) continue;
            double score = 0; int strong = 0; boolean supported = true;
            for (int k = 0; k < 4; k++) {
                double[] start = corners[k], end = corners[(k+1)%4];
                double length = dominant(start, end);
                double support = support(neighborhood, width, height, start, end);
                if (support < .2 - 1e-6) { supported = false; break; }
                if (support >= .6) strong++;
                score += support * length;
            }
            if (supported && strong >= 2 && score > bestScore) {
                bestScore = score; winner = candidate; winningPoints = corners;
            }
        }
        if (winner == null) return null;
        DocumentEdgeLines.Line[] refined = winner.clone();
        for (int k = 0; k < 4; k++) if (!winner[k].synthetic) {
            refined[k] = refit(winner[k], winningPoints[k], winningPoints[(k+1)%4], map, width, height);
        }
        double[][] corners = intersections(refined);
        if (!valid(corners, width, height)) corners = winningPoints;
        // No implicit polygon inflation: offset is zero.
        for (double[] point : corners) {
            point[0] = Math.max(0, Math.min(width, point[0]));
            point[1] = Math.max(0, Math.min(height, point[1]));
        }
        if (!valid(corners, width, height)) return null;
        double cx = 0, cy = 0;
        for (double[] point : corners) { cx += point[0]/4; cy += point[1]/4; }
        final double centerX = cx, centerY = cy;
        Arrays.sort(corners, Comparator.comparingDouble(p -> Math.atan2(p[1]-centerY, p[0]-centerX)));
        int first = 0;
        for (int i = 1; i < 4; i++) if (corners[i][0]+corners[i][1] < corners[first][0]+corners[first][1]) first = i;
        float[] result = new float[8];
        for (int i = 0; i < 4; i++) {
            result[2*i] = (float)(corners[(first+i)%4][0]/width);
            result[2*i+1] = (float)(corners[(first+i)%4][1]/height);
        }
        return result;
    }

    private static double[][] intersections(DocumentEdgeLines.Line[] lines) {
        double[][] points = new double[4][2];
        for (int i = 0; i < 4; i++) {
            DocumentEdgeLines.Line a = lines[(i+3)%4], b = lines[i];
            double det = a.a*b.b-b.a*a.b;
            if (Math.abs(det) < 1e-7) return null;
            points[i][0] = (a.b*b.c-b.b*a.c)/det;
            points[i][1] = (a.c*b.a-b.c*a.a)/det;
        }
        return points;
    }

    private static boolean valid(double[][] p, int width, int height) {
        if (p == null) return false;
        double area = 0, sign = 0;
        for (int i = 0; i < 4; i++) {
            double[] a = p[i], b = p[(i+1)%4], c = p[(i+2)%4];
            if (!Double.isFinite(a[0]) || !Double.isFinite(a[1]) || a[0] < -.2*width
                    || a[0] > 1.2*width || a[1] < -.2*height || a[1] > 1.2*height) return false;
            double ux = b[0]-a[0], uy = b[1]-a[1], vx = c[0]-b[0], vy = c[1]-b[1];
            double cross = ux*vy-uy*vx, lengths = Math.hypot(ux,uy)*Math.hypot(vx,vy);
            if (lengths < 1 || Math.abs(cross) < 1e-6 || (sign != 0 && cross*sign <= 0)) return false;
            if (Math.abs((ux*vx+uy*vy)/lengths) > Math.sqrt(.5)+1e-8) return false;
            sign = cross; area += a[0]*b[1]-a[1]*b[0];
        }
        return Math.abs(area)*.5 >= .01*width*height;
    }

    private static double dominant(double[] a, double[] b) {
        return Math.max(Math.abs(b[0]-a[0]), Math.abs(b[1]-a[1]));
    }

    private static float weight(int value) {
        return value < 103 ? 0 : value < 128 ? .2f : value < 154 ? .4f : value < 206 ? .8f : 1.6f;
    }

    /** Separable spatial maximum, equivalent to the clipped 7 by 7 neighborhood. */
    private static float[] neighborhoodWeights(byte[] map, int width, int height) {
        float[] horizontal = new float[map.length], result = new float[map.length];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            float max = 0;
            for (int dx = Math.max(0,x-3); dx <= Math.min(width-1,x+3); dx++) max = Math.max(max,weight(map[y*width+dx]&255));
            horizontal[y*width+x] = max;
        }
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            float max = 0;
            for (int dy = Math.max(0,y-3); dy <= Math.min(height-1,y+3); dy++) max = Math.max(max,horizontal[dy*width+x]);
            result[y*width+x] = max;
        }
        return result;
    }

    private static double support(float[] map, int width, int height, double[] a, double[] b) {
        double length = dominant(a,b);
        if (length < 50) return 0;
        double sum = 0;
        for (double distance = 0; distance <= length; distance += 5) {
            double t = distance/length, x = a[0]+t*(b[0]-a[0]), y = a[1]+t*(b[1]-a[1]);
            // Geometric image edges may be exactly width/height; sample their last pixel.
            if (x < 0 || x > width || y < 0 || y > height) continue;
            int ix = Math.max(0,Math.min(width-1,(int)Math.round(x)));
            int iy = Math.max(0,Math.min(height-1,(int)Math.round(y)));
            sum += map[iy*width+ix];
        }
        return sum*5/length;
    }

    private static DocumentEdgeLines.Line refit(DocumentEdgeLines.Line original, double[] a, double[] b,
                                               byte[] map, int width, int height) {
        double length = dominant(a,b);
        int count = (int)Math.floor(length/5)+1;
        if (count <= 20) return original;
        double[] xs = new double[count], ys = new double[count]; int accepted = 0;
        for (int i = 10; i < count-10; i++) {
            double t = i*5/length, x = a[0]+t*(b[0]-a[0]), y = a[1]+t*(b[1]-a[1]);
            int best = 103; double px = 0, py = 0;
            // Our deterministic preference is strongest response, then nearest normal offset.
            for (int step = 0; step <= 20; step++) {
                int offset = step == 0 ? 0 : (step+1)/2 * (step%2 == 1 ? 1 : -1);
                int ix = (int)Math.round(x+offset*original.a), iy = (int)Math.round(y+offset*original.b);
                if (ix < 0 || ix >= width || iy < 0 || iy >= height) continue;
                int value = map[iy*width+ix]&255;
                if (value > best) { best = value; px = ix; py = iy; }
            }
            if (best > 103) { xs[accepted] = px; ys[accepted++] = py; }
        }
        if (accepted < 6) return original;
        double cx = 0, cy = 0;
        for (int i = 0; i < accepted; i++) { cx += xs[i]/accepted; cy += ys[i]/accepted; }
        double xx = 0, xy = 0, yy = 0;
        for (int i = 0; i < accepted; i++) {
            double dx = xs[i]-cx, dy = ys[i]-cy; xx += dx*dx; xy += dx*dy; yy += dy*dy;
        }
        if (xx+yy < 1e-6) return original;
        double theta = .5*Math.atan2(2*xy,xx-yy), tx = Math.cos(theta), ty = Math.sin(theta);
        return new DocumentEdgeLines.Line(cx-tx*length/2,cy-ty*length/2,cx+tx*length/2,cy+ty*length/2);
    }

    private static final class Pair {
        final int a, b; final double rank;
        Pair(int a, int b, double rank) { this.a = a; this.b = b; this.rank = rank; }
    }
}
