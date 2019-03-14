package com.kaart.openqa;

import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.View;

import org.openstreetmap.josm.actions.LassoModeAction;
import org.openstreetmap.josm.actions.mapmode.MapMode;
import org.openstreetmap.josm.actions.mapmode.SelectAction;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.DataSelectionListener;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.HighlightUpdateListener;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.visitor.BoundingXYVisitor;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.dialogs.LayerListDialog;
import org.openstreetmap.josm.gui.dialogs.LayerListPopup;
import org.openstreetmap.josm.gui.layer.AbstractModifiableLayer;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.progress.swing.PleaseWaitProgressMonitor;
import org.openstreetmap.josm.gui.widgets.HtmlPanel;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.ColorHelper;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Utils;
import org.openstreetmap.josm.tools.ImageProvider.ImageSizes;
import org.openstreetmap.josm.tools.bugreport.BugReport;
import org.openstreetmap.josm.tools.bugreport.ReportedException;

import com.kaart.openqa.profiles.GenericInformation;

public class ErrorLayer extends AbstractModifiableLayer implements MouseListener, DataSelectionListener, HighlightUpdateListener {
	/**
	 * Pattern to detect end of sentences followed by another one, or a link, in western script.
	 * Group 1 (capturing): period, interrogation mark, exclamation mark
	 * Group non capturing: at least one horizontal or vertical whitespace
	 * Group 2 (capturing): a letter (any script), or any punctuation
	 */
	private static final Pattern SENTENCE_MARKS_WESTERN = Pattern.compile("([\\.\\?\\!])(?:[\\h\\v]+)([\\p{L}\\p{Punct}])");

	/**
	 * Pattern to detect end of sentences followed by another one, or a link, in eastern script.
	 * Group 1 (capturing): ideographic full stop
	 * Group 2 (capturing): a letter (any script), or any punctuation
	 */
	private static final Pattern SENTENCE_MARKS_EASTERN = Pattern.compile("(\\u3002)([\\p{L}\\p{Punct}])");

	HashMap<GenericInformation, DataSet> dataSets = new HashMap<>();
	HashMap<GenericInformation, Boolean> enabledSources = new HashMap<>();

	private Node displayedNode;
	private JPanel displayedPanel;
	private JWindow displayedWindow;
	private PaintWindow window;

	ArrayList<Node> previousNodes;

	final String CACHE_DIR;

	EastNorth lastClick;


	/**
	 * Create a new ErrorLayer using a class that extends {@code GenericInformation}
	 * @param type A class that extends {@code GenericInformation}
	 */
	public ErrorLayer(String CACHE_DIR) {
		super(tr("{0} Layers", OpenQA.NAME));
		this.CACHE_DIR = CACHE_DIR;
		hookUpMapViewer();
	}

	/**
	 * Set the error classes
	 * @param types The types of class to get errors for. Must extend GenericInformation.
	 */
	public void setErrorClasses(Class<?> ... types) {
		for (Class<?> type : types) {
			if (!GenericInformation.class.isAssignableFrom(type)) continue;
			try {
				Constructor<?> constructor = type.getConstructor(String.class);
				Object obj = constructor.newInstance(CACHE_DIR);
				if (!(obj instanceof GenericInformation)) continue;
				GenericInformation info = (GenericInformation) obj;
				dataSets.put(info, new DataSet());
				enabledSources.put(info, true);
			} catch (InstantiationException | IllegalAccessException | IllegalArgumentException
					| InvocationTargetException | NoSuchMethodException | SecurityException e) {
				e.printStackTrace();
				new BugReport(new ReportedException(e));
			}
		}
		addListeners();
	}

