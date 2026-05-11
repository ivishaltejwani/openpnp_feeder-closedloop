package org.openpnp.machine.photon.calibration;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.openpnp.gui.MainFrame;
import org.openpnp.machine.photon.PhotonFeeder;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.OpenCvUtils;
import org.openpnp.util.VisionUtils;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.stages.DetectCircularSymmetry;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.simpleframework.xml.Element;
import org.simpleframework.xml.Root;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Calibrates a PhotonFeeder's pocket location using EIA-481 tape geometry.
 *
 * Algorithm:
 *  1. Optionally feed one pitch to bring a fresh pocket into view.
 *  2. Move top camera over the feeder's current pick location.
 *  3. Run the pipeline (DetectCircularSymmetry → "results" stage).
 *  4. Filter circles by score >= MIN_SCORE (4.0).
 *  5. Find the pair of high-score circles whose Euclidean distance matches
 *     sprocketPitchMm ± 1.5 mm; among ties, pick the pair whose midpoint is
 *     closest to the image centre.
 *  6. Compute the pocket pixel position from the midpoint using EIA-481 geometry.
 *  7. Convert to machine coords via VisionUtils.getPixelLocation().
 *  8. Write additive correction to feeder.setOffset() and persist.
 *  9. Show annotated result in main camera view for 5 seconds.
 * 10. Move the camera to the computed pocket location for visual confirmation.
 */
@Root(strict = false)
public class PocketCalibrator {

    private static final Logger LOG = LoggerFactory.getLogger(PocketCalibrator.class);

    public static final double MAX_CORRECTION_MM = 5.0;
    public static final double MIN_SCORE = 4.0;

    /** Describes how the tape is oriented in the camera image. */
    public enum Orientation {
        /** Tape runs top-to-bottom, sprocket holes on the left. Pocket is to the right. */
        VERTICAL_HOLES_LEFT,
        /** Tape runs top-to-bottom, sprocket holes on the right. Pocket is to the left. */
        VERTICAL_HOLES_RIGHT,
        /** Tape runs left-to-right, sprocket holes on the top. Pocket is below. */
        HORIZONTAL_HOLES_TOP,
        /** Tape runs left-to-right, sprocket holes on the bottom. Pocket is above. */
        HORIZONTAL_HOLES_BOTTOM
    }

    private static final double SPROCKET_PITCH_MM = 4.0;

    /** EIA-481 tape width (mm): 8, 12, 16, or 24. Determines the F dimension (perpendicular offset). */
    @Element(required = false)
    private double tapeWidthMm = 8.0;

    /** Part pitch along the feed direction (mm): 2, 4, 8, or 12. */
    @Element(required = false)
    private double partPitchMm = 4.0;

    /** Along-feed offset from sprocket pair midpoint to pocket centre (mm). 0 = centred between holes. */
    @Element(required = false)
    private double alongOffsetMm = 0.0;

    @Element(required = false)
    private double roiRadiusMm = 3.0;

    /** Maximum pixel difference along the feed axis allowed between two circles in a candidate pair.
     *  Rejects false positives that are roughly pitch-distance away but laterally displaced
     *  (e.g. a pocket blob paired with a sprocket hole). */
    @Element(required = false)
    private double alignmentTolerancePx = 50.0;

    /** Nominal sprocket hole diameter (mm) for pipeline property injection. EIA-481: 1.5 mm. */
    @Element(required = false)
    private double sprocketHoleDiameterMm = 1.5;

    /** If true, feed one part pitch before capturing the calibration image. */
    @Element(required = false)
    private boolean feedBeforeCalibrate = true;

    @Element(required = false)
    private CvPipeline pipeline = createDefaultPipeline();

    public static class Result {
        public final boolean success;
        public final String message;
        public final Location measuredOffset;
        public final Location newFeederOffset;
        public final org.opencv.core.Point pocketPixelCenter;
        public final double pocketAreaPx;

        public Result(boolean success, String message,
                      Location measuredOffset, Location newFeederOffset,
                      org.opencv.core.Point pocketPixelCenter, double pocketAreaPx) {
            this.success = success;
            this.message = message;
            this.measuredOffset = measuredOffset;
            this.newFeederOffset = newFeederOffset;
            this.pocketPixelCenter = pocketPixelCenter;
            this.pocketAreaPx = pocketAreaPx;
        }
    }

