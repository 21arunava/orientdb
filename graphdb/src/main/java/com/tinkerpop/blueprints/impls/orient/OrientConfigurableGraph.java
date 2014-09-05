/*
 *
 *  *  Copyright 2014 Orient Technologies LTD (info(at)orientechnologies.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://www.orientechnologies.com
 *  
 */

package com.tinkerpop.blueprints.impls.orient;

import org.apache.commons.configuration.Configuration;

import com.orientechnologies.orient.core.intent.OIntent;

/**
 * Base class to manage graph settings.
 * 
 * @author Luca Garulli (http://www.orientechnologies.com)
 */
public abstract class OrientConfigurableGraph {
  protected Settings settings = new Settings();

  public enum THREAD_MODE {
    MANUAL, AUTOSET_IFNULL, ALWAYS_AUTOSET
  }

  public class Settings {
    protected boolean     useLightweightEdges                 = true;
    protected boolean     useClassForEdgeLabel                = true;
    protected boolean     useClassForVertexLabel              = true;
    protected boolean     keepInMemoryReferences              = false;
    protected boolean     useVertexFieldsForEdgeLabels        = true;
    protected boolean     saveOriginalIds                     = false;
    protected boolean     standardElementConstraints          = true;
    protected boolean     warnOnForceClosingTx                = true;
    protected boolean     autoScaleEdgeType                   = false;
    protected int         edgeContainerEmbedded2TreeThreshold = -1;
    protected int         edgeContainerTree2EmbeddedThreshold = -1;
    protected THREAD_MODE threadMode                          = THREAD_MODE.AUTOSET_IFNULL;

    public Settings copy() {
      final Settings copy = new Settings();
      copy.useLightweightEdges = useLightweightEdges;
      copy.useClassForEdgeLabel = useClassForEdgeLabel;
      copy.useClassForVertexLabel = useClassForVertexLabel;
      copy.keepInMemoryReferences = keepInMemoryReferences;
      copy.useVertexFieldsForEdgeLabels = useVertexFieldsForEdgeLabels;
      copy.saveOriginalIds = saveOriginalIds;
      copy.standardElementConstraints = standardElementConstraints;
      copy.warnOnForceClosingTx = warnOnForceClosingTx;
      copy.autoScaleEdgeType = autoScaleEdgeType;
      copy.edgeContainerEmbedded2TreeThreshold = edgeContainerEmbedded2TreeThreshold;
      copy.edgeContainerTree2EmbeddedThreshold = edgeContainerTree2EmbeddedThreshold;
      copy.threadMode = threadMode;
      return copy;
    }
  }

  protected OrientConfigurableGraph() {
  }

  public abstract void declareIntent(OIntent iIntent);

  /**
   * Returns true if is using lightweight edges, otherwise false.
   */
  public boolean isUseLightweightEdges() {
    return settings.useLightweightEdges;
  }

  /**
   * Changes the setting about usage of lightweight edges.
   */
  public OrientConfigurableGraph setUseLightweightEdges(final boolean useDynamicEdges) {
    settings.useLightweightEdges = useDynamicEdges;
    return this;
  }

  /**
   * Returns true if is using auto scale edge type, otherwise false.
   */
  public boolean isAutoScaleEdgeType() {
    return settings.autoScaleEdgeType;
  }

  /**
   * Changes the setting about usage of auto scale edge type.
   */
  public OrientConfigurableGraph setAutoScaleEdgeType(final boolean autoScaleEdgeType) {
    settings.autoScaleEdgeType = autoScaleEdgeType;
    return this;
  }

  /**
   * Returns the minimum number of edges for edge containers to transform the underlying structure from embedded to tree.
   */
  public int getEdgeContainerEmbedded2TreeThreshold() {
    return settings.edgeContainerEmbedded2TreeThreshold;
  }

  /**
   * Changes the minimum number of edges for edge containers to transform the underlying structure from embedded to tree. Use -1 to
   * disable transformation.
   */
  public OrientConfigurableGraph setEdgeContainerEmbedded2TreeThreshold(final int edgeContainerEmbedded2TreeThreshold) {
    this.settings.edgeContainerEmbedded2TreeThreshold = edgeContainerEmbedded2TreeThreshold;
    return this;
  }

  /**
   * Returns the minimum number of edges for edge containers to transform the underlying structure from tree to embedded.
   */
  public int getEdgeContainerTree2EmbeddedThreshold() {
    return settings.edgeContainerTree2EmbeddedThreshold;
  }

  /**
   * Changes the minimum number of edges for edge containers to transform the underlying structure from tree to embedded. Use -1 to
   * disable transformation.
   */
  public OrientConfigurableGraph setEdgeContainerTree2EmbeddedThreshold(final int edgeContainerTree2EmbeddedThreshold) {
    this.settings.edgeContainerTree2EmbeddedThreshold = edgeContainerTree2EmbeddedThreshold;
    return this;
  }