	public void update() {
		List<OsmDataLayer> dataLayers = MainApplication.getLayerManager().getLayersOfType(OsmDataLayer.class);
		PleaseWaitProgressMonitor progressMonitor = new PleaseWaitProgressMonitor();
		progressMonitor.beginTask(tr("Updating {0} layers", OpenQA.NAME));
		for (GenericInformation type : dataSets.keySet()) {
			for (OsmDataLayer layer : dataLayers) {
				DataSet ds = dataSets.get(type);
				progressMonitor.indeterminateSubTask(tr("Updating {0}", type.getLayerName()));
				if (ds == null || ds.isEmpty()) {
					ds = type.getErrors(layer.getDataSet(), progressMonitor);
				} else {
					ds.mergeFrom(type.getErrors(layer.getDataSet(), progressMonitor));
				}
				dataSets.put(type, ds);
			}
		}
		progressMonitor.finishTask();
		progressMonitor.close();
		invalidate();
	}

	/**
	 * Add this class to a map viewer. Usually called during initialization.
	 */
	public void hookUpMapViewer() {
		MainApplication.getMap().mapView.addMouseListener(this);
		addListeners();
	}

	private void addListeners() {
		for (DataSet ds : dataSets.values()) {
			ds.addHighlightUpdateListener(this);
			ds.addSelectionListener(this);
		}
	}

	@Override
	public synchronized void destroy() {
		MainApplication.getMap().mapView.removeMouseListener(this);
		for (DataSet ds : dataSets.values()) {
			try {
				ds.removeHighlightUpdateListener(this);
				ds.removeSelectionListener(this);
			} catch (IllegalArgumentException e) {
				Logging.debug(e.getMessage());
			}
		}
		hideNodeWindow();
		super.destroy();
	}

	/**
	 * Add notes from a {@code DataSet}
	 * @param newDataSet {@code DataSet} with notes
	 * @return true if added
	 */
	public boolean addNotes(GenericInformation type, DataSet newDataSet) {
		for (GenericInformation currentType : dataSets.keySet()) {
			if (currentType.getClass().equals(type.getClass())) {
				dataSets.get(currentType).mergeFrom(newDataSet);
				break;
			}
		}
		return true;
	}

	@Override
	public boolean isModified() {
		boolean modified = false;
		for (DataSet ds : dataSets.values()) {
			if (ds.isModified()) {
				modified = true;
				break;
			}
		}
		return modified;
	}

	@Override
	public void paint(Graphics2D g, MapView mv, Bounds bbox) {
		if (window == null) {
			window = new PaintWindow(g, mv, bbox);
		} else {
			window.setGraphics2d(g);
			window.setMapView(mv);
		}
		window.run();
	}

	private class PaintWindow implements Runnable {
		Graphics2D g;
		MapView mv;

		public PaintWindow(Graphics2D g, MapView mv, Bounds bbox) {
			this.g = g;
			this.mv = mv;
		}

		public void setGraphics2d(Graphics2D g) {
			this.g = g;
		}

		public void setMapView(MapView mv) {
			this.mv = mv;
		}

		@Override
		public void run() {
			for (GenericInformation type : dataSets.keySet()) {
				if (enabledSources.containsKey(type) && !enabledSources.get(type)) continue;
				realrun(type);
			}
		}

		private void realrun(GenericInformation type) {
			final ImageSizes size = ImageProvider.ImageSizes.LARGEICON;
			DataSet ds = dataSets.get(type);
			for (Node node : ds.getNodes()) {
				Point p = mv.getPoint(node.getCoor());
				String error = type.getError(node);
				ImageIcon icon = type.getIcon(error, size);
				int width = icon.getIconWidth();
				int height = icon.getIconHeight();
				g.drawImage(icon.getImage(), p.x - (width / 2), p.y - (height / 2), MainApplication.getMap().mapView);
			}
			createNodeWindow(g, mv, size);
		}

		private void createNodeWindow(Graphics2D g, MapView mv, ImageSizes size) {
			HashMap<GenericInformation, ArrayList<Node>> selectedErrors = new HashMap<>();

			for (GenericInformation type : dataSets.keySet()) {
				DataSet ds = dataSets.get(type);
				ArrayList<Node> selectedNodes = new ArrayList<>(ds.getSelectedNodes());
				selectedNodes.sort(null);
				if (!selectedNodes.isEmpty()) {
					selectedErrors.put(type, selectedNodes);
				}
			}
			MapMode mode = MainApplication.getMap().mapMode;
			if (!selectedErrors.isEmpty() && mode != null && (mode instanceof SelectAction || mode instanceof LassoModeAction)) {
				final int iconHeight = size.getAdjustedHeight();
				final int iconWidth = size.getAdjustedWidth();
				paintSelectedNode(g, mv, iconHeight, iconWidth, selectedErrors);
			} else {
				for (DataSet ds : dataSets.values()) {
					ds.clearSelection();
				}
				hideNodeWindow();
			}
		}

