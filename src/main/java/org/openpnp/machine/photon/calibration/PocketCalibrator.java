package org.openpnp.machine.photon.calibration;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.openpnp.machine.photon.PhotonFeeder;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.VisionUtils;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.opencv.core.KeyPoint;
import org.opencv.core.Point;
import org.simpleframework.xml.Element;
import org.simpleframework.xml.Root;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Calibrates a PhotonFeeder's pocket location by capturing the top camera
 * over the feeder's pick location, running a CvPipeline to find the pocket
 * center, computing the offset between detected center and image center,
 * and writing that offset to PhotonFeeder.setOffset().
 *
 * The CvPipeline is editable per-feeder via OpenPnP's pipeline editor UI.
 * The default pipeline performs: BlurGaussian -> MaskCircle (ROI) ->
 * ConvertColor (gray) -> Threshold (Otsu, inverted) -> FindContours ->
 * FilterContours (by area) -> MinAreaRect -> draw result.
 *
 * Safety: refuses to write offsets larger than MAX_CORRECTION_MM.
 */
@Root
public class PocketCalibrator {

    private static final Logger LOG = LoggerFactory.getLogger(PocketCalibrator.class);

    /** Max allowed correction. Anything larger is treated as a vision error. */
    public static final double MAX_CORRECTION_MM = 2.5;

    /** ROI radius (mm) around the expected pocket center to constrain search. */
    @Element(required = false)
    private double roiRadiusMm = 3.0;

    @Element(required = false)
    private CvPipeline pipeline = createDefaultPipeline();

    /**
     * Result of a calibration attempt.
     */
    public static class Result {
        public final boolean success;
        public final String message;
        public final Location measuredOffset;       // mm, dx/dy
        public final Location newFeederOffset;      // mm, what was written
        public final Point pocketPixelCenter;       // pixel coords in capture
        public final double pocketAreaPx;

        public Result(boolean success, String message,
                      Location measuredOffset, Location newFeederOffset,
                      Point pocketPixelCenter, double pocketAreaPx) {
            this.success = success;
            this.message = message;
            this.measuredOffset = measuredOffset;
            this.newFeederOffset = newFeederOffset;
            this.pocketPixelCenter = pocketPixelCenter;
            this.pocketAreaPx = pocketAreaPx;
        }
    }

    /**
     * Run calibration on the given feeder using the given camera.
     * Must be called inside machine.execute() / a machine task.
     */
    public Result calibrate(PhotonFeeder feeder, Camera camera) throws Exception {
        Location pickLocation = feeder.getPickLocation()
                .convertToUnits(LengthUnit.Millimeters);
        Location currentOffset = feeder.getOffset()
                .convertToUnits(LengthUnit.Millimeters);

        LOG.info("Calibrating pocket for feeder '{}' at pick {}, current offset {}",
                feeder.getName(), pickLocation, currentOffset);

        // Move camera to pick X/Y, keep camera's own Z and rotation
        Location cameraLoc = camera.getLocation();
        Location target = cameraLoc.derive(pickLocation, true, true, false, false);
        MovableUtils.moveToLocationAtSafeZ(camera, target);
        camera.moveTo(target);
        Thread.sleep(300);

        // Run the pipeline (do NOT use try-with-resources — pipeline is a shared field
        // and closing it would release native OpenCV resources for subsequent runs/edits)
        pipeline.setProperty("camera", camera);
        pipeline.setProperty("feeder", feeder);
        pipeline.setProperty("MaskCircle.diameter",
                new Length(roiRadiusMm * 2.0, LengthUnit.Millimeters));
        pipeline.process();

        // Pipeline expected to expose a "result" stage that produces a
        // single point (the pocket center) — either as KeyPoint, Point,
        // or RotatedRect center. Look for any of these.
        Point pocketPixel = extractCenterFromPipeline(pipeline);
        if (pocketPixel == null) {
            return new Result(false,
                    "No pocket detected by pipeline. "
                    + "Edit the pipeline to debug.",
                    null, null, null, 0);
        }

        // Use VisionUtils which handles units-per-pixel correctly
        Location detectedMachineLoc = VisionUtils.getPixelLocation(
                camera, pocketPixel.x, pocketPixel.y);
        detectedMachineLoc = detectedMachineLoc.convertToUnits(LengthUnit.Millimeters);

        // Measured offset = where the pocket actually is, minus where we expected
        Location measuredOffset = detectedMachineLoc.subtract(pickLocation);
        measuredOffset = measuredOffset.derive(null, null, 0.0, 0.0); // X/Y only

        double mag = Math.sqrt(measuredOffset.getX() * measuredOffset.getX()
                             + measuredOffset.getY() * measuredOffset.getY());

        LOG.info("Detected pocket at pixel ({}, {}) -> machine {} -> offset {} (|{}|mm)",
                pocketPixel.x, pocketPixel.y, detectedMachineLoc,
                measuredOffset, mag);

        if (mag > MAX_CORRECTION_MM) {
            return new Result(false,
                    String.format("Measured offset %.3fmm exceeds safety limit %.1fmm. "
                                + "Likely vision error.", mag, MAX_CORRECTION_MM),
                    measuredOffset, null, pocketPixel, 0);
        }

        // New feeder offset = current + measured (additive, so re-running converges)
        Location newFeederOffset = new Location(LengthUnit.Millimeters,
                currentOffset.getX() + measuredOffset.getX(),
                currentOffset.getY() + measuredOffset.getY(),
                currentOffset.getZ(),
                currentOffset.getRotation());

        feeder.setOffset(newFeederOffset);
        LOG.info("Wrote new feeder offset: {}", newFeederOffset);

        return new Result(true,
                String.format("OK. Offset adjusted by dx=%+.3f dy=%+.3f mm.",
                        measuredOffset.getX(), measuredOffset.getY()),
                measuredOffset, newFeederOffset, pocketPixel, 0);
    }

