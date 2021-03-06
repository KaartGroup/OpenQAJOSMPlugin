// License: GPL. For details, see LICENSE file.
package com.kaart.openqa.profiles;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.io.File;
import java.io.IOException;
import java.util.*;

import javax.swing.ImageIcon;
import javax.swing.JButton;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.User;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.io.XmlWriter;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.ImageProvider.ImageSizes;
import org.openstreetmap.josm.tools.Logging;

import com.kaart.openqa.CachedFile;
import com.kaart.openqa.ErrorLayer;
import com.kaart.openqa.OpenQA;

/**
 * @author Taylor Smock
 *
 */
public abstract class GenericInformation {
    /** The subdirectory to store the data. This can be deleted at any time. */
    public static final String DATA_SUB_DIR = "data";
    /** The subdirectory to store the images. This is usually not deleted. */
    public static final String IMG_SUB_DIR = "img";

    /** The key to store unique the error id */
    public static final String ERROR_ID = "error_id";

    /** the difference between groups (integer numbers) */
    public static final int GROUP_DIFFERENCE = 10;

    protected String cacheDir;

    public GenericInformation(String cacheDir) {
        this.cacheDir = cacheDir;
    }

    /** The layer name */
    public abstract String getName();

    /** The API URL, usually something like https://www.example.org/api/0.2 */
    public abstract String getBaseApi();

    /** The base URL for images */
    public abstract String getBaseImg();

    /** The base URL for error data or error manipulation */
    public abstract String getBaseErrorUrl();

    /**
     * The possible errors for the class
     * {@code NavigableMap<Integer errorValue, String description>}
     */
    public abstract NavigableMap<String, String> getErrors();

    /**
     * Cache a file for 24 hours
     *
     * @param url       The URL to cache
     * @param type      The accepted times for {@code CachedFile.setHttpAccept}
     * @param directory The directory to store the file in
     * @return The @{code CachedFile} object with which to get a file
     */
    public static CachedFile getFile(String url, String type, String directory) {
        CachedFile cache = new CachedFile(url);
        cache.setMaxAge(86400);
        cache.setHttpAccept(type);
        cache.setDestDir(directory);
        return cache;
    }

    /**
     * Get an icon for a string/size combination
     *
     * @param string The string with which to get information -- defaults to
     *               "dialogs/notes"
     * @param size   The size of the note
     * @return {@code ImageIcon} to associate with a {@code String string}
     */
    public ImageIcon getIcon(String string, ImageSizes size) {
        return ImageProvider.get("dialogs/notes", "note_open", size);
    }

    /**
     * Get the errors for a layer
     *
     * @param dataSet         {@code DataSet} to get errors for
     * @param progressMonitor The {@code ProgressMonitor} with which to monitor
     *                        progress
     * @return A new {@code DataSet} that has error information for the
     *         {@code bounds}
     */
    public DataSet getErrors(DataSet dataSet, ProgressMonitor progressMonitor) {
        List<Bounds> bounds = dataSet.getDataSourceBounds();
        if (bounds.isEmpty()) {
            bounds = getDefaultBounds(dataSet, progressMonitor.createSubTaskMonitor(0, false));
        }
        return getErrors(bounds, progressMonitor);
    }

    /**
     * Get errors given a defaultDownloadTypes of bounds
     *
     * @param bounds          {@code List<Bounds>} to get data for
     * @param progressMonitor The {@code ProgressMonitor} with which to monitor
     *                        progress
     * @return A new {@code Layer} that has error information for the {@code bounds}
     */
    public abstract DataSet getErrors(List<Bounds> bounds, ProgressMonitor progressMonitor);

    /**
     * Get the bounds for a dataSet
     *
     * @param dataSet with the data of interest
     * @param monitor the ProgressMonitor with which to see progress with
     * @return The bounds that encompasses the @{code DataSet}
     */
    public static List<Bounds> getDefaultBounds(DataSet dataSet, ProgressMonitor monitor) {
        if (false) {
            monitor.beginTask(tr("Building default bounds"), dataSet.allPrimitives().size());
            List<BBox> bboxes = new ArrayList<>();
            double bboxMaxSize = 10000000;
            // Ensure that we go through a dataset predictably
            TreeSet<OsmPrimitive> treeSet = new TreeSet<>(dataSet.allPrimitives());
            for (OsmPrimitive osm : treeSet) {
                if (monitor.isCanceled())
                    break;
                monitor.worked(1);
                // Don't look at relations -- they can get really large, really fast.
                if (!(osm instanceof Relation)) {
                    boolean added = false;
                    BBox smallestBBox = null;
                    BBox osmBBox = osm.getBBox();
                    for (BBox bound : bboxes) {
                        if (bound.bounds(osmBBox) && bound.isValid()) {
                            // Since osmBBox is entirely within the bound, don't add it.
                            added = true;
                            smallestBBox = bound;
                            break;
                        }
                        BBox tBound = new BBox(bound);
                        tBound.add(osmBBox);
                        double area = getArea(tBound);
                        if (area < bboxMaxSize && area < getArea(smallestBBox)) {
                            smallestBBox = bound;
                        }
                    }
                    if (!added && smallestBBox == null) {
                        bboxes.add(osmBBox);
                    } else if (!added && smallestBBox != null) {
                        smallestBBox.add(osmBBox);
                        if (!bboxes.contains(smallestBBox))
                            bboxes.add(smallestBBox);
                    }
                }
            }

            List<Bounds> rBounds = new ArrayList<>();
            for (BBox bound : bboxes) {
                Bounds rBound = new Bounds(bound.getBottomRight());
                rBound.extend(bound.getTopLeft());
                rBounds.add(rBound);
            }
            monitor.finishTask();
            return rBounds;
        }
        return Collections.emptyList();
    }