		/**
		 * Create the note window
		 * @param g The {@code Graphics2D} object that will be the note background
		 * @param mv The {@code MapView} object that we are drawing on
		 * @param iconHeight The height of the selection box that we are drawing
		 * @param iconWidth The width of the selection box we are drawing
		 * @param selectedNode The selected node to get information from
		 */
		private void paintSelectedNode(Graphics2D g, MapView mv, int iconHeight, int iconWidth, HashMap<GenericInformation, ArrayList<Node>> selectedErrors) {
			double averageEast = 0.0;
			double averageNorth = 0.0;
			int number = 0;
			for (GenericInformation type : selectedErrors.keySet()) {
				for (Node node : selectedErrors.get(type)) {
					number++;
					EastNorth ten = node.getEastNorth();
					averageEast += ten.east();
					averageNorth += ten.north();
				}
			}
			EastNorth currentClick = new EastNorth(averageEast / number, averageNorth / number);
			Point p = mv.getPoint(currentClick);
			g.setColor(ColorHelper.html2color(Config.getPref().get("color.selected")));
			g.drawRect(p.x - (iconWidth / 2), p.y - (iconHeight / 2), iconWidth - 1, iconHeight - 1);

			if (!currentClick.equals(lastClick)) {
				hideNodeWindow();
			}

			int xl = p.x - (iconWidth / 2) - 5;
			int xr = p.x + (iconWidth / 2) + 5;
			int yb = p.y - iconHeight - 1;
			int yt = p.y + (iconHeight / 2) + 2;
			Point pTooltip;
			displayedPanel = new JPanel();
			displayedPanel.setLayout(new BoxLayout(displayedPanel, BoxLayout.Y_AXIS));

			if (displayedWindow == null) {
				displayedWindow = new JWindow(MainApplication.getMainFrame());
				displayedWindow.setAutoRequestFocus(false);
				displayedWindow.add(displayedPanel);
				// Forward mouse wheel scroll event to MapMover
				displayedWindow.addMouseWheelListener(e -> mv.getMapMover().mouseWheelMoved(
						(MouseWheelEvent) SwingUtilities.convertMouseEvent(displayedWindow, e, mv)));
			}
			for (GenericInformation type : dataSets.keySet()) {
				for (OsmPrimitive osmPrimitive : dataSets.get(type).getSelected()) {
					if (!(osmPrimitive instanceof Node)) continue;
					Node selectedNode = (Node) osmPrimitive;
					String text = type.getNodeToolTip(selectedNode);

					HtmlPanel htmlPanel = new HtmlPanel(text);
					htmlPanel.setBackground(UIManager.getColor("ToolTip.background"));
					htmlPanel.setForeground(UIManager.getColor("ToolTip.foreground"));
					htmlPanel.setFont(UIManager.getFont("ToolTip.font"));
					htmlPanel.setBorder(BorderFactory.createLineBorder(Color.black));
					htmlPanel.enableClickableHyperlinks();
					JPanel tPanel = new JPanel();
					tPanel.setLayout(new BoxLayout(tPanel, BoxLayout.Y_AXIS));
					tPanel.add(htmlPanel);

					List<JButton> actions = type.getActions(selectedNode);
					JPanel pActions = new JPanel();
					double minWidth = 0.0;
					for (JButton action : actions) {
						pActions.add(action);
						minWidth += action.getPreferredSize().getWidth();
					}
					Dimension d = pActions.getPreferredSize();
					d.setSize(minWidth, d.getHeight());
					pActions.setPreferredSize(d);
					tPanel.add(pActions);
					displayedPanel.add(tPanel);
				}
			}

			pTooltip = fixPanelSizeAndLocation(mv, displayedPanel, xl, xr, yt, yb);
			displayedWindow.pack();
			displayedWindow.setLocation(pTooltip);
			displayedWindow.setVisible(mv.contains(p));
			lastClick = currentClick;
		}

