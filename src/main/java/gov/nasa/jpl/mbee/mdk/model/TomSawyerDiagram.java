package gov.nasa.jpl.mbee.mdk.model;

import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.ui.ProjectWindow;
import com.nomagic.magicdraw.ui.WindowComponentInfo;
import com.nomagic.magicdraw.ui.WindowsManager;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.EnumerationLiteral;
import com.tomsawyer.canvas.TSViewportCanvas;
import com.tomsawyer.canvas.image.svg.TSSVGImageCanvas;
import com.tomsawyer.canvas.image.svg.TSSVGImageCanvasPreferenceTailor;
import com.tomsawyer.canvas.rendering.TSRenderingPreferenceTailor;
import com.tomsawyer.magicdraw.action.DocGenDelegateExtension;
import com.tomsawyer.magicdraw.action.TSActionConstants;
import com.tomsawyer.util.preference.TSPreferenceData;
import gov.nasa.jpl.mbee.mdk.docgen.DocGenProfile;
import gov.nasa.jpl.mbee.mdk.docgen.docbook.DBTomSawyerDiagram;
import gov.nasa.jpl.mbee.mdk.docgen.docbook.DocumentElement;
import gov.nasa.jpl.mbee.mdk.util.GeneratorUtils;

import javax.swing.*;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static gov.nasa.jpl.mbee.mdk.model.TomSawyerDiagram.diagramType.*;
import static gov.nasa.jpl.mbee.mdk.model.TomSawyerDiagram.diagramType.Table;


/**
 * Created by johannes on 11/21/16.
 */
public class TomSawyerDiagram extends Query {
    private diagramType type;
    private int imagenum = 0;

    public void setType(diagramType type) {
        this.type = type;
    }

    protected static final String BDD_DRAWING_VIEW_NAME = "Block Definition Diagram";
    protected static final String IBD_DRAWING_VIEW_NAME = "Internal Block Diagram";
    protected static final String STATE_MACHINE_DRAWING_VIEW_NAME = "State Machine";
    protected static final String ACTIVITY_DIAGRAM_DRAWING_VIEW_NAME = "Activity Diagram";

    public enum diagramType {
        Block_Definition_Diagram, Internal_Block_Diagram, State_Machine_Diagram, Activity_Diagram, Sequence_Diagram, Table
    }


    public TomSawyerDiagram() {
        super();
    }

    @SuppressWarnings("unchecked")
    @Override
    public void initialize() {
        Object enumliteral = GeneratorUtils.getObjectProperty(dgElement, DocGenProfile.tomsawyerDiagramStereotype,
                "diagram_type", false);
        if (enumliteral instanceof String) {
            setType(diagramType.valueOf(enumliteral.toString()));
        }
        if (enumliteral instanceof EnumerationLiteral) {
            ((EnumerationLiteral) enumliteral).getEnumeration();
            switch (((EnumerationLiteral) enumliteral).getName()) {
                case "Block_Definition_Diagram":
                    setType(Block_Definition_Diagram);
                    break;
                case "Internal_Block_Diagram":
                    setType(Internal_Block_Diagram);
                    break;
                case "State_Machine_Diagram":
                    setType(State_Machine_Diagram);
                    break;
                case "Activity_Diagram":
                    setType(Activity_Diagram);
                    break;
                case "Sequence_Diagram":
                    setType(Sequence_Diagram);
                    break;
                case "Table":
                    setType(Table);
                    break;
                default:
            }
        }
    }

//    public List<DocumentElement> visit(boolean forViewEditor, String outputDir) {
//        List<Element> elements = new ArrayList<>();
//        for (Object ob : this.getTargets()) {
//            if (ob instanceof Element) {
//                elements.add((Element) ob);
//            }
//        }
//        DBTomSawyerDiagram dbts = new DBTomSawyerDiagram(elements);
//        dbts.setType(type);
//        return Collections.singletonList(dbts);
//
//    }