  /**
   * Returns true if it saves the original Id, otherwise false.
   */
  public boolean isSaveOriginalIds() {
    return settings.saveOriginalIds;
  }

  /**
   * Changes the setting about usage of lightweight edges.
   */
  public OrientConfigurableGraph setSaveOriginalIds(final boolean saveIds) {
    settings.saveOriginalIds = saveIds;
    return this;
  }

  /**
   * Returns true if the references are kept in memory.
   */
  public boolean isKeepInMemoryReferences() {
    return settings.keepInMemoryReferences;
  }

  /**
   * Changes the setting about using references in memory.
   */
  public OrientConfigurableGraph setKeepInMemoryReferences(boolean useReferences) {
    settings.keepInMemoryReferences = useReferences;
    return this;
  }

  /**
   * Returns true if the class are use for Edge labels.
   */
  public boolean isUseClassForEdgeLabel() {
    return settings.useClassForEdgeLabel;
  }

  /**
   * Changes the setting to use the Edge class for Edge labels.
   */
  public OrientConfigurableGraph setUseClassForEdgeLabel(final boolean useCustomClassesForEdges) {
    settings.useClassForEdgeLabel = useCustomClassesForEdges;
    return this;
  }

  /**
   * Returns true if the class are use for Vertex labels.
   */
  public boolean isUseClassForVertexLabel() {
    return settings.useClassForVertexLabel;
  }

  /**
   * Changes the setting to use the Vertex class for Vertex labels.
   */
  public OrientConfigurableGraph setUseClassForVertexLabel(final boolean useCustomClassesForVertex) {
    this.settings.useClassForVertexLabel = useCustomClassesForVertex;
    return this;
  }

  /**
   * Returns true if the out/in fields in vertex are post-fixed with edge labels. This improves traversal time by partitioning edges
   * on different collections, one per Edge's class.
   */
  public boolean isUseVertexFieldsForEdgeLabels() {
    return settings.useVertexFieldsForEdgeLabels;
  }

  /**
   * Changes the setting to postfix vertices fields with edge labels. This improves traversal time by partitioning edges on
   * different collections, one per Edge's class.
   */
  public OrientConfigurableGraph setUseVertexFieldsForEdgeLabels(final boolean useVertexFieldsForEdgeLabels) {
    this.settings.useVertexFieldsForEdgeLabels = useVertexFieldsForEdgeLabels;
    return this;
  }

  /**
   * Returns true if Blueprints standard constraints are applied to elements.
   */
  public boolean isStandardElementConstraints() {
    return settings.standardElementConstraints;
  }

  /**
   * Changes the setting to apply the Blueprints standard constraints against elements.
   */
  public OrientConfigurableGraph setStandardElementConstraints(final boolean allowsPropertyValueNull) {
    this.settings.standardElementConstraints = allowsPropertyValueNull;
    return this;
  }

  /**
   * Returns true if the warning is generated on force the graph closing.
   */
  public boolean isWarnOnForceClosingTx() {
    return settings.warnOnForceClosingTx;
  }

  /**
   * Changes the setting to generate a warning if the graph closing has been forced.
   */
  public OrientConfigurableGraph setWarnOnForceClosingTx(final boolean warnOnSchemaChangeInTx) {
    this.settings.warnOnForceClosingTx = warnOnSchemaChangeInTx;
    return this;
  }

  /**
   * Returns the current thread mode:
   * <ul>
   * <li><b>MANUAL</b> the user has to manually invoke the current database in Thread Local:
   * ODatabaseRecordThreadLocal.INSTANCE.set(graph.getRawGraph());</li>
   * <li><b>AUTOSET_IFNULL</b> (default) each call assures the current graph instance is set in the Thread Local only if no one was
   * set before</li>
   * <li><b>ALWAYS_AUTOSET</b> each call assures the current graph instance is set in the Thread Local</li>
   * </ul>
   * 
   * @see #setThreadMode(THREAD_MODE)
   * @return Current Graph instance to allow calls in chain (fluent interface)
   */

  public THREAD_MODE getThreadMode() {
    return settings.threadMode;
  }

  /**
   * Changes the thread mode:
   * <ul>
   * <li><b>MANUAL</b> the user has to manually invoke the current database in Thread Local:
   * ODatabaseRecordThreadLocal.INSTANCE.set(graph.getRawGraph());</li>
   * <li><b>AUTOSET_IFNULL</b> (default) each call assures the current graph instance is set in the Thread Local only if no one was
   * set before</li>
   * <li><b>ALWAYS_AUTOSET</b> each call assures the current graph instance is set in the Thread Local</li>
   * </ul>
   * 
   * @param iControl
   *          Value to set
   * @see #getThreadMode()
   * @return Current Graph instance to allow calls in chain (fluent interface)
   */
  public OrientConfigurableGraph setThreadMode(final THREAD_MODE iControl) {
    this.settings.threadMode = iControl;
    return this;
  }

