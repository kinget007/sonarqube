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

import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.batch.protocol.Constants;
import org.sonar.batch.protocol.output.BatchReport;
import org.sonar.batch.protocol.output.BatchReport.Range;
import org.sonar.core.metric.db.MetricDto;
import org.sonar.core.persistence.DbSession;
import org.sonar.core.persistence.DbTester;
import org.sonar.server.computation.batch.BatchReportReaderRule;
import org.sonar.server.computation.batch.TreeRootHolderRule;
import org.sonar.server.computation.component.Component;
import org.sonar.server.computation.component.DbIdsRepository;
import org.sonar.server.computation.component.DumbComponent;
import org.sonar.server.db.DbClient;
import org.sonar.server.measure.persistence.MeasureDao;
import org.sonar.server.metric.persistence.MetricDao;
import org.sonar.test.DbTests;

import static com.google.common.collect.Lists.newArrayList;
import static org.assertj.core.api.Assertions.assertThat;

@Category(DbTests.class)
public class PersistDuplicationsStepTest extends BaseStepTest {

  private static final String PROJECT_KEY = "PROJECT_KEY";

  @ClassRule
  public static DbTester dbTester = new DbTester();

  @Rule
  public BatchReportReaderRule reportReader = new BatchReportReaderRule();

  @Rule
  public TreeRootHolderRule treeRootHolder = new TreeRootHolderRule();

  DbIdsRepository dbIdsRepository = new DbIdsRepository();

  DbSession session;

  DbClient dbClient;

  PersistDuplicationsStep sut;

  @Before
  public void setup() throws Exception {
    dbTester.truncateTables();
    session = dbTester.myBatis().openSession(false);
    dbClient = new DbClient(dbTester.database(), dbTester.myBatis(), new MeasureDao(), new MetricDao());

    sut = new PersistDuplicationsStep(dbClient, dbIdsRepository, treeRootHolder, reportReader);
  }

  @Override
  protected ComputationStep step() {
    return sut;
  }

  @After
  public void tearDown() {
    session.close();
  }

  @Test
  public void nothing_to_do_when_no_duplication() {
    saveDuplicationMetric();
    initReportWithProjectAndFile();

    sut.execute();

    assertThat(dbTester.countRowsOfTable("project_measures")).isEqualTo(0);
  }

  @Test
  public void persist_duplications_on_same_file() {
    MetricDto duplicationMetric = saveDuplicationMetric();

    initReportWithProjectAndFile();

    BatchReport.Duplication duplication = BatchReport.Duplication.newBuilder()
      .setOriginPosition(Range.newBuilder()
        .setStartLine(1)
        .setEndLine(5)
        .build())
      .addDuplicate(BatchReport.Duplicate.newBuilder()
        .setOtherFileRef(2)
        .setRange(Range.newBuilder()
          .setStartLine(6)
          .setEndLine(10)
          .build())
        .build())
      .build();
    reportReader.putDuplications(2, newArrayList(duplication));

    sut.execute();

    assertThat(dbTester.countRowsOfTable("project_measures")).isEqualTo(1);

    Map<String, Object> dto = dbTester.selectFirst("select snapshot_id as \"snapshotId\", metric_id as \"metricId\", text_value as \"textValue\" from project_measures");
    assertThat(dto.get("snapshotId")).isEqualTo(11L);
    assertThat(dto.get("metricId")).isEqualTo(duplicationMetric.getId().longValue());
    assertThat(dto.get("textValue")).isEqualTo("<duplications><g><b s=\"1\" l=\"5\" r=\"PROJECT_KEY:file\"/><b s=\"6\" l=\"5\" r=\"PROJECT_KEY:file\"/></g></duplications>");
  }

