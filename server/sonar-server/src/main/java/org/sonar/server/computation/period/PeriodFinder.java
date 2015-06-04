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

package org.sonar.server.computation.period;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import javax.annotation.CheckForNull;
import org.sonar.api.CoreProperties;
import org.sonar.api.utils.DateUtils;
import org.sonar.core.component.SnapshotDto;
import org.sonar.core.component.SnapshotQuery;
import org.sonar.core.persistence.DbSession;
import org.sonar.server.db.DbClient;

import static org.sonar.core.component.SnapshotQuery.SORT_FIELD.BY_DATE;
import static org.sonar.core.component.SnapshotQuery.SORT_ORDER.ASC;
import static org.sonar.core.component.SnapshotQuery.SORT_ORDER.DESC;

public class PeriodFinder {

  private final DbClient dbClient;

  public PeriodFinder(DbClient dbClient) {
    this.dbClient = dbClient;
  }

  Period findByDate(DbSession session, Long projectId, long date) {
    SnapshotDto snapshot = findFirstSnapshot(session, createCommonQuery(projectId).setCreatedAfter(date).setSort(BY_DATE, ASC));
    SimpleDateFormat format = new SimpleDateFormat(DateUtils.DATE_FORMAT);
    return new Period(CoreProperties.TIMEMACHINE_MODE_DATE, date, snapshot).setModeParameter(format.format(date));
  }

  Period findByDays(DbSession session, Long projectId, long date, int days) {
    List<SnapshotDto> snapshots = dbClient.snapshotDao().selectSnapshotsByQuery(session, createCommonQuery(projectId).setCreatedBefore(date).setSort(BY_DATE, ASC));
    long targetDate = DateUtils.addDays(new Date(date), -days).getTime();
    SnapshotDto snapshot = findNearestSnapshotToTargetDate(snapshots, targetDate);
    return new Period(CoreProperties.TIMEMACHINE_MODE_DAYS, targetDate, snapshot).setModeParameter(String.valueOf(days));
  }

  Period findByPreviousAnalysis(DbSession session, Long projectId, long date) {
    SnapshotDto snapshot = findFirstSnapshot(session, createCommonQuery(projectId).setCreatedBefore(date).setIsLast(true).setSort(BY_DATE, DESC));
    if (snapshot == null) {
      return new Period(CoreProperties.TIMEMACHINE_MODE_PREVIOUS_ANALYSIS);
    }
    SimpleDateFormat format = new SimpleDateFormat(DateUtils.DATE_FORMAT);
    return new Period(CoreProperties.TIMEMACHINE_MODE_PREVIOUS_ANALYSIS, snapshot.getCreatedAt(), snapshot).setModeParameter(format.format(snapshot.getCreatedAt()));
  }

  Period findByPreviousVersion(DbSession session, Long projectId, String version) {
    List<SnapshotDto> snapshotDtos = dbClient.snapshotDao().selectPreviousVersionSnapshots(session, projectId, version);
    if (snapshotDtos.isEmpty()) {
      return new Period(CoreProperties.TIMEMACHINE_MODE_PREVIOUS_VERSION);
    }
    SnapshotDto snapshotDto = snapshotDtos.get(0);
    return new Period(CoreProperties.TIMEMACHINE_MODE_PREVIOUS_VERSION, snapshotDto.getCreatedAt(), snapshotDto).setModeParameter(snapshotDto.getVersion());
  }

  Period findByVersion(DbSession session, Long projectId, String version) {
    SnapshotDto snapshot = findFirstSnapshot(session, createCommonQuery(projectId).setVersion(version).setSort(BY_DATE, DESC));

    if (snapshot == null) {
      return new Period(CoreProperties.TIMEMACHINE_MODE_VERSION);
    } else {
      return new Period(CoreProperties.TIMEMACHINE_MODE_VERSION, snapshot.getCreatedAt(), snapshot).setModeParameter(version);
    }
  }

  @CheckForNull
  private static SnapshotDto findNearestSnapshotToTargetDate(List<SnapshotDto> snapshots, Long targetDate) {
    long bestDistance = Long.MAX_VALUE;
    SnapshotDto nearest = null;
    for (SnapshotDto snapshot : snapshots) {
      long distance = Math.abs(snapshot.getCreatedAt() - targetDate);
      if (distance <= bestDistance) {
        bestDistance = distance;
        nearest = snapshot;
      }
    }
    return nearest;
  }

  @CheckForNull
  private SnapshotDto findFirstSnapshot(DbSession session, SnapshotQuery query) {
    List<SnapshotDto> snapshots = dbClient.snapshotDao().selectSnapshotsByQuery(session, query);
    if (snapshots.size() >= 1) {
      return snapshots.get(0);
    }
    return null;
  }

  private static SnapshotQuery createCommonQuery(Long projectId) {
    return new SnapshotQuery().setComponentId(projectId).setStatus(SnapshotDto.STATUS_PROCESSED);
  }

}