		/**
		 * Get the location of the note panel
		 * @param mv {@code MapView} that is being drawn on
		 * @param panel The JPanel we are drawing
		 * @param xl The left side of the icon
		 * @param xr The right side of the icon
		 * @param yt The top of the icon
		 * @param yb The bottom of the icon
		 * @return The point at which we are drawing the note panel
		 */
		private Point fixPanelSizeAndLocation(MapView mv, JPanel panel, int xl, int xr, int yt, int yb) {
			int leftMaxWidth = (int) (0.95 * xl);
			int rightMaxWidth = (int) (0.95 * mv.getWidth() - xr);
			int topMaxHeight = (int) (0.95 * yt);
			int bottomMaxHeight = (int) (0.95 * mv.getHeight() - yb);
			int maxWidth = Math.max(leftMaxWidth, rightMaxWidth);
			int maxHeight = Math.max(topMaxHeight, bottomMaxHeight);
			for (Component sComponent : panel.getComponents()) {
				if (sComponent instanceof JPanel) {
					JPanel tPanel = (JPanel) sComponent;
					for (Component component : tPanel.getComponents()) {
						if (component instanceof HtmlPanel) {
							HtmlPanel htmlPanel = (HtmlPanel) component;
							JEditorPane pane = htmlPanel.getEditorPane();
							Dimension d = pane.getPreferredSize();

							if ((d.width > maxWidth || d.height > maxHeight) && Config.getPref().getBoolean("note.text.break-on-sentence-mark", true)) {
								// To make sure long notes are displayed correctly
								htmlPanel.setText(insertLineBreaks(pane.getText()));
							}
							// If still too large, enforce maximum size
							d = pane.getPreferredSize();

							if (d.width > maxWidth || d.height > maxHeight) {
								View v = (View) pane.getClientProperty(BasicHTML.propertyKey);
								if (v == null) {
									BasicHTML.updateRenderer(pane, pane.getText());
									v = (View) pane.getClientProperty(BasicHTML.propertyKey);
								}
								if (v != null) {
									v.setSize(maxWidth, 0);
									int w = (int) Math.ceil(v.getPreferredSpan(View.X_AXIS));
									int h = (int) Math.ceil(v.getPreferredSpan(View.Y_AXIS)) + 10;
									pane.setPreferredSize(new Dimension(w, h));
								}
							}
							//htmlPanel.setPreferredSize(pane.getPreferredSize());
							Dimension daction = htmlPanel.getPreferredSize();
							d = pane.getPreferredSize();
							d.setSize(Math.max(d.getWidth(), daction.getWidth()), Math.max(d.getHeight(), daction.getHeight()));
							pane.setPreferredSize(d);
						}
					}
				}
			}
			Dimension d = panel.getPreferredSize();
			// place tooltip on left or right side of icon, based on its width
			Point screenloc = mv.getLocationOnScreen();
			return new Point(
					screenloc.x + (d.width > rightMaxWidth && d.width <= leftMaxWidth ? xl - d.width : xr),
					screenloc.y + (d.height > bottomMaxHeight && d.height <= topMaxHeight ? yt - d.height - 10 : yb));
		}
	}

	/**
	 * Hide the displayedWindow of the error notes
	 */
	private void hideNodeWindow() {
		if (displayedWindow != null) {
			displayedWindow.setVisible(false);
			for (MouseWheelListener listener : displayedWindow.getMouseWheelListeners()) {
				displayedWindow.removeMouseWheelListener(listener);
			}
			displayedWindow.dispose();
			displayedWindow = null;
			displayedPanel = null;
			displayedNode = null;
		}
	}

	@Override
	public void mouseClicked(MouseEvent e) {
		if (!SwingUtilities.isLeftMouseButton(e)) {
			return;
		}
		DataSet ds = MainApplication.getLayerManager().getActiveDataSet();
		if (!ds.isModified()) {
			GenericInformation.addChangeSetTag(null, null);
		}
		new GetClosestNode(e).run();
	}