  @Test
  public void persist_duplications_on_same_file_linked_on_a_module() throws Exception {
    Component file = new DumbComponent(Component.Type.FILE, 3, "CDEF", "MODULE_KEY:file");
    Component module = new DumbComponent(Component.Type.MODULE, 2, "BCDE", "MODULE_KEY", file);
    Component project = new DumbComponent(Component.Type.PROJECT, 1, "ABCD", PROJECT_KEY, module);
    treeRootHolder.setRoot(project);

    dbIdsRepository.setComponentId(project, 1);
    dbIdsRepository.setComponentId(module, 3);
    dbIdsRepository.setComponentId(file, 2);

    saveDuplicationMetric();

    // TODO remove this when snapshot id will come from the DbIdsRepo
    reportReader.putComponent(BatchReport.Component.newBuilder()
      .setRef(1)
      .setType(Constants.ComponentType.PROJECT)
      .setKey(PROJECT_KEY)
      .setSnapshotId(10L)
      .addChildRef(2)
      .build());
    reportReader.putComponent(BatchReport.Component.newBuilder()
      .setRef(2)
      .setType(Constants.ComponentType.MODULE)
      .setKey("MODULE_KEY")
      .setSnapshotId(11L)
      .addChildRef(3)
      .build());
    reportReader.putComponent(BatchReport.Component.newBuilder()
      .setRef(3)
      .setType(Constants.ComponentType.FILE)
      .setSnapshotId(12L)
      .setPath("file")
      .build());

    BatchReport.Duplication duplication = BatchReport.Duplication.newBuilder()
      .setOriginPosition(Range.newBuilder()
        .setStartLine(1)
        .setEndLine(5)
        .build())
      .addDuplicate(BatchReport.Duplicate.newBuilder()
        .setOtherFileRef(3)
        .setRange(Range.newBuilder()
          .setStartLine(6)
          .setEndLine(10)
          .build())
        .build())
      .build();
    reportReader.putDuplications(3, newArrayList(duplication));

    sut.execute();

    assertThat(dbTester.countRowsOfTable("project_measures")).isEqualTo(1);

    Map<String, Object> dto = dbTester.selectFirst("select snapshot_id as \"snapshotId\", text_value as \"textValue\" from project_measures");
    assertThat(dto.get("snapshotId")).isEqualTo(12L);
    assertThat(dto.get("textValue")).isEqualTo("<duplications><g><b s=\"1\" l=\"5\" r=\"MODULE_KEY:file\"/><b s=\"6\" l=\"5\" r=\"MODULE_KEY:file\"/></g></duplications>");
  }

  @Test
  public void persist_duplications_on_same_file_linked_on_a_folder() {
    Component file = new DumbComponent(Component.Type.FILE, 3, "CDEF", "PROJECT_KEY:file");
    Component directory = new DumbComponent(Component.Type.DIRECTORY, 2, "BCDE", "PROJECT_KEY:dir", file);
    Component project = new DumbComponent(Component.Type.PROJECT, 1, "ABCD", PROJECT_KEY, directory);
    treeRootHolder.setRoot(project);

    dbIdsRepository.setComponentId(project, 1);
    dbIdsRepository.setComponentId(directory, 3);
    dbIdsRepository.setComponentId(file, 2);

    saveDuplicationMetric();

    // TODO remove this when snapshot id will come from the DbIdsRepo
    reportReader.putComponent(BatchReport.Component.newBuilder()
      .setRef(1)
      .setType(Constants.ComponentType.PROJECT)
      .setKey(PROJECT_KEY)
      .setSnapshotId(10L)
      .addChildRef(2)
      .build());
    reportReader.putComponent(BatchReport.Component.newBuilder()
      .setRef(2)
      .setType(Constants.ComponentType.DIRECTORY)
      .setSnapshotId(11L)
      .addChildRef(3)
      .setPath("dir")
      .build());
    reportReader.putComponent(BatchReport.Component.newBuilder()
      .setRef(3)
      .setType(Constants.ComponentType.FILE)
      .setSnapshotId(12L)
      .setPath("file")
      .build());

    BatchReport.Duplication duplication = BatchReport.Duplication.newBuilder()
      .setOriginPosition(Range.newBuilder()
        .setStartLine(1)
        .setEndLine(5)
        .build())
      .addDuplicate(BatchReport.Duplicate.newBuilder()
        .setOtherFileRef(3)
        .setRange(Range.newBuilder()
          .setStartLine(6)
          .setEndLine(10)
          .build())
        .build())
      .build();
    reportReader.putDuplications(3, newArrayList(duplication));

    sut.execute();

    assertThat(dbTester.countRowsOfTable("project_measures")).isEqualTo(1);

    Map<String, Object> dto = dbTester.selectFirst("select snapshot_id as \"snapshotId\", text_value as \"textValue\" from project_measures");
    assertThat(dto.get("snapshotId")).isEqualTo(12L);
    assertThat(dto.get("textValue")).isEqualTo("<duplications><g><b s=\"1\" l=\"5\" r=\"PROJECT_KEY:file\"/><b s=\"6\" l=\"5\" r=\"PROJECT_KEY:file\"/></g></duplications>");
  }

