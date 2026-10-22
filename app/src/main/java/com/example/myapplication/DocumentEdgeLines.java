package com.example.myapplication;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Independently written gradient-anchor / edge-chain / total-least-squares line extractor.
 * Input is an unsigned grayscale probability image. No bitmap, model, or native resources
 * are retained. This conventional edge drawing approach is not a port of vendor EDLine.
 * All endpoint coordinates and distances use analysis-image pixels; the line equation's
 * normal (a,b) has unit length. Synthetic image borders are added only on explicit request.
 */
final class DocumentEdgeLines {
    private DocumentEdgeLines() { }

    static final class Line {
        final double x1, y1, x2, y2, length;
        /** Unit normal; a*x + b*y + c is signed perpendicular distance in image pixels. */
        final double a, b, c;
        final boolean synthetic;

        Line(double x1, double y1, double x2, double y2) {
            this(x1, y1, x2, y2, false);
        }

        Line(double x1, double y1, double x2, double y2, boolean synthetic) {
            if (!Double.isFinite(x1) || !Double.isFinite(y1)
                    || !Double.isFinite(x2) || !Double.isFinite(y2)) {
                throw new IllegalArgumentException(UiText.get(R.string.ui_non_finite_line_endpoint));
            }
            double dx = x2 - x1, dy = y2 - y1;
            double n = Math.hypot(dx, dy);
            if (n < 1e-8) throw new IllegalArgumentException(UiText.get(R.string.ui_coincident_line_endpoints));
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
            this.length = n;
            double na = -dy / n, nb = dx / n;
            // A stable sign makes comparable lines independent of traversal direction.
            if (na < -1e-12 || (Math.abs(na) <= 1e-12 && nb < 0)) {
                na = -na; nb = -nb;
            }
            this.a = na; this.b = nb; this.c = -(na * x1 + nb * y1);
            this.synthetic = synthetic;
        }

        double distance(double x, double y) { return Math.abs(a * x + b * y + c); }
    }

    static List<Line> extract(byte[] probability, int width, int height) {
        return extract(probability, width, height, 40, .05 * Math.min(width, height));
    }