	private class GetClosestNode implements Runnable {
		MouseEvent e;
		GetClosestNode(MouseEvent e) {
			this.e = e;
		}
		/**
		 * Get the closest nodes to a point
		 * @param mousePoint The current location of the mouse
		 * @param snapDistance The maximum distance to find the closest node
		 * @return The closest {@code Node}s
		 */
		private HashMap<GenericInformation, ArrayList<Node>> getClosestNode(Point mousePoint, double snapDistance) {
			HashMap<GenericInformation, ArrayList<Node>> closestNodes = new HashMap<>();
			for (GenericInformation type : dataSets.keySet()) {
				DataSet ds = dataSets.get(type);
				ArrayList<Node> closestNode = new ArrayList<>();
				for (Node node : ds.getNodes()) {
					Point notePoint = MainApplication.getMap().mapView.getPoint(node.getBBox().getCenter());
					if (mousePoint.distance(notePoint) < snapDistance) {
						closestNode.add(node);
					}
				}
				if (!closestNode.isEmpty()) {
					closestNodes.put(type, closestNode);
				}
			}
			return closestNodes;
		}

		@Override
		public void run() {
			HashMap<GenericInformation, ArrayList<Node>> closestNode = getClosestNode(e.getPoint(), 10);
			if (!closestNode.isEmpty()) {
				for (GenericInformation type : closestNode.keySet()) {
					dataSets.get(type).setSelected(closestNode.get(type));
				}
			}
			for (GenericInformation type : dataSets.keySet()) {
				if (!closestNode.containsKey(type)) {
					dataSets.get(type).clearSelection();
				}
			}
			boolean gotNode = false;
			if (displayedNode != null) {
				for (DataSet ds : dataSets.values()) {
					if (ds.containsNode(displayedNode)) {
						gotNode = true;
						break;
					}
				}
			}
			if (!gotNode) {
				hideNodeWindow();
			}
			invalidate();
		}
	}

	@Override
	public Action[] getMenuEntries() {
		ArrayList<Action> actions = new ArrayList<>();
		actions.add(LayerListDialog.getInstance().createShowHideLayerAction());
		actions.add(LayerListDialog.getInstance().createDeleteLayerAction());
		actions.add(new LayerListPopup.InfoAction(this));
		actions.add(new ForceClear());
		for (GenericInformation type : enabledSources.keySet()) {
			actions.add(new ToggleSource(type));
		}
		return actions.toArray(new Action[0]);
	}

	private class ToggleSource extends AbstractAction {
		private static final long serialVersionUID = -3530922723120575358L;
		private GenericInformation type;
		public ToggleSource(GenericInformation type) {
			this.type = type;
			if (!enabledSources.get(type)) {
				new ImageProvider("warning-small").getResource().attachImageIcon(this, true);
			} else {
				new ImageProvider("dialogs", "validator").getResource().attachImageIcon(this, true);
			}
	        putValue(SHORT_DESCRIPTION, tr("Toggle source {0}", type.getName()));
	        putValue(NAME, tr("Toggle source {0}", type.getName()));
		}
		@Override
		public void actionPerformed(ActionEvent e) {
			enabledSources.put(type, !enabledSources.get(type));
			invalidate();
		}
	}

	private class ForceClear extends AbstractAction {
		private static final long serialVersionUID = -4472400258489788312L;

		public ForceClear() {
	        new ImageProvider("dialogs", "delete").getResource().attachImageIcon(this, true);
	        putValue(SHORT_DESCRIPTION, tr("Clear cached information for OpenQA."));
	        putValue(NAME, tr("Clear"));
		}
		@Override
		public void actionPerformed(ActionEvent e) {
			File directory = new File(CACHE_DIR, GenericInformation.DATA_SUB_DIR);
			Utils.deleteDirectory(directory);
			directory.mkdirs();
			for (GenericInformation type : dataSets.keySet()) {
				DataSet ds = dataSets.get(type);
				DataSet temporaryDataSet = new DataSet();
				for (OsmPrimitive osmPrimitive : ds.allPrimitives()) {
					if (osmPrimitive.hasKey("actionTaken")) {
						ds.removePrimitive(osmPrimitive);
						temporaryDataSet.addPrimitive(osmPrimitive);
					}
				}
				ds.clear();
				ds.mergeFrom(temporaryDataSet);
				dataSets.put(type, ds);
			}
			OpenQALayerChangeListener.updateOpenQALayers(CACHE_DIR);
		}

	}