  @Test
  public void persist_duplications_on_same_file_linked_on_sub_folder() {
    Component file = new DumbComponent(Component.Type.FILE, 10, "DEFG", "PROJECT_KEY:file");
    Component directory1 = new DumbComponent(Component.Type.DIRECTORY, 3, "CDEF", "PROJECT_KEY:dir1", file);
    Component directory2 = new DumbComponent(Component.Type.DIRECTORY, 2, "BCDE", "PROJECT_KEY:dir2", directory1);
    Component project = new DumbComponent(Component.Type.PROJECT, 1, "ABCD", PROJECT_KEY, directory2);
    treeRootHolder.setRoot(project);

    dbIdsRepository.setComponentId(project, 1);
    dbIdsRepository.setComponentId(directory1, 2);
    dbIdsRepository.setComponentId(directory2, 3);
    dbIdsRepository.setComponentId(file, 10);

    saveDuplicationMetric();

    // TODO remove this when snapshot id will come from the DbIdsRepo
    reportReader.putComponent(BatchReport.Component.newBuilder()
      .setRef(1)
      .setType(Constants.ComponentType.PROJECT)
      .setKey(PROJECT_KEY)
      .setSnapshotId(10L)
      .addChildRef(2)
      .build());
    reportReader.putComponent(BatchReport.Component.newBuilder()
      .setRef(2)
      .setType(Constants.ComponentType.DIRECTORY)
      .setSnapshotId(11L)
      .addChildRef(3)
      .setPath("dir")
      .build());
    reportReader.putComponent(BatchReport.Component.newBuilder()
      .setRef(3)
      .setType(Constants.ComponentType.DIRECTORY)
      .setSnapshotId(12L)
      .addChildRef(10)
      .setPath("dir2")
      .build());
    reportReader.putComponent(BatchReport.Component.newBuilder()
      .setRef(10)
      .setType(Constants.ComponentType.FILE)
      .setSnapshotId(20L)
      .setPath("file")
      .build());

    BatchReport.Duplication duplication = BatchReport.Duplication.newBuilder()
      .setOriginPosition(Range.newBuilder()
        .setStartLine(1)
        .setEndLine(5)
        .build())
      .addDuplicate(BatchReport.Duplicate.newBuilder()
        .setOtherFileRef(10)
        .setRange(Range.newBuilder()
          .setStartLine(6)
          .setEndLine(10)
          .build())
        .build())
      .build();
    reportReader.putDuplications(10, newArrayList(duplication));

    sut.execute();

    assertThat(dbTester.countRowsOfTable("project_measures")).isEqualTo(1);

    Map<String, Object> dto = dbTester.selectFirst("select snapshot_id as \"snapshotId\", text_value as \"textValue\" from project_measures");
    assertThat(dto.get("textValue")).isEqualTo("<duplications><g><b s=\"1\" l=\"5\" r=\"PROJECT_KEY:file\"/><b s=\"6\" l=\"5\" r=\"PROJECT_KEY:file\"/></g></duplications>");
  }