    /**
     * Get the area of a bbox
     *
     * @param bbox to get an area of
     * @return The area of a bbox in meters^2
     */
    private static double getArea(BBox bbox) {
        if (bbox == null)
            return Double.MAX_VALUE;
        LatLon lr = bbox.getBottomRight();
        LatLon ul = bbox.getTopLeft();
        LatLon ll = new LatLon(lr.lat(), ul.lon());
        double width = ll.greatCircleDistance(lr);
        double height = ll.greatCircleDistance(ul);
        return width * height;
    }

    /**
     * Build an error defaultDownloadTypes to download
     *
     * @return {@code String} of errors to pass to a download method
     */
    public abstract String buildDownloadErrorList();

    /**
     * Build an {@code ArrayList<String>} of default preferences
     *
     * @return An {@code ArrayList<String>} of default preferences
     */
    public abstract List<String> buildDefaultPref();

    /**
     * Get the tooltip for a node
     *
     * @param node {@code Node} to get information from
     * @return {@code String} with the information in HTML format
     */
    public abstract String getNodeToolTip(Node node);

    /**
     * Cache additional information for a node
     *
     * @param node to get information from
     * @return true if there is additional information
     */
    public boolean cacheAdditionalInformation(Node node) {
        return false;
    }

    /**
     * Get a username from an {@code OsmPrimitiveId} as a {@code Long}
     *
     * @param objId The id to find in the dataset
     * @return The username
     */
    protected static String getUserName(long objId) {
        OsmPrimitive osm = new Node();
        osm.setOsmId(objId, 1);
        ArrayList<OsmDataLayer> layers = new ArrayList<>(
                MainApplication.getLayerManager().getLayersOfType(OsmDataLayer.class));
        for (OsmDataLayer layer : layers) {
            OsmPrimitive rosm = layer.data.getPrimitiveById(osm.getOsmPrimitiveId());
            if (rosm != null) {
                osm = rosm;
                break;
            }
        }
        User user = osm.getUser();
        String userName;
        if (user == null) {
            userName = null;
        } else {
            userName = user.getName();
        }
        return getUserName(userName);
    }

    /**
     * Convert a userName to be compatible with XML 1.0
     *
     * @param userName a username to ensure we can display
     * @return userName in XML 1.0 encoding
     */
    protected static String getUserName(String userName) {
        if (userName == null || userName.trim().isEmpty()) {
            userName = "&lt;Anonymous&gt;";
        } else {
            userName = XmlWriter.encode(userName);
        }
        return userName;
    }

    /**
     * Get the cache directory
     *
     * @return the directory in which we cache files
     */
    public abstract String getCacheDir();

    /**
     * Get the Layer name
     *
     * @return The Layer name
     */
    public abstract String getLayerName();

    /**
     * Get the extra error information for a node
     *
     * @param node {@code Node} with the information
     * @return a {@code String} with the error id
     */
    public abstract String getError(Node node);

    /**
     * Get the possible actions for a error node
     *
     * @param selectedNode {@code Node} that has error information
     * @return A defaultDownloadTypes of {@code JButton}s with associated actions to
     *         add to a dialog
     */
    public abstract List<JButton> getActions(Node selectedNode);

    /**
     * Redraw error layers
     *
     * @param name of the layer to redraw
     */
    public static void redrawErrorLayers(String name) {
        List<ErrorLayer> layers = MainApplication.getLayerManager().getLayersOfType(ErrorLayer.class);
        for (ErrorLayer layer : layers) {
            layer.invalidate();
        }
    }

    protected static class SendInformation implements Runnable {
        final String url;
        final String directory;

        public SendInformation(String url, String cacheDir) {
            this.url = url;
            directory = cacheDir;
        }

        @Override
        public void run() {
            try (CachedFile cache = new CachedFile(url)) {
                File dir = new File(directory, DATA_SUB_DIR);
                cache.setDestDir(dir.getPath());
                cache.getFile();
                cache.clear();
            } catch (IOException e) {
                Logging.debug(e.getMessage());
            }
        }
    }

    public static void addChangeSetTag(String source, String id) {
        DataSet data = MainApplication.getLayerManager().getActiveDataSet();
        // TODO figure out if we want to keep this
        boolean addChangesetTags = Config.getPref().getBoolean(OpenQA.PREF_PREFIX.concat("changesetTags"), false);
        if (addChangesetTags && data != null && !data.allPrimitives().isEmpty()) {
            Map<String, String> tags = data.getChangeSetTags();
            String key = OpenQA.NAME.toLowerCase();
            // Clear the changeset tag if needed
            if (source == null || id == null) {
                data.addChangeSetTag(key, "");
                return;
            }
            if (data.isModified()) {
                String addTag = source.concat("-").concat(id);
                if (tags.containsKey(key) && !tags.get(key).trim().isEmpty()) {
                    addTag = tags.get(key).concat(",").concat(addTag);
                }
                data.addChangeSetTag(key, addTag);
            } else {
                data.addChangeSetTag(key, "");
            }
        }
    }
}