	/**
	 * Inserts HTML line breaks ({@code <br>} at the end of each sentence mark
	 * (period, interrogation mark, exclamation mark, ideographic full stop).
	 * @param longText a long text that does not fit on a single line without exceeding half of the map view
	 * @return text with line breaks
	 */
	static String insertLineBreaks(String longText) {
		return SENTENCE_MARKS_WESTERN.matcher(SENTENCE_MARKS_EASTERN.matcher(longText).replaceAll("$1<br>$2")).replaceAll("$1<br>$2");
	}

	@Override
	public Icon getIcon() {
		return ImageProvider.get("dialogs/notes", "note_open", ImageProvider.ImageSizes.SMALLICON);
	}

	@Override
	public String getToolTipText() {
		int size = 0;
		for (DataSet ds : dataSets.values()) {
			size += ds.getNodes().size();
		}
		return trn("{0} {1} note", "{0} {1} notes", size, size, OpenQA.NAME);
	}

	@Override
	public void mergeFrom(Layer from) {
		if (from instanceof ErrorLayer) {
			ErrorLayer efrom = (ErrorLayer) from;
			for (GenericInformation type : efrom.dataSets.keySet()) {
				boolean merged = false;
				for (GenericInformation current : dataSets.keySet()) {
					if (type.getClass().equals(current.getClass())) {
						DataSet ds = efrom.dataSets.get(type);
						dataSets.get(current).mergeFrom(ds);
						merged = true;
						break;
					}
				}
				if (!merged) {
					dataSets.put(type, efrom.dataSets.get(type));
				}
			}
		}
	}

	@Override
	public boolean isMergable(Layer other) {
		return (other instanceof ErrorLayer);
	}

	@Override
	public void visitBoundingBox(BoundingXYVisitor v) {
		for (DataSet ds : dataSets.values()) {
			for (OsmPrimitive osm : ds.allPrimitives()) {
				v.visit(osm.getBBox().getCenter());
			}
		}

	}

	@Override
	public Object getInfoComponent() {
		int size = 0;
		for (DataSet ds : dataSets.values()) {
			size += ds.allPrimitives().size();
		}
		StringBuilder sb = new StringBuilder();
		sb.append(tr("Keep Right Layer"))
		.append('\n').append(tr("Total notes")).append(' ')
		.append(size);
		return sb;
	}

	@Override
	public void selectionChanged(SelectionChangeEvent event) {
		Set<OsmPrimitive> selected = event.getAdded();
		if (event.getAdded().iterator().hasNext()) {
			DataSet ds = event.getAdded().iterator().next().getDataSet();
			ds.setSelected(selected);
		}
		invalidate();
	}

	@Override
	public void mousePressed(MouseEvent e) {
		// Do nothing
	}

	@Override
	public void mouseReleased(MouseEvent e) {
		// Do nothing
	}

	@Override
	public void mouseEntered(MouseEvent e) {
		// Do nothing
	}

	@Override
	public void mouseExited(MouseEvent e) {
		// Do nothing
	}

	@Override
	public void highlightUpdated(HighlightUpdateEvent e) {
		for (OsmPrimitive osmPrimitive : e.getDataSet().allPrimitives()) {
			if (osmPrimitive instanceof Node && (osmPrimitive.hasKey("actionTaken") || "false".equals(osmPrimitive.get("actionTaken")))) {
				Node node = (Node) osmPrimitive;
				for (GenericInformation type : dataSets.keySet()) {
					if (!dataSets.get(type).containsNode(node)) continue;
					type.getNodeToolTip(node);
					if (!osmPrimitive.hasKey("actionTaken")) {
						osmPrimitive.put("actionTaken", "true");
					}
				}
			}
		}
	}
}