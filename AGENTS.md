# QuantumHy repository rules

## JFR source

Files under `src/jfr/**`, `RuntimeMetrics.java`, and `RuntimeProfiler.java` are tracked and published to GitHub. Do not add code comments, Javadoc, block comments, or line comments to them. Do not add comments to JFR or dev-performance integration code in other published files, including `stopDevPerfMeterIfPresent` in `QuantumHyPlugin`. Keep this code comment-free whenever it is changed. The `verifyJfrSourceComments` Gradle task enforces the JFR source and public profiling bridge during `check` and `jfrClasses`.
