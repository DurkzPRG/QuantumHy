package com.durkz.quantumhy.view;

/**
 * Immutable layout of the circular density scan. The layout only depends on configuration, so it
 * keeps radius checks and ring-weight square roots out of the per-player world-thread pass.
 */
final class DensityScanPlan {

    private final int[] dx;
    private final int[] dz;
    private final double[] weights;

    private DensityScanPlan(int[] dx, int[] dz, double[] weights) {
        this.dx = dx;
        this.dz = dz;
        this.weights = weights;
    }

    static DensityScanPlan of(int configuredRadius, boolean ringWeighting, double edgeWeight) {
        int radius = Math.max(0, configuredRadius);
        int radiusSq = radius * radius;
        int columns = ViewAdaptPolicy.expectedScanChunks(radius);
        int[] dx = new int[columns];
        int[] dz = new int[columns];
        double[] weights = new double[columns];
        int column = 0;
        for (int z = -radius; z <= radius; z++) {
            int zSq = z * z;
            for (int x = -radius; x <= radius; x++) {
                int distanceSq = x * x + zSq;
                if (distanceSq > radiusSq) {
                    continue;
                }
                dx[column] = x;
                dz[column] = z;
                if (ringWeighting && radius > 0) {
                    double t = Math.min(1.0D, Math.sqrt(distanceSq) / radius);
                    weights[column] = 1.0D - t * (1.0D - edgeWeight);
                } else {
                    weights[column] = 1.0D;
                }
                column++;
            }
        }
        return new DensityScanPlan(dx, dz, weights);
    }

    int columns() {
        return dx.length;
    }

    int dx(int column) {
        return dx[column];
    }

    int dz(int column) {
        return dz[column];
    }

    double weight(int column) {
        return weights[column];
    }
}
