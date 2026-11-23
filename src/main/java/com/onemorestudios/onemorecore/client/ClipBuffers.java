package com.onemorestudios.onemorecore.client;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/**
 * Buffers that cut text and items at the edges of a box on a screen's canvas, so a row that scrolls in shows the part of its
 * text and icon that is already in view. Quads come in world space; they are taken back to canvas space (toCanvas), and a quad
 * that lies along the canvas axes (glyphs, flat item sprites) is cut to the box with its texture; any other quad (the sides of
 * a block item) shows only when it is in the box in whole. A wrapper writes a quad when its four vertices are complete, at the
 * latest when the next buffer is asked for or flush() is called.
 */
final class ClipBuffers implements MultiBufferSource {
    private final MultiBufferSource inner;
    private final Matrix4f toCanvas, toWorld;
    private final float x0, y0, x1, y1;
    private Clip current;

    ClipBuffers(MultiBufferSource inner, Matrix4f toWorld, float x0, float y0, float x1, float y1) {
        this.inner = inner;
        this.toWorld = new Matrix4f(toWorld);
        this.toCanvas = new Matrix4f(toWorld).invert();
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
    }

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        flush();
        current = new Clip(inner.getBuffer(type));
        return current;
    }

    void flush() {
        if (current != null) current.flush();
        current = null;
    }

    /** One vertex: position and what was set on it (-1: not set). */
    private static final class V {
        float x, y, z, u, v, nx, ny, nz;
        int r = -1, g, b, a, u1 = -1, v1, u2 = -1, v2;
        boolean uv, normal;

        V copy() {
            V c = new V();
            c.x = x; c.y = y; c.z = z; c.u = u; c.v = v; c.nx = nx; c.ny = ny; c.nz = nz;
            c.r = r; c.g = g; c.b = b; c.a = a; c.u1 = u1; c.v1 = v1; c.u2 = u2; c.v2 = v2; c.uv = uv; c.normal = normal;
            return c;
        }
    }

    private final class Clip implements VertexConsumer {
        private final VertexConsumer out;
        private final V[] quad = new V[4];
        private int count;

        Clip(VertexConsumer out) {
            this.out = out;
        }

        private V last() {
            return quad[count - 1];
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            if (count == 4) emit();
            V v = new V();
            v.x = x;
            v.y = y;
            v.z = z;
            quad[count++] = v;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            V v = last();
            v.r = r; v.g = g; v.b = b; v.a = a;
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            V x = last();
            x.u = u; x.v = v; x.uv = true;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            V x = last();
            x.u1 = u; x.v1 = v;
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            V x = last();
            x.u2 = u; x.v2 = v;
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            V v = last();
            v.nx = x; v.ny = y; v.nz = z; v.normal = true;
            return this;
        }

        void flush() {
            if (count == 4) emit();
            count = 0;
        }

        /** Writes the quad cut to the box, or nothing when it lies outside. */
        private void emit() {
            count = 0;
            Vector3f[] c = new Vector3f[4];
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                c[i] = toCanvas.transformPosition(quad[i].x, quad[i].y, quad[i].z, new Vector3f());
                minX = Math.min(minX, c[i].x); maxX = Math.max(maxX, c[i].x);
                minY = Math.min(minY, c[i].y); maxY = Math.max(maxY, c[i].y);
            }
            if (maxX <= x0 || minX >= x1 || maxY <= y0 || minY >= y1) return;
            if (minX >= x0 && maxX <= x1 && minY >= y0 && maxY <= y1) {
                for (V v : quad) write(v);
                return;
            }
            float w = maxX - minX, h = maxY - minY, eps = Math.max(w, h) * 1e-3f + 1e-4f;
            // Only a quad along the axes is cut; each corner is then (min or max x, min or max y).
            V[] corner = new V[4];
            for (int i = 0; i < 4; i++) {
                boolean right = Math.abs(c[i].x - maxX) < eps, bottom = Math.abs(c[i].y - maxY) < eps;
                if (!right && Math.abs(c[i].x - minX) >= eps || !bottom && Math.abs(c[i].y - minY) >= eps || w < eps || h < eps) return;
                int k = (right ? 1 : 0) + (bottom ? 2 : 0);
                if (corner[k] != null) return;
                corner[k] = quad[i];
            }
            float cx0 = Math.max(minX, x0), cx1 = Math.min(maxX, x1), cy0 = Math.max(minY, y0), cy1 = Math.min(maxY, y1);
            for (int i = 0; i < 4; i++) {
                boolean right = Math.abs(c[i].x - maxX) < eps, bottom = Math.abs(c[i].y - maxY) < eps;
                float nx = right ? cx1 : cx0, ny = bottom ? cy1 : cy0, fx = (nx - minX) / w, fy = (ny - minY) / h;
                V v = lerp(lerp(corner[0], corner[1], fx), lerp(corner[2], corner[3], fx), fy);
                Vector3f world = toWorld.transformPosition(nx, ny, c[i].z, new Vector3f());
                v.x = world.x; v.y = world.y; v.z = world.z;
                write(v);
            }
        }

        private V lerp(V a, V b, float t) {
            V v = a.copy();
            v.u = a.u + (b.u - a.u) * t;
            v.v = a.v + (b.v - a.v) * t;
            if (a.r >= 0) {
                v.r = Math.round(a.r + (b.r - a.r) * t); v.g = Math.round(a.g + (b.g - a.g) * t);
                v.b = Math.round(a.b + (b.b - a.b) * t); v.a = Math.round(a.a + (b.a - a.a) * t);
            }
            return v;
        }

        private void write(V v) {
            out.addVertex(v.x, v.y, v.z);
            if (v.r >= 0) out.setColor(v.r, v.g, v.b, v.a);
            if (v.uv) out.setUv(v.u, v.v);
            if (v.u1 >= 0) out.setUv1(v.u1, v.v1);
            if (v.u2 >= 0) out.setUv2(v.u2, v.v2);
            if (v.normal) out.setNormal(v.nx, v.ny, v.nz);
        }
    }
}