    /**
     * minimumChain is a count of connected edge pixels; minimumLength is in image pixels.
     * Book callers may use minimumChain=round(.5*shortSide), minimumLength=.25*shortSide.
     */
    static List<Line> extract(byte[] probability, int width, int height,
                              int minimumChain, double minimumLength) {
        if (probability == null || width < 5 || height < 5
                || (long) width * height != probability.length) return Collections.emptyList();
        if (minimumChain < 2 || !Double.isFinite(minimumLength) || minimumLength <= 0) {
            throw new IllegalArgumentException(UiText.get(R.string.ui_invalid_line_extraction_limits));
        }
        final int count = probability.length;
        float[] gray = blur(probability, width, height);
        float[] gx = new float[count], gy = new float[count], magnitude = new float[count];
        float maximum = 0;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int p = y * width + x;
                float dx = gray[p-width+1] + 2*gray[p+1] + gray[p+width+1]
                        - gray[p-width-1] - 2*gray[p-1] - gray[p+width-1];
                float dy = gray[p+width-1] + 2*gray[p+width] + gray[p+width+1]
                        - gray[p-width-1] - 2*gray[p-width] - gray[p-width+1];
                gx[p] = dx; gy[p] = dy;
                magnitude[p] = Math.abs(dx) + Math.abs(dy);
                maximum = Math.max(maximum, magnitude[p]);
            }
        }
        if (maximum < 12) return Collections.emptyList();
        // Fixed minimum rejects tiny quantization ripples; relative part adapts to a soft map.
        final float threshold = Math.max(12, maximum * .045f);
        final float anchorProminence = Math.max(1.5f, threshold * .12f);
        ArrayList<Integer> anchors = new ArrayList<>();
        for (int y = 2; y < height - 2; y++) {
            for (int x = 2; x < width - 2; x++) {
                int p = y*width+x;
                float value = magnitude[p];
                if (value < threshold) continue;
                int across = Math.abs(gx[p]) >= Math.abs(gy[p]) ? 1 : width;
                // Permit a two-pixel plateau while selecting its deterministic leading side.
                float before = magnitude[p-across], after = magnitude[p+across];
                if (value >= before && value > after
                        && value - Math.min(before, after) >= anchorProminence) anchors.add(p);
            }
        }
        anchors.sort((left, right) -> Float.compare(magnitude[right], magnitude[left]));
        boolean[] visited = new boolean[count];
        ArrayList<Line> segments = new ArrayList<>();
        IntList forward = new IntList(), backward = new IntList(), chain = new IntList();
        for (int anchor : anchors) {
            if (visited[anchor]) continue;
            forward.clear(); backward.clear(); chain.clear();
            visited[anchor] = true;
            double n = Math.hypot(gx[anchor], gy[anchor]);
            if (n < 1e-6) continue;
            double tx = -gy[anchor]/n, ty = gx[anchor]/n;
            trace(anchor, tx, ty, width, height, gx, gy, magnitude, threshold, visited, forward);
            trace(anchor, -tx, -ty, width, height, gx, gy, magnitude, threshold, visited, backward);
            for (int i = backward.size - 1; i >= 0; i--) chain.add(backward.data[i]);
            chain.add(anchor);
            for (int i = 0; i < forward.size; i++) chain.add(forward.data[i]);
            if (chain.size >= minimumChain) split(chain.data, 0, chain.size, width,
                    minimumLength, 0, segments);
        }
        return mergeCollinear(segments, minimumLength);
    }

    /** Returns a new list; the input collection and its line records remain untouched. */
    static List<Line> withImageBorders(List<Line> measured, int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException(UiText.get(R.string.ui_invalid_image_size));
        ArrayList<Line> result = new ArrayList<>();
        if (measured != null) result.addAll(measured);
        // Coordinates describe image edges, consistently permitting x==width and y==height.
        result.add(new Line(0, 0, width, 0, true));
        result.add(new Line(width, 0, width, height, true));
        result.add(new Line(width, height, 0, height, true));
        result.add(new Line(0, height, 0, 0, true));
        return result;
    }

    private static float[] blur(byte[] source, int width, int height) {
        float[] horizontal = new float[source.length], result = new float[source.length];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int p = y*width+x, left = y*width+Math.max(0, x-1), right = y*width+Math.min(width-1,x+1);
            horizontal[p] = ((source[left]&255) + 2*(source[p]&255) + (source[right]&255))*.25f;
        }
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int p = y*width+x, top = Math.max(0,y-1)*width+x, bottom = Math.min(height-1,y+1)*width+x;
            result[p] = (horizontal[top] + 2*horizontal[p] + horizontal[bottom])*.25f;
        }
        return result;
    }

    private static void trace(int first, double dx, double dy, int width, int height,
                              float[] gx, float[] gy, float[] magnitude, float threshold,
                              boolean[] visited, IntList output) {
        int current = first;
        for (int step = 0; step < magnitude.length; step++) {
            int x = current%width, y = current/width, best = -1;
            double bestScore = -Double.MAX_VALUE;
            // Three forward positions along the dominant tangent axis, as in edge drawing.
            boolean horizontal = Math.abs(dx) >= Math.abs(dy);
            int axisStep = horizontal ? (dx >= 0 ? 1 : -1) : (dy >= 0 ? 1 : -1);
            for (int offset = -1; offset <= 1; offset++) {
                int nx = x + (horizontal ? axisStep : offset);
                int ny = y + (horizontal ? offset : axisStep);
                if (nx < 1 || nx >= width-1 || ny < 1 || ny >= height-1) continue;
                int q = ny*width+nx;
                if (visited[q] || magnitude[q] < threshold) continue;
                double moveX = nx-x, moveY = ny-y;
                double along = (moveX*dx+moveY*dy)/Math.hypot(moveX,moveY);
                if (along < .35) continue;
                double gn = Math.hypot(gx[q],gy[q]);
                if (gn < 1e-6) continue;
                double tangentAlignment = Math.abs((-gy[q]*dx+gx[q]*dy)/gn);
                // Reject a sudden jump onto a crossing stroke, but allow a gradual curve.
                if (tangentAlignment < .45) continue;
                double score = magnitude[q]*(.55+.45*along)*(.6+.4*tangentAlignment);
                if (score > bestScore) { bestScore = score; best = q; }
            }
            if (best < 0) return;
            double moveX = best%width-x, moveY = best/width-y;
            double norm = Math.hypot(moveX, moveY);
            double tx = -gy[best], ty = gx[best];
            double tn = Math.hypot(tx,ty); tx /= tn; ty /= tn;
            if (tx*moveX+ty*moveY < 0) { tx = -tx; ty = -ty; }
            dx = .55*moveX/norm+.45*tx; dy = .55*moveY/norm+.45*ty;
            double directionNorm = Math.hypot(dx,dy); dx /= directionNorm; dy /= directionNorm;
            visited[best] = true; output.add(best); current = best;
        }
    }

    private static void split(int[] chain, int from, int to, int width,
                               double minimumLength, int depth, List<Line> output) {
        int size = to-from;
        if (size < 6) return;
        Fit fit = fit(chain,from,to,width);
        if (fit == null) return;
        if (fit.maximumError <= 2.4 && fit.rmsError <= 1.15) {
            if (fit.line.length > minimumLength) output.add(fit.line);
            return;
        }
        if (depth >= 24 || size < 12) return;
        int middle = fit.worstIndex;
        if (middle < from+5 || middle >= to-5) middle = from+size/2;
        split(chain,from,middle+1,width,minimumLength,depth+1,output);
        split(chain,middle,to,width,minimumLength,depth+1,output);
    }

    private static Fit fit(int[] points, int from, int to, int width) {
        double cx = 0, cy = 0;
        for (int i = from; i < to; i++) { cx += points[i]%width; cy += points[i]/width; }
        cx /= to-from; cy /= to-from;
        double xx = 0, xy = 0, yy = 0;
        for (int i = from; i < to; i++) {
            double x = points[i]%width-cx, y = points[i]/width-cy;
            xx += x*x; xy += x*y; yy += y*y;
        }
        if (xx+yy < 1e-8) return null;
        double theta = .5*Math.atan2(2*xy,xx-yy), tx = Math.cos(theta), ty = Math.sin(theta);
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        double max = 0, squared = 0; int worst = from;
        for (int i = from; i < to; i++) {
            double x = points[i]%width-cx, y = points[i]/width-cy;
            double projection = x*tx+y*ty, error = Math.abs(-x*ty+y*tx);
            lo = Math.min(lo,projection); hi = Math.max(hi,projection);
            squared += error*error;
            if (error > max) { max = error; worst = i; }
        }
        if (hi-lo < 1e-8) return null;
        return new Fit(new Line(cx+lo*tx,cy+lo*ty,cx+hi*tx,cy+hi*ty),max,
                Math.sqrt(squared/(to-from)),worst);
    }

    private static List<Line> mergeCollinear(List<Line> segments, double minimumLength) {
        segments.sort(Comparator.comparingDouble((Line line) -> line.length).reversed());
        ArrayList<Line> result = new ArrayList<>();
        for (Line line : segments) {
            boolean consumed = false;
            for (int i = 0; i < result.size(); i++) {
                Line kept = result.get(i);
                if (Math.abs(kept.a*line.a+kept.b*line.b) <= .996) continue;
                if (kept.distance((line.x1+line.x2)*.5,(line.y1+line.y2)*.5) >= 20
                        || line.distance((kept.x1+kept.x2)*.5,(kept.y1+kept.y2)*.5) >= 20) continue;
                double tx = (kept.x2-kept.x1)/kept.length, ty = (kept.y2-kept.y1)/kept.length;
                double p1 = (line.x1-kept.x1)*tx+(line.y1-kept.y1)*ty;
                double p2 = (line.x2-kept.x1)*tx+(line.y2-kept.y1)*ty;
                double lo = Math.min(p1,p2), hi = Math.max(p1,p2);
                // Do not combine distant unrelated collinear objects into an invented edge.
                if (lo > kept.length+20 || hi < -20) continue;
                lo = Math.min(0,lo); hi = Math.max(kept.length,hi);
                result.set(i,new Line(kept.x1+lo*tx,kept.y1+lo*ty,
                        kept.x1+hi*tx,kept.y1+hi*ty));
                consumed = true; break;
            }
            if (!consumed && line.length > minimumLength) result.add(line);
        }
        result.sort(Comparator.comparingDouble((Line line) -> line.length).reversed());
        return result;
    }

    private static final class Fit {
        final Line line;
        final double maximumError, rmsError;
        final int worstIndex;
        Fit(Line line, double maximumError, double rmsError, int worstIndex) {
            this.line = line; this.maximumError = maximumError;
            this.rmsError = rmsError; this.worstIndex = worstIndex;
        }
    }

    private static final class IntList {
        int[] data = new int[128]; int size;
        void clear() { size = 0; }
        void add(int value) {
            if (size == data.length) data = Arrays.copyOf(data,data.length*2);
            data[size++] = value;
        }
    }
}
