package org.example.metrics;

public final class TimeWeightedMetric {
    private double startTime = Double.NaN;
    private double lastTime  = Double.NaN;
    private double lastValue = 0.0;
    private double area      = 0.0;

    // Add a sample at simulation time t.
    public void add(double t, double value) {
        if (Double.isNaN(startTime)) startTime = t;
        if (!Double.isNaN(lastTime) && t > lastTime) {
            area += lastValue * (t - lastTime);
        }
        lastTime = t;
        lastValue = value;
    }

    // Get average up to 'untilTime'.
    public double average(double untilTime) {
        if (Double.isNaN(startTime) || Double.isNaN(lastTime)) return 0.0;
        double duration = Math.max(0.0, untilTime - startTime);
        if (duration == 0.0) return lastValue; // degenerate case
        double areaWithTail = area;
        if (untilTime > lastTime) {
            areaWithTail += lastValue * (untilTime - lastTime);
        }
        return areaWithTail / duration;
    }
}