    /**
     * Run calibration. Must be called inside machine.execute() / a machine task.
     */
    public Result calibrate(PhotonFeeder feeder, Camera camera) throws Exception {
        Location pickLocation = feeder.getPickLocation().convertToUnits(LengthUnit.Millimeters);
        LOG.info("Calibrating feeder '{}', pick={}", feeder.getName(), pickLocation);

        // Optionally feed one pitch to advance a fresh pocket into view.
        if (feedBeforeCalibrate) {
            try {
                feeder.feed(null);
                Thread.sleep(500);
                LOG.info("Pre-calibration feed complete.");
            }
            catch (Exception e) {
                LOG.warn("Pre-calibration feed failed (continuing anyway): {}", e.getMessage());
            }
        }

        // Move camera to current pick location.
        Location target = camera.getLocation().derive(pickLocation, true, true, false, false);
        MovableUtils.moveToLocationAtSafeZ(camera, target);
        camera.moveTo(target);
        Thread.sleep(300);

        pipeline.setProperty("camera", camera);
        pipeline.setProperty("feeder", feeder);
        // Inject DetectFixedCirclesHough.* (pixel values, required by that stage).
        Integer pxMinDistance = (int) VisionUtils.toPixels(
                new Length(SPROCKET_PITCH_MM * 0.9, LengthUnit.Millimeters), camera);
        Integer pxMinDiameter = (int) VisionUtils.toPixels(
                new Length(sprocketHoleDiameterMm * 0.9, LengthUnit.Millimeters), camera);
        Integer pxMaxDiameter = (int) VisionUtils.toPixels(
                new Length(sprocketHoleDiameterMm * 1.1, LengthUnit.Millimeters), camera);
        pipeline.setProperty("DetectFixedCirclesHough.minDistance", pxMinDistance);
        pipeline.setProperty("DetectFixedCirclesHough.minDiameter", pxMinDiameter);
        pipeline.setProperty("DetectFixedCirclesHough.maxDiameter", pxMaxDiameter);
        pipeline.setProperty("sprocketHole.diameter",
                new Length(sprocketHoleDiameterMm, LengthUnit.Millimeters));
        pipeline.setProperty("sprocketHole.maxDistance",
                new Length(SPROCKET_PITCH_MM * 0.6, LengthUnit.Millimeters));
        pipeline.process();

        // Log circle count so detection issues are immediately visible in the log.
        {
            List<CvStage.Result.Circle> found = getResultCircles(pipeline);
            LOG.info("Pipeline 'results' stage: {} circle(s) detected: {}",
                    found != null ? found.size() : "null", found);
        }

        // Image dimensions for centre-of-frame reference.
        int imgWidth = 0, imgHeight = 0;
        for (CvStage stage : pipeline.getStages()) {
            CvStage.Result r = pipeline.getResult(stage);
            if (r != null && r.image != null) {
                imgWidth = r.image.cols();
                imgHeight = r.image.rows();
                break;
            }
        }
        double imgCx = imgWidth / 2.0;
        double imgCy = imgHeight / 2.0;

        // Pixels-per-mm from the camera's calibrated UPP.
        Location upp = camera.getUnitsPerPixelAtZ().convertToUnits(LengthUnit.Millimeters);
        double pxPerMm = 2.0 / (upp.getX() + upp.getY());

        // Read circles from the "results" stage (DetectCircularSymmetry output).
        List<CvStage.Result.Circle> allCircles = getResultCircles(pipeline);
        if (allCircles == null || allCircles.isEmpty()) {
            saveAndShow(camera, pipeline, null, null, -1, -1, null, null);
            return new Result(false, "No circles from 'results' stage. Check pipeline.", null, null, null, 0);
        }

        // Score filter: keep circles with score >= MIN_SCORE.
        List<CvStage.Result.Circle> scoredCircles = new ArrayList<>();
        for (CvStage.Result.Circle c : allCircles) {
            if (circleScore(c) >= MIN_SCORE) {
                scoredCircles.add(c);
            }
        }
        if (scoredCircles.isEmpty()) {
            saveAndShow(camera, pipeline, allCircles, null, -1, -1, null, null);
            return new Result(false,
                    String.format("No circles with score >= %.1f (%d total). Best: %s",
                            MIN_SCORE, allCircles.size(), topScoreStr(allCircles, 3)),
                    null, null, null, 0);
        }

        // Find sprocket pair using three filters (logged per-pair):
        //   1. Euclidean distance ≈ sprocketPitchMm ± 1.5 mm
        //   2. Alignment: for VERTICAL orientations |dx| < alignmentTolerancePx;
        //                 for HORIZONTAL orientations |dy| < alignmentTolerancePx
        //   3. Among survivors, pick pair whose midpoint is closest to image centre.
        double pitchPx = SPROCKET_PITCH_MM * pxPerMm;
        double tolPx   = 1.5 * pxPerMm;
        boolean verticalOrientation = true;
        int bestI = -1, bestJ = -1;
        double bestMidDist = Double.MAX_VALUE;
        for (int i = 0; i < scoredCircles.size(); i++) {
            CvStage.Result.Circle ci = scoredCircles.get(i);
            for (int j = i + 1; j < scoredCircles.size(); j++) {
                CvStage.Result.Circle cj = scoredCircles.get(j);
                double dist = Math.hypot(cj.x - ci.x, cj.y - ci.y);
                double distMm = dist / pxPerMm;
                double alignDelta = verticalOrientation
                        ? Math.abs(cj.x - ci.x)
                        : Math.abs(cj.y - ci.y);
                String axisLabel = verticalOrientation ? "dx" : "dy";

                boolean distOk = Math.abs(dist - pitchPx) <= tolPx;
                boolean alignOk = alignDelta <= alignmentTolerancePx;

                if (!distOk) {
                    LOG.debug("PAIR ({},{})<->({},{}): distance={:.2f}mm FAIL (expected {:.1f}±1.5)",
                            (int)ci.x, (int)ci.y, (int)cj.x, (int)cj.y,
                            distMm, SPROCKET_PITCH_MM);
                    continue;
                }
                if (!alignOk) {
                    LOG.info("PAIR ({},{})<->({},{}): distance={:.2f}mm OK, alignment_{}={:.0f}px FAIL (tol={:.0f})",
                            (int)ci.x, (int)ci.y, (int)cj.x, (int)cj.y,
                            distMm, axisLabel, alignDelta, alignmentTolerancePx);
                    continue;
                }

                double mx = (ci.x + cj.x) / 2.0;
                double my = (ci.y + cj.y) / 2.0;
                double midDist = Math.hypot(mx - imgCx, my - imgCy);
                LOG.info("PAIR ({},{})<->({},{}): distance={:.2f}mm OK, alignment_{}={:.0f}px OK, midpoint_dist={:.1f}px{}",
                        (int)ci.x, (int)ci.y, (int)cj.x, (int)cj.y,
                        distMm, axisLabel, alignDelta, midDist,
                        midDist < bestMidDist ? " → SELECTED" : " → not best");
                if (midDist < bestMidDist) {
                    bestMidDist = midDist;
                    bestI = i;
                    bestJ = j;
                }
            }
        }
        if (bestI < 0) {
            saveAndShow(camera, pipeline, allCircles, scoredCircles, -1, -1, null, null);
            return new Result(false,
                    String.format("No valid pair found among %d scored circles. "
                            + "Filters: pitch=%.1f±1.5mm, alignment<%dpx. "
                            + "Check sprocket pitch and alignment tolerance settings.",
                            scoredCircles.size(), SPROCKET_PITCH_MM, (int) alignmentTolerancePx),
                    null, null, null, 0);
        }

        CvStage.Result.Circle c1 = scoredCircles.get(bestI);
        CvStage.Result.Circle c2 = scoredCircles.get(bestJ);
        org.opencv.core.Point midpoint = new org.opencv.core.Point(
                (c1.x + c2.x) / 2.0, (c1.y + c2.y) / 2.0);

        // EIA-481 geometry: pocket pixel position from sprocket midpoint.
        // PhotonFeeder tape is always VERTICAL_HOLES_LEFT: holes on left, pocket to the right.
        // Pixel x increases right, y increases down.
        double perpPx  = computePerpOffsetMm() * pxPerMm;
        double alongPx = alongOffsetMm * pxPerMm;
        double pocketPx = midpoint.x + perpPx;
        double pocketPy = midpoint.y + alongPx;
        org.opencv.core.Point pocketPixel = new org.opencv.core.Point(pocketPx, pocketPy);

        LOG.info("Pair: ({},{}) score={} / ({},{}) score={}, midpoint ({},{}), pocket px ({},{})",
                (int) c1.x, (int) c1.y, String.format("%.2f", circleScore(c1)),
                (int) c2.x, (int) c2.y, String.format("%.2f", circleScore(c2)),
                (int) midpoint.x, (int) midpoint.y, (int) pocketPx, (int) pocketPy);

        saveAndShow(camera, pipeline, allCircles, scoredCircles, bestI, bestJ, midpoint, pocketPixel);

        // Convert pocket pixel coords to machine location.
        Location pocketLocation = VisionUtils.getPixelLocation(camera, pocketPx, pocketPy)
                .convertToUnits(LengthUnit.Millimeters);

        // Sanity-check: pocket must be within MAX_CORRECTION_MM of expected pick location.
        double mag = Math.hypot(
                pocketLocation.getX() - pickLocation.getX(),
                pocketLocation.getY() - pickLocation.getY());

        LOG.info("Pocket machine location: {}", pocketLocation);
        LOG.info("Distance from expected pick: {}mm", String.format("%.3f", mag));

        if (mag > MAX_CORRECTION_MM) {
            return new Result(false,
                    String.format("Pocket is %.3f mm from expected pick (limit %.1f mm). "
                            + "Check orientation and perp-offset settings.", mag, MAX_CORRECTION_MM),
                    null, null, pocketPixel, 0);
        }

        // If a previous calibration wrote to Part Offset, clear it to (0,0,0,0) so it
        // doesn't compound with the slot location we're about to write.
        Location currentOffset = feeder.getOffset();
        if (currentOffset != null) {
            Location zero = new Location(LengthUnit.Millimeters, 0, 0,
                    currentOffset.convertToUnits(LengthUnit.Millimeters).getZ(),
                    currentOffset.convertToUnits(LengthUnit.Millimeters).getRotation());
            boolean nonZeroXY = Math.abs(currentOffset.convertToUnits(LengthUnit.Millimeters).getX()) > 1e-6
                    || Math.abs(currentOffset.convertToUnits(LengthUnit.Millimeters).getY()) > 1e-6;
            if (nonZeroXY) {
                feeder.setOffset(zero);
                LOG.info("Cleared non-zero Part Offset XY to 0 (was X={} Y={})",
                        String.format("%.4f", currentOffset.convertToUnits(LengthUnit.Millimeters).getX()),
                        String.format("%.4f", currentOffset.convertToUnits(LengthUnit.Millimeters).getY()));
            }
        }

        // Update ONLY slot location X/Y — preserve Z and Rotation from the slot.
        Location currentSlot = feeder.getSlot().getLocation().convertToUnits(LengthUnit.Millimeters);
        Location newSlot = new Location(LengthUnit.Millimeters,
                pocketLocation.getX(),
                pocketLocation.getY(),
                currentSlot.getZ(),
                currentSlot.getRotation());
        feeder.getSlot().setLocation(newSlot);  // Slot fires "location" → SlotProxy → UI bindings
        Configuration.get().save();
        LOG.info("Saved new slot location: X={} Y={} Z={} R={}",
                String.format("%.4f", newSlot.getX()),
                String.format("%.4f", newSlot.getY()),
                String.format("%.4f", newSlot.getZ()),
                String.format("%.4f", newSlot.getRotation()));

        // Move camera to computed pocket location for visual confirmation.
        camera.moveTo(camera.getLocation().derive(pocketLocation, true, true, false, false));

        return new Result(true,
                String.format("OK. Slot X=%.3f Y=%.3f (was X=%.3f Y=%.3f). Camera over pocket — verify image.",
                        newSlot.getX(), newSlot.getY(), currentSlot.getX(), currentSlot.getY()),
                null, newSlot, pocketPixel, 0);
    }

