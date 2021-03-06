/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonar.server.computation.step;

import com.google.common.annotations.VisibleForTesting;
import java.util.List;
import javax.annotation.CheckForNull;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.rule.RuleKey;
import org.sonar.batch.protocol.output.BatchReport;
import org.sonar.core.measure.db.MeasureDto;
import org.sonar.core.persistence.DbSession;
import org.sonar.server.computation.batch.BatchReportReader;
import org.sonar.server.computation.component.Component;
import org.sonar.server.computation.component.DbIdsRepository;
import org.sonar.server.computation.component.DepthTraversalTypeAwareVisitor;
import org.sonar.server.computation.component.TreeRootHolder;
import org.sonar.server.computation.issue.RuleCache;
import org.sonar.server.computation.measure.MetricCache;
import org.sonar.server.db.DbClient;

import static com.google.common.collect.Lists.newArrayList;
import static org.sonar.server.computation.component.DepthTraversalTypeAwareVisitor.Order.PRE_ORDER;

public class PersistMeasuresStep implements ComputationStep {

  /**
   * List of metrics that should not be received from the report, as they should only by fed by the compute engine
   */
  private static final List<String> FORBIDDEN_METRIC_KEYS = newArrayList(CoreMetrics.DUPLICATIONS_DATA_KEY);

  private final DbClient dbClient;
  private final RuleCache ruleCache;
  private final MetricCache metricCache;
  private final DbIdsRepository dbIdsRepository;
  private final TreeRootHolder treeRootHolder;
  private final BatchReportReader reportReader;

  public PersistMeasuresStep(DbClient dbClient, RuleCache ruleCache, MetricCache metricCache,
    DbIdsRepository dbIdsRepository, TreeRootHolder treeRootHolder, BatchReportReader reportReader) {
    this.dbClient = dbClient;
    this.ruleCache = ruleCache;
    this.metricCache = metricCache;
    this.dbIdsRepository = dbIdsRepository;
    this.treeRootHolder = treeRootHolder;
    this.reportReader = reportReader;
  }

  @Override
  public String getDescription() {
    return "Persist measures";
  }

  @Override
  public void execute() {
    DbSession dbSession = dbClient.openSession(true);
    try {
      new MeasureVisitor(dbSession).visit(treeRootHolder.getRoot());
      dbSession.commit();
    } finally {
      dbSession.close();
    }
  }

  private class MeasureVisitor extends DepthTraversalTypeAwareVisitor {

    private final DbSession session;

    private MeasureVisitor(DbSession session) {
      super(Component.Type.FILE, PRE_ORDER);
      this.session = session;
    }

    @Override
    protected void visitAny(Component component) {
      int componentRef = component.getRef();
      BatchReport.Component batchComponent = reportReader.readComponent(componentRef);
      List<BatchReport.Measure> measures = reportReader.readComponentMeasures(componentRef);
      persistMeasures(measures, dbIdsRepository.getComponentId(component), batchComponent.getSnapshotId());

    }

    private void persistMeasures(List<BatchReport.Measure> batchReportMeasures, long componentId, long snapshotId) {
      for (BatchReport.Measure measure : batchReportMeasures) {
        if (FORBIDDEN_METRIC_KEYS.contains(measure.getMetricKey())) {
          throw new IllegalStateException(String.format("Measures on metric '%s' cannot be send in the report", measure.getMetricKey()));
        }
        dbClient.measureDao().insert(session, toMeasureDto(measure, componentId, snapshotId));
      }
    }
  }

  @VisibleForTesting
  MeasureDto toMeasureDto(BatchReport.Measure in, long componentId, long snapshotId) {
    if (!in.hasValueType()) {
      throw new IllegalStateException(String.format("Measure %s does not have value type", in));
    }
    if (!in.hasMetricKey()) {
      throw new IllegalStateException(String.format("Measure %s does not have metric key", in));
    }

    MeasureDto out = new MeasureDto();
    out.setVariation(1, in.hasVariationValue1() ? in.getVariationValue1() : null);
    out.setVariation(2, in.hasVariationValue2() ? in.getVariationValue2() : null);
    out.setVariation(3, in.hasVariationValue3() ? in.getVariationValue3() : null);
    out.setVariation(4, in.hasVariationValue4() ? in.getVariationValue4() : null);
    out.setVariation(5, in.hasVariationValue5() ? in.getVariationValue5() : null);
    out.setAlertStatus(in.hasAlertStatus() ? in.getAlertStatus() : null);
    out.setAlertText(in.hasAlertText() ? in.getAlertText() : null);
    out.setDescription(in.hasDescription() ? in.getDescription() : null);
    out.setSeverity(in.hasSeverity() ? in.getSeverity().name() : null);
    out.setComponentId(componentId);
    out.setSnapshotId(snapshotId);
    out.setMetricId(metricCache.get(in.getMetricKey()).getId());
    out.setRuleId(in.hasRuleKey() ? ruleCache.get(RuleKey.parse(in.getRuleKey())).getId() : null);
    out.setCharacteristicId(in.hasCharactericId() ? in.getCharactericId() : null);
    out.setPersonId(in.hasPersonId() ? in.getPersonId() : null);
    out.setValue(valueAsDouble(in));
    setData(in, out);
    return out;
  }

  private MeasureDto setData(BatchReport.Measure in, MeasureDto out) {
    if (in.hasStringValue()) {
      out.setData(in.getStringValue());
    }
    return out;
  }

  /**
   * return the numerical value as a double. It's the type used in db.
   * Returns null if no numerical value found
   */
  @CheckForNull
  private static Double valueAsDouble(BatchReport.Measure measure) {
    switch (measure.getValueType()) {
      case BOOLEAN:
        return measure.hasBooleanValue() ? (measure.getBooleanValue() ? 1.0d : 0.0d) : null;
      case INT:
        return measure.hasIntValue() ? (double) measure.getIntValue() : null;
      case LONG:
        return measure.hasLongValue() ? (double) measure.getLongValue() : null;
      case DOUBLE:
        return measure.hasDoubleValue() ? measure.getDoubleValue() : null;
      default:
        return null;
    }
  }
}