    /**
     * Find the detected center from the pipeline output. Looks for the last
     * stage that produced a single Point, KeyPoint, or RotatedRect.
     */
    private Point extractCenterFromPipeline(CvPipeline pipeline) {
        // Walk stages in reverse, take the first usable result
        List<CvStage> stages = pipeline.getStages();
        for (int i = stages.size() - 1; i >= 0; i--) {
            CvStage stage = stages.get(i);
            CvStage.Result r = pipeline.getResult(stage);
            if (r == null || r.model == null) {
                continue;
            }
            Object m = r.model;
            if (m instanceof Point) {
                return (Point) m;
            }
            if (m instanceof KeyPoint) {
                return ((KeyPoint) m).pt;
            }
            if (m instanceof org.opencv.core.RotatedRect) {
                return ((org.opencv.core.RotatedRect) m).center;
            }
            if (m instanceof java.util.List) {
                java.util.List<?> list = (java.util.List<?>) m;
                if (!list.isEmpty()) {
                    Object first = list.get(0);
                    if (first instanceof Point) {
                        return (Point) first;
                    }
                    if (first instanceof KeyPoint) {
                        return ((KeyPoint) first).pt;
                    }
                    if (first instanceof org.opencv.core.RotatedRect) {
                        return ((org.opencv.core.RotatedRect) first).center;
                    }
                }
            }
        }
        return null;
    }

    public CvPipeline getPipeline() {
        return pipeline;
    }

    public void setPipeline(CvPipeline pipeline) {
        this.pipeline = pipeline;
    }

    public double getRoiRadiusMm() {
        return roiRadiusMm;
    }

    public void setRoiRadiusMm(double roiRadiusMm) {
        this.roiRadiusMm = roiRadiusMm;
    }

    /**
     * Default pipeline loaded from XML resource. The pipeline is editable
     * via OpenPnP's CvPipeline editor UI — same as fiducial alignment.
     */
    public static CvPipeline createDefaultPipeline() {
        try (InputStream in = PocketCalibrator.class.getResourceAsStream(
                "PocketCalibrator-default-pipeline.xml")) {
            if (in == null) {
                throw new IOException("Default pipeline resource not found");
            }
            String xml = new String(in.readAllBytes());
            return new CvPipeline(xml);
        }
        catch (Exception e) {
            LOG.error("Failed to load default pocket calibration pipeline", e);
            return new CvPipeline();
        }
    }

    /**
     * Reset to default pipeline (called from UI button).
     */
    public void resetPipeline() {
        this.pipeline = createDefaultPipeline();
    }
}