    public List<DocumentElement> visit(boolean forViewEditor, String outputDir) {
        List<Element> elements = new ArrayList<>();
        for (Object ob : this.getTargets()) {
            if (ob instanceof Element) {
                elements.add((Element) ob);
            }
        }

        DocGenDelegateExtension delegate = new DocGenDelegateExtension();
        delegate.addObjectsToShow(elements); //Johannes' method.
        DBTomSawyerDiagram dbts = new DBTomSawyerDiagram(elements);
        if (type != null) {
            dbts.setType(type);
            switch (type) {
                case Block_Definition_Diagram:
                    delegate.init("bdd", "Docgen Generated Block Diagram", BDD_DRAWING_VIEW_NAME);
                    break;
                case Internal_Block_Diagram:
                    delegate.init("ibd", "Docgen Generated Internal Block Diagram", IBD_DRAWING_VIEW_NAME);
                    break;
                case State_Machine_Diagram:
                    delegate.init("sm", "Docgen Generated State Machine Diagram", STATE_MACHINE_DRAWING_VIEW_NAME);
                    break;
                case Activity_Diagram:
                    delegate.init("ad", "Docgen Generated Activity Diagram", ACTIVITY_DIAGRAM_DRAWING_VIEW_NAME);
                    break;
                case Sequence_Diagram:
                    delegate.init("sequence diagram not implemented", "equence diagram not implemented", BDD_DRAWING_VIEW_NAME);
                    break;
                case Table:
                    delegate.init("table not implemented", "table not implemented", BDD_DRAWING_VIEW_NAME);
                    break;
                default:
            }
        } else {
            delegate.init("bdd", "Docgen Generated Block Diagram", BDD_DRAWING_VIEW_NAME);
        }
        try {
            delegate.loadData();
        } catch (Exception exception) {
            exception.printStackTrace();
            JOptionPane.showMessageDialog(null, exception.getMessage());
        }
        Set<String> viewElements = null;
        if (type.equals(Internal_Block_Diagram)) {
            delegate.postLoadDataIBDAction();
            viewElements = delegate.postLoadDataGetUUID();
        } else {
            viewElements = delegate.postLoadDataGetUUID();
        }


        boolean showInMD = true;
        if (showInMD) {
            TSStandardWindowComponentContent windowComponentContent =
                    new TSStandardWindowComponentContent(delegate);
            ProjectWindow window = new ProjectWindow(new WindowComponentInfo(delegate.getId(), delegate.getName(), TSActionConstants.WINDOW_ICON, WindowsManager.SIDE_EAST, WindowsManager.STATE_DOCKED, false), windowComponentContent);
            Application.getInstance().getMainFrame().getProjectWindowsManager().addWindow(window);
            windowComponentContent.setDividerLocation(0.7);
        }

        boolean printForDocgen = true;
        if (printForDocgen) {
            TSViewportCanvas canvas = delegate.getDiagramDrawing().getCanvas();
            TSPreferenceData preferenceData = new TSPreferenceData();
            FileOutputStream stream = null;
            String svgfilename = this.type + ".svg";
            File svgdiagramFile = new File(outputDir, svgfilename);

            try {
                stream = new FileOutputStream(svgdiagramFile);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }

            TSSVGImageCanvasPreferenceTailor imageCanvasTailor = new TSSVGImageCanvasPreferenceTailor(preferenceData);

            imageCanvasTailor.setExportAll();
            imageCanvasTailor.setScaleByZoomLevel();
            imageCanvasTailor.setScalingZoomLevel(1.0);
            imageCanvasTailor.setWidth(-1);
            imageCanvasTailor.setHeight(-1);

            TSRenderingPreferenceTailor renderingTailor = new TSRenderingPreferenceTailor(preferenceData);

            boolean isDrawNodesBeforeEdges = new TSRenderingPreferenceTailor(canvas.getPreferenceData()).isDrawNodesBeforeEdges();
            renderingTailor.setDrawNodesBeforeEdges(isDrawNodesBeforeEdges);

            renderingTailor.setDrawSelectedOnly(false);
            renderingTailor.setDrawSelectionState(false);
            renderingTailor.setDrawHighlightState(false);
            renderingTailor.setDrawHoverState(false);

            TSSVGImageCanvas imageCanvas = new TSSVGImageCanvas(canvas.getGraphManager(), stream);

            imageCanvas.setDisplayCanvas(canvas);
            imageCanvas.setPreferenceData(preferenceData);
            imageCanvas.paint();
        }


        return Collections.singletonList(dbts);
    }
}