    // Returns the DetectCircularSymmetry score, or MAX_VALUE for plain Circle objects.
    private static double circleScore(CvStage.Result.Circle c) {
        if (c instanceof DetectCircularSymmetry.SymmetryCircle) {
            return ((DetectCircularSymmetry.SymmetryCircle) c).score;
        }
        return Double.MAX_VALUE;
    }

    // Top-N scores as a comma-separated string for error messages.
    private static String topScoreStr(List<CvStage.Result.Circle> circles, int n) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (CvStage.Result.Circle c : circles) {
            if (count++ >= n) {
                break;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(String.format("%.2f", circleScore(c)));
        }
        return sb.toString();
    }

    private List<CvStage.Result.Circle> getResultCircles(CvPipeline pipeline) {
        try {
            return pipeline.getExpectedResult(VisionUtils.PIPELINE_RESULTS_NAME)
                    .getExpectedListModel(CvStage.Result.Circle.class, null);
        }
        catch (Exception e) {
            LOG.warn("No circles from '{}' stage: {}", VisionUtils.PIPELINE_RESULTS_NAME, e.getMessage());
            return null;
        }
    }

    /**
     * Build annotated debug Mat, save to /tmp/pocket_cal_last.png,
     * and display in the main camera view for 5 seconds.
     *
     * Legend:
     *   White crosshair  = image centre (expected pick location)
     *   Red circles      = all detected circles, with score labels
     *   Blue circles     = score-filtered (>= MIN_SCORE), not selected
     *   Green circles    = selected sprocket pair (thick)
     *   Green dot        = midpoint of pair
     *   Yellow crosshair = computed pocket location
     */
    private void saveAndShow(Camera camera, CvPipeline pipeline,
            List<CvStage.Result.Circle> allCircles,
            List<CvStage.Result.Circle> scoredCircles,
            int selectedI, int selectedJ,
            org.opencv.core.Point midpoint,
            org.opencv.core.Point pocketPixel) {
        try {
            Mat img = null;
            for (CvStage stage : pipeline.getStages()) {
                CvStage.Result r = pipeline.getResult(stage);
                if (r != null && r.image != null && r.image.channels() >= 3) {
                    img = r.image.clone();
                    break;
                }
            }
            if (img == null) {
                for (CvStage stage : pipeline.getStages()) {
                    CvStage.Result r = pipeline.getResult(stage);
                    if (r != null && r.image != null) {
                        img = r.image.clone();
                        break;
                    }
                }
            }
            if (img == null) {
                LOG.warn("saveAndShow: no stage image available");
                return;
            }
            if (img.channels() == 1) {
                Imgproc.cvtColor(img, img, Imgproc.COLOR_GRAY2BGR);
            }

            double cx = img.cols() / 2.0;
            double cy = img.rows() / 2.0;
            Scalar white  = new Scalar(255, 255, 255);
            Scalar red    = new Scalar(0, 80, 255);
            Scalar blue   = new Scalar(255, 80, 0);
            Scalar green  = new Scalar(0, 255, 0);
            Scalar yellow = new Scalar(0, 255, 255);

            // White crosshair = image centre
            int arm = 30;
            Imgproc.line(img, new org.opencv.core.Point(cx - arm, cy),
                    new org.opencv.core.Point(cx + arm, cy), white, 1);
            Imgproc.line(img, new org.opencv.core.Point(cx, cy - arm),
                    new org.opencv.core.Point(cx, cy + arm), white, 1);

            // Red: all circles with score label
            if (allCircles != null) {
                for (CvStage.Result.Circle c : allCircles) {
                    int r = Math.max((int)(c.diameter / 2), 5);
                    Imgproc.circle(img, new org.opencv.core.Point(c.x, c.y), r, red, 1);
                    double score = circleScore(c);
                    if (score != Double.MAX_VALUE) {
                        Imgproc.putText(img, String.format("%.1f", score),
                                new org.opencv.core.Point(c.x + r + 2, c.y + 4),
                                Imgproc.FONT_HERSHEY_SIMPLEX, 0.35, red, 1);
                    }
                }
            }

            // Blue: scored but not selected
            if (scoredCircles != null) {
                for (int i = 0; i < scoredCircles.size(); i++) {
                    if (i == selectedI || i == selectedJ) {
                        continue;
                    }
                    CvStage.Result.Circle c = scoredCircles.get(i);
                    int r = Math.max((int)(c.diameter / 2), 5);
                    Imgproc.circle(img, new org.opencv.core.Point(c.x, c.y), r, blue, 2);
                }
                // Green: selected pair
                for (int idx : new int[]{selectedI, selectedJ}) {
                    if (idx >= 0 && idx < scoredCircles.size()) {
                        CvStage.Result.Circle c = scoredCircles.get(idx);
                        int r = Math.max((int)(c.diameter / 2), 5);
                        Imgproc.circle(img, new org.opencv.core.Point(c.x, c.y), r, green, 3);
                        Imgproc.circle(img, new org.opencv.core.Point(c.x, c.y), 4, green, -1);
                    }
                }
            }

            // Green dot = midpoint
            if (midpoint != null) {
                Imgproc.circle(img, midpoint, 5, green, -1);
            }

            // Yellow crosshair + circle = computed pocket
            if (pocketPixel != null) {
                int parm = 35;
                Imgproc.line(img, new org.opencv.core.Point(pocketPixel.x - parm, pocketPixel.y),
                        new org.opencv.core.Point(pocketPixel.x + parm, pocketPixel.y), yellow, 2);
                Imgproc.line(img, new org.opencv.core.Point(pocketPixel.x, pocketPixel.y - parm),
                        new org.opencv.core.Point(pocketPixel.x, pocketPixel.y + parm), yellow, 2);
                Imgproc.circle(img, pocketPixel, 10, yellow, 2);
                if (midpoint != null) {
                    Imgproc.line(img, midpoint, pocketPixel, yellow, 1);
                }
            }

            Imgcodecs.imwrite("/tmp/pocket_cal_last.png", img);
            LOG.info("Debug image -> /tmp/pocket_cal_last.png  all={} scored={}",
                    allCircles == null ? 0 : allCircles.size(),
                    scoredCircles == null ? 0 : scoredCircles.size());

            // Show annotated image in main camera view for 5 seconds.
            try {
                final BufferedImage buffered = OpenCvUtils.toBufferedImage(img);
                MainFrame.get().getCameraViews().getCameraView(camera)
                        .showFilteredImage(buffered, "Pocket Calibration", 5000);
            }
            catch (Exception e) {
                LOG.warn("Could not show result in camera view: {}", e.getMessage());
            }

            img.release();
        }
        catch (Exception e) {
            LOG.warn("Failed to save/show debug image", e);
        }
    }

