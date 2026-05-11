package org.openpnp.machine.photon.calibration;

import java.awt.event.ActionEvent;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.TitledBorder;

import org.openpnp.gui.MainFrame;
import org.openpnp.model.Configuration;
import org.openpnp.machine.photon.PhotonFeeder;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Machine;
import org.openpnp.util.UiUtils;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.ui.CvPipelineEditor;
import org.openpnp.vision.pipeline.ui.CvPipelineEditorDialog;

import com.jgoodies.forms.layout.ColumnSpec;
import com.jgoodies.forms.layout.FormLayout;
import com.jgoodies.forms.layout.FormSpecs;
import com.jgoodies.forms.layout.RowSpec;

/**
 * UI panel for pocket calibration controls.
 */
public class PocketCalibrationPanel extends JPanel {

    private final PhotonFeeder feeder;
    private PocketCalibrator calibrator;

    public PocketCalibrationPanel(PhotonFeeder feeder) {
        this.feeder = feeder;
        this.calibrator = getOrCreateCalibrator(feeder);

        setBorder(new TitledBorder(null, "Pocket Calibration",
                TitledBorder.LEADING, TitledBorder.TOP, null, null));

        // 9-column layout: gap, grow, gap, def, gap, def, gap, def, gap
        setLayout(new FormLayout(new ColumnSpec[]{
                FormSpecs.RELATED_GAP_COLSPEC,
                ColumnSpec.decode("default:grow"),
                FormSpecs.RELATED_GAP_COLSPEC,
                FormSpecs.DEFAULT_COLSPEC,
                FormSpecs.RELATED_GAP_COLSPEC,
                FormSpecs.DEFAULT_COLSPEC,
                FormSpecs.RELATED_GAP_COLSPEC,
                FormSpecs.DEFAULT_COLSPEC,
                FormSpecs.RELATED_GAP_COLSPEC,},
                new RowSpec[]{
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,}));

        // Computed offset label declared early so tape width listener can update it.
        JLabel perpOffsetLabel = new JLabel("Computed pocket offset: "
                + String.format("%.1f", calibrator.computePerpOffsetMm()) + " mm");

        // Row 1: Tape width
        add(new JLabel("Tape width:"), "2, 2, right, default");
        JComboBox<String> tapeWidthCombo = new JComboBox<>(new String[]{"8 mm", "12 mm", "16 mm", "24 mm"});
        tapeWidthCombo.setSelectedItem((int) calibrator.getTapeWidthMm() + " mm");
        tapeWidthCombo.addActionListener(e -> {
            String sel = (String) tapeWidthCombo.getSelectedItem();
            calibrator.setTapeWidthMm(Double.parseDouble(sel.replace(" mm", "").trim()));
            perpOffsetLabel.setText("Computed pocket offset: "
                    + String.format("%.1f", calibrator.computePerpOffsetMm()) + " mm");
        });
        add(tapeWidthCombo, "4, 2");

        // Row 2: Part pitch
        add(new JLabel("Part pitch:"), "2, 4, right, default");
        JComboBox<String> partPitchCombo = new JComboBox<>(new String[]{"2 mm", "4 mm", "8 mm", "12 mm"});
        partPitchCombo.setSelectedItem((int) calibrator.getPartPitchMm() + " mm");
        partPitchCombo.addActionListener(e -> {
            String sel = (String) partPitchCombo.getSelectedItem();
            calibrator.setPartPitchMm(Double.parseDouble(sel.replace(" mm", "").trim()));
        });
        add(partPitchCombo, "4, 4");

        // Row 3: Computed pocket offset (read-only, updates when tape width changes)
        add(perpOffsetLabel, "2, 6, 7, 1");

        // Row 4: ROI radius + Along offset
        add(new JLabel("ROI radius (mm):"), "2, 8, right, default");
        JSpinner roiSpinner = new JSpinner(new SpinnerNumberModel(
                calibrator.getRoiRadiusMm(), 0.5, 20.0, 0.5));
        roiSpinner.addChangeListener(e -> calibrator.setRoiRadiusMm((Double) roiSpinner.getValue()));
        add(roiSpinner, "4, 8");

        add(new JLabel("Along offset (mm):"), "6, 8, right, default");
        JSpinner alongSpinner = new JSpinner(new SpinnerNumberModel(
                calibrator.getAlongOffsetMm(), -5.0, 5.0, 0.5));
        alongSpinner.addChangeListener(e -> calibrator.setAlongOffsetMm((Double) alongSpinner.getValue()));
        add(alongSpinner, "8, 8");

        // Row 5: Align tolerance + Feed before calibrate
        add(new JLabel("Align tolerance (px):"), "2, 10, right, default");
        JSpinner alignSpinner = new JSpinner(new SpinnerNumberModel(
                calibrator.getAlignmentTolerancePx(), 5.0, 500.0, 5.0));
        alignSpinner.addChangeListener(e -> calibrator.setAlignmentTolerancePx((Double) alignSpinner.getValue()));
        add(alignSpinner, "4, 10");

        add(new JLabel("Feed before calibrate:"), "6, 10, right, default");
        JCheckBox feedCheckBox = new JCheckBox();
        feedCheckBox.setSelected(calibrator.isFeedBeforeCalibrate());
        feedCheckBox.addActionListener(e -> calibrator.setFeedBeforeCalibrate(feedCheckBox.isSelected()));
        add(feedCheckBox, "8, 10");

        // Row 6: Edit Pipeline + Reset Pipeline
        add(new JButton(editPipelineAction), "6, 12");
        add(new JButton(resetPipelineAction), "8, 12");

        // Row 7: Calibrate Pocket Now (full width)
        JButton calibrateBtn = new JButton(calibrateAction);
        calibrateBtn.setText("Calibrate Pocket Now");
        add(calibrateBtn, "2, 14, 7, 1, fill, fill");
    }

