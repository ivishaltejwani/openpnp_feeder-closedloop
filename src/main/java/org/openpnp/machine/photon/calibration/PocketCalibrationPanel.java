package org.openpnp.machine.photon.calibration;

import java.awt.event.ActionEvent;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.TitledBorder;
import javax.swing.BorderFactory;

import org.openpnp.gui.MainFrame;
import org.openpnp.gui.support.Icons;
import org.openpnp.model.Configuration;
import org.openpnp.machine.photon.PhotonFeeder;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Machine;
import org.openpnp.util.UiUtils;
import org.openpnp.vision.pipeline.ui.CvPipelineEditor;
import org.openpnp.vision.pipeline.ui.CvPipelineEditorDialog;

import com.jgoodies.forms.layout.ColumnSpec;
import com.jgoodies.forms.layout.FormLayout;
import com.jgoodies.forms.layout.FormSpecs;
import com.jgoodies.forms.layout.RowSpec;

/**
 * UI panel for pocket calibration controls. Add this panel to
 * PhotonFeederConfigurationWizard's createUI() method.
 *
 * Provides:
 *   - "Calibrate Pocket" button: runs PocketCalibrator on the feeder
 *   - "Edit Pipeline" button: opens OpenPnP's CvPipeline editor
 *   - "Reset Pipeline" button: restores the default pipeline
 *   - ROI radius spinner: adjusts the search radius
 */
public class PocketCalibrationPanel extends JPanel {

    private final PhotonFeeder feeder;
    private PocketCalibrator calibrator;
    private final JSpinner roiSpinner;

    public PocketCalibrationPanel(PhotonFeeder feeder) {
        this.feeder = feeder;
        this.calibrator = getOrCreateCalibrator(feeder);

        setBorder(new TitledBorder(null, "Pocket Calibration",
                TitledBorder.LEADING, TitledBorder.TOP, null, null));

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
                FormSpecs.RELATED_GAP_ROWSPEC,}));

        // Row 1: ROI controls + Edit Pipeline + Reset
        JLabel roiLabel = new JLabel("ROI radius (mm):");
        add(roiLabel, "2, 2, right, default");

        roiSpinner = new JSpinner(new SpinnerNumberModel(
                calibrator.getRoiRadiusMm(), 0.5, 20.0, 0.5));
        roiSpinner.addChangeListener(e ->
                calibrator.setRoiRadiusMm((Double) roiSpinner.getValue()));
        add(roiSpinner, "4, 2");

        JButton editPipelineBtn = new JButton(editPipelineAction);
        add(editPipelineBtn, "6, 2");

        JButton resetBtn = new JButton(resetPipelineAction);
        add(resetBtn, "8, 2");

        // Row 2: Big Calibrate button (full width)
        JButton calibrateBtn = new JButton(calibrateAction);
        calibrateBtn.setText("Calibrate Pocket Now");
        add(calibrateBtn, "2, 4, 7, 1, fill, fill");
    }

    /**
     * Look up an existing calibrator on the feeder, or create one.
     * Persists by being stored on the feeder via setPocketCalibrator().
     */
    private PocketCalibrator getOrCreateCalibrator(PhotonFeeder feeder) {
        // PhotonFeeder must be modified to add getPocketCalibrator/setPocketCalibrator
        // and serialize it as a child element.
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
                // Show result on EDT
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
                Machine machine = Configuration.get().getMachine();
                Camera camera = machine.getDefaultHead().getDefaultCamera();
                // Use a clone so user can cancel the edit
                org.openpnp.vision.pipeline.CvPipeline pipeline =
                        calibrator.getPipeline().clone();
                pipeline.setProperty("camera", camera);
                pipeline.setProperty("feeder", feeder);

                CvPipelineEditor editor = new CvPipelineEditor(pipeline);
                CvPipelineEditorDialog dialog = new CvPipelineEditorDialog(
                        MainFrame.get(),
                        feeder.getName() + " — Pocket Calibration Pipeline",
                        editor);
                dialog.setVisible(true);

                // On close, save the edited pipeline back
                calibrator.setPipeline(pipeline);
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