  @Test
  public void persist_duplications_on_different_files() {
    saveDuplicationMetric();

    Component file2 = new DumbComponent(Component.Type.FILE, 3, "CDEF", "PROJECT_KEY:file2");
    Component file = new DumbComponent(Component.Type.FILE, 2, "BCDE", "PROJECT_KEY:file");
    Component project = new DumbComponent(Component.Type.PROJECT, 1, "ABCD", PROJECT_KEY, file, file2);
    treeRootHolder.setRoot(project);

    dbIdsRepository.setComponentId(project, 1);
    dbIdsRepository.setComponentId(file, 2);

    // TODO remove this when snapshot id will come from the DbIdsRepo
    reportReader.putComponent(BatchReport.Component.newBuilder()
      .setRef(1)
      .setType(Constants.ComponentType.PROJECT)
      .setKey(PROJECT_KEY)
      .setSnapshotId(10L)
      .addChildRef(2)
      .build());
    reportReader.putComponent(BatchReport.Component.newBuilder()
      .setRef(2)
      .setType(Constants.ComponentType.FILE)
      .setSnapshotId(11L)
      .setPath("file")
      .build());
    reportReader.putComponent(BatchReport.Component.newBuilder()
      .setRef(3)
      .setType(Constants.ComponentType.FILE)
      .setSnapshotId(12L)
      .setPath("file2")
      .build());

    BatchReport.Duplication duplication = BatchReport.Duplication.newBuilder()
      .setOriginPosition(Range.newBuilder()
        .setStartLine(1)
        .setEndLine(5)
        .build())
      .addDuplicate(BatchReport.Duplicate.newBuilder()
        .setOtherFileRef(3)
        .setRange(Range.newBuilder()
          .setStartLine(6)
          .setEndLine(10)
          .build())
        .build())
      .build();
    reportReader.putDuplications(2, newArrayList(duplication));

    sut.execute();

    assertThat(dbTester.countRowsOfTable("project_measures")).isEqualTo(1);

    Map<String, Object> dto = dbTester.selectFirst("select snapshot_id as \"snapshotId\", text_value as \"textValue\" from project_measures");
    assertThat(dto.get("snapshotId")).isEqualTo(11L);
    assertThat(dto.get("textValue")).isEqualTo("<duplications><g><b s=\"1\" l=\"5\" r=\"PROJECT_KEY:file\"/><b s=\"6\" l=\"5\" r=\"PROJECT_KEY:file2\"/></g></duplications>");
  }

  @Test
  public void persist_duplications_on_different_projects() {
    saveDuplicationMetric();
    initReportWithProjectAndFile();

    BatchReport.Duplication duplication = BatchReport.Duplication.newBuilder()
      .setOriginPosition(Range.newBuilder()
        .setStartLine(1)
        .setEndLine(5)
        .build())
      .addDuplicate(BatchReport.Duplicate.newBuilder()
        .setOtherFileKey("PROJECT2_KEY:file2")
        .setRange(Range.newBuilder()
          .setStartLine(6)
          .setEndLine(10)
          .build())
        .build())
      .build();
    reportReader.putDuplications(2, newArrayList(duplication));

    sut.execute();

    assertThat(dbTester.countRowsOfTable("project_measures")).isEqualTo(1);

    Map<String, Object> dto = dbTester.selectFirst("select snapshot_id as \"snapshotId\", text_value as \"textValue\" from project_measures");
    assertThat(dto.get("snapshotId")).isEqualTo(11L);
    assertThat(dto.get("textValue")).isEqualTo("<duplications><g><b s=\"1\" l=\"5\" r=\"PROJECT_KEY:file\"/><b s=\"6\" l=\"5\" r=\"PROJECT2_KEY:file2\"/></g></duplications>");
  }

  private void initReportWithProjectAndFile() {
    Component file = new DumbComponent(Component.Type.FILE, 2, "BCDE", "PROJECT_KEY:file");
    Component project = new DumbComponent(Component.Type.PROJECT, 1, "ABCD", PROJECT_KEY, file);
    treeRootHolder.setRoot(project);

    dbIdsRepository.setComponentId(project, 1);
    dbIdsRepository.setComponentId(file, 2);

    // TODO remove this when snapshot id will come from the DbIdsRepo
    reportReader.putComponent(BatchReport.Component.newBuilder()
      .setRef(1)
      .setType(Constants.ComponentType.PROJECT)
      .setKey(PROJECT_KEY)
      .setSnapshotId(10L)
      .addChildRef(2)
      .build());
    reportReader.putComponent(BatchReport.Component.newBuilder()
      .setRef(2)
      .setType(Constants.ComponentType.FILE)
      .setSnapshotId(11L)
      .setPath("file")
      .build());
  }

  private MetricDto saveDuplicationMetric() {
    MetricDto duplicationMetric = new MetricDto().setKey(CoreMetrics.DUPLICATIONS_DATA_KEY)
      .setOptimizedBestValue(false)
      .setDeleteHistoricalData(false)
      .setHidden(false);
    dbClient.metricDao().insert(session, duplicationMetric);
    session.commit();
    return duplicationMetric;
  }

}