    private PocketCalibrator getOrCreateCalibrator(PhotonFeeder feeder) {
        PocketCalibrator c = feeder.getPocketCalibrator();
        if (c == null) {
            c = new PocketCalibrator();
            feeder.setPocketCalibrator(c);
        }
        return c;
    }

    private final AbstractAction calibrateAction = new AbstractAction("Calibrate") {
        @Override
        public void actionPerformed(ActionEvent e) {
            UiUtils.submitUiMachineTask(() -> {
                Machine machine = Configuration.get().getMachine();
                Camera camera = machine.getDefaultHead().getDefaultCamera();
                PocketCalibrator.Result result = calibrator.calibrate(feeder, camera);
                javax.swing.SwingUtilities.invokeLater(() -> {
                    if (result.success) {
                        JOptionPane.showMessageDialog(
                                PocketCalibrationPanel.this,
                                result.message,
                                "Pocket Calibration",
                                JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        JOptionPane.showMessageDialog(
                                PocketCalibrationPanel.this,
                                result.message,
                                "Pocket Calibration Failed",
                                JOptionPane.WARNING_MESSAGE);
                    }
                });
                return null;
            });
        }
    };

    private final AbstractAction editPipelineAction = new AbstractAction("Edit Pipeline") {
        @Override
        public void actionPerformed(ActionEvent e) {
            UiUtils.messageBoxOnException(() -> {
                CvPipeline pipeline = calibrator.getPipeline();
                org.openpnp.spi.Camera camera =
                        Configuration.get().getMachine().getDefaultHead().getDefaultCamera();
                pipeline.setProperty("camera", camera);
                pipeline.setProperty("feeder", feeder);
                pipeline.setProperty("sprocketHole.diameter",
                        new org.openpnp.model.Length(
                                calibrator.getSprocketHoleDiameterMm(),
                                org.openpnp.model.LengthUnit.Millimeters));
                pipeline.setProperty("sprocketHole.maxDistance",
                        new org.openpnp.model.Length(
                                4.0 * 0.6,
                                org.openpnp.model.LengthUnit.Millimeters));
                Integer pxMinDistance = (int) org.openpnp.util.VisionUtils.toPixels(
                        new org.openpnp.model.Length(
                                4.0 * 0.9,
                                org.openpnp.model.LengthUnit.Millimeters), camera);
                Integer pxMinDiameter = (int) org.openpnp.util.VisionUtils.toPixels(
                        new org.openpnp.model.Length(
                                calibrator.getSprocketHoleDiameterMm() * 0.9,
                                org.openpnp.model.LengthUnit.Millimeters), camera);
                Integer pxMaxDiameter = (int) org.openpnp.util.VisionUtils.toPixels(
                        new org.openpnp.model.Length(
                                calibrator.getSprocketHoleDiameterMm() * 1.1,
                                org.openpnp.model.LengthUnit.Millimeters), camera);
                pipeline.setProperty("DetectFixedCirclesHough.minDistance", pxMinDistance);
                pipeline.setProperty("DetectFixedCirclesHough.minDiameter", pxMinDiameter);
                pipeline.setProperty("DetectFixedCirclesHough.maxDiameter", pxMaxDiameter);
                CvPipelineEditor editor = new CvPipelineEditor(pipeline);
                CvPipelineEditorDialog dialog = new CvPipelineEditorDialog(
                        MainFrame.get(),
                        feeder.getName() + " — Pocket Calibration Pipeline",
                        editor);
                dialog.setVisible(true);
            });
        }
    };

    private final AbstractAction resetPipelineAction = new AbstractAction("Reset Pipeline") {
        @Override
        public void actionPerformed(ActionEvent e) {
            int confirm = JOptionPane.showConfirmDialog(
                    PocketCalibrationPanel.this,
                    "Reset pocket calibration pipeline to default?",
                    "Reset Pipeline", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                calibrator.resetPipeline();
            }
        }
    };
}