    public CvPipeline getPipeline() { return pipeline; }
    public void setPipeline(CvPipeline pipeline) { this.pipeline = pipeline; }

    public double getRoiRadiusMm() { return roiRadiusMm; }
    public void setRoiRadiusMm(double v) { this.roiRadiusMm = v; }

    public double getTapeWidthMm() { return tapeWidthMm; }
    public void setTapeWidthMm(double v) { this.tapeWidthMm = v; }

    public double getPartPitchMm() { return partPitchMm; }
    public void setPartPitchMm(double v) { this.partPitchMm = v; }

    /** EIA-481 F dimension: 8→3.5, 12→5.5, 16→7.5, 24→11.5 mm. */
    public double computePerpOffsetMm() {
        if (tapeWidthMm <= 8.0) {
            return 3.5;
        }
        if (tapeWidthMm <= 12.0) {
            return 5.5;
        }
        if (tapeWidthMm <= 16.0) {
            return 7.5;
        }
        return 11.5;
    }

    public double getAlongOffsetMm() { return alongOffsetMm; }
    public void setAlongOffsetMm(double v) { this.alongOffsetMm = v; }

    public boolean isFeedBeforeCalibrate() { return feedBeforeCalibrate; }
    public void setFeedBeforeCalibrate(boolean v) { this.feedBeforeCalibrate = v; }

    public double getAlignmentTolerancePx() { return alignmentTolerancePx; }
    public void setAlignmentTolerancePx(double v) { this.alignmentTolerancePx = v; }

    public double getSprocketHoleDiameterMm() { return sprocketHoleDiameterMm; }
    public void setSprocketHoleDiameterMm(double v) { this.sprocketHoleDiameterMm = v; }

    public static CvPipeline createDefaultPipeline() {
        try (InputStream in = PocketCalibrator.class.getResourceAsStream(
                "PocketCalibrator-default-pipeline.xml")) {
            if (in == null) {
                throw new IOException("Default pipeline resource not found");
            }
            return new CvPipeline(new String(in.readAllBytes()));
        }
        catch (Exception e) {
            LOG.error("Failed to load default pocket calibration pipeline", e);
            return new CvPipeline();
        }
    }

    public void resetPipeline() {
        this.pipeline = createDefaultPipeline();
    }
}