  /**
   * Builds a OrientGraph instance passing a configuration. Supported configuration settings are:
   * <table>
   * <tr>
   * <td><b>Name</b></td>
   * <td><b>Description</b></td>
   * <td><b>Default value</b></td>
   * </tr>
   * <tr>
   * <td>blueprints.orientdb.url</td>
   * <td>Database URL</td>
   * <td>-</td>
   * </tr>
   * <tr>
   * <td>blueprints.orientdb.username</td>
   * <td>User name</td>
   * <td>admin</td>
   * </tr>
   * <tr>
   * <td>blueprints.orientdb.password</td>
   * <td>User password</td>
   * <td>admin</td>
   * </tr>
   * <tr>
   * <td>blueprints.orientdb.saveOriginalIds</td>
   * <td>Saves the original element IDs by using the property origId. This could be useful on import of graph to preserve original
   * ids</td>
   * <td>false</td>
   * </tr>
   * <tr>
   * <td>blueprints.orientdb.keepInMemoryReferences</td>
   * <td>Avoid to keep records in memory but only RIDs</td>
   * <td>false</td>
   * </tr>
   * <tr>
   * <td>blueprints.orientdb.useCustomClassesForEdges</td>
   * <td>Use Edge's label as OrientDB class. If doesn't exist create it under the hood</td>
   * <td>true</td>
   * </tr>
   * <tr>
   * <td>blueprints.orientdb.useCustomClassesForVertex</td>
   * <td>Use Vertex's label as OrientDB class. If doesn't exist create it under the hood</td>
   * <td>true</td>
   * </tr>
   * <tr>
   * <td>blueprints.orientdb.useVertexFieldsForEdgeLabels</td>
   * <td>Store the edge relationships in vertex by using the Edge's class. This allow to use multiple fields and make faster
   * traversal by edge's label (class)</td>
   * <td>true</td>
   * </tr>
   * <tr>
   * <td>blueprints.orientdb.lightweightEdges</td>
   * <td>Uses lightweight edges. This avoid to create a physical document per edge. Documents are created only when they have
   * properties</td>
   * <td>true</td>
   * </tr>
   * <tr>
   * <td>blueprints.orientdb.autoScaleEdgeType</td>
   * <td>Set auto scale of edge type. True means one edge is managed as LINK, 2 or more are managed with a LINKBAG</td>
   * <td>false</td>
   * </tr>
   * <tr>
   * <td>blueprints.orientdb.edgeContainerEmbedded2TreeThreshold</td>
   * <td>Changes the minimum number of edges for edge containers to transform the underlying structure from embedded to tree. Use -1
   * to disable transformation</td>
   * <td>-1</td>
   * </tr>
   * <tr>
   * <td>blueprints.orientdb.edgeContainerTree2EmbeddedThreshold</td>
   * <td>Changes the minimum number of edges for edge containers to transform the underlying structure from tree to embedded. Use -1
   * to disable transformation</td>
   * <td>-1</td>
   * </tr>
   * </table>
   *
   * @param configuration
   *          of graph
   */
  protected void init(final Configuration configuration) {
    final Boolean saveOriginalIds = configuration.getBoolean("blueprints.orientdb.saveOriginalIds", null);
    if (saveOriginalIds != null)
      setSaveOriginalIds(saveOriginalIds);

    final Boolean keepInMemoryReferences = configuration.getBoolean("blueprints.orientdb.keepInMemoryReferences", null);
    if (keepInMemoryReferences != null)
      setKeepInMemoryReferences(keepInMemoryReferences);

    final Boolean useCustomClassesForEdges = configuration.getBoolean("blueprints.orientdb.useCustomClassesForEdges", null);
    if (useCustomClassesForEdges != null)
      setUseClassForEdgeLabel(useCustomClassesForEdges);

    final Boolean useCustomClassesForVertex = configuration.getBoolean("blueprints.orientdb.useCustomClassesForVertex", null);
    if (useCustomClassesForVertex != null)
      setUseClassForVertexLabel(useCustomClassesForVertex);

    final Boolean useVertexFieldsForEdgeLabels = configuration.getBoolean("blueprints.orientdb.useVertexFieldsForEdgeLabels", null);
    if (useVertexFieldsForEdgeLabels != null)
      setUseVertexFieldsForEdgeLabels(useVertexFieldsForEdgeLabels);

    final Boolean lightweightEdges = configuration.getBoolean("blueprints.orientdb.lightweightEdges", null);
    if (lightweightEdges != null)
      setUseLightweightEdges(lightweightEdges);

    final Boolean autoScaleEdgeType = configuration.getBoolean("blueprints.orientdb.autoScaleEdgeType", null);
    if (autoScaleEdgeType != null)
      setAutoScaleEdgeType(autoScaleEdgeType);
  }
}
